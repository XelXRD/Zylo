// Phase 7: AniList + MyAnimeList. Split-cour mapping, list import + matching, finishing an episode updates the
// primary tracker once, offline retry, MAL PKCE sign-in and token refresh. Needs TEST_DATABASE_URL (wiped).
import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import pg from "pg";
import { buildApp } from "./app.js";
import { pickEntry } from "./anime.js";
import { encrypt } from "./secrets.js";

const url = process.env.TEST_DATABASE_URL;
if (!url) throw new Error("Set TEST_DATABASE_URL to an empty, migrated database");
process.env.ZYLO_SECRET ??= "test-secret-test-secret-test-secret-123";

// Kometa's shape: AniDB id → ids. Attack on Titan S3 is two cours on one TVDB season.
const IDS = {
  "13241": { tvdb_id: 267440, tvdb_season: 3, tvdb_epoffset: 0, mal_id: 35760, anilist_id: 99147 },
  "14444": { tvdb_id: 267440, tvdb_season: 3, tvdb_epoffset: 12, mal_id: 38524, anilist_id: 104578 },
  "112": { tvdb_id: 81797, tvdb_season: -1, tvdb_epoffset: 0, mal_id: 21, anilist_id: 21 },
  "112b": { tvdb_id: 81797, tvdb_season: -1, tvdb_epoffset: 1000, mal_id: 9999, anilist_id: 9999 },
  "377": { tvdb_epoffset: 0, imdb_id: "tt0245429", mal_id: 199, anilist_id: 199 },
};
const rows = (r: (typeof IDS)[keyof typeof IDS] & { tvdb_season?: number; tvdb_id?: number }) => ({
  tvdb: r.tvdb_id ?? null, season: r.tvdb_season ?? null, offset: r.tvdb_epoffset, imdb: [], anilist: [r.anilist_id], mal: [r.mal_id],
});

test("pickEntry: split cour by TVDB offset, absolute order, nothing past the list", () => {
  const aot = [rows(IDS["13241"]), rows(IDS["14444"])];
  assert.deepEqual(pickEntry(aot, "anilist", { season: 3, episode: 12, absolute: null }), { id: 99147, episode: 12 });
  assert.deepEqual(pickEntry(aot, "anilist", { season: 3, episode: 13, absolute: null }), { id: 104578, episode: 1 });
  assert.deepEqual(pickEntry(aot, "mal", { season: 3, episode: 22, absolute: null }), { id: 38524, episode: 10 });
  assert.equal(pickEntry(aot, "anilist", { season: 1, episode: 1, absolute: null }), null, "season 1 isn't in this list");
  const op = [rows(IDS["112"]), rows(IDS["112b"])];
  assert.deepEqual(pickEntry(op, "anilist", { season: 5, episode: 3, absolute: 1003 }), { id: 9999, episode: 3 }, "absolute numbering");
});

// --- mocks: TMDB, Kometa list, AniList GraphQL, MAL API + OAuth ---
const anilistList = new Map<number, { status: string; progress: number; episodes: number | null; title: string }>([
  [99147, { status: "CURRENT", progress: 10, episodes: 12, title: "Shingeki no Kyojin Season 3" }],
  [199, { status: "PLANNING", progress: 0, episodes: 1, title: "Sen to Chihiro no Kamikakushi" }],
  [424242, { status: "COMPLETED", progress: 24, episodes: 24, title: "Something Zylo can't match" }],
]);
const anilistEpisodes: Record<number, number> = { 99147: 12, 104578: 10, 199: 1 };
const anilistSaves: [number, number, string][] = [];
let anilistDown = false;
const malSaves: [number, string, string][] = [];
const malTokens: string[] = [];
let lastChallenge = "";

const mock = createServer((req, res) => {
  const u = new URL(req.url!, "http://x");
  const p = u.pathname;
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    const send = (s: number, b?: unknown) => res.writeHead(s, { "content-type": "application/json" }).end(b === undefined ? "" : JSON.stringify(b));
    if (p === "/ids.json") return send(200, IDS);
    if (p.startsWith("/3/")) {
      if (p === "/3/configuration") return send(200, {});
      if (p === "/3/tv/1429") return send(200, { id: 1429, name: "Attack on Titan", first_air_date: "2013-04-07", genres: [], external_ids: { tvdb_id: 267440, imdb_id: "tt2560140" }, seasons: [1, 2, 3].map((n) => ({ season_number: n, name: `Season ${n}`, episode_count: [25, 12, 22][n - 1], air_date: null, poster_path: null })) });
      if (p === "/3/movie/129") return send(200, { id: 129, title: "Spirited Away", release_date: "2001-07-20", genres: [], external_ids: { imdb_id: "tt0245429" } });
      if (p === "/3/find/267440" && u.searchParams.get("external_source") === "tvdb_id") return send(200, { tv_results: [{ id: 1429 }] });
      if (p === "/3/tv/1429/season/4")
        return send(200, { id: 9004, name: "Season 4", season_number: 4, air_date: "2019-04-29", episodes: [1, 2].map((n) => ({ id: 94000 + n, name: `Part 2 #${n}`, episode_number: n, season_number: 4, air_date: n === 1 ? "2019-04-29" : "2019-05-06" })) });
      if (p === "/3/find/tt0245429") return send(200, { movie_results: [{ id: 129 }], tv_results: [] });
      return send(404, { status_message: "nope" });
    }
    if (p === "/anilist") {
      if (anilistDown) return send(500, { errors: [{ message: "Internal Server Error" }] });
      const { query, variables } = JSON.parse(raw);
      if (!/Media\(id: 1\)/.test(query) && req.headers.authorization !== "Bearer anilist-token-0123456789abcdef") return send(401, { errors: [{ message: "Invalid token", status: 401 }] });
      if (query.includes("Viewer")) return send(200, { data: { Viewer: { id: 7, name: "alex_al" } } });
      if (query.includes("MediaListCollection")) {
        const entries = [...anilistList].map(([id, e]) => ({ status: e.status, progress: e.progress, score: 0, media: { id, episodes: e.episodes, title: { userPreferred: e.title } } }));
        return send(200, { data: { MediaListCollection: { lists: [{ entries }, { entries: entries.slice(0, 1) }] } } }); // a custom list repeats one
      }
      if (query.includes("SaveMediaListEntry")) {
        anilistSaves.push([variables.id, variables.p, variables.s]);
        anilistList.set(variables.id, { ...(anilistList.get(variables.id) ?? { title: "x", episodes: anilistEpisodes[variables.id] }), status: variables.s, progress: variables.p });
        return send(200, { data: { SaveMediaListEntry: { id: 1 } } });
      }
      if (query.includes("Media(id: $id)")) return send(200, { data: { Media: { episodes: anilistEpisodes[variables.id] ?? null } } });
      return send(200, { data: { Media: { id: 1 } } });
    }
    // TVDB numbers the same episode as season 3, episode 17 (TMDB: season 4, episode 1).
    if (p === "/tvdb/login") return send(200, { data: { token: "t" } });
    if (p === "/tvdb/series/267440/episodes/default")
      return send(200, { data: { episodes: [{ id: 1, seasonNumber: 3, number: 16, aired: "2019-04-22" }, { id: 2, seasonNumber: 3, number: 17, aired: "2019-04-29" }] }, links: { next: null } });
    if (p === "/mal-auth/token") {
      const f = new URLSearchParams(raw);
      if (f.get("client_id") !== "mal-client-id-0123456789abcdef") return send(401, { error: "invalid_client" });
      if (f.get("grant_type") === "authorization_code") {
        if (f.get("code") !== "mal-code" || f.get("code_verifier") !== lastChallenge || !f.get("redirect_uri")) return send(400, { error: "invalid_grant" });
      } else if (f.get("refresh_token") !== "mal-refresh-1") return send(400, { error: "invalid_grant" });
      const access = `mal-access-${malTokens.length + 1}`;
      malTokens.push(access);
      return send(200, { access_token: access, refresh_token: "mal-refresh-1", expires_in: 2678400 });
    }
    if (p.startsWith("/mal/")) {
      if (p === "/mal/anime/1") return req.headers["x-mal-client-id"] === "mal-client-id-0123456789abcdef" ? send(200, { id: 1 }) : send(403, { error: "forbidden" });
      if (!malTokens.includes(String(req.headers.authorization).replace("Bearer ", ""))) return send(401, { error: "invalid_token" });
      if (p === "/mal/users/@me") return send(200, { name: "alex_mal" });
      if (p === "/mal/users/@me/animelist")
        return send(200, { data: [{ node: { id: 35760, title: "Shingeki no Kyojin Season 3", num_episodes: 12 }, list_status: { status: "completed", score: 9, num_episodes_watched: 12 } }], paging: {} });
      const m = p.match(/^\/mal\/anime\/(\d+)(\/my_list_status)?$/);
      if (m?.[2] && req.method === "PATCH") {
        const f = new URLSearchParams(raw);
        malSaves.push([Number(m[1]), f.get("num_watched_episodes")!, f.get("status")!]);
        return send(200, {});
      }
      if (m) return send(200, { num_episodes: Number(m[1]) === 38524 ? 10 : 12 });
    }
    send(404, {});
  });
});

test("phase 7: connect AniList + MAL, import and match, episodes update the primary tracker once", async () => {
  await new Promise<void>((r) => mock.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${(mock.address() as { port: number }).port}`;
  const client = new pg.Client({ connectionString: url });
  await client.connect();
  await client.query('TRUNCATE "User", "Integration", "MediaItem" CASCADE');
  const app = buildApp({
    databaseUrl: url,
    bases: { tmdb: `${base}/3`, tvdb: `${base}/tvdb` },
    animeBases: { anilist: `${base}/anilist`, anilistAuth: `${base}/anilist-auth`, malApi: `${base}/mal`, malAuth: `${base}/mal-auth`, ids: `${base}/ids.json` },
  });
  let cookie = "";
  let profile = "";
  const call = async (method: string, path: string, body?: object) => {
    const res = await app.inject({ method: method as "GET", url: path, payload: body, headers: { cookie, ...(profile && { "x-zylo-profile": profile }) } });
    return { status: res.statusCode, json: res.body ? JSON.parse(res.body) : null };
  };
  const watch = (season: number, episode: number) => call("POST", "/titles/series/1429/watched", { season, episode });
  try {
    cookie = String((await app.inject({ method: "POST", url: "/setup", payload: { email: "a@example.com", password: "password123", name: "Alex" } })).headers["set-cookie"]).split(";")[0];
    profile = (await call("GET", "/profiles")).json[0].id;
    await call("PUT", "/integrations/tmdb", { token: "eyJhbGciOiJIUzI1NiJ9.good-read-access-token" });

    // Admin adds the apps: AniList ids are numbers; MAL's is checked against MAL.
    assert.equal((await call("PUT", "/integrations/anilist", { clientId: "not-a-number" })).status, 400);
    assert.equal((await call("PUT", "/integrations/anilist", { clientId: "12345" })).status, 204);
    assert.equal((await call("PUT", "/integrations/mal", { clientId: "wrong-mal-client-id-000000" })).json.error, "MyAnimeList didn't accept that Client ID.");
    assert.equal((await call("PUT", "/integrations/mal", { clientId: "mal-client-id-0123456789abcdef" })).status, 204);
    assert.equal((await call("POST", "/integrations/mal/test")).json.ok, true);
    const integrations = (await call("GET", "/integrations")).json.map((i: { id: string; connected: boolean }) => `${i.id}:${i.connected}`);
    assert.deepEqual(integrations, ["tmdb:true", "tvdb:false", "trakt:false", "anilist:true", "mal:true"]);

    // AniList: implicit grant, token comes back to Zylo's callback page.
    const auth = (await call("POST", "/anime/anilist/authorize", { redirectUri: "http://zylo.local/connect/anilist" })).json.url;
    assert.equal(auth, `${base}/anilist-auth/authorize?client_id=12345&response_type=token`);
    assert.equal((await call("POST", "/anime/anilist/connect", { accessToken: "wrong-token-000000000000" })).status, 400);
    assert.equal((await call("POST", "/anime/anilist/connect", { accessToken: "anilist-token-0123456789abcdef", expiresIn: 31536000 })).json.userName, "alex_al");
    assert.equal((await call("POST", "/anime/anilist/sync")).status, 204);

    // Imported once each (the custom list's repeat is ignored), matched to Zylo titles where Kometa knows them.
    let status = (await call("GET", "/anime")).json;
    assert.equal(status.primary, "anilist");
    assert.deepEqual(status.accounts.map((a: { tracker: string; entries: number; lastError: string | null }) => [a.tracker, a.entries, a.lastError]), [["anilist", 3, null]]);
    let list = (await call("GET", "/anime/list")).json;
    const byId = (id: number) => list.entries.find((e: { id: number }) => e.id === id);
    assert.deepEqual(byId(99147).card, { type: "series", tmdbId: 1429, title: "Shingeki no Kyojin Season 3", year: null, posterPath: null });
    assert.equal(byId(199).card.type, "movie");
    assert.equal(byId(199).card.tmdbId, 129);
    assert.equal(byId(424242).card, null, "unmatched entries are still listed");
    assert.equal(byId(99147).url, "https://anilist.co/anime/99147");

    // Finishing S3E11 moves cour 1 forward; S3E12 completes it; repeating an episode sends nothing.
    assert.equal((await watch(3, 11)).status, 204);
    assert.deepEqual(anilistSaves, [[99147, 11, "CURRENT"]]);
    await watch(3, 12);
    assert.deepEqual(anilistSaves.at(-1), [99147, 12, "COMPLETED"]);
    assert.equal((await watch(3, 12)).status, 204, "marking twice in the same second is fine");
    await watch(3, 5);
    assert.equal(anilistSaves.length, 2, "once per episode, never backwards");

    // S3E13 is episode 1 of the second cour, which wasn't on the list yet.
    await watch(3, 13);
    assert.deepEqual(anilistSaves.at(-1), [104578, 1, "CURRENT"]);

    // Tracker down: progress waits in Zylo, then goes on the next sync. Playback isn't affected.
    anilistDown = true;
    assert.equal((await watch(3, 14)).status, 204);
    assert.equal(anilistSaves.length, 3);
    anilistDown = false;
    await call("POST", "/anime/anilist/sync");
    assert.deepEqual(anilistSaves.at(-1), [104578, 2, "CURRENT"]);
    assert.equal(anilistSaves.length, 4, "sent once");
    await call("POST", "/anime/anilist/sync");
    assert.equal(anilistSaves.length, 4, "nothing left to send");

    // A film: one episode, completed.
    assert.equal((await call("POST", "/titles/movie/129/watched", {})).status, 204);
    assert.deepEqual(anilistSaves.at(-1), [199, 1, "COMPLETED"]);

    // MAL: PKCE with the verifier inside the encrypted state; another profile can't use it.
    const malAuth = new URL((await call("POST", "/anime/mal/authorize", { redirectUri: "http://zylo.local/connect/mal" })).json.url);
    assert.equal(malAuth.searchParams.get("code_challenge_method"), "plain");
    lastChallenge = malAuth.searchParams.get("code_challenge")!;
    assert.ok(lastChallenge.length >= 43 && lastChallenge.length <= 128);
    const state = malAuth.searchParams.get("state")!;
    const kid = (await call("POST", "/profiles", { name: "Kid", color: "teal" })).json.id;
    const real = profile;
    profile = kid;
    assert.match((await call("POST", "/anime/mal/connect", { code: "mal-code", state })).json.error, /different profile/);
    profile = real;
    assert.equal((await call("POST", "/anime/mal/connect", { code: "bad", state })).status, 400);
    assert.equal((await call("POST", "/anime/mal/connect", { code: "mal-code", state })).json.userName, "alex_mal");
    await call("POST", "/anime/mal/sync");

    // Switch primary to MAL: the next episode goes there, not to AniList.
    assert.equal((await call("PUT", "/anime/primary", { primary: "mal" })).status, 204);
    await watch(3, 15);
    assert.deepEqual(malSaves, [[38524, "3", "watching"]]);
    assert.equal(anilistSaves.length, 5);

    // MAL tokens refresh before they expire.
    await client.query(`UPDATE "AnimeAccount" SET token = $1 WHERE tracker = 'mal'`, [encrypt({ access: "mal-access-1", refresh: "mal-refresh-1", expiresAt: Date.now() + 3600_000 })]);
    await watch(3, 16);
    assert.equal(malTokens.length, 2, "refreshed");
    assert.deepEqual(malSaves.at(-1), [38524, "4", "watching"]);

    // With TVDB connected, TMDB's numbering is translated by air date: TMDB S4E1 = TVDB S3E17 = cour 2 episode 5.
    assert.equal((await call("PUT", "/integrations/tvdb", { apikey: "tvdb-key-123" })).status, 204);
    await watch(4, 1);
    assert.deepEqual(malSaves.at(-1), [38524, "5", "watching"]);

    status = (await call("GET", "/anime")).json;
    assert.deepEqual(status.accounts.map((a: { tracker: string }) => a.tracker), ["anilist", "mal"]);
    assert.equal((await call("DELETE", "/anime/anilist")).status, 204);
    assert.equal((await client.query(`SELECT count(*)::int AS n FROM "AnimeEntry" WHERE tracker = 'anilist'`)).rows[0].n, 0);
    list = (await call("GET", "/anime/list")).json;
    assert.equal(list.tracker, "mal");
  } finally {
    await app.close();
    await client.end();
    mock.close();
  }
});
