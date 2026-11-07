// Anime trackers: AniList and MyAnimeList, per profile. Each profile can connect either or both and picks a
// primary one. Lists import into Zylo; finishing an episode in Zylo moves the primary tracker forward, once
// (progress only ever rises to the episode just finished, and is sent as an absolute number).
//
// Matching uses Kometa's anime ID list (AniDB → AniList/MAL/TVDB/IMDb, with TVDB season + episode offset), so a
// split-cour series (Attack on Titan S3 = two AniList entries, episodes 1-12 and 13-22) maps to the right entry.
// Anime episode numbers are never assumed to equal TMDB or TVDB ones.
import { randomBytes } from "node:crypto";
import type { FastifyInstance, preHandlerHookHandler } from "fastify";
import type { MediaItem, PrismaClient } from "./generated/prisma/client.js";
import { fetchJson, UpstreamError } from "./http.js";
import { cardOf, type MetadataService, type TitleData } from "./metadata.js";
import { decrypt, encrypt } from "./secrets.js";

export const TRACKERS = { anilist: "AniList", mal: "MyAnimeList" } as const;
export type Tracker = keyof typeof TRACKERS;
export type AnimeBases = { anilist?: string; anilistAuth?: string; malApi?: string; malAuth?: string; ids?: string };
const BASES: Required<AnimeBases> = {
  anilist: "https://graphql.anilist.co",
  anilistAuth: "https://anilist.co/api/v2/oauth",
  malApi: "https://api.myanimelist.net/v2",
  malAuth: "https://myanimelist.net/v1/oauth2",
  ids: "https://raw.githubusercontent.com/Kometa-Team/Anime-IDs/master/anime_ids.json",
};

export const STATUSES = ["watching", "completed", "paused", "dropped", "planning"] as const;
export type Status = (typeof STATUSES)[number];
const FROM_ANILIST: Record<string, Status> = { CURRENT: "watching", REPEATING: "watching", COMPLETED: "completed", PAUSED: "paused", DROPPED: "dropped", PLANNING: "planning" };
const TO_ANILIST: Record<Status, string> = { watching: "CURRENT", completed: "COMPLETED", paused: "PAUSED", dropped: "DROPPED", planning: "PLANNING" };
const FROM_MAL: Record<string, Status> = { watching: "watching", completed: "completed", on_hold: "paused", dropped: "dropped", plan_to_watch: "planning" };
const TO_MAL: Record<Status, string> = { watching: "watching", completed: "completed", paused: "on_hold", dropped: "dropped", planning: "plan_to_watch" };

type AppSecret = { clientId: string; clientSecret?: string };
type Token = { access: string; refresh?: string; expiresAt: number };
type Remote = { id: number; title: string; status: Status; progress: number; episodes: number | null; score: number | null };
type Client = {
  me(): Promise<string>;
  list(): Promise<Remote[]>;
  save(id: number, progress: number, status: Status): Promise<void>;
  episodes(id: number): Promise<number | null>;
};

// --- Tracker clients ------------------------------------------------------------

function anilist(token: string, url: string): Client {
  const gql = async <T>(query: string, variables: object = {}) => {
    const r = await fetchJson<{ data: T; errors?: { message: string; status?: number }[] }>("AniList", url, {
      method: "POST",
      headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
      body: JSON.stringify({ query, variables }),
    });
    if (r.errors?.length) throw new UpstreamError("AniList", r.errors[0].status ?? 400, r.errors[0].message);
    return r.data;
  };
  const viewer = () => gql<{ Viewer: { id: number; name: string } }>("{ Viewer { id name } }").then((d) => d.Viewer);
  type L = { status: string; progress: number; score: number; media: { id: number; episodes: number | null; title: { userPreferred: string } } };
  return {
    me: async () => (await viewer()).name,
    async list() {
      const { id } = await viewer();
      const d = await gql<{ MediaListCollection: { lists: { entries: L[] }[] } }>(
        "query($id: Int) { MediaListCollection(userId: $id, type: ANIME) { lists { entries { status progress score(format: POINT_10_DECIMAL) media { id episodes title { userPreferred } } } } } }",
        { id },
      );
      // Custom lists repeat entries: keep one per anime.
      const all = new Map(d.MediaListCollection.lists.flatMap((l) => l.entries).map((e) => [e.media.id, e]));
      return [...all.values()].map((e) => ({
        id: e.media.id,
        title: e.media.title.userPreferred,
        status: FROM_ANILIST[e.status] ?? "watching",
        progress: e.progress ?? 0,
        episodes: e.media.episodes,
        score: e.score || null,
      }));
    },
    async save(id, progress, status) {
      await gql("mutation($id: Int, $p: Int, $s: MediaListStatus) { SaveMediaListEntry(mediaId: $id, progress: $p, status: $s) { id } }", { id, p: progress, s: TO_ANILIST[status] });
    },
    episodes: async (id) => (await gql<{ Media: { episodes: number | null } }>("query($id: Int) { Media(id: $id) { episodes } }", { id })).Media.episodes,
  };
}

function mal(token: string, api: string): Client {
  const call = <T>(path: string, init: RequestInit = {}) => fetchJson<T>("MyAnimeList", path.startsWith("http") ? path : `${api}${path}`, { ...init, headers: { authorization: `Bearer ${token}`, ...init.headers } });
  type Page = { data: { node: { id: number; title: string; num_episodes: number }; list_status: { status: string; score: number; num_episodes_watched: number } }[]; paging?: { next?: string } };
  return {
    me: async () => (await call<{ name: string }>("/users/@me")).name,
    async list() {
      const out: Remote[] = [];
      let next: string | undefined = "/users/@me/animelist?fields=list_status,num_episodes&limit=1000&nsfw=true";
      while (next) {
        const page: Page = await call<Page>(next);
        for (const { node, list_status: s } of page.data)
          out.push({ id: node.id, title: node.title, status: FROM_MAL[s.status] ?? "watching", progress: s.num_episodes_watched, episodes: node.num_episodes || null, score: s.score || null });
        next = page.paging?.next?.startsWith(api) ? page.paging.next : undefined; // only ever follow MAL's own links
      }
      return out;
    },
    async save(id, progress, status) {
      await call(`/anime/${id}/my_list_status`, { method: "PATCH", body: new URLSearchParams({ status: TO_MAL[status], num_watched_episodes: String(progress) }) });
    },
    episodes: async (id) => (await call<{ num_episodes: number }>(`/anime/${id}?fields=num_episodes`)).num_episodes || null,
  };
}

// --- Kometa anime IDs -----------------------------------------------------------

type Row = { tvdb: number | null; season: number | null; offset: number; imdb: string[]; anilist: number[]; mal: number[] };
type Ids = { at: number; by: Record<Tracker, Map<number, Row>>; byTvdb: Map<number, Row[]>; byImdb: Map<string, Row[]> };
const nums = (v: unknown) => String(v ?? "").split(",").map(Number).filter((n) => n > 0);
let idsCache: Promise<Ids> | null = null;
let idsAt = 0;

function loadIds(url: string): Promise<Ids> {
  if (idsCache && Date.now() - idsAt < 86_400_000) return idsCache; // Kometa regenerates it daily
  idsAt = Date.now();
  idsCache = fetchJson<Record<string, Record<string, unknown>>>("the anime ID list", url).then((raw) => {
    const ids: Ids = { at: Date.now(), by: { anilist: new Map(), mal: new Map() }, byTvdb: new Map(), byImdb: new Map() };
    for (const v of Object.values(raw)) {
      const row: Row = {
        tvdb: nums(v.tvdb_id)[0] ?? null,
        season: typeof v.tvdb_season === "number" ? v.tvdb_season : null,
        offset: Number(v.tvdb_epoffset) || 0,
        imdb: String(v.imdb_id ?? "").split(",").filter((x) => /^tt\d+$/.test(x)),
        anilist: nums(v.anilist_id),
        mal: nums(v.mal_id),
      };
      for (const t of ["anilist", "mal"] as const) for (const id of row[t]) ids.by[t].set(id, row);
      if (row.tvdb) ids.byTvdb.set(row.tvdb, [...(ids.byTvdb.get(row.tvdb) ?? []), row]);
      for (const i of row.imdb) ids.byImdb.set(i, [...(ids.byImdb.get(i) ?? []), row]);
    }
    return ids;
  });
  idsCache.catch(() => (idsCache = null)); // try again next time
  return idsCache;
}

/** The anime entry for this episode: same TVDB season with the largest offset below it, else absolute order. */
export function pickEntry(rows: Row[], tracker: Tracker, at: { season: number; episode: number; absolute: number | null }) {
  const best = (season: number, n: number) =>
    rows.filter((r) => r.season === season && r.offset < n && r[tracker].length).sort((a, b) => b.offset - a.offset)[0];
  const r = best(at.season, at.episode);
  if (r) return { id: r[tracker][0], episode: at.episode - r.offset };
  const a = at.absolute !== null ? best(-1, at.absolute) : undefined;
  return a ? { id: a[tracker][0], episode: at.absolute! - a.offset } : null;
}

// --- The service ------------------------------------------------------------------

export function animeService(db: PrismaClient, metadata: MetadataService, bases: AnimeBases = {}) {
  const B = { ...BASES, ...Object.fromEntries(Object.entries(bases).filter(([, v]) => v)) } as Required<AnimeBases>;
  const app = async (t: Tracker) => {
    const row = await db.integration.findUnique({ where: { id: t } });
    return row ? decrypt<AppSecret>(row.secret) : null;
  };

  async function clientFor(acct: { id: string; tracker: string; token: string }): Promise<Client> {
    const t = acct.tracker as Tracker;
    let token = decrypt<Token>(acct.token);
    const again = `${TRACKERS[t]} needs this profile to connect again (Settings > Anime).`;
    if (token.expiresAt - Date.now() < 86_400_000) {
      // MAL tokens last about a month and refresh; AniList's last a year and don't.
      if (t === "anilist" || !token.refresh) throw new UpstreamError(TRACKERS[t], 401, again);
      const a = (await app("mal"))!;
      const r = await fetchJson<{ access_token: string; refresh_token: string; expires_in: number }>("MyAnimeList", `${B.malAuth}/token`, {
        method: "POST",
        body: new URLSearchParams({ client_id: a.clientId, ...(a.clientSecret && { client_secret: a.clientSecret }), grant_type: "refresh_token", refresh_token: token.refresh }),
      }).catch((e) => {
        throw e instanceof UpstreamError && (e.status === 400 || e.status === 401) ? new UpstreamError("MyAnimeList", 401, again) : e;
      });
      token = { access: r.access_token, refresh: r.refresh_token, expiresAt: Date.now() + r.expires_in * 1000 };
      await db.animeAccount.update({ where: { id: acct.id }, data: { token: encrypt(token) } });
    }
    return t === "anilist" ? anilist(token.access, B.anilist) : mal(token.access, B.malApi);
  }

  async function primary(profileId: string) {
    const [profile, accounts] = await Promise.all([
      db.profile.findUniqueOrThrow({ where: { id: profileId } }),
      db.animeAccount.findMany({ where: { profileId }, orderBy: { createdAt: "asc" } }),
    ]);
    const chosen = (profile.settings as { anime?: { primary?: Tracker } }).anime?.primary;
    return accounts.find((a) => a.tracker === chosen) ?? accounts[0] ?? null;
  }

  // TMDB → TVDB numbering for one episode. With TVDB connected, matched by air date; otherwise assumed equal.
  const tvdbEpisodes = new Map<number, { at: number; eps: Promise<{ seasonNumber: number; number: number; absoluteNumber?: number | null; aired?: string | null }[]> }>();
  async function tvdbNumbering(series: MediaItem, tmdbId: number, tvdbId: number, season: number, episode: number) {
    const data = series.data as TitleData;
    // ponytail: absolute number from TMDB season sizes when TVDB isn't connected; fine for most anime.
    const absolute = season > 0 ? (data.seasons ?? []).filter((s) => s.number > 0 && s.number < season).reduce((n, s) => n + s.episodeCount, 0) + episode : null;
    const client = await metadata.tvdb().catch(() => null);
    if (!client) return { season, episode, absolute };
    let c = tvdbEpisodes.get(tvdbId);
    if (!c || Date.now() - c.at > 3_600_000) tvdbEpisodes.set(tvdbId, (c = { at: Date.now(), eps: client.episodes(tvdbId, "default") }));
    const [eps, tmdb] = await Promise.all([c.eps.catch(() => []), metadata.season(tmdbId, season).catch(() => null)]);
    const mine = tmdb?.episodes.find((e) => e.episodeNumber === episode);
    const day = mine?.releaseDate?.slice(0, 10);
    const sameDay = day ? eps.filter((e) => e.seasonNumber > 0 && e.aired === day) : [];
    const k = day ? tmdb!.episodes.filter((e) => e.releaseDate?.slice(0, 10) === day && (e.episodeNumber ?? 0) < episode).length : 0;
    const hit = sameDay[k] ?? sameDay[0];
    return hit ? { season: hit.seasonNumber, episode: hit.number, absolute: hit.absoluteNumber ?? absolute } : { season, episode, absolute };
  }

  /** Which tracker entry and episode a Zylo film or episode is. */
  async function locate(tracker: Tracker, item: MediaItem, season?: number, episode?: number) {
    const ids = await loadIds(B.ids);
    const ext = await db.externalId.findMany({ where: { mediaItemId: item.id } });
    if (item.type === "movie") {
      const imdb = ext.find((x) => x.system === "imdb")?.value;
      const row = imdb ? ids.byImdb.get(imdb)?.find((r) => r[tracker].length) : undefined;
      return row ? { id: row[tracker][0], episode: 1 } : null;
    }
    const tvdb = Number(ext.find((x) => x.system === "tvdb")?.value);
    const tmdb = Number(ext.find((x) => x.system === "tmdb")?.value);
    const rows = tvdb ? ids.byTvdb.get(tvdb) : undefined;
    if (!rows || season === undefined || episode === undefined) return null;
    return pickEntry(rows, tracker, await tvdbNumbering(item, tmdb, tvdb, season, episode));
  }

  /** Called when a profile finishes a film or episode in Zylo. Never throws: tracking must not break playback. */
  async function watched(profileId: string, item: MediaItem, season?: number, episode?: number) {
    try {
      const acct = await primary(profileId);
      if (!acct) return;
      const t = acct.tracker as Tracker;
      const hit = await locate(t, item, season, episode);
      if (!hit) return;
      const key = { profileId_tracker_remoteId: { profileId, tracker: t, remoteId: hit.id } };
      const entry = await db.animeEntry.findUnique({ where: key });
      if (entry && entry.progress >= hit.episode) return; // already counted (or an earlier episode): nothing to send
      await db.animeEntry.upsert({
        where: key,
        create: { profileId, tracker: t, remoteId: hit.id, title: item.title, status: "watching", progress: hit.episode, mediaItemId: item.id, mapped: true },
        update: { progress: hit.episode, status: entry?.status === "completed" ? "completed" : "watching" },
      });
      await push(profileId, t).catch(() => {}); // the next sync retries
    } catch {
      // Mapping list or tracker unreachable: the progress stays local until the next sync.
    }
  }

  /** Send progress watched in Zylo that the tracker hasn't seen yet. */
  async function push(profileId: string, t: Tracker) {
    const acct = await db.animeAccount.findUnique({ where: { profileId_tracker: { profileId, tracker: t } } });
    if (!acct) return;
    const pending = (await db.animeEntry.findMany({ where: { profileId, tracker: t } })).filter((e) => e.progress > e.pushedProgress);
    if (!pending.length) return;
    const client = await clientFor(acct);
    for (const e of pending) {
      const episodes = e.episodes ?? (await client.episodes(e.remoteId));
      const status: Status = episodes && e.progress >= episodes ? "completed" : "watching";
      await client.save(e.remoteId, e.progress, status);
      await db.animeEntry.update({ where: { id: e.id }, data: { pushedProgress: e.progress, status, episodes } });
    }
  }

  /** Match list entries to Zylo titles (TMDB via IMDb for films, TVDB for series). */
  async function match(profileId: string, t: Tracker) {
    const todo = await db.animeEntry.findMany({ where: { profileId, tracker: t, mapped: false }, take: 300 }); // ponytail: 300 per sync
    if (!todo.length) return;
    const ids = await loadIds(B.ids);
    const { client } = await metadata.tmdb();
    for (const e of todo) {
      const row = ids.by[t].get(e.remoteId);
      let mediaItemId: string | null = null;
      try {
        const viaImdb = row?.imdb[0] ? await client.findByImdb(row.imdb[0]) : null;
        if (viaImdb?.movie_results[0] && (row!.season === null || row!.season === 0)) mediaItemId = await metadata.ensureStub("movie", viaImdb.movie_results[0].id, e.title, null);
        else if (row?.tvdb) {
          const tv = (await client.findByTvdb(row.tvdb)).tv_results[0] ?? viaImdb?.tv_results[0];
          if (tv) mediaItemId = await metadata.ensureStub("series", tv.id, e.title, null);
        }
      } catch {
        continue; // TMDB hiccup: try again next sync
      }
      await db.animeEntry.update({ where: { id: e.id }, data: { mediaItemId, mapped: true } });
    }
  }

  // One sync per profile and tracker at a time: a second caller (Sync now during the import) shares the first.
  const running = new Map<string, Promise<void>>();
  function sync(profileId: string, t: Tracker) {
    const key = `${profileId}:${t}`;
    if (!running.has(key)) running.set(key, syncOnce(profileId, t).finally(() => running.delete(key)));
    return running.get(key)!;
  }

  /** Tracker → Zylo (the tracker wins, except progress Zylo hasn't sent yet), then send what's pending. */
  async function syncOnce(profileId: string, t: Tracker) {
    const acct = await db.animeAccount.findUnique({ where: { profileId_tracker: { profileId, tracker: t } } });
    if (!acct) return;
    try {
      const remote = await (await clientFor(acct)).list();
      const local = new Map((await db.animeEntry.findMany({ where: { profileId, tracker: t } })).map((e) => [e.remoteId, e]));
      for (const r of remote) {
        const l = local.get(r.id);
        const pending = l && l.progress > r.progress && l.progress > l.pushedProgress;
        const data = { title: r.title, status: pending ? l.status : r.status, progress: pending ? l.progress : r.progress, pushedProgress: r.progress, episodes: r.episodes, score: r.score };
        await db.animeEntry.upsert({ where: { profileId_tracker_remoteId: { profileId, tracker: t, remoteId: r.id } }, create: { profileId, tracker: t, remoteId: r.id, ...data }, update: data });
        local.delete(r.id);
      }
      // Removed on the tracker (and nothing waiting to be sent): remove here too.
      const gone = [...local.values()].filter((e) => e.progress <= e.pushedProgress).map((e) => e.id);
      if (gone.length) await db.animeEntry.deleteMany({ where: { id: { in: gone } } });
      await push(profileId, t);
      await match(profileId, t).catch(() => {});
      await db.animeAccount.update({ where: { id: acct.id }, data: { lastSyncedAt: new Date(), lastError: null } });
    } catch (e) {
      await db.animeAccount.update({ where: { id: acct.id }, data: { lastError: (e as Error).message } });
      throw e;
    }
  }

  return { app, clientFor, primary, locate, watched, push, sync, bases: B };
}

export type AnimeService = ReturnType<typeof animeService>;

// --- Routes ---------------------------------------------------------------------

export function registerAnimeRoutes(
  app: FastifyInstance,
  db: PrismaClient,
  guards: { requireAdmin: preHandlerHookHandler; requireProfile: preHandlerHookHandler },
  anime: AnimeService,
) {
  const B = anime.bases;
  const tracker = { type: "object", required: ["tracker"], properties: { tracker: { enum: Object.keys(TRACKERS) } } } as const;
  const save = (t: Tracker, s: AppSecret) =>
    db.integration.upsert({ where: { id: t }, create: { id: t, secret: encrypt(s), lastCheckedAt: new Date() }, update: { secret: encrypt(s), lastCheckedAt: new Date(), lastError: null } });

  // --- admin: one AniList and one MAL app per server ---
  app.put<{ Body: { clientId: string } }>(
    "/integrations/anilist",
    { preHandler: guards.requireAdmin, schema: { body: { type: "object", required: ["clientId"], additionalProperties: false, properties: { clientId: { type: "string", pattern: "^\\s*\\d{1,10}\\s*$" } } } } },
    async (req, reply) => {
      await save("anilist", { clientId: req.body.clientId.trim() });
      return reply.code(204).send();
    },
  );
  app.put<{ Body: { clientId: string; clientSecret?: string } }>(
    "/integrations/mal",
    {
      preHandler: guards.requireAdmin,
      schema: {
        body: {
          type: "object",
          required: ["clientId"],
          additionalProperties: false,
          properties: { clientId: { type: "string", minLength: 20, maxLength: 100 }, clientSecret: { type: "string", maxLength: 200 } },
        },
      },
    },
    async (req, reply) => {
      const s = { clientId: req.body.clientId.trim(), ...(req.body.clientSecret?.trim() && { clientSecret: req.body.clientSecret.trim() }) };
      try {
        // Public data needs only the Client ID, so this proves it's real.
        await fetchJson("MyAnimeList", `${B.malApi}/anime/1?fields=id`, { headers: { "x-mal-client-id": s.clientId } });
      } catch (e) {
        const status = e instanceof UpstreamError ? e.status : 0;
        return reply.code(400).send({
          error: status === 401 || status === 403 ? "MyAnimeList didn't accept that Client ID." : `Couldn't check the Client ID with MyAnimeList: ${(e as Error).message}`,
        });
      }
      await save("mal", s);
      return reply.code(204).send();
    },
  );

  // Static routes win over metadata's /integrations/:id/test.
  app.post("/integrations/anilist/test", { preHandler: guards.requireAdmin }, async () => check("anilist", () => fetchJson("AniList", B.anilist, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ query: "{ Media(id: 1) { id } }" }) })));
  app.post("/integrations/mal/test", { preHandler: guards.requireAdmin }, async () =>
    check("mal", async () => fetchJson("MyAnimeList", `${B.malApi}/anime/1?fields=id`, { headers: { "x-mal-client-id": (await anime.app("mal"))!.clientId } })),
  );
  async function check(id: Tracker, fn: () => Promise<unknown>) {
    let lastError: string | null = null;
    await fn().catch((e: Error) => (lastError = e.message));
    await db.integration.update({ where: { id }, data: { lastCheckedAt: new Date(), lastError } });
    return { ok: !lastError, error: lastError };
  }

  // --- profile ---
  app.get("/anime", { preHandler: guards.requireProfile }, async (req) => {
    const [apps, accounts, counts, chosen] = await Promise.all([
      db.integration.findMany({ where: { id: { in: ["anilist", "mal"] } }, select: { id: true } }),
      db.animeAccount.findMany({ where: { profileId: req.profile.id }, orderBy: { createdAt: "asc" } }),
      db.animeEntry.groupBy({ by: ["tracker"], where: { profileId: req.profile.id }, _count: true }),
      anime.primary(req.profile.id),
    ]);
    return {
      available: { anilist: apps.some((a) => a.id === "anilist"), mal: apps.some((a) => a.id === "mal") },
      primary: chosen?.tracker ?? null,
      accounts: accounts.map((a) => ({
        tracker: a.tracker,
        name: TRACKERS[a.tracker as Tracker],
        userName: a.userName,
        lastSyncedAt: a.lastSyncedAt,
        lastError: a.lastError,
        entries: counts.find((c) => c.tracker === a.tracker)?._count ?? 0,
      })),
    };
  });

  // Where to send the browser to sign in. MAL's PKCE verifier rides in the (encrypted) state, so nothing is stored.
  app.post<{ Params: { tracker: Tracker }; Body: { redirectUri: string } }>(
    "/anime/:tracker/authorize",
    {
      preHandler: guards.requireProfile,
      schema: { params: tracker, body: { type: "object", required: ["redirectUri"], properties: { redirectUri: { type: "string", pattern: "^https?://", maxLength: 500 } } } },
    },
    async (req, reply) => {
      const t = req.params.tracker;
      const a = await anime.app(t);
      if (!a) return reply.code(409).send({ error: `The server admin hasn't added ${TRACKERS[t]} yet (Settings > Integrations).` });
      if (t === "anilist") return { url: `${B.anilistAuth}/authorize?${new URLSearchParams({ client_id: a.clientId, response_type: "token" })}` };
      const verifier = randomBytes(48).toString("base64url"); // 64 characters; MAL allows only the "plain" method
      const state = encrypt({ p: req.profile.id, v: verifier, r: req.body.redirectUri, exp: Date.now() + 15 * 60_000 });
      const q = { response_type: "code", client_id: a.clientId, code_challenge: verifier, code_challenge_method: "plain", state, redirect_uri: req.body.redirectUri };
      return { url: `${B.malAuth}/authorize?${new URLSearchParams(q)}` };
    },
  );

  const connected = async (profileId: string, t: Tracker, client: Client, token: Token) => {
    const userName = await client.me();
    await db.animeAccount.upsert({
      where: { profileId_tracker: { profileId, tracker: t } },
      create: { profileId, tracker: t, userName, token: encrypt(token) },
      update: { userName, token: encrypt(token), lastError: null },
    });
    void anime.sync(profileId, t).catch(() => {}); // import in the background; Settings shows progress and errors
    return { tracker: t, userName };
  };

  app.post<{ Body: { accessToken: string; expiresIn?: number } }>(
    "/anime/anilist/connect",
    {
      preHandler: guards.requireProfile,
      schema: { body: { type: "object", required: ["accessToken"], properties: { accessToken: { type: "string", minLength: 20, maxLength: 4000 }, expiresIn: { type: "integer", minimum: 1 } } } },
    },
    async (req, reply) => {
      const token = { access: req.body.accessToken, expiresAt: Date.now() + (req.body.expiresIn ?? 31_536_000) * 1000 };
      try {
        return await connected(req.profile.id, "anilist", anilist(token.access, B.anilist), token);
      } catch (e) {
        return reply.code(400).send({ error: `AniList didn't accept the sign-in: ${(e as Error).message}` });
      }
    },
  );

  app.post<{ Body: { code: string; state: string } }>(
    "/anime/mal/connect",
    {
      preHandler: guards.requireProfile,
      schema: { body: { type: "object", required: ["code", "state"], properties: { code: { type: "string", minLength: 1, maxLength: 4000 }, state: { type: "string", maxLength: 2000 } } } },
    },
    async (req, reply) => {
      let s: { p: string; v: string; r: string; exp: number };
      try {
        s = decrypt(req.body.state);
      } catch {
        return reply.code(400).send({ error: "That sign-in link isn't valid. Start again from Settings." });
      }
      if (s.p !== req.profile.id) return reply.code(400).send({ error: "That sign-in was started from a different profile. Switch to it, or start again." });
      if (s.exp < Date.now()) return reply.code(400).send({ error: "That sign-in took too long. Start again from Settings." });
      const a = (await anime.app("mal"))!;
      try {
        const r = await fetchJson<{ access_token: string; refresh_token: string; expires_in: number }>("MyAnimeList", `${B.malAuth}/token`, {
          method: "POST",
          body: new URLSearchParams({ client_id: a.clientId, ...(a.clientSecret && { client_secret: a.clientSecret }), grant_type: "authorization_code", code: req.body.code, code_verifier: s.v, redirect_uri: s.r }),
        });
        const token = { access: r.access_token, refresh: r.refresh_token, expiresAt: Date.now() + r.expires_in * 1000 };
        return await connected(req.profile.id, "mal", mal(token.access, B.malApi), token);
      } catch (e) {
        return reply.code(400).send({ error: `MyAnimeList didn't accept the sign-in: ${(e as Error).message}` });
      }
    },
  );

  app.post<{ Params: { tracker: Tracker } }>("/anime/:tracker/sync", { preHandler: guards.requireProfile, schema: { params: tracker } }, async (req, reply) => {
    try {
      await anime.sync(req.profile.id, req.params.tracker);
    } catch (e) {
      return reply.code(502).send({ error: (e as Error).message });
    }
    return reply.code(204).send();
  });

  app.delete<{ Params: { tracker: Tracker } }>("/anime/:tracker", { preHandler: guards.requireProfile, schema: { params: tracker } }, async (req, reply) => {
    const where = { profileId: req.profile.id, tracker: req.params.tracker };
    await db.$transaction([db.animeEntry.deleteMany({ where }), db.animeAccount.deleteMany({ where })]);
    return reply.code(204).send();
  });

  app.put<{ Body: { primary: Tracker } }>(
    "/anime/primary",
    { preHandler: guards.requireProfile, schema: { body: { type: "object", required: ["primary"], properties: { primary: { enum: Object.keys(TRACKERS) } } } } },
    async (req, reply) => {
      const settings = req.profile.settings as Record<string, unknown>;
      await db.profile.update({ where: { id: req.profile.id }, data: { settings: { ...settings, anime: { primary: req.body.primary } } } });
      return reply.code(204).send();
    },
  );

  // The primary tracker's list, for the Library page.
  app.get("/anime/list", { preHandler: guards.requireProfile }, async (req) => {
    const acct = await anime.primary(req.profile.id);
    if (!acct) return { tracker: null, entries: [] };
    const entries = await db.animeEntry.findMany({ where: { profileId: req.profile.id, tracker: acct.tracker }, orderBy: { updatedAt: "desc" } });
    const items = await db.mediaItem.findMany({ where: { id: { in: entries.flatMap((e) => (e.mediaItemId ? [e.mediaItemId] : [])) } }, include: { externalIds: true } });
    const card = new Map(items.map((i) => [i.id, cardOf(i)]));
    return {
      tracker: acct.tracker,
      entries: entries.map((e) => ({
        id: e.remoteId,
        title: e.title,
        status: e.status,
        progress: e.progress,
        episodes: e.episodes,
        score: e.score,
        card: e.mediaItemId ? (card.get(e.mediaItemId) ?? null) : null,
        url: acct.tracker === "anilist" ? `https://anilist.co/anime/${e.remoteId}` : `https://myanimelist.net/anime/${e.remoteId}`,
      })),
    };
  });
}
