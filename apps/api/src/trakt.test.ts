// Phase 3 against a stateful mock of Trakt (and a minimal TMDB). Needs TEST_DATABASE_URL (wiped).
import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import pg from "pg";
import { buildApp } from "./app.js";
import { traktClient } from "./trakt.js";

const url = process.env.TEST_DATABASE_URL;
if (!url) throw new Error("Set TEST_DATABASE_URL to an empty, migrated database");
process.env.ZYLO_SECRET ??= "test-secret-test-secret-test-secret-123";

const TMDB_TOKEN = "eyJhbGciOiJIUzI1NiJ9.good-read-access-token";
const CLIENT_ID = "trakt-client-id-0123456789abcdef";

// --- mock Trakt state ---
const t = {
  polls: 0,
  sessionGone: false,
  failPosts: false,
  accessToken: "access-1",
  refreshToken: "refresh-1",
  posts: [] as { path: string; body: any }[],
  revoked: [] as string[],
  act: { movies: { watched_at: "a", rated_at: "a", watchlisted_at: "a" }, episodes: { watched_at: "a", rated_at: "a" }, shows: { rated_at: "a", watchlisted_at: "a" }, lists: { updated_at: "a" }, watchlist: { updated_at: "a" } } as Record<string, Record<string, string>>,
  history: [
    { id: 1, watched_at: "2026-10-01T20:00:00.000Z", type: "movie", movie: { title: "Pulp Fiction", year: 1994, ids: { trakt: 1, tmdb: 680 } } },
    { id: 2, watched_at: "2026-10-02T20:00:00.000Z", type: "episode", episode: { season: 1, number: 1, ids: { tmdb: 63056 } }, show: { title: "Game of Thrones", year: 2011, ids: { trakt: 2, tmdb: 1399 } } },
    { id: 3, watched_at: "2026-10-03T20:00:00.000Z", type: "movie", movie: { title: "Obscure", year: 1950, ids: { trakt: 3, tmdb: null } } },
  ],
  ratings: new Map<number, { rating: number; rated_at: string; title: string }>([[680, { rating: 9, rated_at: "2026-10-01T21:00:00.000Z", title: "Pulp Fiction" }]]),
  watchlist: new Map<number, string>([[603, "The Matrix"]]),
};
const bump = (...keys: string[]) => {
  for (const k of keys) {
    const [a, b] = k.split(".");
    t.act[a][b] = new Date(Date.now() + Math.random()).toISOString();
  }
};

const mock = createServer((req, res) => {
  const u = new URL(req.url!, "http://x");
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    const body = raw ? JSON.parse(raw) : null;
    const send = (s: number, b?: unknown) => res.writeHead(s, { "content-type": "application/json" }).end(b === undefined ? "" : JSON.stringify(b));
    const p = u.pathname;

    // Minimal TMDB: any film id, plus Game of Thrones.
    if (p.startsWith("/3/")) {
      if (req.headers.authorization !== `Bearer ${TMDB_TOKEN}`) return send(401, { status_message: "Invalid API key" });
      if (p === "/3/configuration") return send(200, { images: {} });
      const m = p.match(/^\/3\/movie\/(\d+)$/);
      if (m) return send(200, { id: Number(m[1]), title: `Film ${m[1]}`, release_date: "2000-01-01", poster_path: `/p${m[1]}.jpg`, runtime: 100, genres: [] });
      if (p === "/3/tv/1399") return send(200, { id: 1399, name: "Game of Thrones", first_air_date: "2011-04-17", poster_path: "/got.jpg", genres: [], seasons: [] });
      return send(404, { status_message: "Not found" });
    }

    // Trakt
    if (!String(req.headers["user-agent"]).startsWith("Zylo/")) return send(403); // Trakt's Cloudflare wants a real User-Agent
    if (req.headers["trakt-api-key"] !== CLIENT_ID) return send(403);
    if (p === "/oauth/device/code") return send(200, { device_code: "dev-code", user_code: "ABCD1234", verification_url: "https://trakt.tv/activate", expires_in: 600, interval: 5 });
    if (p === "/oauth/device/token") {
      if ("client_secret" in body) return send(400, { error: "invalid_request" }); // new apps have none; we must not invent one
      return ++t.polls < 2 ? send(400) : send(200, { access_token: t.accessToken, refresh_token: t.refreshToken, expires_in: 86400, created_at: Math.floor(Date.now() / 1000) });
    }
    if (p === "/oauth/token") {
      if (t.sessionGone) return send(400, { error: "invalid_grant", error_description: "session not found" });
      if (body.refresh_token !== t.refreshToken || body.redirect_uri !== "http://localhost") return send(400); // must match the app page
      t.accessToken = "access-2";
      t.refreshToken = "refresh-2";
      return send(200, { access_token: t.accessToken, refresh_token: t.refreshToken, expires_in: 86400, created_at: Math.floor(Date.now() / 1000) });
    }
    if (p === "/oauth/revoke") return t.revoked.push(body.token), send(200);
    if (req.headers.authorization !== `Bearer ${t.accessToken}`) return send(401);

    if (req.method === "POST") {
      if (t.failPosts) return send(503);
      t.posts.push({ path: p, body });
      if (p === "/sync/ratings") for (const m of body.movies ?? []) t.ratings.set(m.ids.tmdb, { rating: m.rating, rated_at: m.rated_at, title: "?" }), bump("movies.rated_at");
      if (p === "/sync/ratings/remove") for (const m of body.movies ?? []) t.ratings.delete(m.ids.tmdb), bump("movies.rated_at");
      if (p === "/sync/watchlist") for (const m of body.movies ?? []) t.watchlist.set(m.ids.tmdb, "?"), bump("watchlist.updated_at");
      if (p === "/sync/watchlist/remove") for (const m of body.movies ?? []) t.watchlist.delete(m.ids.tmdb), bump("watchlist.updated_at");
      if (p === "/sync/history") bump("movies.watched_at");
      return send(201, { added: {} });
    }

    if (p === "/users/settings") return send(200, { user: { username: "alex_watches" } });
    if (p === "/sync/last_activities") return send(200, { all: "x", ...t.act });
    if (p === "/sync/history") {
      const page = Number(u.searchParams.get("page") ?? 1);
      return send(200, page === 1 ? t.history : []);
    }
    if (p === "/sync/ratings/movies") return send(200, [...t.ratings].map(([tmdb, r]) => ({ type: "movie", rating: r.rating, rated_at: r.rated_at, movie: { title: r.title, year: 2000, ids: { tmdb } } })));
    if (p === "/sync/ratings/shows" || p === "/sync/ratings/episodes") return send(200, []);
    if (p === "/sync/watchlist")
      return send(200, [
        ...[...t.watchlist].map(([tmdb, title]) => ({ type: "movie", listed_at: "2026-10-05T10:00:00.000Z", movie: { title, year: 1999, ids: { tmdb } } })),
        { type: "season", listed_at: "2026-10-05T10:00:00.000Z", season: { number: 2 }, show: { title: "Game of Thrones", year: 2011, ids: { tmdb: 1399 } } },
      ]);
    if (p === "/users/me/lists") return send(200, [{ name: "Favourites", description: "All-timers", ids: { trakt: 77 } }]);
    if (p === "/users/me/lists/77/items") return send(200, [{ type: "movie", movie: { title: "Pulp Fiction", year: 1994, ids: { tmdb: 680 } } }]);
    if (p.startsWith("/recommendations/movies")) return send(200, [{ title: "Rec", year: 2001, ids: { tmdb: 4242 } }]);
    if (p.startsWith("/recommendations/shows")) return send(200, []);
    send(404);
  });
});

test("phase 3: Trakt connect, import, push, outbox, mirror, refresh", async () => {
  await new Promise<void>((r) => mock.listen(0, r));
  const port = (mock.address() as { port: number }).port;
  const base = `http://127.0.0.1:${port}`;

  const client = new pg.Client({ connectionString: url });
  await client.connect();
  await client.query('TRUNCATE "User", "Integration", "MediaItem" CASCADE');

  const app = buildApp({ databaseUrl: url, bases: { tmdb: `${base}/3`, trakt: base } });
  let cookie = "";
  let profile = "";
  const call = async (method: string, path: string, body?: object) => {
    const res = await app.inject({ method: method as "GET", url: path, payload: body, headers: { cookie, ...(profile && { "x-zylo-profile": profile }) } });
    return { status: res.statusCode, json: res.body ? JSON.parse(res.body) : null, setCookie: res.headers["set-cookie"] };
  };

  cookie = String((await call("POST", "/setup", { email: "a@example.com", password: "password123", name: "Alex" })).setCookie).split(";")[0];
  profile = (await call("GET", "/profiles")).json[0].id;
  assert.equal((await call("PUT", "/integrations/tmdb", { token: TMDB_TOKEN })).status, 204);

  // Profile header must belong to the user.
  const saved = profile;
  profile = "00000000-0000-0000-0000-000000000000";
  assert.equal((await call("GET", "/library")).status, 400);
  profile = saved;

  // Everything works before Trakt exists: local rating and watch.
  assert.equal((await call("PUT", "/titles/movie/550/rating", { rating: 7 })).status, 204);
  assert.equal((await call("POST", "/titles/movie/550/watched", {})).status, 204);
  assert.equal((await call("POST", "/titles/series/1399/watched", {})).status, 400, "a series needs an episode");
  assert.equal((await call("GET", "/trakt")).json.available, false);
  assert.equal((await call("POST", "/trakt/connect")).status, 409);

  // Admin adds the Trakt app; a wrong Client ID is caught.
  const refused = await call("PUT", "/integrations/trakt", { clientId: "wrong-client-id-000000000" });
  assert.equal(refused.status, 400);
  assert.match(refused.json.error, /^Trakt refused that Client ID \(403/, "says what Trakt answered");
  assert.equal((await call("PUT", "/integrations/trakt", { clientId: CLIENT_ID, redirectUri: "http://localhost" })).status, 204, "Client ID only");

  // Device sign-in: pending, then connected (which runs the first sync inline in tests).
  const code = (await call("POST", "/trakt/connect")).json;
  assert.equal(code.userCode, "ABCD1234");
  assert.equal((await call("POST", "/trakt/connect/poll")).json.status, "pending");
  assert.equal((await call("POST", "/trakt/connect/poll")).json.status, "connected");

  const status = (await call("GET", "/trakt")).json;
  assert.equal(status.username, "alex_watches");
  assert.equal(status.lastError, null);
  assert.ok(status.lastSyncAt);

  // First sync imported Trakt data and pushed Zylo-only data up.
  const lib = (await call("GET", "/library")).json;
  assert.deepEqual(lib.watchlist.map((c: { tmdbId: number }) => c.tmdbId), [603], "season item skipped");
  assert.deepEqual(lib.lists.map((l: { name: string; count: number }) => [l.name, l.count]), [["Favourites", 1]]);
  assert.equal(lib.stats.filmsWatched, 2, "550 (Zylo) + 680 (Trakt); item without TMDB id skipped");
  assert.equal(lib.stats.episodesWatched, 1);
  assert.ok(t.posts.some((x) => x.path === "/sync/ratings" && x.body.movies[0].ids.tmdb === 550 && x.body.movies[0].rating === 7), "local rating pushed");
  assert.ok(t.posts.some((x) => x.path === "/sync/history" && x.body.movies[0].ids.tmdb === 550), "local watch pushed");

  // Imported titles start as stubs and fill in when viewed.
  const stub = (await client.query(`SELECT language FROM "MediaItem" m JOIN "ExternalId" e ON e."mediaItemId" = m.id WHERE e.value = '603' AND e.type = 'movie'`)).rows[0];
  assert.equal(stub.language, "stub");
  assert.equal((await call("GET", "/titles/movie/603")).json.title, "Film 603");

  // Done-when: a rating made in Zylo appears on Trakt.
  t.posts = [];
  assert.equal((await call("PUT", "/titles/movie/680/rating", { rating: 10 })).status, 204);
  assert.deepEqual(t.posts[0].path, "/sync/ratings");
  assert.equal(t.posts[0].body.movies[0].ids.tmdb, 680);
  assert.equal(t.posts[0].body.movies[0].rating, 10);
  assert.equal(t.ratings.get(680)!.rating, 10);

  // Episode watched → nested show/season/episode body.
  assert.equal((await call("POST", "/titles/series/1399/watched", { season: 1, episode: 2 })).status, 204);
  assert.deepEqual(t.posts[1].body.shows[0].seasons[0].episodes[0].number, 2);
  const state = (await call("GET", "/titles/series/1399/state")).json;
  assert.deepEqual(state.watchedEpisodes.sort(), ["1:1", "1:2"]);

  // Watchlist add/remove are pushed.
  assert.equal((await call("PUT", "/titles/movie/550/watchlist")).status, 204);
  assert.ok(t.watchlist.has(550));
  assert.equal((await call("GET", "/titles/movie/550/state")).json.inWatchlist, true);

  // Trakt down: the change waits in the outbox, then goes up on the next sync.
  t.failPosts = true;
  assert.equal((await call("PUT", "/titles/movie/603/rating", { rating: 6 })).status, 204, "the user isn't blocked");
  assert.equal((await call("GET", "/trakt")).json.pendingChanges, 1);
  t.failPosts = false;
  assert.equal((await call("POST", "/trakt/sync")).status, 202);
  assert.equal((await call("GET", "/trakt")).json.pendingChanges, 0);
  assert.equal(t.ratings.get(603)!.rating, 6);

  // After the first sync, Trakt wins: things removed there disappear here.
  t.watchlist.clear();
  bump("watchlist.updated_at");
  t.ratings.delete(680);
  bump("movies.rated_at");
  await call("POST", "/trakt/sync");
  assert.equal((await call("GET", "/library")).json.watchlist.length, 0);
  assert.equal((await call("GET", "/titles/movie/680/state")).json.rating, null);
  assert.equal((await call("GET", "/titles/movie/550/state")).json.rating, 7, "ratings still on Trakt are kept");

  // Tokens near expiry are refreshed with the refresh token.
  await client.query(`UPDATE "TraktAccount" SET "expiresAt" = now() + interval '10 minutes'`);
  await call("POST", "/trakt/sync");
  assert.equal(t.accessToken, "access-2");
  assert.equal((await call("GET", "/trakt")).json.lastError, null);

  // A refresh Trakt rejects (tokens from before its sign-in migration) asks for a fresh connection, plainly.
  t.sessionGone = true;
  await client.query(`UPDATE "TraktAccount" SET "expiresAt" = now() + interval '10 minutes'`);
  await call("POST", "/trakt/sync");
  assert.match((await call("GET", "/trakt")).json.lastError, /connect again/);
  t.sessionGone = false;
  await client.query(`UPDATE "TraktAccount" SET "expiresAt" = now() + interval '7 days', "lastError" = null`);

  // Recommendations become full title cards.
  assert.equal((await call("GET", "/discover/recommended")).json.results[0].title, "Film 4242");

  // Disconnect: token revoked, local library kept.
  assert.equal((await call("DELETE", "/trakt")).status, 204);
  assert.deepEqual(t.revoked, ["access-2"]);
  assert.equal((await call("GET", "/trakt")).json.connected, false);
  assert.ok((await call("GET", "/library")).json.stats.filmsWatched >= 2);

  await client.end();
  await app.close();
  mock.close();
});

test("Trakt OAuth goes to auth.trakt.tv, API calls to api.trakt.tv; no secret unless one exists", async () => {
  const seen: { url: string; body: Record<string, unknown> | null }[] = [];
  const real = globalThis.fetch;
  globalThis.fetch = (async (url: string, init?: RequestInit) => {
    seen.push({ url, body: init?.body ? JSON.parse(String(init.body)) : null });
    return new Response(JSON.stringify({ access_token: "a", refresh_token: "r", expires_in: 604800, created_at: 0, device_code: "d", user_code: "U", verification_url: "https://auth.trakt.tv/activate", expires_in_s: 600, interval: 5, user: { username: "u" } }), { status: 200 });
  }) as typeof fetch;
  try {
    const c = traktClient({ clientId: "cid", redirectUri: "http://localhost" });
    await c.deviceCode();
    await c.pollDevice("d");
    await c.refresh("r");
    await c.revoke("a");
    await c.as("a").get("/users/settings");
    assert.deepEqual(seen.map((x) => new URL(x.url).host), ["auth.trakt.tv", "auth.trakt.tv", "auth.trakt.tv", "auth.trakt.tv", "api.trakt.tv"]);
    assert.ok(seen.every((x) => !x.body || !("client_secret" in x.body)), "no client_secret sent");
    assert.equal(seen[2].body!.redirect_uri, "http://localhost");
    seen.length = 0;
    await traktClient({ clientId: "cid", clientSecret: "legacy" }).pollDevice("d");
    assert.equal(seen[0].body!.client_secret, "legacy", "older apps with a secret keep sending it");
  } finally {
    globalThis.fetch = real;
  }
});
