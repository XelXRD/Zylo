// Playback: choosing a source, remembering where each profile got to, Trakt scrobbling, subtitles.
import type { FastifyInstance, preHandlerHookHandler } from "fastify";
import type { PrismaClient } from "./generated/prisma/client.js";
import { collectStreams } from "./addons.js";
import { cardOf, type MetadataService } from "./metadata.js";
import { safeFetchJson, safeFetchText, UnsafeUrl, type SafeFetchOptions } from "./safe-fetch.js";
import { decrypt } from "./secrets.js";
import { baseOf, resourceUrl, supports, type Manifest, type PlaybackOption } from "./stremio.js";
import { syncBody, type Target, type TraktService } from "./trakt.js";

export const COMPLETE_AT = 0.9; // 90% watched counts as watched (end credits)
const QUALITIES = ["4K", "1080p", "720p", "480p"] as const;
export type PlaybackPrefs = { maxQuality: (typeof QUALITIES)[number]; autoPlay: boolean };
export const DEFAULT_PREFS: PlaybackPrefs = { maxQuality: "1080p", autoPlay: true };

// --- Choosing a source ----------------------------------------------------------

/** Containers browsers play natively. MKV/AVI often fail (codec support varies), so they rank lower. */
const browserFriendly = (o: PlaybackOption) => {
  if (o.kind !== "url" || o.notWebReady || o.proxyHeaders) return false;
  const name = (o.filename ?? new URL(o.url!).pathname).toLowerCase();
  return !/\.(mkv|avi|wmv|flv|ts|m2ts|iso)$/.test(name);
};

/**
 * Order sources for the built-in player. Only direct links are auto-play candidates (YouTube entries are
 * usually trailers; websites can't be embedded). Order: what this profile picked last time for this title,
 * then what the browser can play, then quality at or under the profile's limit (best first), then addon order.
 */
export function rankOptions(options: PlaybackOption[], prefs: PlaybackPrefs, choice?: { addonId: string; quality: string | null } | null, bingeGroup?: string) {
  const cap = QUALITIES.indexOf(prefs.maxQuality);
  const qualityRank = (q: string | null) => {
    const i = q ? QUALITIES.indexOf(q as (typeof QUALITIES)[number]) : -1;
    if (i === -1) return 10; // unknown quality: after known ones that fit
    return i < cap ? 20 + i : i - cap; // over the limit goes last
  };
  return options
    .map((o, i) => ({ o, i }))
    .filter(({ o }) => o.kind === "url")
    .sort((a, b) => {
      const k = (x: PlaybackOption) => [
        bingeGroup && x.bingeGroup === bingeGroup ? 0 : 1,
        choice && x.source.id === choice.addonId && x.quality === choice.quality ? 0 : 1,
        browserFriendly(x) ? 0 : 1,
        qualityRank(x.quality),
      ];
      const ka = k(a.o);
      const kb = k(b.o);
      for (let n = 0; n < ka.length; n++) if (ka[n] !== kb[n]) return ka[n] - kb[n];
      return a.i - b.i; // addon order, then the addon's own order
    })
    .map(({ o }) => ({ ...o, browserFriendly: browserFriendly(o) }));
}

// --- Subtitles ------------------------------------------------------------------

/** SRT → WebVTT (browsers only understand VTT). Already-VTT text passes through. */
export function toVtt(text: string) {
  const t = text.replace(/\r\n?/g, "\n").trim();
  if (t.startsWith("WEBVTT")) return t + "\n";
  const body = t
    .split(/\n{2,}/)
    .map((cue) => cue.replace(/^\d+\n(?=\d\d:\d\d)/, "").replace(/(\d\d:\d\d:\d\d),(\d{3})/g, "$1.$2"))
    .join("\n\n");
  return `WEBVTT\n\n${body}\n`;
}

// --- Routes ---------------------------------------------------------------------

type Guards = { requireUser: preHandlerHookHandler; requireProfile: preHandlerHookHandler };
type Where = { type: "movie" | "series"; id: number; season?: number; episode?: number };

const where = {
  type: "object",
  required: ["type", "id"],
  properties: { type: { enum: ["movie", "series"] }, id: { type: "integer", minimum: 1 }, season: { type: "integer", minimum: 0 }, episode: { type: "integer", minimum: 0 } },
} as const;

export function registerPlaybackRoutes(
  app: FastifyInstance,
  db: PrismaClient,
  guards: Guards,
  deps: { metadata: MetadataService; trakt: TraktService; fetchOptions?: SafeFetchOptions },
) {
  const { requireUser, requireProfile } = guards;
  const fetchOptions: SafeFetchOptions = deps.fetchOptions ?? { allowPrivate: process.env.ZYLO_ALLOW_PRIVATE_ADDONS === "true" };
  const prefsOf = (settings: unknown): PlaybackPrefs => ({ ...DEFAULT_PREFS, ...((settings as { playback?: Partial<PlaybackPrefs> })?.playback ?? {}) });
  const ep = (w: Where) => (w.type === "series" && w.season !== undefined && w.episode !== undefined ? { seasonNumber: w.season, episodeNumber: w.episode } : { seasonNumber: -1, episodeNumber: -1 });
  const target = (w: Where): Target => ({ type: w.type, tmdbId: w.id, ...(w.type === "series" && { season: w.season, episode: w.episode }) });
  const needsEpisode = (w: Where) => w.type === "series" && (w.season === undefined || w.episode === undefined);

  // Profile playback preferences.
  app.get("/profile/playback", { preHandler: requireProfile }, async (req) => prefsOf(req.profile.settings));
  app.put<{ Body: Partial<PlaybackPrefs> }>(
    "/profile/playback",
    { preHandler: requireProfile, schema: { body: { type: "object", additionalProperties: false, properties: { maxQuality: { enum: QUALITIES }, autoPlay: { type: "boolean" } } } } },
    async (req) => {
      const playback = { ...prefsOf(req.profile.settings), ...req.body };
      await db.profile.update({ where: { id: req.profile.id }, data: { settings: { ...(req.profile.settings as object), playback } } });
      return playback;
    },
  );

  // Everything the player needs to start: where we got to, and sources in the order to try them.
  app.get<{ Querystring: Where & { bingeGroup?: string } }>(
    "/playback/start",
    { preHandler: requireProfile, schema: { querystring: { ...where, properties: { ...where.properties, bingeGroup: { type: "string", maxLength: 200 } } } } },
    async (req, reply) => {
      const w = req.query;
      if (needsEpisode(w)) return reply.code(400).send({ error: "Pick an episode to play." });
      const item = await deps.metadata.title(w.type, w.id);
      const [progress, choice, streams] = await Promise.all([
        db.playbackProgress.findUnique({ where: { profileId_mediaItemId_seasonNumber_episodeNumber: { profileId: req.profile.id, mediaItemId: item.id, ...ep(w) } } }),
        db.sourceChoice.findUnique({ where: { profileId_mediaItemId: { profileId: req.profile.id, mediaItemId: item.id } } }),
        collectStreams(db, deps.metadata, fetchOptions, req.profile.userId, w.type, w.id, w.season, w.episode),
      ]);
      const prefs = prefsOf(req.profile.settings);
      const all = streams.addons.flatMap((a) => a.options);
      const ranked = rankOptions(all, prefs, choice, w.bingeGroup);
      return {
        prefs,
        // Offer to resume past the first minute, unless it was finished.
        resumeAt: progress && !progress.completed && progress.position > 60 ? progress.position : null,
        ranked,
        others: all.filter((o) => o.kind !== "url" && o.playable), // YouTube, websites: listed, never auto-played
        addonErrors: streams.addons.filter((a) => a.status === "error").map((a) => ({ name: a.name, error: a.error })),
      };
    },
  );

  // Remember a hand-picked source for this film or series.
  app.post<{ Body: Where & { addonId: string; quality: string | null } }>(
    "/playback/choice",
    {
      preHandler: requireProfile,
      schema: { body: { ...where, required: ["type", "id", "addonId"], properties: { ...where.properties, addonId: { type: "string", format: "uuid" }, quality: { type: ["string", "null"], maxLength: 20 } } } },
    },
    async (req, reply) => {
      const item = await deps.metadata.title(req.body.type, req.body.id);
      const key = { profileId: req.profile.id, mediaItemId: item.id };
      await db.sourceChoice.upsert({ where: { profileId_mediaItemId: key }, create: { ...key, addonId: req.body.addonId, quality: req.body.quality }, update: { addonId: req.body.addonId, quality: req.body.quality } });
      return reply.code(204).send();
    },
  );

  // Player events. Saves position (shared across devices) and drives Trakt scrobbling.
  // Completion (≥90%) is recorded once per viewing: one history entry here, one scrobble on Trakt.
  app.post<{ Body: Where & { event: "start" | "progress" | "pause" | "stop"; position: number; duration: number; profileId?: string } }>(
    "/playback/progress",
    {
      preHandler: requireProfile,
      schema: {
        body: {
          ...where,
          required: ["type", "id", "event", "position", "duration"],
          properties: {
            ...where.properties,
            event: { enum: ["start", "progress", "pause", "stop"] },
            position: { type: "number", minimum: 0, maximum: 86400 },
            duration: { type: "number", minimum: 1, maximum: 86400 },
            profileId: { type: "string" },
          },
        },
      },
    },
    async (req, reply) => {
      const w = req.body;
      if (needsEpisode(w)) return reply.code(400).send({ error: "Pick an episode." });
      const item = await deps.metadata.title(w.type, w.id);
      const key = { profileId: req.profile.id, mediaItemId: item.id, ...ep(w) };
      const position = Math.min(w.position, w.duration);
      const fraction = position / w.duration;
      const pct = Math.round(fraction * 1000) / 10;

      const before = await db.playbackProgress.findUnique({ where: { profileId_mediaItemId_seasonNumber_episodeNumber: key } });
      // Starting again near the beginning of something finished begins a fresh viewing.
      const restarting = before?.completed && w.event === "start" && fraction < 0.05;
      const wasComplete = before?.completed && !restarting;
      const nowComplete = wasComplete || fraction >= COMPLETE_AT;

      await db.playbackProgress.upsert({
        where: { profileId_mediaItemId_seasonNumber_episodeNumber: key },
        create: { ...key, position, duration: w.duration, completed: nowComplete },
        update: { position, duration: w.duration, completed: nowComplete },
      });

      const t = target(w);
      if (nowComplete && !wasComplete) {
        // First time over the line: log it here, and on Trakt via a scrobble (or the outbox if Trakt is unreachable).
        const watchedAt = new Date(Math.floor(Date.now() / 1000) * 1000);
        await db.watchEvent.upsert({
          where: { profileId_mediaItemId_seasonNumber_episodeNumber_watchedAt: { ...key, watchedAt } },
          create: { ...key, watchedAt, source: "zylo" },
          update: {},
        });
        const scrobbled = await deps.trakt.scrobble(req.profile.id, "stop", t, Math.max(pct, 90));
        if (!scrobbled) await deps.trakt.push(req.profile.id, "/sync/history", syncBody(t, { watched_at: watchedAt.toISOString() }));
      } else if (!nowComplete && (w.event === "start" || w.event === "pause" || w.event === "stop")) {
        // Live status only; nothing is recorded on Trakt until it's finished. Fire and forget.
        void deps.trakt.scrobble(req.profile.id, w.event === "start" ? "start" : "pause", t, pct);
      }
      return { completed: nowComplete, newlyCompleted: nowComplete && !wasComplete };
    },
  );

  // Clear a resume point ("Start over" or "Remove from Continue watching").
  app.delete<{ Querystring: Where }>("/playback/progress", { preHandler: requireProfile, schema: { querystring: where } }, async (req, reply) => {
    const item = await deps.metadata.title(req.query.type, req.query.id);
    await db.playbackProgress.deleteMany({ where: { profileId: req.profile.id, mediaItemId: item.id, ...ep(req.query) } });
    return reply.code(204).send();
  });

  // Resume points for a whole series (or one film), for title pages.
  app.get<{ Params: { type: "movie" | "series"; id: number } }>(
    "/titles/:type/:id/progress",
    { preHandler: requireProfile, schema: { params: { type: "object", required: ["type", "id"], properties: { type: { enum: ["movie", "series"] }, id: { type: "integer", minimum: 1 } } } } },
    async (req) => {
      const item = await deps.metadata.title(req.params.type, req.params.id);
      const rows = await db.playbackProgress.findMany({ where: { profileId: req.profile.id, mediaItemId: item.id }, orderBy: { updatedAt: "desc" } });
      return rows.map((r) => ({ season: r.seasonNumber >= 0 ? r.seasonNumber : null, episode: r.episodeNumber >= 0 ? r.episodeNumber : null, position: r.position, duration: r.duration, completed: r.completed, updatedAt: r.updatedAt }));
    },
  );

  // Continue watching: started, not finished, most recent first.
  app.get("/playback/continue", { preHandler: requireProfile }, async (req) => {
    const rows = await db.playbackProgress.findMany({
      where: { profileId: req.profile.id, completed: false, position: { gt: 60 } },
      include: { mediaItem: { include: { externalIds: { where: { system: "tmdb" } } } } },
      orderBy: { updatedAt: "desc" },
      take: 12,
    });
    return rows
      .map((r) => ({
        card: cardOf(r.mediaItem),
        season: r.seasonNumber >= 0 ? r.seasonNumber : null,
        episode: r.episodeNumber >= 0 ? r.episodeNumber : null,
        position: r.position,
        duration: r.duration,
        backdropPath: r.mediaItem.backdropPath,
      }))
      .filter((r) => r.card);
  });

  // Subtitles from the profile's subtitle addons (by video id, plus file name/size when known).
  app.get<{ Querystring: Where & { filename?: string; videoSize?: number } }>(
    "/playback/subtitles",
    { preHandler: requireProfile, schema: { querystring: { ...where, properties: { ...where.properties, filename: { type: "string", maxLength: 500 }, videoSize: { type: "integer", minimum: 0 } } } } },
    async (req) => {
      const w = req.query;
      const item = await deps.metadata.title(w.type, w.id);
      const imdb = (await db.externalId.findFirst({ where: { mediaItemId: item.id, system: "imdb" } }))?.value;
      const suffix = w.type === "series" ? `:${w.season}:${w.episode}` : "";
      const ids = [imdb && `${imdb}${suffix}`, `tmdb:${w.id}${suffix}`].filter((x): x is string => !!x);
      const extra = Object.fromEntries(Object.entries({ filename: w.filename, videoSize: w.videoSize?.toString() }).filter(([, v]) => v)) as Record<string, string>;
      const addons = await db.addon.findMany({ where: { userId: req.profile.userId, enabled: true }, orderBy: { position: "asc" } });
      const lists = await Promise.all(
        addons.map(async (a) => {
          const m = a.manifest as Manifest;
          const vid = ids.find((i) => supports(m, "subtitles", w.type, i));
          if (!vid) return [];
          try {
            const r = await safeFetchJson<{ subtitles?: unknown[] }>(resourceUrl(baseOf(decrypt<string>(a.transportUrl)), "subtitles", w.type, vid, extra), fetchOptions);
            return (Array.isArray(r?.subtitles) ? r.subtitles : [])
              .filter((s): s is Record<string, string> => !!s && typeof s === "object" && typeof (s as Record<string, unknown>).url === "string" && typeof (s as Record<string, unknown>).lang === "string")
              .filter((s) => /^https?:\/\//i.test(s.url))
              .slice(0, 30)
              .map((s, i) => ({ id: `${a.id}:${s.id ?? i}`, lang: s.lang, label: s.label ?? null, url: s.url, addon: m.name }));
          } catch {
            return [];
          }
        }),
      );
      return lists.flat();
    },
  );

  // Fetch a subtitle file for the player, safely, as WebVTT (and same-origin, so <track> works).
  // Signed-in only; no profile needed (a <track> element can't send the profile header).
  app.get<{ Querystring: { url: string } }>(
    "/playback/subtitle.vtt",
    { preHandler: requireUser, schema: { querystring: { type: "object", required: ["url"], properties: { url: { type: "string", minLength: 8, maxLength: 4000 } } } } },
    async (req, reply) => {
      try {
        const text = await safeFetchText(req.query.url, { ...fetchOptions, maxBytes: 2 * 1024 * 1024 });
        return reply.header("content-type", "text/vtt; charset=utf-8").header("cache-control", "private, max-age=3600").send(toVtt(text));
      } catch (e) {
        return reply.code(502).send({ error: e instanceof UnsafeUrl ? e.message : "Couldn't load those subtitles." });
      }
    },
  );
}
