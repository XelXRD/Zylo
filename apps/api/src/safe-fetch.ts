// Fetch JSON from an untrusted, user-supplied URL (Stremio addons) without letting it reach the
// server's own network. Uses node:http(s) with a custom DNS lookup so the address is checked at
// connect time: a hostname that resolves to a public IP when checked and a private one when
// connecting (DNS rebinding) can't slip through, because the checked address is the one dialled.
import { lookup as dnsLookup, type LookupAddress } from "node:dns";
import http from "node:http";
import https from "node:https";
import { BlockList, isIP, type LookupFunction } from "node:net";

export class UnsafeUrl extends Error {
  /** HTTP status when the addon answered with an error response. */
  status?: number;
}

const blocked = new BlockList();
for (const [net, bits] of [
  ["0.0.0.0", 8], ["10.0.0.0", 8], ["100.64.0.0", 10], ["127.0.0.0", 8], ["169.254.0.0", 16], ["172.16.0.0", 12],
  ["192.0.0.0", 24], ["192.0.2.0", 24], ["192.168.0.0", 16], ["198.18.0.0", 15], ["198.51.100.0", 24], ["203.0.113.0", 24],
  ["224.0.0.0", 4], ["240.0.0.0", 4],
] as const) blocked.addSubnet(net, bits, "ipv4");
for (const [net, bits] of [
  ["::", 128], ["::1", 128], ["fc00::", 7], ["fe80::", 10], ["ff00::", 8], ["64:ff9b::", 96], ["64:ff9b:1::", 48], ["2001:db8::", 32], ["2002::", 16],
] as const) blocked.addSubnet(net, bits, "ipv6");

/** True for loopback, private, link-local, carrier-grade NAT, multicast, documentation and similar ranges. */
export function isPrivateAddress(ip: string) {
  const mapped = ip.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/i); // IPv4-mapped IPv6
  if (mapped) return isPrivateAddress(mapped[1]);
  const family = isIP(ip);
  if (family === 4) return blocked.check(ip, "ipv4");
  if (family === 6) return blocked.check(ip, "ipv6");
  return true; // not an IP at all: refuse
}

type Resolver = (host: string) => Promise<LookupAddress[]>;
const systemResolver: Resolver = (host) =>
  new Promise((resolve, reject) => dnsLookup(host, { all: true }, (err, addrs) => (err ? reject(err) : resolve(addrs))));

export type SafeFetchOptions = {
  allowPrivate?: boolean; // for addons the owner runs on their own network (ZYLO_ALLOW_PRIVATE_ADDONS)
  timeoutMs?: number;
  maxBytes?: number;
  maxRedirects?: number;
  resolver?: Resolver; // injectable for tests
};

/** Turns stremio:// into https:// and rejects anything that isn't plain http(s) without credentials. */
export function normaliseUrl(raw: string) {
  let url: URL;
  try {
    url = new URL(raw.trim().replace(/^stremio:\/\//i, "https://"));
  } catch {
    throw new UnsafeUrl("That isn't a valid web address.");
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") throw new UnsafeUrl("Only http and https addresses are supported.");
  if (url.username || url.password) throw new UnsafeUrl("Addresses with a username or password in them aren't supported.");
  return url;
}

export async function safeFetchJson<T>(raw: string, opts: SafeFetchOptions = {}): Promise<T> {
  const { allowPrivate = false, timeoutMs = 10_000, maxBytes = 5 * 1024 * 1024, maxRedirects = 3, resolver = systemResolver } = opts;
  let url = normaliseUrl(raw);

  // Checked at connect time, for every connection, including after redirects.
  const lookup: LookupFunction = (host, options, cb) => {
    const literal = isIP(host) ? [{ address: host, family: isIP(host) }] : null;
    (literal ? Promise.resolve(literal) : resolver(host)).then(
      (addrs) => {
        const ok = allowPrivate ? addrs : addrs.filter((a) => !isPrivateAddress(a.address));
        if (!ok.length) return cb(new UnsafeUrl(`${host} points to a private or local network address, which addons aren't allowed to use.`), "", 4);
        if ((options as { all?: boolean }).all) return (cb as unknown as (e: null, a: LookupAddress[]) => void)(null, ok);
        cb(null, ok[0].address, ok[0].family);
      },
      (e) => cb(e as NodeJS.ErrnoException, "", 4),
    );
  };

  for (let hop = 0; ; hop++) {
    // An IP literal never goes through `lookup`, so check it here.
    const bareHost = url.hostname.replace(/^\[|\]$/g, "");
    if (isIP(bareHost) && !allowPrivate && isPrivateAddress(bareHost)) {
      throw new UnsafeUrl(`${url.hostname} is a private or local network address, which addons aren't allowed to use.`);
    }
    const res = await request(url, lookup, timeoutMs, maxBytes);
    if (res.status >= 300 && res.status < 400 && res.location) {
      if (hop >= maxRedirects) throw new UnsafeUrl("The addon redirected too many times.");
      url = normaliseUrl(new URL(res.location, url).toString());
      continue;
    }
    if (res.status < 200 || res.status >= 300) throw Object.assign(new UnsafeUrl(`The addon answered with an error (${res.status}).`), { status: res.status });
    try {
      return JSON.parse(res.body) as T;
    } catch {
      throw new UnsafeUrl("The addon didn't send back valid JSON.");
    }
  }
}

function request(url: URL, lookup: LookupFunction, timeoutMs: number, maxBytes: number) {
  return new Promise<{ status: number; location?: string; body: string }>((resolve, reject) => {
    let done = false;
    const finish = (err: Error | null, value?: { status: number; location?: string; body: string }) => {
      if (done) return;
      done = true;
      clearTimeout(deadline);
      if (err) {
        req.destroy();
        reject(err instanceof UnsafeUrl ? err : new UnsafeUrl(`Couldn't reach the addon (${(err as NodeJS.ErrnoException).code ?? err.message}).`));
      } else resolve(value!);
    };
    // A hard deadline for the whole exchange, not just idle time.
    const deadline = setTimeout(() => finish(new UnsafeUrl("The addon took too long to answer.")), timeoutMs);
    const lib = url.protocol === "https:" ? https : http;
    const req = lib.get(url, { lookup, headers: { accept: "application/json", "user-agent": "Zylo" } }, (res) => {
      const status = res.statusCode ?? 0;
      if (status >= 300 && status < 400) {
        res.resume();
        return finish(null, { status, location: res.headers.location, body: "" });
      }
      let size = 0;
      const chunks: Buffer[] = [];
      res.on("data", (c: Buffer) => {
        size += c.length;
        if (size > maxBytes) return finish(new UnsafeUrl("The addon's response was too large."));
        chunks.push(c);
      });
      res.on("end", () => finish(null, { status, body: Buffer.concat(chunks).toString("utf8") }));
      res.on("error", (e) => finish(e));
    });
    req.on("error", (e) => finish(e));
  });
}
