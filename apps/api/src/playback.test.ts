// Phase 5: ranking, subtitles, progress across devices, completion once, Trakt scrobble + de-dupe.
// Needs TEST_DATABASE_URL (wiped).
import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import pg from "pg";
import { buildApp } from "./app.js";
import { DEFAULT_PREFS, rankOptions, toVtt } from "./playback.js";
import type { PlaybackOption } from "./stremio.js";

const url = process.env.TEST_DATABASE_URL;
if (!url) throw new Error("Set TEST_DATABASE_URL to an empty, migrated database");
process.env.ZYLO_SECRET ??= "test-secret-test-secret-test-secret-123";
const TMDB_TOKEN = "eyJhbGciOiJIUzI1NiJ9.good-read-access-token";
const CLIENT_ID = "trakt-client-id-0123456789abcdef";

const opt = (p: Partial<PlaybackOption> & { id: string }): PlaybackOption => ({
  source: { kind: "addon", id: "a1", name: "A" }, label: p.id, description: null, kind: "url", url: `https://x.example/${p.id}.mp4`,
  quality: null, hdr: [], size: null, filename: null, subtitles: [], notWebReady: false, bingeGroup: null, playable: true, note: null, ...p,
});

test("rankOptions: choice, browser support, quality cap, addon order; only direct links", () => {
  const options = [
    opt({ id: "mkv-1080", quality: "1080p", url: "https://x.example/a.mkv" }),
    opt({ id: "mp4-4k", quality: "4K" }),
    opt({ id: "mp4-720", quality: "720p" }),
    opt({ id: "mp4-1080", quality: "1080p" }),
    opt({ id: "yt", kind: "youtube", url: undefined, ytId: "abc" }),
    opt({ id: "b-1080", quality: "1080p", source: { kind: "addon", id: "a2", name: "B" } }),
  ];
  assert.deepEqual(rankOptions(options, DEFAULT_PREFS).map((o) => o.id), ["mp4-1080", "b-1080", "mp4-720", "mp4-4k", "mkv-1080"]);
  assert.deepEqual(rankOptions(options, { ...DEFAULT_PREFS, maxQuality: "4K" }).map((o) => o.id)[0], "mp4-4k");
  assert.equal(rankOptions(options, DEFAULT_PREFS, { addonId: "a2", quality: "1080p" })[0].id, "b-1080", "remembered choice wins");
  assert.equal(rankOptions([...options, opt({ id: "bg", quality: "480p", bingeGroup: "a-480" })], DEFAULT_PREFS, null, "a-480")[0].id, "bg", "same binge group for the next episode");
});

test("toVtt converts SRT and keeps VTT", () => {
  const srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\nHello\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nWorld\r\n";
  assert.equal(toVtt(srt), "WEBVTT\n\n00:00:01.000 --> 00:00:02.500\nHello\n\n00:00:03.000 --> 00:00:04.000\nWorld\n");
  assert.equal(toVtt("WEBVTT\n\n00:01.000 --> 00:02.000\nHi"), "WEBVTT\n\n00:01.000 --> 00:02.000\nHi\n");
});

// --- mock TMDB + addon + Trakt ---
const scrobbles: { action: string; progress: number }[] = [];
const historyPosts: unknown[] = [];
let traktDown = false;
let traktHistory: unknown[] = [];
let stamp = "1";

const mock = createServer((req, res) => {
  const u = new URL(req.url!, "http://x");
  const p = decodeURIComponent(u.pathname);
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    const body = raw ? JSON.parse(raw) : null;
    const send = (s: number, b?: unknown) => res.writeHead(s, { "content-type": "application/json" }).end(b === undefined ? "" : JSON.stringify(b));
    if (p.startsWith("/3/")) {
      if (p === "/3/configuration") return send(200, {});
      if (p === "/3/movie/550") return send(200, { id: 550, title: "A Film", release_date: "2000-01-01", genres: [], external_ids: { imdb_id: "tt0000550" } });
      if (p === "/3/tv/1399") return send(200, { id: 1399, name: "A Series", first_air_date: "2011-01-01", genres: [], seasons: [], external_ids: { imdb_id: "tt0001399" } });
      return send(404, {});
    }
    if (p.startsWith("/addon/")) {
      const rest = p.slice("/addon/".length);
      if (rest === "manifest.json") return send(200, { id: "org.example.a", version: "1", name: "A", types: ["movie", "series"], idPrefixes: ["tt"], resources: ["stream", "subtitles"], catalogs: [] });
      if (rest === "stream/movie/tt0000550.json") return send(200, { streams: [{ name: "4K", url: "https://cdn.example/4k.mp4" }, { name: "1080p", url: "https://cdn.example/1080.mkv" }, { name: "720p", url: "https://cdn.example/720.mp4" }] });
      if (rest.startsWith("subtitles/movie/tt0000550/")) return send(200, { subtitles: [{ id: "en1", lang: "eng", url: `http://127.0.0.1:${port}/subs/en.srt` }, { id: "bad", lang: "eng", url: "file:///etc/passwd" }] });
      return send(404, {});
    }
    if (p === "/subs/en.srt") return res.writeHead(200).end("1\n00:00:01,000 --> 00:00:02,000\nCaf\xe9\n");
    // Trakt
    if (req.headers["trakt-api-key"] !== CLIENT_ID) return send(403);
    if (p === "/oauth/device/code") return send(200, { device_code: "d", user_code: "U", verification_url: "https://trakt.tv/activate", expires_in: 600, interval: 1 });
    if (p === "/oauth/device/token") return send(200, { access_token: "tok", refresh_token: "ref", expires_in: 86400, created_at: Math.floor(Date.now() / 1000) });
    if (p === "/users/settings") return send(200, { user: { username: "u" } });
    if (traktDown) return send(503);
    if (p.startsWith("/scrobble/")) {
      scrobbles.push({ action: p.split("/")[2], progress: body.progress });
      if (p === "/scrobble/stop" && body.progress >= 80) {
        traktHistory = [{ watched_at: new Date(Date.now() + 3000).toISOString(), type: "movie", movie: { title: "A Film", year: 2000, ids: { tmdb: 550 } } }];
        stamp = String(Date.now());
      }
      return send(201, { action: "scrobble" });
    }
    if (p === "/sync/history" && req.method === "POST") return historyPosts.push(body), send(201, {});
    if (p === "/sync/last_activities") return send(200, { movies: { watched_at: stamp }, episodes: {}, shows: {}, lists: {}, watchlist: {} });
    if (p === "/sync/history") return send(200, Number(u.searchParams.get("page")) > 1 ? [] : traktHistory);
    if (p.startsWith("/sync/ratings/") || p === "/sync/watchlist" || p === "/users/me/lists") return send(200, []);
    send(404, {});
  });
});
let port = 0;

test("phase 5: start, resume elsewhere, complete once, scrobble, de-dupe, fallback, subtitles", async () => {
  await new Promise<void>((r) => mock.listen(0, "127.0.0.1", r));
  port = (mock.address() as { port: number }).port;
  const base = `http://127.0.0.1:${port}`;
  const client = new pg.Client({ connectionString: url });
  await client.connect();
  await client.query('TRUNCATE "User", "Integration", "MediaItem" CASCADE');

  const app = buildApp({ databaseUrl: url, bases: { tmdb: `${base}/3`, trakt: base }, addonFetch: { allowPrivate: true } });
  let cookie = "";
  let profile = "";
  const call = async (method: string, path: string, body?: object, headers: Record<string, string> = {}) => {
    const res = await app.inject({ method: method as "GET", url: path, payload: body, headers: { cookie, ...(profile && { "x-zylo-profile": profile }), ...headers } });
    return { status: res.statusCode, json: res.body && res.headers["content-type"]?.toString().includes("json") ? JSON.parse(res.body) : null, raw: res.body, setCookie: res.headers["set-cookie"] };
  };
  cookie = String((await call("POST", "/setup", { email: "a@example.com", password: "password123", name: "Alex" })).setCookie).split(";")[0];
  profile = (await call("GET", "/profiles")).json[0].id;
  await call("PUT", "/integrations/tmdb", { token: TMDB_TOKEN });
  await call("POST", "/addons", { url: `${base}/addon/manifest.json` });

  // Sources ranked for the browser: 4K is over the default 1080p cap, MKV ranks after MP4.
  const start = (await call("GET", "/playback/start?type=movie&id=550")).json;
  assert.deepEqual(start.ranked.map((o: { label: string }) => o.label), ["720p", "4K", "1080p"]);
  assert.equal(start.resumeAt, null);
  assert.equal(start.ranked[2].browserFriendly, false);

  // A higher quality cap reorders the sources.
  const prefsUp = await call("PUT", "/profile/playback", { maxQuality: "4K" });
  assert.equal(prefsUp.json.maxQuality, "4K");
  assert.equal((await call("GET", "/playback/start?type=movie&id=550")).json.ranked[0].label, "4K");

  // Progress on one "device", resume offered on another.
  await call("POST", "/playback/progress", { type: "movie", id: 550, event: "start", position: 0, duration: 6000 });
  await call("POST", "/playback/progress", { type: "movie", id: 550, event: "progress", position: 1800, duration: 6000 });
  // sendBeacon: no header, profile in the body.
  const beacon = await app.inject({ method: "POST", url: "/playback/progress", payload: { type: "movie", id: 550, event: "pause", position: 2400, duration: 6000, profileId: profile }, headers: { cookie } });
  assert.equal(beacon.statusCode, 200);
  assert.equal((await call("GET", "/playback/start?type=movie&id=550")).json.resumeAt, 2400);
  const cont = (await call("GET", "/playback/continue")).json;
  assert.equal(cont[0].card.tmdbId, 550);
  assert.equal(cont[0].position, 2400);

  // Not connected to Trakt yet: finishing records history locally, once.
  let r = await call("POST", "/playback/progress", { type: "movie", id: 550, event: "progress", position: 5500, duration: 6000 });
  assert.equal(r.json.newlyCompleted, true);
  r = await call("POST", "/playback/progress", { type: "movie", id: 550, event: "stop", position: 5990, duration: 6000 });
  assert.equal(r.json.newlyCompleted, false, "credits rolling don't count twice");
  assert.equal((await client.query(`SELECT count(*) FROM "WatchEvent"`)).rows[0].count, "1");
  assert.deepEqual((await call("GET", "/playback/continue")).json, [], "finished things leave Continue watching");

  // Connect Trakt; the first sync uploads that local play (so Trakt has it), then watch again.
  await call("PUT", "/integrations/trakt", { clientId: CLIENT_ID });
  await call("POST", "/trakt/connect");
  assert.equal((await call("POST", "/trakt/connect/poll")).json.status, "connected");
  assert.equal(historyPosts.length, 1, "first sync pushed the earlier play");

  // Rewatch: starting near the beginning resets; start → pause → complete = one scrobble stop.
  scrobbles.length = 0;
  await call("POST", "/playback/progress", { type: "movie", id: 550, event: "start", position: 10, duration: 6000 });
  await call("POST", "/playback/progress", { type: "movie", id: 550, event: "pause", position: 3000, duration: 6000 });
  await new Promise((res) => setTimeout(res, 1100)); // a real rewatch is never in the same second
  await call("POST", "/playback/progress", { type: "movie", id: 550, event: "progress", position: 5600, duration: 6000 });
  await call("POST", "/playback/progress", { type: "movie", id: 550, event: "stop", position: 5900, duration: 6000 });
  await new Promise((res) => setTimeout(res, 100)); // fire-and-forget scrobbles
  assert.deepEqual(scrobbles.map((s) => s.action), ["start", "pause", "stop"]);
  assert.ok(scrobbles[2].progress >= 90);
  assert.equal((await client.query(`SELECT count(*) FROM "WatchEvent"`)).rows[0].count, "2", "second viewing logged once");

  // Trakt's own record of that scrobble comes back on the next sync: not duplicated.
  await call("POST", "/trakt/sync");
  assert.equal((await client.query(`SELECT count(*) FROM "WatchEvent"`)).rows[0].count, "2");

  // Trakt down when an episode finishes: the play goes to the outbox instead of being lost.
  traktDown = true;
  await call("POST", "/playback/progress", { type: "series", id: 1399, season: 1, episode: 1, event: "progress", position: 2900, duration: 3000 });
  assert.equal((await call("GET", "/trakt")).json.pendingChanges, 1);
  traktDown = false;
  await call("POST", "/trakt/sync");
  assert.equal((await call("GET", "/trakt")).json.pendingChanges, 0);
  assert.equal(historyPosts.length, 2);
  assert.equal((await call("GET", "/playback/start?type=series&id=1399")).status, 400, "series need an episode");

  // Subtitles from the addon (unsafe URL dropped), fetched and converted for <track>.
  const subs = (await call("GET", "/playback/subtitles?type=movie&id=550&filename=a.mp4")).json;
  assert.equal(subs.length, 1);
  const vtt = await call("GET", `/playback/subtitle.vtt?url=${encodeURIComponent(subs[0].url)}`);
  assert.equal(vtt.raw, "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nCafé\n", "Latin-1 decoded, SRT converted");
  const blocked = await call("GET", `/playback/subtitle.vtt?url=${encodeURIComponent("http://169.254.169.254/latest")}`);
  assert.equal(blocked.status, 502);

  await client.end();
  await app.close();
  mock.closeAllConnections();
  mock.close();
});
