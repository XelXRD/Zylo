// Per-profile library: watch history, ratings, watchlist, lists, Trakt connection.
// Everything works without Trakt; when a profile is connected, changes are pushed to Trakt too.
import type { FastifyInstance, preHandlerHookHandler } from "fastify";
import type { PrismaClient } from "./generated/prisma/client.js";
import { cardOf, type MetadataService } from "./metadata.js";
import { decrypt, encrypt } from "./secrets.js";
import type { Card } from "./tmdb.js";
import { syncBody, traktClient, TRAKT_BASE, type Target, type TraktService } from "./trakt.js";

type Guards = { requireUser: preHandlerHookHandler; requireAdmin: preHandlerHookHandler; requireProfile: preHandlerHookHandler };
type TitleParams = { type: "movie" | "series"; id: number };
type EpisodeRef = { season?: number; episode?: number };

const titleParams = {
  type: "object",
  required: ["type", "id"],
  properties: { type: { enum: ["movie", "series"] }, id: { type: "integer", minimum: 1 } },
} as const;
const episodeRef = { season: { type: "integer", minimum: 0, maximum: 500 }, episode: { type: "integer", minimum: 0, maximum: 5000 } } as const;

// In-progress device sign-ins, by profile. ponytail: in-process memory; a restart just means "start again"
const pendingDevice = new Map<string, { code: string; until: number }>();
const recommended = new Map<string, { until: number; cards: Card[] }>();

export function registerLibraryRoutes(
  app: FastifyInstance,
  db: PrismaClient,
  guards: Guards,
  deps: { metadata: MetadataService; trakt: TraktService; enqueueSync: (profileId: string) => Promise<void>; traktBase?: string },
) {
  const { requireAdmin, requireProfile } = guards;
  const { metadata, trakt, enqueueSync } = deps;

  // --- admin: the Trakt app (one per server) ---

  app.put<{ Body: { clientId: string; clientSecret?: string; redirectUri?: string } }>(
    "/integrations/trakt",
    {
      preHandler: requireAdmin,
      schema: {
        body: {
          type: "object",
          required: ["clientId"],
          additionalProperties: false,
          properties: {
            clientId: { type: "string", minLength: 20, maxLength: 200 },
            clientSecret: { type: "string", maxLength: 200 }, // only older Trakt apps have one
            redirectUri: { type: "string", minLength: 1, maxLength: 500 },
          },
        },
      },
    },
    async (req, reply) => {
      const creds = {
        clientId: req.body.clientId.trim(),
        ...(req.body.clientSecret?.trim() && { clientSecret: req.body.clientSecret.trim() }),
        redirectUri: req.body.redirectUri?.trim() || "http://localhost",
      };
      try {
        // Asking for a device code proves the Client ID is real (the secret is checked at first sign-in).
        await traktClient(creds, deps.traktBase ?? TRAKT_BASE).deviceCode();
      } catch {
        return reply.code(400).send({ error: "Trakt didn't recognise that Client ID. Copy it again from your Trakt app page." });
      }
      await db.integration.upsert({
        where: { id: "trakt" },
        create: { id: "trakt", secret: encrypt(creds), lastCheckedAt: new Date() },
        update: { secret: encrypt(creds), lastCheckedAt: new Date(), lastError: null },
      });
      return reply.code(204).send();
    },
  );

  // --- profile: Trakt connection ---

  app.get("/trakt", { preHandler: requireProfile }, async (req) => {
    const [available, acct, pending] = await Promise.all([
      db.integration.count({ where: { id: "trakt" } }),
      db.traktAccount.findUnique({ where: { profileId: req.profile.id } }),
      db.traktOutbox.count({ where: { profileId: req.profile.id } }),
    ]);
    return {
      available: available > 0,
      connected: Boolean(acct),
      username: acct?.username ?? null,
      syncEnabled: acct?.syncEnabled ?? false,
      syncState: acct?.syncState ?? "idle",
      lastSyncAt: acct?.lastSyncAt ?? null,
      lastError: acct?.lastError ?? null,
      pendingChanges: pending,
    };
  });

  app.post("/trakt/connect", { preHandler: requireProfile }, async (req, reply) => {
    const client = await trakt.app();
    if (!client) return reply.code(409).send({ error: "Trakt isn't set up on this server yet. An admin can add it in Settings > Integrations." });
    const d = await client.deviceCode();
    pendingDevice.set(req.profile.id, { code: d.device_code, until: Date.now() + d.expires_in * 1000 });
    return { userCode: d.user_code, verificationUrl: d.verification_url, expiresIn: d.expires_in, interval: d.interval };
  });

  app.post("/trakt/connect/poll", { preHandler: requireProfile }, async (req, reply) => {
    const pending = pendingDevice.get(req.profile.id);
    if (!pending || pending.until < Date.now()) return { status: "expired" };
    const client = await trakt.app();
    if (!client) return reply.code(409).send({ error: "Trakt isn't set up on this server." });
    const r = await client.pollDevice(pending.code);
    if (r.status !== "connected") {
      if (r.status !== "pending" && r.status !== "slow_down") pendingDevice.delete(req.profile.id);
      return { status: r.status };
    }
    pendingDevice.delete(req.profile.id);
    await trakt.saveTokens(req.profile.id, r.tokens);
    await enqueueSync(req.profile.id);
    return { status: "connected" };
  });

  app.post("/trakt/sync", { preHandler: requireProfile }, async (req, reply) => {
    if (!(await db.traktAccount.findUnique({ where: { profileId: req.profile.id } }))) return reply.code(404).send({ error: "This profile isn't connected to Trakt." });
    await enqueueSync(req.profile.id);
    return reply.code(202).send({ queued: true });
  });

  app.patch<{ Body: { syncEnabled: boolean } }>(
    "/trakt",
    { preHandler: requireProfile, schema: { body: { type: "object", required: ["syncEnabled"], properties: { syncEnabled: { type: "boolean" } } } } },
    async (req, reply) => {
      const { count } = await db.traktAccount.updateMany({ where: { profileId: req.profile.id }, data: { syncEnabled: req.body.syncEnabled } });
      return count ? reply.code(204).send() : reply.code(404).send({ error: "This profile isn't connected to Trakt." });
    },
  );

  app.delete("/trakt", { preHandler: requireProfile }, async (req, reply) => {
    // Best effort: tell Trakt to forget the token. Local history, ratings and lists are kept.
    const [acct, client] = await Promise.all([db.traktAccount.findUnique({ where: { profileId: req.profile.id } }), trakt.app()]);
    if (acct && client) await client.revoke(decrypt<{ accessToken: string }>(acct.secret).accessToken).catch(() => {});
    await db.traktOutbox.deleteMany({ where: { profileId: req.profile.id } });
    await db.traktAccount.deleteMany({ where: { profileId: req.profile.id } });
    return reply.code(204).send();
  });

  // --- profile: library ---

  const withIds = { externalIds: { where: { system: "tmdb" } } } as const;

  app.get("/library", { preHandler: requireProfile }, async (req) => {
    const profileId = req.profile.id;
    const [watchlist, lists, history, movies, episodes, ratings] = await Promise.all([
      db.collectionItem.findMany({ where: { collection: { profileId, key: "watchlist" } }, include: { mediaItem: { include: withIds } }, orderBy: { addedAt: "desc" } }),
      db.collection.findMany({ where: { profileId, key: { not: "watchlist" } }, include: { _count: { select: { items: true } } }, orderBy: { name: "asc" } }),
      db.watchEvent.findMany({ where: { profileId }, include: { mediaItem: { include: withIds } }, orderBy: { watchedAt: "desc" }, take: 40 }),
      db.watchEvent.findMany({ where: { profileId, seasonNumber: -1, mediaItem: { type: "movie" } }, distinct: ["mediaItemId"], select: { mediaItemId: true } }),
      db.watchEvent.count({ where: { profileId, episodeNumber: { gte: 0 } } }),
      db.rating.count({ where: { profileId } }),
    ]);
    return {
      watchlist: watchlist.map((w) => cardOf(w.mediaItem)).filter(Boolean),
      lists: lists.map((l) => ({ id: l.id, name: l.name, description: l.description, count: l._count.items })),
      history: history
        .map((h) => ({ card: cardOf(h.mediaItem), season: h.seasonNumber >= 0 ? h.seasonNumber : null, episode: h.episodeNumber >= 0 ? h.episodeNumber : null, watchedAt: h.watchedAt }))
        .filter((h) => h.card),
      stats: { filmsWatched: movies.length, episodesWatched: episodes, ratings },
    };
  });

  app.get<{ Params: { id: string } }>(
    "/library/lists/:id",
    { preHandler: requireProfile, schema: { params: { type: "object", properties: { id: { type: "string", format: "uuid" } } } } },
    async (req, reply) => {
      const list = await db.collection.findFirst({
        where: { id: req.params.id, profileId: req.profile.id },
        include: { items: { include: { mediaItem: { include: withIds } }, orderBy: { addedAt: "desc" } } },
      });
      if (!list) return reply.code(404).send({ error: "List not found." });
      return { name: list.name, description: list.description, items: list.items.map((i) => cardOf(i.mediaItem)).filter(Boolean) };
    },
  );

  // --- profile: per-title state and actions ---

  const target = (p: TitleParams, e: EpisodeRef = {}): Target => ({
    type: p.type,
    tmdbId: p.id,
    ...(p.type === "series" && e.season !== undefined && e.episode !== undefined && { season: e.season, episode: e.episode }),
  });
  const epKey = (p: TitleParams, e: EpisodeRef) =>
    p.type === "series" && e.season !== undefined && e.episode !== undefined ? { seasonNumber: e.season, episodeNumber: e.episode } : { seasonNumber: -1, episodeNumber: -1 };

  app.get<{ Params: TitleParams }>("/titles/:type/:id/state", { preHandler: requireProfile, schema: { params: titleParams } }, async (req) => {
    const item = await metadata.title(req.params.type, req.params.id);
    const profileId = req.profile.id;
    const [events, rating, inWatchlist, episodeRatings] = await Promise.all([
      db.watchEvent.findMany({ where: { profileId, mediaItemId: item.id }, orderBy: { watchedAt: "desc" } }),
      db.rating.findFirst({ where: { profileId, mediaItemId: item.id, seasonNumber: -1 } }),
      db.collectionItem.count({ where: { mediaItemId: item.id, collection: { profileId, key: "watchlist" } } }),
      db.rating.findMany({ where: { profileId, mediaItemId: item.id, episodeNumber: { gte: 0 } } }),
    ]);
    const own = events.filter((e) => e.seasonNumber === -1);
    const watchedEpisodes = [...new Set(events.filter((e) => e.episodeNumber >= 0).map((e) => `${e.seasonNumber}:${e.episodeNumber}`))];
    return {
      plays: own.length,
      lastWatchedAt: own[0]?.watchedAt ?? null,
      rating: rating?.rating ?? null,
      inWatchlist: inWatchlist > 0,
      watchedEpisodes,
      episodeRatings: Object.fromEntries(episodeRatings.map((r) => [`${r.seasonNumber}:${r.episodeNumber}`, r.rating])),
    };
  });

  app.post<{ Params: TitleParams; Body: EpisodeRef }>(
    "/titles/:type/:id/watched",
    { preHandler: requireProfile, schema: { params: titleParams, body: { type: "object", additionalProperties: false, properties: episodeRef } } },
    async (req, reply) => {
      const body = req.body ?? {};
      if (req.params.type === "series" && (body.season === undefined || body.episode === undefined)) {
        return reply.code(400).send({ error: "Mark a specific episode as watched." });
      }
      const item = await metadata.title(req.params.type, req.params.id);
      const watchedAt = new Date(Math.floor(Date.now() / 1000) * 1000); // Trakt keeps whole seconds
      await db.watchEvent.create({ data: { profileId: req.profile.id, mediaItemId: item.id, ...epKey(req.params, body), watchedAt, source: "zylo" } });
      await trakt.push(req.profile.id, "/sync/history", syncBody(target(req.params, body), { watched_at: watchedAt.toISOString() }));
      return reply.code(204).send();
    },
  );

  app.delete<{ Params: TitleParams; Querystring: EpisodeRef }>(
    "/titles/:type/:id/watched",
    { preHandler: requireProfile, schema: { params: titleParams, querystring: { type: "object", properties: episodeRef } } },
    async (req, reply) => {
      const item = await metadata.title(req.params.type, req.params.id);
      await db.watchEvent.deleteMany({ where: { profileId: req.profile.id, mediaItemId: item.id, ...epKey(req.params, req.query) } });
      await trakt.push(req.profile.id, "/sync/history/remove", syncBody(target(req.params, req.query)));
      return reply.code(204).send();
    },
  );

  app.put<{ Params: TitleParams; Body: EpisodeRef & { rating: number } }>(
    "/titles/:type/:id/rating",
    {
      preHandler: requireProfile,
      schema: { params: titleParams, body: { type: "object", required: ["rating"], additionalProperties: false, properties: { rating: { type: "integer", minimum: 1, maximum: 10 }, ...episodeRef } } },
    },
    async (req, reply) => {
      const item = await metadata.title(req.params.type, req.params.id);
      const key = { profileId: req.profile.id, mediaItemId: item.id, ...epKey(req.params, req.body) };
      const ratedAt = new Date();
      await db.rating.upsert({
        where: { profileId_mediaItemId_seasonNumber_episodeNumber: key },
        create: { ...key, rating: req.body.rating, ratedAt },
        update: { rating: req.body.rating, ratedAt },
      });
      await trakt.push(req.profile.id, "/sync/ratings", syncBody(target(req.params, req.body), { rating: req.body.rating, rated_at: ratedAt.toISOString() }));
      return reply.code(204).send();
    },
  );

  app.delete<{ Params: TitleParams; Querystring: EpisodeRef }>(
    "/titles/:type/:id/rating",
    { preHandler: requireProfile, schema: { params: titleParams, querystring: { type: "object", properties: episodeRef } } },
    async (req, reply) => {
      const item = await metadata.title(req.params.type, req.params.id);
      await db.rating.deleteMany({ where: { profileId: req.profile.id, mediaItemId: item.id, ...epKey(req.params, req.query) } });
      await trakt.push(req.profile.id, "/sync/ratings/remove", syncBody(target(req.params, req.query)));
      return reply.code(204).send();
    },
  );

  app.put<{ Params: TitleParams }>("/titles/:type/:id/watchlist", { preHandler: requireProfile, schema: { params: titleParams } }, async (req, reply) => {
    const item = await metadata.title(req.params.type, req.params.id);
    const list = await trakt.collection(req.profile.id, "watchlist", "Watchlist");
    await db.collectionItem.upsert({ where: { collectionId_mediaItemId: { collectionId: list.id, mediaItemId: item.id } }, create: { collectionId: list.id, mediaItemId: item.id }, update: {} });
    await trakt.push(req.profile.id, "/sync/watchlist", syncBody(target(req.params)));
    return reply.code(204).send();
  });

  app.delete<{ Params: TitleParams }>("/titles/:type/:id/watchlist", { preHandler: requireProfile, schema: { params: titleParams } }, async (req, reply) => {
    const item = await metadata.title(req.params.type, req.params.id);
    await db.collectionItem.deleteMany({ where: { mediaItemId: item.id, collection: { profileId: req.profile.id, key: "watchlist" } } });
    await trakt.push(req.profile.id, "/sync/watchlist/remove", syncBody(target(req.params)));
    return reply.code(204).send();
  });

  // Trakt's personal recommendations, as full title cards. Empty when the profile isn't connected.
  app.get("/discover/recommended", { preHandler: requireProfile }, async (req) => {
    const hit = recommended.get(req.profile.id);
    if (hit && hit.until > Date.now()) return { results: hit.cards };
    const api = await trakt.tokenFor(req.profile.id);
    if (!api) return { results: [] };
    type Rec = { title: string; year: number | null; ids: { tmdb?: number | null } };
    const [movies, shows] = await Promise.all([
      api.get<Rec[]>("/recommendations/movies?limit=12&ignore_collected=true"),
      api.get<Rec[]>("/recommendations/shows?limit=12&ignore_collected=true"),
    ]);
    const wanted = [...movies.map((m) => ["movie", m] as const), ...shows.map((s) => ["series", s] as const)].filter(([, r]) => r.ids.tmdb);
    // Fetch full details (posters) for each; titles TMDB can't find are simply left out.
    const items = await Promise.all(wanted.map(([type, r]) => metadata.title(type, r.ids.tmdb!).catch(() => null)));
    const withExt = await db.mediaItem.findMany({ where: { id: { in: items.filter((i) => i).map((i) => i!.id) } }, include: withIds });
    const cards = withExt.map(cardOf).filter((c): c is Card => c !== null);
    recommended.set(req.profile.id, { until: Date.now() + 3_600_000, cards });
    return { results: cards };
  });
}
