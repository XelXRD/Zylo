// The addon fetcher must never reach the server's own network unless the owner allows it.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { isPrivateAddress, normaliseUrl, safeFetchJson, UnsafeUrl } from "./safe-fetch.js";

test("private and special addresses are recognised", () => {
  for (const ip of ["127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.10", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "::", "fe80::1", "fd00::1", "::ffff:127.0.0.1", "::ffff:192.168.0.1", "64:ff9b::7f00:1", "not-an-ip"]) {
    assert.equal(isPrivateAddress(ip), true, ip);
  }
  for (const ip of ["8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "::ffff:8.8.8.8"]) assert.equal(isPrivateAddress(ip), false, ip);
});

test("only plain http(s) addresses are accepted", () => {
  assert.equal(normaliseUrl("stremio://addon.example.com/manifest.json").protocol, "https:");
  for (const bad of ["file:///etc/passwd", "ftp://x.example/manifest.json", "javascript:alert(1)", "https://user:pass@x.example/m.json", "nonsense"]) {
    assert.throws(() => normaliseUrl(bad), UnsafeUrl, bad);
  }
});

test("blocks private targets, DNS tricks and redirects; enforces size and JSON", async () => {
  const hits: string[] = [];
  const server = createServer((req, res) => {
    hits.push(req.url!);
    if (req.url === "/ok") return res.writeHead(200, { "content-type": "application/json" }).end('{"ok":true}');
    if (req.url === "/redirect-to-local") return res.writeHead(302, { location: `http://127.0.0.1:${port}/ok` }).end();
    if (req.url === "/loop") return res.writeHead(302, { location: "/loop" }).end();
    if (req.url === "/big") return res.writeHead(200).end("x".repeat(2000));
    if (req.url === "/html") return res.writeHead(200).end("<html>");
    if (req.url === "/slow") return setTimeout(() => res.end("{}"), 2000);
    res.writeHead(404).end();
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  const port = (server.address() as { port: number }).port;

  // IP literals and localhost names are refused outright.
  await assert.rejects(safeFetchJson(`http://127.0.0.1:${port}/ok`), UnsafeUrl);
  await assert.rejects(safeFetchJson(`http://[::1]:${port}/ok`), UnsafeUrl);
  await assert.rejects(safeFetchJson(`http://localhost:${port}/ok`), UnsafeUrl);
  await assert.rejects(safeFetchJson("http://169.254.169.254/latest/meta-data/"), UnsafeUrl);

  // A public-looking name that resolves to a private address (DNS rebinding) is refused at connect time.
  const rebind = async () => [{ address: "127.0.0.1", family: 4 }];
  await assert.rejects(safeFetchJson(`http://addon.example.com:${port}/ok`, { resolver: rebind }), /private or local/);
  // Mixed answers: only public addresses would be used; none here are reachable, so nothing hits our server.
  const mixed = async () => [{ address: "127.0.0.1", family: 4 }, { address: "10.0.0.5", family: 4 }];
  await assert.rejects(safeFetchJson(`http://addon.example.com:${port}/ok`, { resolver: mixed }), UnsafeUrl);
  assert.equal(hits.length, 0, "no blocked request reached the server");

  // With private access allowed (owner's choice), it works…
  assert.deepEqual(await safeFetchJson(`http://127.0.0.1:${port}/ok`, { allowPrivate: true }), { ok: true });
  // …but redirects are still followed only a few times, and every response is size- and format-checked.
  await assert.rejects(safeFetchJson(`http://127.0.0.1:${port}/loop`, { allowPrivate: true }), /redirected too many/);
  await assert.rejects(safeFetchJson(`http://127.0.0.1:${port}/big`, { allowPrivate: true, maxBytes: 1000 }), /too large/);
  await assert.rejects(safeFetchJson(`http://127.0.0.1:${port}/html`, { allowPrivate: true }), /valid JSON/);
  await assert.rejects(safeFetchJson(`http://127.0.0.1:${port}/slow`, { allowPrivate: true, timeoutMs: 300 }), /too long/);

  // A public host that redirects to a private one is stopped at the redirect.
  const publicThenLocal = async (host: string) => [{ address: host === "addon.example.com" ? "127.0.0.1" : "8.8.8.8", family: 4 }];
  hits.length = 0;
  await assert.rejects(safeFetchJson(`http://addon.example.com:${port}/redirect-to-local`, { resolver: publicThenLocal }), UnsafeUrl);
  assert.equal(hits.length, 0);

  server.closeAllConnections();
  server.close();
});
