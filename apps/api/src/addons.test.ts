// Phase 4 against local mock addons (and a minimal TMDB). Needs TEST_DATABASE_URL (wiped).
import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { createHash } from "node:crypto";
import pg from "pg";
import { buildApp } from "./app.js";
import { normaliseStream } from "./stremio.js";

const url = process.env.TEST_DATABASE_URL;
if (!url) throw new Error("Set TEST_DATABASE_URL to an empty, migrated database");
process.env.ZYLO_SECRET ??= "test-secret-test-secret-test-secret-123";
const TMDB_TOKEN = "eyJhbGciOiJIUzI1NiJ9.good-read-access-token";

const seen: string[] = [];
const manifests: Record<string, object> = {
  alpha: {
    id: "org.example.alpha", version: "1.2.0", name: "Alpha", description: "Public-domain films", types: ["movie", "series"], idPrefixes: ["tt"],
    resources: ["stream", "catalog"],
    catalogs: [{ type: "movie", id: "top", name: "Top films" }, { type: "movie", id: "search", extra: [{ name: "search", isRequired: true }] }],
    behaviorHints: { configurable: true, p2p: true },
  },
  beta: { id: "org.example.beta", version: "0.1.0", name: "Beta", types: ["movie"], resources: [{ name: "stream", types: ["movie"], idPrefixes: ["tmdb:"] }], catalogs: [] },
  seriesonly: { id: "org.example.series", version: "1", name: "Series only", types: ["series"], resources: ["stream"], catalogs: [] },
  broken: { id: "org.example.broken", version: "1", name: "Broken", types: ["movie"], resources: ["stream"], catalogs: [] },
};

const mock = createServer((req, res) => {
  const u = new URL(req.url!, "http://x");
  const p = decodeURIComponent(u.pathname);
  seen.push(p);
  const send = (s: number, b: unknown) => res.writeHead(s, { "content-type": "application/json", "access-control-allow-origin": "*" }).end(JSON.stringify(b));

  if (p.startsWith("/3/")) {
    if (req.headers.authorization !== `Bearer ${TMDB_TOKEN}`) return send(401, {});
    if (p === "/3/configuration") return send(200, { images: {} });
    if (p === "/3/movie/550") return send(200, { id: 550, title: "Night of the Living Dead", release_date: "1968-10-01", poster_path: "/n.jpg", genres: [], external_ids: { imdb_id: "tt0063350" } });
    if (p === "/3/tv/1399") return send(200, { id: 1399, name: "A Series", first_air_date: "2011-04-17", genres: [], seasons: [], external_ids: { imdb_id: "tt0944947" } });
    if (p === "/3/find/tt0000001") return send(200, { movie_results: [{ id: 777 }], tv_results: [] });
    return send(404, {});
  }

  // /<addon>/[secret-config/]manifest.json and resources
  const m = p.match(/^\/(\w+)(?:\/secret-config)?\/(.*)$/);
  if (!m) return send(404, {});
  const [, name, rest] = m;
  if (rest === "manifest.json") return name === "notamanifest" ? send(200, { hello: "world" }) : send(200, manifests[name]);
  if (name === "broken") return send(500, {});
  if (name === "alpha" && rest === "stream/movie/tt0063350.json")
    return send(200, {
      streams: [
        { name: "Alpha\n1080p", description: "Night.of.the.Living.Dead.1968.1080p.HDR.mkv\n💾 2.1 GB", url: "https://cdn.example.com/notld.mkv", behaviorHints: { bingeGroup: "alpha-1080p", filename: "notld.mkv" }, subtitles: [{ id: "en", url: "https://cdn.example.com/en.vtt", lang: "eng" }, { id: "bad", url: "file:///etc/passwd", lang: "eng" }] },
        { name: "Alpha 4K", infoHash: "A".repeat(40), fileIdx: 1 },
        { name: "Trailer", ytId: "dQw4w9WgXcQ" },
        { name: "Library", externalUrl: "https://archive.example.org/details/notld" },
        { name: "Usenet", nzbUrl: "https://nzb.example/x.nzb" },
        { name: "Junk" },
        "not even an object",
      ],
    });
  if (name === "alpha" && rest === "stream/series/tt0944947:1:1.json") return send(200, { streams: [{ name: "Ep", url: "https://cdn.example.com/s1e1.mp4" }] });
  if (name === "beta" && rest === "stream/movie/tmdb:550.json") return send(200, { streams: [{ name: "Beta 720p", url: "https://beta.example.com/720.mp4" }] });
  if (name === "alpha" && rest === "catalog/movie/top.json")
    return send(200, { metas: [{ id: "tt0063350", type: "movie", name: "Night of the Living Dead", releaseInfo: "1968", poster: "https://img.example.com/n.jpg" }, { id: "custom:42", type: "movie", name: "Addon-only" }, { id: "tt0000001", type: "movie", name: "Unknown", poster: "http://insecure.example/p.jpg" }] });
  send(404, {});
});

test("normaliseStream: kinds, quality, size, safe subtitles", () => {
  const src = { kind: "addon" as const, id: "a", name: "A" };
  const o = normaliseStream({ name: "X 2160p DV", description: "Big file 58.3 GB", url: "https://x/y.mkv" }, src, 0)!;
  assert.equal(o.kind, "url");
  assert.equal(o.quality, "4K");
  assert.deepEqual(o.hdr, ["DV"]);
  assert.equal(o.size, "58.3 GB");
  assert.equal(normaliseStream({ url: "javascript:alert(1)" }, src, 1), null, "only http(s) links");
  assert.equal(normaliseStream({ infoHash: "nothex" }, src, 2), null);
});

test("phase 4: install, manage, streams, catalogues, isolation, SSRF", async () => {
  await new Promise<void>((r) => mock.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${(mock.address() as { port: number }).port}`;

  const client = new pg.Client({ connectionString: url });
  await client.connect();
  await client.query('TRUNCATE "User", "Integration", "MediaItem" CASCADE');

  const app = buildApp({ databaseUrl: url, bases: { tmdb: `${base}/3` }, addonFetch: { allowPrivate: true } });
  let cookie = "";
  const call = async (method: string, path: string, body?: object, as = cookie) => {
    const res = await app.inject({ method: method as "GET", url: path, payload: body, headers: { cookie: as } });
    return { status: res.statusCode, json: res.body ? JSON.parse(res.body) : null, raw: res.body, setCookie: res.headers["set-cookie"] };
  };
  cookie = String((await call("POST", "/setup", { email: "a@example.com", password: "password123", name: "Alex" })).setCookie).split(";")[0];
  await call("PUT", "/integrations/tmdb", { token: TMDB_TOKEN });

  // Install: must be a manifest URL, and a real manifest.
  assert.match((await call("POST", "/addons", { url: `${base}/alpha/` })).json.error, /manifest\.json/);
  assert.match((await call("POST", "/addons", { url: `${base}/notamanifest/manifest.json` })).json.error, /valid Stremio addon manifest/);
  const alpha = await call("POST", "/addons", { url: `${base}/alpha/secret-config/manifest.json` });
  assert.equal(alpha.status, 201);
  assert.equal(alpha.json.p2p, true);
  for (const n of ["beta", "seriesonly", "broken"]) assert.equal((await call("POST", "/addons", { url: `${base}/${n}/manifest.json` })).status, 201);

  // The configured URL (which may hold someone's keys) never comes back, and is encrypted at rest.
  const list = await call("GET", "/addons");
  assert.ok(!list.raw.includes("secret-config"));
  assert.deepEqual(list.json.map((a: { name: string }) => a.name), ["Alpha", "Beta", "Series only", "Broken"]);
  const stored = (await client.query(`SELECT "transportUrl" FROM "Addon"`)).rows.map((r) => r.transportUrl).join();
  assert.ok(!stored.includes("secret-config"));
  assert.match((await call("GET", `/addons/${alpha.json.id}/configure`)).json.url, /secret-config\/configure$/);

  // Reinstalling the same addon replaces its configuration in place.
  const again = await call("POST", "/addons", { url: `${base}/alpha/secret-config/manifest.json` });
  assert.equal(again.status, 200);
  assert.equal(again.json.replaced, true);
  assert.equal((await call("GET", "/addons")).json.length, 4);

  // Streams for a film: IMDb-keyed addon, TMDB-keyed addon, a broken one; series-only addon skipped.
  const s = (await call("GET", "/titles/movie/550/streams")).json;
  assert.equal(s.skipped, 1);
  assert.deepEqual(s.addons.map((a: { name: string; status: string }) => [a.name, a.status]), [["Alpha", "ok"], ["Beta", "ok"], ["Broken", "error"]]);
  const opts = s.addons[0].options;
  assert.deepEqual(opts.map((o: { kind: string }) => o.kind), ["url", "torrent", "youtube", "external", "usenet"], "junk dropped");
  assert.equal(opts[0].quality, "1080p");
  assert.deepEqual(opts[0].hdr, ["HDR"]);
  assert.equal(opts[0].size, "2.1 GB");
  assert.equal(opts[0].subtitles.length, 1, "non-http subtitle dropped");
  assert.equal(opts[1].playable, false);
  assert.equal(opts[1].infoHash, "a".repeat(40));
  assert.equal(s.addons[1].options[0].url, "https://beta.example.com/720.mp4");

  // Episodes use imdb:season:episode, URL-encoded.
  const ep = (await call("GET", "/titles/series/1399/streams?season=1&episode=1")).json;
  assert.equal(ep.addons.find((a: { name: string }) => a.name === "Alpha").options[0].url, "https://cdn.example.com/s1e1.mp4");
  assert.ok(seen.includes("/alpha/secret-config/stream/series/tt0944947:1:1.json"));
  assert.equal((await call("GET", "/titles/series/1399/streams")).status, 400);
  const none = (await call("GET", "/titles/series/1399/streams?season=1&episode=2")).json;
  assert.deepEqual(none.addons.find((a: { name: string }) => a.name === "Alpha"), { id: alpha.json.id, name: "Alpha", logo: null, status: "ok", options: [] }, "a 404 means no streams, not a failure");

  // Reorder and disable.
  const betaId = list.json[1].id;
  assert.equal((await call("PATCH", `/addons/${betaId}`, { move: "up" })).json.position, 0);
  assert.equal((await call("PATCH", `/addons/${betaId}`, { enabled: false })).json.enabled, false);
  const brokenId = list.json[3].id;
  await call("DELETE", `/addons/${brokenId}`);
  const s2 = (await call("GET", "/titles/movie/550/streams")).json;
  assert.deepEqual(s2.addons.map((a: { name: string }) => a.name), ["Alpha"]);

  // Test connection reports errors without throwing.
  assert.equal((await call("POST", `/addons/${alpha.json.id}/test`)).json.ok, true);

  // Catalogues: browsable ones only, IMDb items only, https posters only.
  const cat = (await call("GET", "/addons/catalogs")).json.rows;
  assert.equal(cat.length, 1);
  assert.deepEqual(cat[0].items.map((i: { imdbId: string }) => i.imdbId), ["tt0063350", "tt0000001"]);
  assert.equal(cat[0].items[1].poster, null);
  assert.deepEqual((await call("GET", "/titles/resolve?imdb=tt0063350&type=movie")).json, { type: "movie", tmdbId: 550 });
  assert.deepEqual((await call("GET", "/titles/resolve?imdb=tt0000001&type=movie")).json, { type: "movie", tmdbId: 777 });

  // Another account can't see or touch these addons.
  await client.query(`INSERT INTO "User" (id, email, "passwordHash") VALUES ('u2', 'b@example.com', 'x')`);
  await client.query(`INSERT INTO "Session" ("tokenHash", "userId", "expiresAt") VALUES ($1, 'u2', now() + interval '1 day')`, [createHash("sha256").update("other").digest("hex")]);
  const other = "zylo_session=other";
  assert.deepEqual((await call("GET", "/addons", undefined, other)).json, []);
  assert.equal((await call("DELETE", `/addons/${alpha.json.id}`, undefined, other)).status, 404);
  assert.deepEqual((await call("GET", "/titles/movie/550/streams", undefined, other)).json.addons, []);

  // Default settings: addons may not point at the server's own network.
  const strict = buildApp({ databaseUrl: url, bases: { tmdb: `${base}/3` } });
  const blocked = await strict.inject({ method: "POST", url: "/addons", payload: { url: `${base}/alpha/manifest.json` }, headers: { cookie } });
  assert.equal(blocked.statusCode, 400);
  assert.match(JSON.parse(blocked.body).error, /private or local network/);
  await strict.close();

  await client.end();
  await app.close();
  mock.closeAllConnections();
  mock.close();
});
