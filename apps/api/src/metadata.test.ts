// Phase 2 against a local mock of TMDB v3 and TVDB v4 (response shapes from their published API docs).
// Needs TEST_DATABASE_URL: an empty, migrated Postgres database. It is wiped.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { createHash } from "node:crypto";
import pg from "pg";
import { PrismaPg } from "@prisma/adapter-pg";
import { PrismaClient } from "./generated/prisma/client.js";
import { buildApp } from "./app.js";
import { metadataService } from "./metadata.js";
import { decrypt, encrypt } from "./secrets.js";

const url = process.env.TEST_DATABASE_URL;
if (!url) throw new Error("Set TEST_DATABASE_URL to an empty, migrated database");
process.env.ZYLO_SECRET ??= "test-secret-test-secret-test-secret-123";

const TOKEN = "eyJhbGciOiJIUzI1NiJ9.good-read-access-token";
const calls: string[] = [];

const movie = {
  id: 550, title: "Fight Club", original_title: "Fight Club", overview: "A ticking-time-bomb insomniac…",
  release_date: "1999-10-15", runtime: 139, poster_path: "/p550.jpg", backdrop_path: "/b550.jpg", vote_average: 8.4,
  genres: [{ id: 18, name: "Drama" }], imdb_id: "tt0137523",
  credits: { cast: [{ id: 1, name: "Edward Norton", character: "Narrator", profile_path: "/en.jpg", order: 0 }, { id: 2, name: "Brad Pitt", character: "Tyler Durden", profile_path: null, order: 1 }], crew: [{ id: 3, name: "David Fincher", job: "Director" }, { id: 4, name: "Someone", job: "Producer" }] },
  videos: { results: [{ key: "teaser1", site: "YouTube", type: "Teaser" }, { key: "trail1", site: "YouTube", type: "Trailer", official: true }] },
  external_ids: { imdb_id: "tt0137523" },
  recommendations: { results: [{ id: 680, title: "Pulp Fiction", release_date: "1994-09-10", poster_path: "/p680.jpg" }] },
  release_dates: { results: [{ iso_3166_1: "GB", release_dates: [{ certification: "" }, { certification: "18" }] }, { iso_3166_1: "US", release_dates: [{ certification: "R" }] }] },
};
const series = {
  id: 1399, name: "Game of Thrones", original_name: "Game of Thrones", overview: "Seven noble families…", first_air_date: "2011-04-17",
  episode_run_time: [60], poster_path: "/p1399.jpg", backdrop_path: "/b1399.jpg", vote_average: 8.5, genres: [{ id: 10765, name: "Sci-Fi & Fantasy" }],
  status: "Ended", created_by: [{ id: 9, name: "David Benioff" }],
  seasons: [{ season_number: 0, name: "Specials", episode_count: 1, air_date: null, poster_path: null }, { season_number: 1, name: "Season 1", episode_count: 2, air_date: "2011-04-17", poster_path: "/s1.jpg" }],
  external_ids: { imdb_id: "tt0944947", tvdb_id: 121361 },
  content_ratings: { results: [{ iso_3166_1: "GB", rating: "18" }] },
  recommendations: { results: [] },
};
const season1 = {
  id: 3624, name: "Season 1", season_number: 1, air_date: "2011-04-17", poster_path: "/s1.jpg",
  episodes: [
    { id: 63056, name: "Winter Is Coming", episode_number: 1, season_number: 1, air_date: "2011-04-17", runtime: 62, still_path: "/e1.jpg", overview: "…" },
    { id: 63057, name: "The Kingsroad", episode_number: 2, season_number: 1, air_date: "2011-04-24", runtime: 56, still_path: null, overview: "…" },
  ],
};

const mock = createServer((req, res) => {
  const u = new URL(req.url!, "http://x");
  calls.push(`${req.method} ${u.pathname}`);
  const send = (status: number, body: unknown) => res.writeHead(status, { "content-type": "application/json" }).end(JSON.stringify(body));
  if (u.pathname.startsWith("/3/")) {
    if (req.headers.authorization !== `Bearer ${TOKEN}`) return send(401, { status_code: 7, status_message: "Invalid API key: You must be granted a valid key." });
    const routes: Record<string, unknown> = {
      "/3/configuration": { images: {} },
      "/3/movie/550": movie,
      "/3/tv/1399": series,
      "/3/tv/1399/season/1": season1,
      "/3/search/multi": { page: 1, total_pages: 1, results: [{ ...movie, media_type: "movie" }, { ...series, media_type: "tv" }, { id: 7, media_type: "person", name: "Brad Pitt" }] },
      "/3/trending/all/week": { results: [{ ...movie, media_type: "movie" }] },
    };
    return u.pathname in routes ? send(200, routes[u.pathname]) : send(404, { status_message: "The resource you requested could not be found." });
  }
  if (u.pathname === "/v4/login") {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => (JSON.parse(body).apikey === "tvdb-key-123" ? send(200, { status: "success", data: { token: "tvdb-token" } }) : send(401, { status: "failure", message: "Unauthorized" })));
    return;
  }
  if (u.pathname === "/v4/series/121361/episodes/dvd") {
    if (req.headers.authorization !== "Bearer tvdb-token") return send(401, {});
    return send(200, {
      status: "success",
      data: { series: { id: 121361 }, episodes: [{ id: 2, name: "The Kingsroad", seasonNumber: 1, number: 2 }, { id: 1, name: "Winter Is Coming", seasonNumber: 1, number: 1, absoluteNumber: 1, aired: "2011-04-17" }] },
      links: { next: null },
    });
  }
  send(404, {});
});

test("secrets: round trip, and tampering is detected", () => {
  const box = encrypt({ token: "abc" });
  assert.deepEqual(decrypt(box), { token: "abc" });
  assert.ok(!box.includes("abc"));
  const parts = box.split(".");
  parts[3] = Buffer.from("x").toString("base64url");
  assert.throws(() => decrypt(parts.join(".")));
  assert.throws(() => decrypt(box, "a-completely-different-secret-of-enough-length"));
});

test("phase 2: connect TMDB/TVDB, search, titles, seasons, refresh", async () => {
  await new Promise<void>((r) => mock.listen(0, r));
  const port = (mock.address() as { port: number }).port;
  const bases = { tmdb: `http://127.0.0.1:${port}/3`, tvdb: `http://127.0.0.1:${port}/v4` };

  const client = new pg.Client({ connectionString: url });
  await client.connect();
  await client.query('TRUNCATE "User", "Integration", "MediaItem" CASCADE');

  const app = buildApp({ databaseUrl: url, bases });
  const call = async (method: string, path: string, body?: object, cookie?: string) => {
    const res = await app.inject({ method: method as "GET", url: path, payload: body, headers: cookie ? { cookie } : {} });
    return { status: res.statusCode, json: res.body ? JSON.parse(res.body) : null, raw: res.body, setCookie: res.headers["set-cookie"] };
  };

  const setup = await call("POST", "/setup", { email: "admin@example.com", password: "password123", name: "Alex" });
  const admin = String(setup.setCookie).split(";")[0];

  // A non-admin member, made directly in the database.
  await client.query(`INSERT INTO "User" (id, email, "passwordHash") VALUES ('u2', 'member@example.com', 'x')`);
  await client.query(`INSERT INTO "Session" ("tokenHash", "userId", "expiresAt") VALUES ($1, 'u2', now() + interval '1 day')`, [
    createHash("sha256").update("member-token").digest("hex"),
  ]);
  const member = "zylo_session=member-token";

  // Not connected yet: clear, specific errors.
  assert.deepEqual((await call("GET", "/metadata/status", undefined, member)).json, { tmdb: false, tvdb: false });
  const notYet = await call("GET", "/search?q=fight", undefined, member);
  assert.equal(notYet.status, 409);
  assert.equal(notYet.json.code, "metadata_not_configured");

  // Only the admin can connect, and a bad token is rejected before saving.
  assert.equal((await call("PUT", "/integrations/tmdb", { token: TOKEN }, member)).status, 403);
  const bad = await call("PUT", "/integrations/tmdb", { token: "eyJ-not-a-real-token-at-all" }, admin);
  assert.equal(bad.status, 400);
  assert.match(bad.json.error, /rejected/);
  assert.equal((await call("PUT", "/integrations/tmdb", { token: TOKEN, language: "en-GB" }, admin)).status, 204);

  const list = await call("GET", "/integrations", undefined, admin);
  assert.equal(list.json.find((i: { id: string }) => i.id === "tmdb").connected, true);
  assert.ok(!list.raw.includes(TOKEN), "token never leaves the server");
  const stored = (await client.query(`SELECT secret FROM "Integration" WHERE id = 'tmdb'`)).rows[0].secret;
  assert.ok(!stored.includes(TOKEN), "token is encrypted at rest");

  // Search: films and series only.
  const search = await call("GET", "/search?q=fight", undefined, member);
  assert.deepEqual(search.json.results.map((r: { type: string }) => r.type), ["movie", "series"]);

  // Film detail, certification for the viewer's region, cached after first fetch.
  const before = calls.length;
  const film = (await call("GET", "/titles/movie/550", undefined, member)).json;
  assert.equal(film.title, "Fight Club");
  assert.equal(film.certification, "18");
  assert.equal(film.trailerKey, "trail1");
  assert.deepEqual(film.directors, ["David Fincher"]);
  assert.equal(film.cast[0].name, "Edward Norton");
  assert.equal(film.recommendations[0].title, "Pulp Fiction");
  assert.equal((await call("GET", "/titles/movie/550", undefined, member)).json.id, film.id);
  assert.equal(calls.length - before, 1, "second view is served from the database");

  await client.query(`UPDATE "User" SET settings = '{"region":"US"}' WHERE id = 'u2'`);
  assert.equal((await call("GET", "/titles/movie/550", undefined, member)).json.certification, "R");

  // Series, seasons and episodes.
  const show = (await call("GET", "/titles/series/1399", undefined, member)).json;
  assert.equal(show.hasTvdb, true);
  assert.deepEqual(show.seasons.map((s: { number: number }) => s.number), [0, 1]);
  const s1 = (await call("GET", "/titles/series/1399/seasons/1", undefined, member)).json;
  assert.deepEqual(s1.episodes.map((e: { title: string }) => e.title), ["Winter Is Coming", "The Kingsroad"]);
  assert.equal((await call("GET", "/titles/movie/999999", undefined, member)).status, 404, "unknown title is a 404");

  // TVDB: optional; alternate orders once connected.
  assert.equal((await call("GET", "/titles/series/1399/tvdb-episodes?order=dvd", undefined, member)).status, 409);
  assert.equal((await call("PUT", "/integrations/tvdb", { apikey: "wrong-key-000" }, admin)).status, 400);
  assert.equal((await call("PUT", "/integrations/tvdb", { apikey: "tvdb-key-123" }, admin)).status, 204);
  const dvd = (await call("GET", "/titles/series/1399/tvdb-episodes?order=dvd", undefined, member)).json;
  assert.deepEqual(dvd.seasons[0].episodes.map((e: { number: number }) => e.number), [1, 2]);
  assert.equal((await call("POST", "/integrations/tvdb/test", undefined, admin)).json.ok, true);

  // Background refresh: stale items are re-fetched, fresh ones left alone.
  const db = new PrismaClient({ adapter: new PrismaPg({ connectionString: url }) });
  const svc = metadataService(db, bases);
  assert.equal(await svc.refreshStale(), 0);
  await client.query(`UPDATE "MediaItem" SET "refreshedAt" = now() - interval '8 days', title = 'Old title' WHERE type = 'movie'`);
  assert.equal(await svc.refreshStale(), 1);
  assert.equal((await call("GET", "/titles/movie/550", undefined, member)).json.title, "Fight Club");

  // Changing the metadata language marks everything for refresh.
  assert.equal((await call("PUT", "/integrations/tmdb", { language: "fr-FR" }, admin)).status, 204);
  assert.ok((await svc.refreshStale()) >= 3, "film, series and season refreshed in the new language");

  await db.$disconnect();
  await client.end();
  await app.close();
  mock.close();
});
