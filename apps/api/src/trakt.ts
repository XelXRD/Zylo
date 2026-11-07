// Trakt adapter + per-profile sync. API docs: https://trakt.docs.apiary.io
// Sign-in uses the device-code flow (no redirect URL needed for a self-hosted app).
// Access tokens last 24 hours since March 2025, so we refresh from expires_in, never a fixed interval.
import { Prisma, type PrismaClient } from "./generated/prisma/client.js";
import { fetchJson, UpstreamError } from "./http.js";
import type { MetadataService } from "./metadata.js";
import { decrypt, encrypt } from "./secrets.js";

export const TRAKT_BASE = "https://api.trakt.tv";
/** Older installs registered the out-of-band URI; Trakt now only accepts localhost addresses. */
export const DEFAULT_REDIRECT = "urn:ietf:wg:oauth:2.0:oob";
const PAGE = 100;

// redirectUri: whatever was entered on the Trakt app page. Sign-in never visits it (device code), but
// Trakt checks it matches when tokens are refreshed.
export type TraktApp = { clientId: string; clientSecret: string; redirectUri?: string };
type Tokens = { access_token: string; refresh_token: string; expires_in: number; created_at: number };
type Ids = { trakt?: number; slug?: string; imdb?: string | null; tmdb?: number | null; tvdb?: number | null };
type TMovie = { title: string; year: number | null; ids: Ids };
type TShow = TMovie;
type TEpisode = { season: number; number: number; title?: string | null; ids: Ids };
type TItem = { type: "movie" | "show" | "episode" | "season" | "person"; movie?: TMovie; show?: TShow; episode?: TEpisode };
export type TraktActivities = Record<string, Record<string, string> | string>;

export function traktClient(app: TraktApp, base = TRAKT_BASE) {
  const headers = { "content-type": "application/json", "trakt-api-version": "2", "trakt-api-key": app.clientId };
  const post = <T>(path: string, body: unknown, token?: string) =>
    call<T>(`${base}${path}`, { method: "POST", headers: { ...headers, ...(token && { authorization: `Bearer ${token}` }) }, body: JSON.stringify(body) });

  return {
    deviceCode: () =>
      post<{ device_code: string; user_code: string; verification_url: string; expires_in: number; interval: number }>("/oauth/device/code", {
        client_id: app.clientId,
      }),
    /** One poll. Trakt signals progress with status codes rather than error bodies. */
    async pollDevice(code: string): Promise<{ status: "connected"; tokens: Tokens } | { status: "pending" | "slow_down" | "expired" | "denied" | "invalid" }> {
      try {
        const tokens = await post<Tokens>("/oauth/device/token", { code, client_id: app.clientId, client_secret: app.clientSecret });
        return { status: "connected", tokens };
      } catch (e) {
        const s = e instanceof UpstreamError ? e.status : 0;
        if (s === 400) return { status: "pending" };
        if (s === 429) return { status: "slow_down" };
        if (s === 410) return { status: "expired" };
        if (s === 418) return { status: "denied" };
        if (s === 404 || s === 409) return { status: "invalid" };
        throw e;
      }
    },
    refresh: (refreshToken: string) =>
      post<Tokens>("/oauth/token", { refresh_token: refreshToken, client_id: app.clientId, client_secret: app.clientSecret, redirect_uri: app.redirectUri ?? DEFAULT_REDIRECT, grant_type: "refresh_token" }),
    revoke: (token: string) => post("/oauth/revoke", { token, client_id: app.clientId, client_secret: app.clientSecret }),
    as(token: string) {
      const auth = { ...headers, authorization: `Bearer ${token}` };
      return {
        get: <T>(path: string) => call<T>(`${base}${path}`, { headers: auth }),
        post: <T>(path: string, body: unknown) => call<T>(`${base}${path}`, { method: "POST", headers: auth, body: JSON.stringify(body) }),
      };
    },
  };
}

// Trakt-specific status codes become plain English.
async function call<T>(url: string, init: RequestInit) {
  try {
    return await fetchJson<T>("Trakt", url, init);
  } catch (e) {
    if (!(e instanceof UpstreamError)) throw e;
    if (e.status === 420) throw new UpstreamError("Trakt", 420, "your Trakt account has hit its free-account limit for lists or the watchlist. Trakt VIP raises it.");
    if (e.status === 423) throw new UpstreamError("Trakt", 423, "your Trakt account is locked. Contact Trakt support.");
    if (e.status === 401) throw new UpstreamError("Trakt", 401, "Trakt signed this profile out. Connect it again in Settings.");
    throw e;
  }
}

// --- Pushing changes ----------------------------------------------------------

export type Target = { type: "movie" | "series"; tmdbId: number; season?: number; episode?: number };

/** Trakt's sync body for one film, series or episode, with extra fields on the leaf (watched_at, rating…). */
export function syncBody(t: Target, extra: Record<string, unknown> = {}) {
  if (t.type === "movie") return { movies: [{ ids: { tmdb: t.tmdbId }, ...extra }] };
  if (t.season === undefined || t.episode === undefined) return { shows: [{ ids: { tmdb: t.tmdbId }, ...extra }] };
  return { shows: [{ ids: { tmdb: t.tmdbId }, seasons: [{ number: t.season, episodes: [{ number: t.episode, ...extra }] }] }] };
}

// --- Per-profile service ------------------------------------------------------

export function traktService(db: PrismaClient, metadata: MetadataService, base = TRAKT_BASE) {
  async function app() {
    const row = await db.integration.findUnique({ where: { id: "trakt" } });
    return row ? traktClient(decrypt<TraktApp>(row.secret), base) : null;
  }

  /** A valid access token for the profile, refreshing it when within an hour of expiry. */
  async function tokenFor(profileId: string) {
    const acct = await db.traktAccount.findUnique({ where: { profileId } });
    const client = await app();
    if (!acct || !client) return null;
    let { accessToken, refreshToken } = decrypt<{ accessToken: string; refreshToken: string }>(acct.secret);
    if (acct.expiresAt.getTime() - Date.now() < 3_600_000) {
      const t = await client.refresh(refreshToken);
      ({ access_token: accessToken, refresh_token: refreshToken } = t);
      await db.traktAccount.update({
        where: { profileId },
        data: { secret: encrypt({ accessToken, refreshToken }), expiresAt: new Date((t.created_at + t.expires_in) * 1000) },
      });
    }
    return client.as(accessToken);
  }

  async function saveTokens(profileId: string, t: Tokens) {
    const client = (await app())!;
    const settings = await client.as(t.access_token).get<{ user: { username: string } }>("/users/settings");
    const data = {
      secret: encrypt({ accessToken: t.access_token, refreshToken: t.refresh_token }),
      expiresAt: new Date((t.created_at + t.expires_in) * 1000),
      username: settings.user.username,
      lastError: null,
    };
    // A (re)connect starts a fresh first sync: lastActivities cleared.
    await db.traktAccount.upsert({ where: { profileId }, create: { profileId, ...data }, update: { ...data, lastActivities: Prisma.DbNull, lastSyncAt: null } });
  }

  /** Push now; if Trakt is unreachable, park it in the outbox for the next sync. No-op when not connected. */
  async function push(profileId: string, path: string, body: unknown) {
    const acct = await db.traktAccount.findUnique({ where: { profileId }, select: { profileId: true } });
    if (!acct) return;
    try {
      const api = await tokenFor(profileId);
      await api!.post(path, body);
    } catch (e) {
      await db.traktOutbox.create({ data: { profileId, path, body: body as object, lastError: (e as Error).message } });
    }
  }

  async function drainOutbox(profileId: string, api: NonNullable<Awaited<ReturnType<typeof tokenFor>>>) {
    const pending = await db.traktOutbox.findMany({ where: { profileId }, orderBy: { createdAt: "asc" } });
    for (const p of pending) {
      try {
        await api.post(p.path, p.body);
        await db.traktOutbox.delete({ where: { id: p.id } });
      } catch (e) {
        await db.traktOutbox.update({ where: { id: p.id }, data: { attempts: { increment: 1 }, lastError: (e as Error).message } });
        if (p.attempts >= 20) await db.traktOutbox.delete({ where: { id: p.id } }); // give up after ~5 hours of 15-minute syncs
      }
    }
  }

  /** Map a Trakt item to a stored title (creating a stub if needed). Items without a TMDB id are skipped. */
  async function resolve(item: TItem) {
    // Lists and watchlists can also hold seasons and people; Zylo tracks films, series and episodes.
    if (item.type !== "movie" && item.type !== "show" && item.type !== "episode") return null;
    const src = item.movie ?? item.show;
    const tmdb = src?.ids.tmdb;
    if (!src || !tmdb) return null;
    const type = item.movie ? "movie" : "series";
    const mediaItemId = await metadata.ensureStub(type, tmdb, src.title, src.year);
    return { mediaItemId, type, tmdbId: tmdb, season: item.episode?.season ?? -1, episode: item.episode?.number ?? -1 } as const;
  }

  async function pages<T>(api: { get: <R>(p: string) => Promise<R> }, path: string) {
    const all: T[] = [];
    const sep = path.includes("?") ? "&" : "?";
    // ponytail: capped at 200 pages (20,000 entries) per section per sync; the next sync carries on from there
    for (let page = 1; page <= 200; page++) {
      const batch = (await api.get<T[]>(`${path}${sep}page=${page}&limit=${PAGE}`)) ?? [];
      all.push(...batch);
      if (batch.length < PAGE) break;
    }
    return all;
  }

  async function sync(profileId: string) {
    const acct = await db.traktAccount.findUnique({ where: { profileId } });
    if (!acct) return;
    await db.traktAccount.update({ where: { profileId }, data: { syncState: "running" } });
    try {
      const api = (await tokenFor(profileId))!;
      await drainOutbox(profileId, api);

      const now = await api.get<TraktActivities>("/sync/last_activities");
      const prev = (acct.lastActivities as TraktActivities | null) ?? null;
      const first = !prev;
      const changed = (...keys: string[]) =>
        first || keys.some((k) => {
          const [a, b] = k.split(".");
          return (now[a] as Record<string, string>)?.[b] !== (prev[a] as Record<string, string>)?.[b];
        });

      if (changed("movies.watched_at", "episodes.watched_at")) {
        const since = acct.lastSyncAt ? `?start_at=${new Date(acct.lastSyncAt.getTime() - 86_400_000).toISOString()}` : "";
        for (const h of await pages<TItem & { watched_at: string }>(api, `/sync/history${since}`)) {
          const r = await resolve(h);
          if (!r) continue;
          await db.watchEvent.upsert({
            where: { profileId_mediaItemId_seasonNumber_episodeNumber_watchedAt: { profileId, mediaItemId: r.mediaItemId, seasonNumber: r.season, episodeNumber: r.episode, watchedAt: new Date(h.watched_at) } },
            create: { profileId, mediaItemId: r.mediaItemId, seasonNumber: r.season, episodeNumber: r.episode, watchedAt: new Date(h.watched_at), source: "trakt" },
            update: {},
          });
        }
      }

      if (changed("movies.rated_at", "shows.rated_at", "episodes.rated_at")) {
        const seen = new Set<string>();
        for (const kind of ["movies", "shows", "episodes"]) {
          for (const x of await api.get<(TItem & { rating: number; rated_at: string })[]>(`/sync/ratings/${kind}`)) {
            const r = await resolve(x);
            if (!r) continue;
            seen.add(`${r.mediaItemId}:${r.season}:${r.episode}`);
            const key = { profileId, mediaItemId: r.mediaItemId, seasonNumber: r.season, episodeNumber: r.episode };
            await db.rating.upsert({ where: { profileId_mediaItemId_seasonNumber_episodeNumber: key }, create: { ...key, rating: x.rating, ratedAt: new Date(x.rated_at) }, update: { rating: x.rating, ratedAt: new Date(x.rated_at) } });
          }
        }
        await reconcile(seen, await db.rating.findMany({ where: { profileId } }), async (local) => {
          const t = await targetOf(local.mediaItemId, local.seasonNumber, local.episodeNumber);
          if (first) return t && (await api.post("/sync/ratings", syncBody(t, { rating: local.rating, rated_at: local.ratedAt.toISOString() })));
          await db.rating.delete({ where: { profileId_mediaItemId_seasonNumber_episodeNumber: { profileId, mediaItemId: local.mediaItemId, seasonNumber: local.seasonNumber, episodeNumber: local.episodeNumber } } });
        }, (l) => `${l.mediaItemId}:${l.seasonNumber}:${l.episodeNumber}`);
      }

      if (changed("watchlist.updated_at", "movies.watchlisted_at", "shows.watchlisted_at")) {
        const list = await collection(profileId, "watchlist", "Watchlist");
        const seen = new Set<string>();
        for (const x of await api.get<(TItem & { listed_at: string })[]>("/sync/watchlist")) {
          const r = await resolve(x);
          if (!r) continue;
          seen.add(r.mediaItemId);
          await db.collectionItem.upsert({ where: { collectionId_mediaItemId: { collectionId: list.id, mediaItemId: r.mediaItemId } }, create: { collectionId: list.id, mediaItemId: r.mediaItemId, addedAt: new Date(x.listed_at) }, update: {} });
        }
        await reconcile(seen, await db.collectionItem.findMany({ where: { collectionId: list.id } }), async (local) => {
          const t = await targetOf(local.mediaItemId);
          if (first) return t && (await api.post("/sync/watchlist", syncBody(t)));
          await db.collectionItem.delete({ where: { collectionId_mediaItemId: { collectionId: list.id, mediaItemId: local.mediaItemId } } });
        }, (l) => l.mediaItemId);
      }

      if (changed("lists.updated_at")) {
        const lists = await api.get<{ name: string; description: string | null; ids: { trakt: number } }[]>("/users/me/lists");
        const keep = new Set<string>();
        for (const l of lists) {
          const key = `trakt:${l.ids.trakt}`;
          keep.add(key);
          const c = await collection(profileId, key, l.name, l.description);
          const ids: string[] = [];
          for (const x of await api.get<TItem[]>(`/users/me/lists/${l.ids.trakt}/items`)) {
            const r = await resolve(x);
            if (r) ids.push(r.mediaItemId);
          }
          await db.$transaction([
            db.collectionItem.deleteMany({ where: { collectionId: c.id } }),
            db.collectionItem.createMany({ data: [...new Set(ids)].map((mediaItemId) => ({ collectionId: c.id, mediaItemId })) }),
          ]);
        }
        await db.collection.deleteMany({ where: { profileId, key: { startsWith: "trakt:", notIn: [...keep] } } });
      }

      if (first) {
        // History watched in Zylo before Trakt was connected goes up once.
        for (const e of await db.watchEvent.findMany({ where: { profileId, source: "zylo" } })) {
          const t = await targetOf(e.mediaItemId, e.seasonNumber, e.episodeNumber);
          if (t) await api.post("/sync/history", syncBody(t, { watched_at: e.watchedAt.toISOString() }));
        }
      }

      // Re-read activities if we pushed anything on a first sync, so the next run doesn't re-import our own changes.
      const final = first ? await api.get<TraktActivities>("/sync/last_activities") : now;
      await db.traktAccount.update({ where: { profileId }, data: { syncState: "idle", lastSyncAt: new Date(), lastActivities: final as object, lastError: null } });
    } catch (e) {
      await db.traktAccount.update({ where: { profileId }, data: { syncState: "idle", lastError: (e as Error).message } });
      throw e;
    }
  }

  /** On a first sync, local-only entries are pushed up; afterwards, Trakt wins and they're removed. */
  async function reconcile<L>(seen: Set<string>, local: L[], handle: (l: L) => Promise<unknown>, keyOf: (l: L) => string) {
    for (const l of local) if (!seen.has(keyOf(l))) await handle(l);
  }

  async function collection(profileId: string, key: string, name: string, description: string | null = null) {
    return db.collection.upsert({ where: { profileId_key: { profileId, key } }, create: { profileId, key, name, description }, update: { name, description } });
  }

  async function targetOf(mediaItemId: string, season = -1, episode = -1): Promise<Target | null> {
    const item = await db.mediaItem.findUnique({ where: { id: mediaItemId }, include: { externalIds: { where: { system: "tmdb" } } } });
    const tmdb = item?.externalIds[0]?.value;
    if (!item || !tmdb || (item.type !== "movie" && item.type !== "series")) return null;
    return { type: item.type, tmdbId: Number(tmdb), ...(season >= 0 && { season, episode }) };
  }

  return { app, tokenFor, saveTokens, push, sync, collection };
}

export type TraktService = ReturnType<typeof traktService>;
