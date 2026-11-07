// Phase 6: debrid. File picking, connecting keys, cache marks on sources, resolving to links on all three
// services (mocked to their documented shapes). Needs TEST_DATABASE_URL (wiped).
import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import pg from "pg";
import { buildApp } from "./app.js";
import { pickFile } from "./debrid.js";

const url = process.env.TEST_DATABASE_URL;
if (!url) throw new Error("Set TEST_DATABASE_URL to an empty, migrated database");
process.env.ZYLO_SECRET ??= "test-secret-test-secret-test-secret-123";

test("pickFile: file name, then index, then episode, then biggest video", () => {
  const files = [
    { name: "Show/Show.S01E01.1080p.mkv", size: 900, idx: 0 },
    { name: "Show/Show.S01E02.1080p.mkv", size: 800, idx: 1 },
    { name: "Show/Show.S01E10.1080p.mkv", size: 700, idx: 2 },
    { name: "Show/sample.mkv", size: 10, idx: 3 },
    { name: "Show/extras.nfo", size: 9999, idx: 4 },
  ];
  assert.equal(pickFile(files, { filename: "show.s01e02.1080p.mkv" }).idx, 1);
  assert.equal(pickFile(files, { fileIdx: 2 }).idx, 2);
  assert.equal(pickFile(files, { season: 1, episode: 1 }).idx, 0, "E1 doesn't match E10");
  assert.equal(pickFile(files, { season: 1, episode: 10 }).idx, 2);
  assert.equal(pickFile(files, {}).idx, 0, "biggest video, never the .nfo");
});

const H = {
  cached: "a".repeat(40),
  rdOnly: "b".repeat(40),
  slow: "c".repeat(40),
};
const TB_KEY = "tb-key-0123456789";
const RD_KEY = "rd-key-0123456789";
const PM_KEY = "pm-key-0123456789";
const seen: string[] = [];
const rdTorrents: {
  id: string;
  hash: string;
  status: string;
  selected: number[];
}[] = [];
const tbTorrents: { id: number; hash: string }[] = [];

const rdFiles = (hash: string) => [
  { id: 1, path: `/${hash.slice(0, 4)}/Film.2000.1080p.mp4`, bytes: 5000 },
  { id: 2, path: `/${hash.slice(0, 4)}/sample.mp4`, bytes: 50 },
];

const mock = createServer((req, res) => {
  const u = new URL(req.url!, "http://x");
  const p = u.pathname;
  const chunks: Buffer[] = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    const raw = Buffer.concat(chunks).toString();
    const ct = String(req.headers["content-type"] ?? "");
    const form = ct.includes("urlencoded") ? new URLSearchParams(raw) : null;
    const send = (s: number, b?: unknown) => res.writeHead(s, { "content-type": "application/json" }).end(b === undefined ? "" : JSON.stringify(b));
    seen.push(`${req.method} ${p}`);

    if (p.startsWith("/3/")) {
      if (p === "/3/configuration") return send(200, {});
      if (p === "/3/movie/550")
        return send(200, {
          id: 550,
          title: "A Film",
          release_date: "2000-01-01",
          genres: [],
          external_ids: { imdb_id: "tt0000550" },
        });
      return send(404, {});
    }
    if (p === "/addon/manifest.json")
      return send(200, {
        id: "org.example.t",
        version: "1",
        name: "T",
        types: ["movie"],
        idPrefixes: ["tt"],
        resources: ["stream"],
        catalogs: [],
      });
    if (p === "/addon/stream/movie/tt0000550.json")
      return send(200, {
        streams: [
          {
            name: "T 1080p",
            title: "Film.2000.1080p.mp4",
            infoHash: H.cached,
            behaviorHints: { filename: "Film.2000.1080p.mp4" },
          },
          { name: "T 720p", title: "Film 720p", infoHash: H.rdOnly },
          { name: "T 4K", title: "Film 2160p", infoHash: H.slow },
        ],
      });

    // TorBox: Bearer key, { success, data } envelope; caches only H.cached.
    if (p.startsWith("/tb/")) {
      const auth = req.headers.authorization === `Bearer ${TB_KEY}` || u.searchParams.get("token") === TB_KEY;
      if (!auth)
        return send(401, {
          success: false,
          error: "BAD_TOKEN",
          detail: "Invalid API key.",
        });
      const path = p.slice(3);
      if (path === "/user/me")
        return send(200, {
          success: true,
          data: { plan: 2, premium_expires_at: "2027-01-01T00:00:00Z" },
        });
      if (path === "/torrents/checkcached")
        return send(200, {
          success: true,
          data: (JSON.parse(raw).hashes as string[]).filter((h) => h === H.cached).map((hash) => ({ hash })),
        });
      const info = (t: { id: number; hash: string }) => ({
        id: t.id,
        hash: t.hash,
        progress: t.hash === H.cached ? 1 : 0.1,
        download_present: t.hash === H.cached,
        files: [
          { id: 0, name: "Film/sample.mp4", size: 50 },
          { id: 1, name: "Film/Film.2000.1080p.mp4", size: 5000 },
        ],
      });
      if (path === "/torrents/mylist") {
        const id = u.searchParams.get("id");
        if (id)
          return send(200, {
            success: true,
            data: info(tbTorrents.find((t) => t.id === Number(id))!),
          });
        return send(200, { success: true, data: tbTorrents.map(info) });
      }
      if (path === "/torrents/createtorrent") {
        assert.ok(ct.includes("multipart/form-data"));
        const hash = raw.match(/btih:([a-f0-9]{40})/)![1];
        tbTorrents.push({ id: tbTorrents.length + 1, hash });
        return send(200, {
          success: true,
          data: { torrent_id: tbTorrents.length, hash },
        });
      }
      if (path === "/torrents/requestdl")
        return send(200, {
          success: true,
          data: `https://tb.example/dl/${u.searchParams.get("torrent_id")}/${u.searchParams.get("file_id")}`,
        });
      return send(404, { success: false, detail: "nope" });
    }

    // Real-Debrid: Bearer key; no cache check; add → select → downloaded (or downloading for H.slow).
    if (p.startsWith("/rd/")) {
      if (req.headers.authorization !== `Bearer ${RD_KEY}`) return send(401, { error: "bad_token", error_code: 8 });
      const path = p.slice(3);
      if (path === "/user")
        return send(200, {
          premium: 86400,
          expiration: "2027-01-01T00:00:00.000Z",
        });
      if (path === "/torrents")
        return rdTorrents.length
          ? send(
              200,
              rdTorrents.map(({ id, hash, status }) => ({ id, hash, status })),
            )
          : send(204);
      if (path === "/torrents/addMagnet") {
        const hash = form!.get("magnet")!.match(/btih:([a-f0-9]{40})/)![1];
        rdTorrents.push({
          id: `RD${rdTorrents.length + 1}`,
          hash,
          status: "waiting_files_selection",
          selected: [],
        });
        return send(201, { id: `RD${rdTorrents.length}`, uri: "x" });
      }
      const t = rdTorrents.find((x) => p.endsWith(`/${x.id}`));
      if (path.startsWith("/torrents/selectFiles/") && t) {
        t.selected = form!.get("files")!.split(",").map(Number);
        t.status = t.hash === H.slow ? "downloading" : "downloaded";
        return send(204);
      }
      if (path.startsWith("/torrents/info/") && t)
        return send(200, {
          id: t.id,
          status: t.status,
          progress: t.status === "downloaded" ? 100 : 12,
          files: rdFiles(t.hash).map((f) => ({
            ...f,
            selected: t.selected.includes(f.id) ? 1 : 0,
          })),
          links: t.status === "downloaded" ? t.selected.map((id) => `https://real-debrid.com/d/${t.id}-${id}`) : [],
        });
      if (path === "/unrestrict/link")
        return send(200, {
          download: `https://rd.example/dl/${form!.get("link")!.split("/").pop()}/Film.mp4`,
          filename: "Film.2000.1080p.mp4",
        });
      return send(404, { error: "unknown_ressource", error_code: 7 });
    }

    // Premiumize: apikey query param; 200 + status "error" on failure; caches only H.cached.
    if (p.startsWith("/pm/")) {
      if (u.searchParams.get("apikey") !== PM_KEY) return send(200, { status: "error", message: "Not logged in." });
      const path = p.slice(3);
      if (path === "/account/info")
        return send(200, {
          status: "success",
          premium_until: Math.floor(Date.now() / 1000) + 86400,
        });
      if (path === "/cache/check")
        return send(200, {
          status: "success",
          response: u.searchParams.getAll("items[]").map((h) => h === H.cached),
        });
      if (path === "/transfer/directdl") {
        if (!form!.get("src")!.includes(H.cached))
          return send(200, {
            status: "error",
            message: "content not in cache",
          });
        return send(200, {
          status: "success",
          content: [
            {
              path: "Film/Film.2000.1080p.mp4",
              size: 5000,
              link: "https://pm.example/dl/film.mp4",
            },
          ],
        });
      }
      return send(404, {});
    }
    send(404, {});
  });
});

test("phase 6: connect debrid, torrents become playable, resolve on TorBox, Real-Debrid and Premiumize", async () => {
  await new Promise<void>((r) => mock.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${(mock.address() as { port: number }).port}`;
  const client = new pg.Client({ connectionString: url });
  await client.connect();
  await client.query('TRUNCATE "User", "Integration", "MediaItem" CASCADE');

  const app = buildApp({
    databaseUrl: url,
    bases: { tmdb: `${base}/3` },
    addonFetch: { allowPrivate: true },
    debridBases: {
      torbox: `${base}/tb`,
      realdebrid: `${base}/rd`,
      premiumize: `${base}/pm`,
    },
  });
  let cookie = "";
  let profile = "";
  const call = async (method: string, path: string, body?: object) => {
    const res = await app.inject({
      method: method as "GET",
      url: path,
      payload: body,
      headers: { cookie, ...(profile && { "x-zylo-profile": profile }) },
    });
    return {
      status: res.statusCode,
      json: res.body ? JSON.parse(res.body) : null,
      raw: res.body,
      setCookie: res.headers["set-cookie"],
    };
  };
  try {
    cookie = String(
      (
        await call("POST", "/setup", {
          email: "a@example.com",
          password: "password123",
          name: "Alex",
        })
      ).setCookie,
    ).split(";")[0];
    profile = (await call("GET", "/profiles")).json[0].id;
    await call("PUT", "/integrations/tmdb", {
      token: "eyJhbGciOiJIUzI1NiJ9.good-read-access-token",
    });
    await call("POST", "/addons", { url: `${base}/addon/manifest.json` });
    const start = async () => (await call("GET", "/playback/start?type=movie&id=550")).json;
    const resolve = (service: string, infoHash: string, extra: object = {}) => call("POST", "/playback/resolve", { service, infoHash, ...extra });

    // No debrid: torrents are listed nowhere playable.
    let s = await start();
    assert.equal(s.ranked.length, 0);
    assert.equal(s.others.length, 0, "torrents never show up as 'elsewhere' links");

    // Connecting checks the key with the service and never echoes it back.
    assert.equal((await call("PUT", "/debrid/torbox", { apiKey: "wrong-key-000000" })).json.error, "TorBox didn't accept that API key.");
    const tb = await call("PUT", "/debrid/torbox", { apiKey: TB_KEY });
    assert.equal(tb.status, 200);
    assert.equal(tb.json.premium, true);
    const list = await call("GET", "/debrid");
    assert.deepEqual(
      list.json.map((d: { service: string; connected: boolean }) => [d.service, d.connected]),
      [
        ["torbox", true],
        ["realdebrid", false],
        ["premiumize", false],
      ],
    );
    assert.ok(!list.raw.includes(TB_KEY), "key never leaves the server");
    assert.ok(!(await client.query('SELECT secret FROM "DebridAccount"')).rows[0].secret.includes(TB_KEY), "stored encrypted");

    // TorBox has only the 1080p cached: that one is ranked, the rest wait.
    s = await start();
    assert.deepEqual(
      s.ranked.map((o: { label: string; debrid: object }) => [o.label, o.debrid]),
      [["T 1080p", { service: "torbox", cached: true }]],
    );
    assert.equal(s.ranked[0].browserFriendly, true);

    // Resolve: added once, the right file, then reused rather than added again.
    const r1 = await resolve("torbox", H.cached, {
      filename: "Film.2000.1080p.mp4",
    });
    assert.deepEqual(r1.json, {
      url: "https://tb.example/dl/1/1",
      filename: "Film.2000.1080p.mp4",
    });
    await resolve("torbox", H.cached);
    assert.equal(tbTorrents.length, 1, "reused");
    const slowTb = await resolve("torbox", H.slow);
    assert.equal(slowTb.status, 409);
    assert.match(slowTb.json.error, /downloading it \(10%\)/);

    // Real-Debrid can't say what's cached, so its sources come after known-ready ones.
    await call("PUT", "/debrid/realdebrid", { apiKey: RD_KEY });
    s = await start();
    assert.deepEqual(
      s.ranked.map((o: { label: string; debrid: { service: string } }) => [o.label, o.debrid.service]),
      [
        ["T 1080p", "torbox"],
        ["T 720p", "realdebrid"],
        ["T 4K", "realdebrid"],
      ],
    );
    const r2 = await resolve("realdebrid", H.rdOnly, { fileIdx: 0 });
    assert.equal(r2.status, 200);
    assert.equal(r2.json.url, "https://rd.example/dl/RD1-1/Film.mp4");
    assert.deepEqual(rdTorrents[0].selected, [1, 2], "every video file selected");
    await resolve("realdebrid", H.rdOnly);
    assert.equal(rdTorrents.length, 1, "reused");
    const slowRd = await resolve("realdebrid", H.slow);
    assert.equal(slowRd.status, 409);
    assert.equal(slowRd.json.notReady, true);

    // Premiumize: cache check + direct link; a miss is a plain message.
    await call("PUT", "/debrid/premiumize", { apiKey: PM_KEY });
    assert.equal((await resolve("premiumize", H.cached)).json.url, "https://pm.example/dl/film.mp4");
    assert.equal((await resolve("premiumize", H.slow)).json.error, "Premiumize doesn't have this cached.");
    assert.equal((await call("PUT", "/debrid/premiumize", { apiKey: "wrong-key-000000" })).status, 400);

    // A broken service doesn't break the page; a removed one can't be used.
    await client.query(`UPDATE "DebridAccount" SET secret = $1 WHERE service = 'torbox'`, [
      (await client.query(`SELECT secret FROM "DebridAccount" WHERE service = 'realdebrid'`)).rows[0].secret,
    ]);
    s = await start();
    assert.equal(s.ranked[0].debrid.service, "premiumize", "TorBox check failed; Premiumize has it cached");
    assert.match((await call("GET", "/debrid")).json[0].error, /Invalid API key/);
    assert.equal((await call("DELETE", "/debrid/torbox")).status, 204);
    assert.match((await resolve("torbox", H.cached)).json.error, /isn't connected/);
    assert.equal(
      (
        await call("POST", "/playback/resolve", {
          service: "torbox",
          infoHash: "not-a-hash",
        })
      ).status,
      400,
    );
  } finally {
    await app.close();
    await client.end();
    mock.close();
  }
});
