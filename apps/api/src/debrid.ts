// Debrid services: turn a torrent source into a direct link the player can use.
// Each account (not profile) connects its own TorBox, Real-Debrid or Premiumize with an API key.
// Only the services' own documented workflows are used: check cache → add magnet → pick file → get link.
import type { FastifyInstance, preHandlerHookHandler } from "fastify";
import type { PrismaClient } from "./generated/prisma/client.js";
import { fetchJson, UpstreamError } from "./http.js";
import { decrypt, encrypt } from "./secrets.js";
import type { PlaybackOption } from "./stremio.js";

export const SERVICES = {
  torbox: "TorBox",
  realdebrid: "Real-Debrid",
  premiumize: "Premiumize",
} as const;
export type Service = keyof typeof SERVICES;
export type DebridBases = Partial<Record<Service, string>>;
const BASES: Record<Service, string> = {
  torbox: "https://api.torbox.app/v1/api",
  realdebrid: "https://api.real-debrid.com/rest/1.0",
  premiumize: "https://www.premiumize.me/api",
};

/** The service has the torrent but it isn't ready to stream (still downloading, or not cached). */
export class NotReady extends Error {}

export type Hint = {
  filename?: string | null;
  fileIdx?: number;
  season?: number;
  episode?: number;
};
type Account = { premium: boolean; until: string | null };
type Adapter = {
  account(): Promise<Account>;
  /** Which of these hashes stream instantly. null = this service can't tell without adding them. */
  cached(hashes: string[]): Promise<Set<string> | null>;
  resolve(hash: string, hint: Hint): Promise<{ url: string; filename: string }>;
};

const VIDEO = /\.(mkv|mp4|m4v|avi|mov|webm|wmv|flv|ts|m2ts|mpg|mpeg)$/i;
const base = (path: string) => path.split("/").pop() ?? path;
const magnet = (hash: string) => `magnet:?xt=urn:btih:${hash}`;

/** Pick the file the addon meant: its file name, then its index, then the episode, then the biggest video. */
export function pickFile<F extends { name: string; size: number; idx?: number }>(files: F[], hint: Hint): F {
  const videos = files.filter((f) => VIDEO.test(f.name));
  const pool = videos.length ? videos : files;
  if (!pool.length) throw new NotReady("That torrent has no files.");
  const name = hint.filename?.toLowerCase();
  const ep =
    hint.season !== undefined && hint.episode !== undefined
      ? new RegExp(`(s0*${hint.season}[ ._-]*e0*${hint.episode}|\\b0*${hint.season}x0*${hint.episode})(?!\\d)`, "i")
      : null;
  return (
    (name && pool.find((f) => base(f.name).toLowerCase() === name)) ||
    (hint.fileIdx !== undefined && pool.find((f) => f.idx === hint.fileIdx)) ||
    (ep && pool.filter((f) => ep.test(base(f.name))).sort((a, b) => b.size - a.size)[0]) ||
    [...pool].sort((a, b) => b.size - a.size)[0]
  );
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

function torbox(key: string, root: string): Adapter {
  const name = SERVICES.torbox;
  const call = async <T>(path: string, init: RequestInit = {}) =>
    (
      await fetchJson<{ data: T }>(name, `${root}${path}`, {
        ...init,
        headers: { authorization: `Bearer ${key}`, ...init.headers },
      })
    ).data;
  type T = {
    id: number;
    hash: string;
    download_present: boolean;
    progress: number;
    files: { id: number; name: string; size: number }[] | null;
  };
  return {
    async account() {
      const me = await call<{
        plan: number;
        premium_expires_at: string | null;
      }>("/user/me");
      return { premium: me.plan > 0, until: me.premium_expires_at };
    },
    async cached(hashes) {
      const hits = await call<{ hash: string }[] | null>("/torrents/checkcached?format=list&list_files=false", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ hashes }),
      });
      return new Set((hits ?? []).map((h) => h.hash.toLowerCase()));
    },
    async resolve(hash, hint) {
      // Reuse it if it's already in the account (adding is rate-limited to 50 an hour).
      let t = (await call<T[] | null>("/torrents/mylist?bypass_cache=true"))?.find((x) => x.hash.toLowerCase() === hash);
      if (!t) {
        const form = new FormData();
        form.set("magnet", magnet(hash));
        const { torrent_id } = await call<{ torrent_id: number }>("/torrents/createtorrent", { method: "POST", body: form });
        t = await call<T>(`/torrents/mylist?bypass_cache=true&id=${torrent_id}`);
      }
      if (!t.download_present || !t.files?.length)
        throw new NotReady(`TorBox doesn't have this yet. It's downloading it (${Math.round(t.progress * 100)}%); try again when it's done.`);
      const f = pickFile(t.files, hint);
      const url = await call<string>(`/torrents/requestdl?${new URLSearchParams({ token: key, torrent_id: String(t.id), file_id: String(f.id) })}`);
      return { url, filename: base(f.name) };
    },
  };
}

function realDebrid(key: string, root: string): Adapter {
  const name = SERVICES.realdebrid;
  const call = <T>(path: string, form?: Record<string, string>) =>
    fetchJson<T>(name, `${root}${path}`, {
      method: form ? "POST" : "GET",
      headers: { authorization: `Bearer ${key}` },
      ...(form && { body: new URLSearchParams(form) }),
    });
  type Info = {
    id: string;
    status: string;
    progress: number;
    links: string[];
    files: { id: number; path: string; bytes: number; selected: number }[];
  };
  const DEAD = ["magnet_error", "error", "virus", "dead"];
  return {
    async account() {
      const u = await call<{ premium: number; expiration: string }>("/user");
      return {
        premium: u.premium > 0,
        until: u.premium > 0 ? u.expiration : null,
      };
    },
    // Real-Debrid switched off its cache check in 2024: the only way to know is to add the torrent.
    cached: async () => null,
    async resolve(hash, hint) {
      // ponytail: looks at the newest 100 torrents only; older duplicates just get added again.
      const mine = (await call<{ id: string; hash: string; status: string }[] | null>("/torrents?limit=100")) ?? [];
      const id =
        mine.find((t) => t.hash.toLowerCase() === hash && !DEAD.includes(t.status))?.id ??
        (
          await call<{ id: string }>("/torrents/addMagnet", {
            magnet: magnet(hash),
          })
        ).id;
      let info = await call<Info>(`/torrents/info/${id}`);
      for (let i = 0; i < 8 && info.status === "magnet_conversion"; i++) {
        await sleep(1000);
        info = await call<Info>(`/torrents/info/${id}`);
      }
      if (info.status === "waiting_files_selection") {
        // Every video file, so a season pack is ready for every episode.
        const videos = info.files.filter((f) => VIDEO.test(f.path));
        await call(`/torrents/selectFiles/${id}`, {
          files: (videos.length ? videos : info.files).map((f) => f.id).join(","),
        });
        info = await call<Info>(`/torrents/info/${id}`);
      }
      if (DEAD.includes(info.status)) throw new NotReady("Real-Debrid couldn't get this torrent (it may be dead or blocked).");
      if (info.status !== "downloaded")
        throw new NotReady(`Real-Debrid doesn't have this yet. It's downloading it (${info.progress}%); try again when it's done.`);
      // links[] lines up with the selected files, in order.
      const selected = info.files
        .filter((f) => f.selected === 1)
        .map((f, i) => ({
          name: f.path,
          size: f.bytes,
          idx: f.id - 1,
          link: info.links[i],
        }));
      const f = pickFile(selected, hint);
      const u = await call<{ download: string; filename: string }>("/unrestrict/link", { link: f.link });
      return { url: u.download, filename: u.filename };
    },
  };
}

function premiumize(key: string, root: string): Adapter {
  const name = SERVICES.premiumize;
  // Premiumize answers 200 with status "error" for most failures.
  const call = async <T>(path: string, form?: Record<string, string>) => {
    const r = await fetchJson<T & { status: string; message?: string }>(
      name,
      `${root}${path}${path.includes("?") ? "&" : "?"}apikey=${encodeURIComponent(key)}`,
      form ? { method: "POST", body: new URLSearchParams(form) } : {},
    );
    if (r.status !== "success") throw new UpstreamError(name, /key|auth|login/i.test(r.message ?? "") ? 401 : 400, r.message ?? "Premiumize said no.");
    return r;
  };
  return {
    async account() {
      const a = await call<{ premium_until: number | false | null }>("/account/info");
      const until = a.premium_until ? a.premium_until * 1000 : 0;
      return {
        premium: until > Date.now(),
        until: until ? new Date(until).toISOString() : null,
      };
    },
    async cached(hashes) {
      const r = await call<{ response: boolean[] }>(`/cache/check?${hashes.map((h) => `items[]=${h}`).join("&")}`);
      return new Set(hashes.filter((_, i) => r.response[i]));
    },
    async resolve(hash, hint) {
      const r = await call<{
        content?: { path: string; size: number; link: string }[];
      }>("/transfer/directdl", { src: magnet(hash) }).catch((e) => {
        if (e instanceof UpstreamError && e.status === 400) throw new NotReady("Premiumize doesn't have this cached.");
        throw e;
      });
      if (!r.content?.length) throw new NotReady("Premiumize doesn't have this cached.");
      const f = pickFile(
        r.content.map((c) => ({ name: c.path, size: c.size, link: c.link })),
        hint,
      );
      return { url: f.link, filename: base(f.name) };
    },
  };
}

export function adapter(service: Service, key: string, bases: DebridBases = {}): Adapter {
  const root = bases[service] || BASES[service];
  return service === "torbox" ? torbox(key, root) : service === "realdebrid" ? realDebrid(key, root) : premiumize(key, root);
}

// --- Marking torrent sources playable ---------------------------------------------

export type DebridMark = { service: Service; cached: boolean };

/** Torrent sources a connected service can stream become playable, marked with that service. */
export async function withDebrid(db: PrismaClient, userId: string, options: PlaybackOption[], bases?: DebridBases): Promise<PlaybackOption[]> {
  const hashes = [...new Set(options.flatMap((o) => (o.kind === "torrent" ? [o.infoHash!] : [])))].slice(0, 100); // ponytail: first 100 torrents per title
  if (!hashes.length) return options;
  const accounts = await db.debridAccount.findMany({
    where: { userId },
    orderBy: { createdAt: "asc" },
  });
  if (!accounts.length) return options;
  const checks = await Promise.all(
    accounts.map(async (a) => ({
      service: a.service as Service,
      // A failed check (service down, key revoked) just means "don't use this service right now".
      cached: await adapter(a.service as Service, decrypt<string>(a.secret), bases)
        .cached(hashes)
        .catch(() => undefined),
    })),
  );
  const names = accounts.map((a) => SERVICES[a.service as Service]).join(" or ");
  return options.map((o) => {
    if (o.kind !== "torrent") return o;
    const hit = checks.find((c) => c.cached?.has(o.infoHash!)) ?? checks.find((c) => c.cached === null);
    if (!hit)
      return {
        ...o,
        note: `Not ready on ${names}: it would have to download first.`,
      };
    return {
      ...o,
      playable: true,
      debrid: { service: hit.service, cached: hit.cached !== null },
      note: null,
    };
  });
}

// --- Routes ---------------------------------------------------------------------

export function registerDebridRoutes(
  app: FastifyInstance,
  db: PrismaClient,
  guards: {
    requireUser: preHandlerHookHandler;
    requireProfile: preHandlerHookHandler;
  },
  bases?: DebridBases,
) {
  const service = {
    type: "object",
    required: ["service"],
    properties: { service: { enum: Object.keys(SERVICES) } },
  } as const;

  // Every service, connected or not, with live account status for the connected ones.
  app.get("/debrid", { preHandler: guards.requireUser }, async (req) => {
    const rows = await db.debridAccount.findMany({
      where: { userId: req.user.id },
    });
    return Promise.all(
      (Object.keys(SERVICES) as Service[]).map(async (s) => {
        const row = rows.find((r) => r.service === s);
        if (!row) return { service: s, name: SERVICES[s], connected: false };
        try {
          return {
            service: s,
            name: SERVICES[s],
            connected: true,
            ...(await adapter(s, decrypt<string>(row.secret), bases).account()),
          };
        } catch (e) {
          return {
            service: s,
            name: SERVICES[s],
            connected: true,
            error: (e as Error).message,
          };
        }
      }),
    );
  });

  app.put<{ Params: { service: Service }; Body: { apiKey: string } }>(
    "/debrid/:service",
    {
      preHandler: guards.requireUser,
      schema: {
        params: service,
        body: {
          type: "object",
          required: ["apiKey"],
          additionalProperties: false,
          properties: {
            apiKey: { type: "string", minLength: 10, maxLength: 200 },
          },
        },
      },
    },
    async (req, reply) => {
      const s = req.params.service;
      const key = req.body.apiKey.trim();
      let account: Account;
      try {
        account = await adapter(s, key, bases).account();
      } catch (e) {
        const status = e instanceof UpstreamError ? e.status : 0;
        return reply.code(400).send({
          error: status === 401 || status === 403 ? `${SERVICES[s]} didn't accept that API key.` : `Couldn't check the key: ${(e as Error).message}`,
        });
      }
      await db.debridAccount.upsert({
        where: { userId_service: { userId: req.user.id, service: s } },
        create: { userId: req.user.id, service: s, secret: encrypt(key) },
        update: { secret: encrypt(key) },
      });
      return { service: s, name: SERVICES[s], connected: true, ...account };
    },
  );

  app.delete<{ Params: { service: Service } }>("/debrid/:service", { preHandler: guards.requireUser, schema: { params: service } }, async (req, reply) => {
    await db.debridAccount.deleteMany({
      where: { userId: req.user.id, service: req.params.service },
    });
    return reply.code(204).send();
  });

  // Turn one torrent source into a link, right before playing it (links expire, so never ahead of time).
  app.post<{ Body: { service: Service; infoHash: string } & Hint }>(
    "/playback/resolve",
    {
      preHandler: guards.requireProfile,
      schema: {
        body: {
          type: "object",
          required: ["service", "infoHash"],
          additionalProperties: false,
          properties: {
            service: { enum: Object.keys(SERVICES) },
            infoHash: { type: "string", pattern: "^[a-f0-9]{40}$" },
            filename: { type: ["string", "null"], maxLength: 500 },
            fileIdx: { type: "integer", minimum: 0 },
            season: { type: "integer", minimum: 0 },
            episode: { type: "integer", minimum: 0 },
          },
        },
      },
    },
    async (req, reply) => {
      const { service: s, infoHash, ...hint } = req.body;
      const row = await db.debridAccount.findUnique({
        where: { userId_service: { userId: req.profile.userId, service: s } },
      });
      if (!row)
        return reply.code(409).send({
          error: `${SERVICES[s]} isn't connected. Add it in Settings > Debrid.`,
        });
      try {
        return await adapter(s, decrypt<string>(row.secret), bases).resolve(infoHash, hint);
      } catch (e) {
        if (e instanceof NotReady) return reply.code(409).send({ error: e.message, notReady: true });
        throw e;
      }
    },
  );
}
