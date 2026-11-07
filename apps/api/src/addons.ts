// Stremio addons: install by manifest URL, manage, and ask them for streams and catalogues.
import type { FastifyInstance, preHandlerHookHandler } from "fastify";
import type { Addon, PrismaClient } from "./generated/prisma/client.js";
import type { MetadataService } from "./metadata.js";
import { normaliseUrl, safeFetchJson, UnsafeUrl, type SafeFetchOptions } from "./safe-fetch.js";
import { decrypt, encrypt } from "./secrets.js";
import { baseOf, fetchManifest, normaliseStream, resourceNames, resourceUrl, supports, type Manifest, type PlaybackOption } from "./stremio.js";

type Guards = { requireUser: preHandlerHookHandler };
export type AddonCard = { type: "movie" | "series"; imdbId: string; title: string; year: number | null; poster: string | null };

// Short-lived caches: stream links expire, catalogues change slowly.
// ponytail: per-process memory; Redis if zylo-core ever runs as several replicas
const cache = new Map<string, { until: number; value: unknown }>();
async function cached<T>(key: string, ttlMs: number, load: () => Promise<T>) {
  const hit = cache.get(key);
  if (hit && hit.until > Date.now()) return hit.value as T;
  const value = await load();
  cache.set(key, { until: Date.now() + ttlMs, value });
  return value;
}

const view = (a: Addon) => {
  const m = a.manifest as Manifest;
  return {
    id: a.id,
    addonId: a.addonId,
    name: m.name,
    version: m.version,
    description: m.description ?? null,
    logo: m.logo ?? null,
    host: a.host,
    enabled: a.enabled,
    position: a.position,
    types: m.types,
    resources: resourceNames(m),
    catalogs: m.catalogs.length,
    adult: Boolean(m.behaviorHints.adult),
    p2p: Boolean(m.behaviorHints.p2p),
    configurable: Boolean(m.behaviorHints.configurable || m.behaviorHints.configurationRequired),
    needsConfiguring: Boolean(m.behaviorHints.configurationRequired),
    lastCheckedAt: a.lastCheckedAt,
    lastError: a.lastError,
  };
};

export function registerAddonRoutes(app: FastifyInstance, db: PrismaClient, guards: Guards, deps: { metadata: MetadataService; fetchOptions?: SafeFetchOptions }) {
  const { requireUser } = guards;
  const fetchOptions: SafeFetchOptions = deps.fetchOptions ?? { allowPrivate: process.env.ZYLO_ALLOW_PRIVATE_ADDONS === "true" };
  const urlOf = (a: Addon) => decrypt<string>(a.transportUrl);
  const idParam = { type: "object", required: ["id"], properties: { id: { type: "string", format: "uuid" } } } as const;
  const own = (userId: string, id: string) => db.addon.findFirst({ where: { id, userId } });

  app.get("/addons", { preHandler: requireUser }, async (req) =>
    (await db.addon.findMany({ where: { userId: req.user.id }, orderBy: { position: "asc" } })).map(view),
  );

  // Install (or replace the configuration of) an addon from its manifest URL.
  app.post<{ Body: { url: string } }>(
    "/addons",
    { preHandler: requireUser, schema: { body: { type: "object", required: ["url"], additionalProperties: false, properties: { url: { type: "string", minLength: 8, maxLength: 4000 } } } } },
    async (req, reply) => {
      let url: string;
      let manifest: Manifest;
      try {
        url = normaliseUrl(req.body.url).toString();
        manifest = await fetchManifest(url, fetchOptions);
      } catch (e) {
        return reply.code(400).send({ error: e instanceof UnsafeUrl ? e.message : "Couldn't read that addon." });
      }
      const existing = await db.addon.findUnique({ where: { userId_addonId: { userId: req.user.id, addonId: manifest.id } } });
      const position = existing?.position ?? (await db.addon.count({ where: { userId: req.user.id } }));
      const data = { transportUrl: encrypt(url), host: new URL(url).host, manifest: manifest as object, lastCheckedAt: new Date(), lastError: null };
      const saved = await db.addon.upsert({
        where: { userId_addonId: { userId: req.user.id, addonId: manifest.id } },
        create: { userId: req.user.id, addonId: manifest.id, position, ...data },
        update: data,
      });
      return reply.code(existing ? 200 : 201).send({ ...view(saved), replaced: Boolean(existing) });
    },
  );

  app.post<{ Params: { id: string } }>("/addons/:id/test", { preHandler: requireUser, schema: { params: idParam } }, async (req, reply) => {
    const a = await own(req.user.id, req.params.id);
    if (!a) return reply.code(404).send({ error: "Addon not found." });
    const started = Date.now();
    try {
      const manifest = await fetchManifest(urlOf(a), fetchOptions);
      const saved = await db.addon.update({ where: { id: a.id }, data: { manifest: manifest as object, lastCheckedAt: new Date(), lastError: null } });
      return { ok: true, ms: Date.now() - started, addon: view(saved) };
    } catch (e) {
      const msg = e instanceof UnsafeUrl ? e.message : "Couldn't read the addon.";
      await db.addon.update({ where: { id: a.id }, data: { lastCheckedAt: new Date(), lastError: msg } });
      return { ok: false, error: msg };
    }
  });

  // Open the addon's own configuration page (it lives on the addon's site).
  app.get<{ Params: { id: string } }>("/addons/:id/configure", { preHandler: requireUser, schema: { params: idParam } }, async (req, reply) => {
    const a = await own(req.user.id, req.params.id);
    if (!a) return reply.code(404).send({ error: "Addon not found." });
    return { url: `${baseOf(urlOf(a))}/configure` };
  });

  app.patch<{ Params: { id: string }; Body: { enabled?: boolean; move?: "up" | "down" } }>(
    "/addons/:id",
    {
      preHandler: requireUser,
      schema: { params: idParam, body: { type: "object", additionalProperties: false, minProperties: 1, properties: { enabled: { type: "boolean" }, move: { enum: ["up", "down"] } } } },
    },
    async (req, reply) => {
      const a = await own(req.user.id, req.params.id);
      if (!a) return reply.code(404).send({ error: "Addon not found." });
      if (req.body.enabled !== undefined) await db.addon.update({ where: { id: a.id }, data: { enabled: req.body.enabled } });
      if (req.body.move) {
        // Swap with the neighbour; then renumber so positions stay 0..n-1.
        const all = await db.addon.findMany({ where: { userId: req.user.id }, orderBy: { position: "asc" } });
        const i = all.findIndex((x) => x.id === a.id);
        const j = req.body.move === "up" ? i - 1 : i + 1;
        if (j >= 0 && j < all.length) [all[i], all[j]] = [all[j], all[i]];
        await db.$transaction(all.map((x, position) => db.addon.update({ where: { id: x.id }, data: { position } })));
      }
      return view((await own(req.user.id, a.id))!);
    },
  );

  app.delete<{ Params: { id: string } }>("/addons/:id", { preHandler: requireUser, schema: { params: idParam } }, async (req, reply) => {
    const { count } = await db.addon.deleteMany({ where: { id: req.params.id, userId: req.user.id } });
    return count ? reply.code(204).send() : reply.code(404).send({ error: "Addon not found." });
  });

  // --- streams ------------------------------------------------------------------

  app.get<{ Params: { type: "movie" | "series"; id: number }; Querystring: { season?: number; episode?: number } }>(
    "/titles/:type/:id/streams",
    {
      preHandler: requireUser,
      schema: {
        params: { type: "object", required: ["type", "id"], properties: { type: { enum: ["movie", "series"] }, id: { type: "integer", minimum: 1 } } },
        querystring: { type: "object", properties: { season: { type: "integer", minimum: 0 }, episode: { type: "integer", minimum: 0 } } },
      },
    },
    async (req, reply) => {
      const { type, id } = req.params;
      const { season, episode } = req.query;
      if (type === "series" && (season === undefined || episode === undefined)) return reply.code(400).send({ error: "Pick an episode to find streams for." });

      const item = await deps.metadata.title(type, id);
      const imdb = (await db.externalId.findFirst({ where: { mediaItemId: item.id, system: "imdb" } }))?.value ?? null;
      const suffix = type === "series" ? `:${season}:${episode}` : "";
      // Addons are keyed mostly by IMDb ("tt…"); some accept TMDB ("tmdb:…"). Try both, in that order.
      const candidates = [imdb && `${imdb}${suffix}`, `tmdb:${id}${suffix}`].filter((x): x is string => !!x);

      const addons = await db.addon.findMany({ where: { userId: req.user.id, enabled: true }, orderBy: { position: "asc" } });
      const results = await Promise.all(
        addons.map(async (a) => {
          const m = a.manifest as Manifest;
          const videoId = candidates.find((c) => supports(m, "stream", type, c));
          const base = { id: a.id, name: m.name, logo: m.logo ?? null };
          if (!videoId) return { ...base, status: "skipped" as const, options: [] as PlaybackOption[] };
          try {
            const raw = await cached(`stream:${a.transportUrl}:${type}:${videoId}`, 5 * 60_000, () =>
              safeFetchJson<{ streams?: unknown[] }>(resourceUrl(baseOf(urlOf(a)), "stream", type, videoId), { ...fetchOptions, timeoutMs: 15_000 }),
            );
            const streams = Array.isArray(raw?.streams) ? raw.streams.slice(0, 200) : [];
            const options = streams
              .map((s, i) => (s && typeof s === "object" ? normaliseStream(s as Record<string, unknown>, { kind: "addon", id: a.id, name: m.name }, i) : null))
              .filter((o): o is PlaybackOption => o !== null);
            return { ...base, status: "ok" as const, options };
          } catch (e) {
            // Many addons answer "not found" when they simply have nothing for this title.
            if (e instanceof UnsafeUrl && e.status === 404) return { ...base, status: "ok" as const, options: [] as PlaybackOption[] };
            return { ...base, status: "error" as const, error: e instanceof UnsafeUrl ? e.message : "The addon didn't answer.", options: [] as PlaybackOption[] };
          }
        }),
      );
      return { addons: results.filter((r) => r.status !== "skipped"), skipped: results.filter((r) => r.status === "skipped").length };
    },
  );

  // --- catalogues -----------------------------------------------------------------

  // Browsable catalogues: film/series catalogues that need no extra input (search-only ones are skipped).
  // ponytail: first 3 catalogues per addon, IMDb-keyed items only; addon-only ids arrive with addon meta support
  app.get("/addons/catalogs", { preHandler: requireUser }, async (req) => {
    const addons = await db.addon.findMany({ where: { userId: req.user.id, enabled: true }, orderBy: { position: "asc" } });
    const rows = await Promise.all(
      addons.flatMap((a) => {
        const m = a.manifest as Manifest;
        return m.catalogs
          .filter((c) => (c.type === "movie" || c.type === "series") && !c.extra?.some((e) => e.isRequired) && !c.extraRequired?.length)
          .slice(0, 3)
          .map(async (c) => {
            try {
              const raw = await cached(`catalog:${a.id}:${c.type}:${c.id}`, 30 * 60_000, () =>
                safeFetchJson<{ metas?: unknown[] }>(resourceUrl(baseOf(urlOf(a)), "catalog", c.type, c.id), fetchOptions),
              );
              const items = (Array.isArray(raw?.metas) ? raw.metas : [])
                .map((x) => x as Record<string, unknown>)
                .filter((x) => typeof x.id === "string" && /^tt\d+$/.test(x.id) && typeof x.name === "string")
                .slice(0, 24)
                .map(
                  (x): AddonCard => ({
                    type: c.type as "movie" | "series",
                    imdbId: x.id as string,
                    title: (x.name as string).slice(0, 200),
                    year: typeof x.releaseInfo === "string" && /^\d{4}/.test(x.releaseInfo) ? Number(x.releaseInfo.slice(0, 4)) : null,
                    poster: typeof x.poster === "string" && /^https:\/\//i.test(x.poster) ? x.poster : null,
                  }),
                );
              return items.length ? { addonId: a.id, addon: m.name, catalog: c.name ?? c.id, type: c.type, items } : null;
            } catch {
              return null; // a broken catalogue shouldn't break the home page
            }
          });
      }),
    );
    return { rows: rows.filter(Boolean) };
  });

  // Turn an IMDb id from a catalogue into Zylo's own title address.
  app.get<{ Querystring: { imdb: string; type: "movie" | "series" } }>(
    "/titles/resolve",
    {
      preHandler: requireUser,
      schema: { querystring: { type: "object", required: ["imdb", "type"], properties: { imdb: { type: "string", pattern: "^tt\\d{1,12}$" }, type: { enum: ["movie", "series"] } } } },
    },
    async (req, reply) => {
      const known = await db.externalId.findFirst({ where: { system: "imdb", value: req.query.imdb, type: req.query.type }, include: { mediaItem: { include: { externalIds: { where: { system: "tmdb" } } } } } });
      const knownTmdb = known?.mediaItem.externalIds[0]?.value;
      if (knownTmdb) return { type: req.query.type, tmdbId: Number(knownTmdb) };
      const { client } = await deps.metadata.tmdb();
      const found = await client.findByImdb(req.query.imdb);
      const hit = (req.query.type === "movie" ? found.movie_results : found.tv_results)[0];
      if (!hit) return reply.code(404).send({ error: "TMDB doesn't know that title." });
      return { type: req.query.type, tmdbId: hit.id };
    },
  );
}
