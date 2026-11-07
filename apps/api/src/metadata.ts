// Metadata: TMDB is the source for films, series, seasons and episodes; TVDB adds alternate episode orders.
// Titles are stored once as canonical MediaItems and refreshed in the background when stale.
import type { FastifyInstance, preHandlerHookHandler } from "fastify";
import type { MediaItem, MediaType, PrismaClient } from "./generated/prisma/client.js";
import { decrypt, encrypt } from "./secrets.js";
import { pickTrailer, TMDB_BASE, tmdbClient, toCard, yearOf, type Card, type TmdbClient, type TmdbMovie, type TmdbSeries } from "./tmdb.js";
import { TVDB_BASE, TVDB_ORDERS, tvdbClient, type TvdbClient, type TvdbEpisode, type TvdbOrder } from "./tvdb.js";
import { UpstreamError } from "./http.js";
import { TRAKT_BASE, traktClient, type TraktApp } from "./trakt.js";

export const STALE_AFTER_MS = 7 * 86_400_000;
export const STUB = "stub";
const LANGUAGE = { type: "string", pattern: "^[a-z]{2}(-[A-Z]{2})?$" } as const;

export class NotConfigured extends Error {
  code = "metadata_not_configured";
  constructor(service: string) {
    super(`${service} isn't connected yet. An admin can add it in Settings > Integrations.`);
  }
}

type TmdbSecret = { token: string };
type TvdbSecret = { apikey: string; pin?: string };
type TitleType = "movie" | "series";
type TitleData = {
  genres?: string[];
  cast?: { name: string; character: string | null; profilePath: string | null }[];
  directors?: string[];
  creators?: string[];
  trailerKey?: string | null;
  certifications?: Record<string, string>;
  recommendations?: Card[];
  seasons?: { number: number; name: string; episodeCount: number; airDate: string | null; posterPath: string | null }[];
  status?: string;
  tvdbId?: number | null;
  imdbId?: string | null;
};

// Small in-process cache for lists that change slowly (trending, TVDB orders).
// ponytail: per-process memory; move to Redis if zylo-core ever runs as several replicas
const memo = new Map<string, { until: number; value: unknown }>();
async function cached<T>(key: string, ttlMs: number, load: () => Promise<T>): Promise<T> {
  const hit = memo.get(key);
  if (hit && hit.until > Date.now()) return hit.value as T;
  const value = await load();
  memo.set(key, { until: Date.now() + ttlMs, value });
  return value;
}

export function metadataService(db: PrismaClient, bases: { tmdb?: string; tvdb?: string } = {}) {
  const clients = new Map<string, { secret: string; client: TmdbClient | TvdbClient }>();

  async function integration<S>(id: "tmdb" | "tvdb" | "trakt") {
    const row = await db.integration.findUnique({ where: { id } });
    return row ? { row, secret: decrypt<S>(row.secret), settings: row.settings as { language?: string } } : null;
  }

  async function tmdb() {
    const i = await integration<TmdbSecret>("tmdb");
    if (!i) throw new NotConfigured("TMDB");
    // Reuse the client while the stored secret is unchanged.
    let c = clients.get("tmdb");
    if (!c || c.secret !== i.row.secret) clients.set("tmdb", (c = { secret: i.row.secret, client: tmdbClient(i.secret.token, bases.tmdb ?? TMDB_BASE) }));
    return { client: c.client as TmdbClient, language: i.settings.language ?? "en-GB" };
  }

  async function tvdb() {
    const i = await integration<TvdbSecret>("tvdb");
    if (!i) return null;
    let c = clients.get("tvdb");
    if (!c || c.secret !== i.row.secret) clients.set("tvdb", (c = { secret: i.row.secret, client: tvdbClient(i.secret, bases.tvdb ?? TVDB_BASE) }));
    return c.client as TvdbClient;
  }

  /** Create or update a MediaItem identified by its TMDB id. */
  async function upsertByTmdb(
    type: MediaType,
    tmdbId: number,
    fields: Omit<MediaItem, "id" | "type" | "createdAt" | "data" | "refreshedAt" | "parentId"> & { data?: TitleData; parentId?: string | null },
    extraIds: { system: string; value: string }[] = [],
  ) {
    const data = { ...fields, data: fields.data ?? {}, refreshedAt: new Date() };
    const key = { system_type_value: { system: "tmdb", type, value: String(tmdbId) } };
    // Two requests for a title Zylo hasn't seen (the player asks for the title and its sources at once) can both
    // try to create it. The loser's create is rolled back as a whole; it updates the winner's record instead.
    const sameId = (e: unknown) => (e as { code?: string }).code === "P2002";
    const existing = await db.externalId.findUnique({ where: key });
    const item = existing
      ? await db.mediaItem.update({ where: { id: existing.mediaItemId }, data })
      : await db.mediaItem.create({ data: { ...data, type, externalIds: { create: { system: "tmdb", type, value: String(tmdbId) } } } }).catch(async (e) => {
          if (!sameId(e)) throw e;
          return db.mediaItem.update({ where: { id: (await db.externalId.findUniqueOrThrow({ where: key })).mediaItemId }, data });
        });
    for (const x of extraIds) {
      await db.externalId
        .upsert({
          where: { system_type_value: { system: x.system, type, value: x.value } },
          create: { ...x, type, mediaItemId: item.id },
          update: {}, // never steal an ID that another record already owns
        })
        .catch((e) => {
          if (!sameId(e)) throw e; // the other request saved it first: same outcome
        });
    }
    return item;
  }

  async function fetchTitle(type: TitleType, tmdbId: number) {
    const { client, language } = await tmdb();
    if (type === "movie") {
      const m: TmdbMovie = await client.movie(tmdbId, language);
      const certifications: Record<string, string> = {};
      for (const r of m.release_dates?.results ?? []) {
        const c = r.release_dates.find((d) => d.certification)?.certification;
        if (c) certifications[r.iso_3166_1] = c;
      }
      const imdbId = m.external_ids?.imdb_id ?? m.imdb_id ?? null;
      return upsertByTmdb(
        "movie",
        tmdbId,
        {
          title: m.title ?? "Untitled",
          originalTitle: m.original_title ?? null,
          overview: m.overview || null,
          year: yearOf(m.release_date),
          releaseDate: m.release_date || null,
          runtime: m.runtime || null,
          posterPath: m.poster_path,
          backdropPath: m.backdrop_path ?? null,
          rating: m.vote_average ?? null,
          seasonNumber: null,
          episodeNumber: null,
          language,
          data: {
            genres: m.genres.map((g) => g.name),
            cast: castOf(m.credits?.cast),
            directors: (m.credits?.crew ?? []).filter((c) => c.job === "Director").map((c) => c.name),
            trailerKey: pickTrailer(m.videos?.results),
            certifications,
            recommendations: cards(m.recommendations?.results, "movie"),
            imdbId,
          },
        },
        imdbId ? [{ system: "imdb", value: imdbId }] : [],
      );
    }
    const s: TmdbSeries = await client.series(tmdbId, language);
    const tvdbId = s.external_ids?.tvdb_id ?? null;
    const imdbId = s.external_ids?.imdb_id ?? null;
    return upsertByTmdb(
      "series",
      tmdbId,
      {
        title: s.name ?? "Untitled",
        originalTitle: s.original_name ?? null,
        overview: s.overview || null,
        year: yearOf(s.first_air_date),
        releaseDate: s.first_air_date || null,
        runtime: s.episode_run_time?.[0] ?? null,
        posterPath: s.poster_path,
        backdropPath: s.backdrop_path ?? null,
        rating: s.vote_average ?? null,
        seasonNumber: null,
        episodeNumber: null,
        language,
        data: {
          genres: s.genres.map((g) => g.name),
          cast: castOf(s.credits?.cast),
          creators: (s.created_by ?? []).map((c) => c.name),
          trailerKey: pickTrailer(s.videos?.results),
          certifications: Object.fromEntries((s.content_ratings?.results ?? []).filter((r) => r.rating).map((r) => [r.iso_3166_1, r.rating])),
          recommendations: cards(s.recommendations?.results, "tv"),
          seasons: s.seasons.map((x) => ({ number: x.season_number, name: x.name, episodeCount: x.episode_count, airDate: x.air_date, posterPath: x.poster_path })),
          status: s.status,
          tvdbId,
          imdbId,
        },
      },
      [...(tvdbId ? [{ system: "tvdb", value: String(tvdbId) }] : []), ...(imdbId ? [{ system: "imdb", value: imdbId }] : [])],
    );
  }

  async function title(type: TitleType, tmdbId: number) {
    const ext = await db.externalId.findUnique({
      where: { system_type_value: { system: "tmdb", type, value: String(tmdbId) } },
      include: { mediaItem: true },
    });
    // Cached → serve it; the worker refreshes stale records. Missing or only a stub → fetch now.
    return ext && ext.mediaItem.language !== STUB ? ext.mediaItem : fetchTitle(type, tmdbId);
  }

  /**
   * A placeholder for a title imported from elsewhere (e.g. Trakt) that TMDB hasn't filled in yet.
   * The worker fills stubs first (oldest refreshedAt), and viewing one fetches it immediately.
   */
  async function ensureStub(type: TitleType, tmdbId: number, name: string, year: number | null) {
    const ext = await db.externalId.findUnique({ where: { system_type_value: { system: "tmdb", type, value: String(tmdbId) } } });
    if (ext) return ext.mediaItemId;
    try {
      const item = await db.mediaItem.create({
        data: { type, title: name || "Untitled", year, language: STUB, refreshedAt: new Date(0), externalIds: { create: { system: "tmdb", type, value: String(tmdbId) } } },
      });
      return item.id;
    } catch {
      // Created concurrently by another sync: use that one.
      return (await db.externalId.findUniqueOrThrow({ where: { system_type_value: { system: "tmdb", type, value: String(tmdbId) } } })).mediaItemId;
    }
  }

  async function fetchSeason(series: MediaItem, seriesTmdbId: number, n: number) {
    const { client, language } = await tmdb();
    const s = await client.season(seriesTmdbId, n, language);
    const season = await upsertByTmdb("season", s.id, {
      parentId: series.id,
      title: s.name,
      originalTitle: null,
      overview: s.overview || null,
      year: yearOf(s.air_date),
      releaseDate: s.air_date,
      runtime: null,
      posterPath: s.poster_path,
      backdropPath: null,
      rating: null,
      seasonNumber: s.season_number,
      episodeNumber: null,
      language,
    });
    for (const e of s.episodes) {
      await upsertByTmdb("episode", e.id, {
        parentId: season.id,
        title: e.name,
        originalTitle: null,
        overview: e.overview || null,
        year: yearOf(e.air_date),
        releaseDate: e.air_date,
        runtime: e.runtime,
        posterPath: null,
        backdropPath: e.still_path,
        rating: e.vote_average ?? null,
        seasonNumber: e.season_number,
        episodeNumber: e.episode_number,
        language,
      });
    }
    return season;
  }

  async function season(seriesTmdbId: number, n: number) {
    const series = await title("series", seriesTmdbId);
    let s = await db.mediaItem.findFirst({ where: { parentId: series.id, type: "season", seasonNumber: n } });
    if (!s || isStale(s)) {
      try {
        s = await fetchSeason(series, seriesTmdbId, n);
      } catch (e) {
        if (!s) throw e; // nothing cached: surface the error; otherwise serve what we have
      }
    }
    const episodes = await db.mediaItem.findMany({ where: { parentId: s.id }, orderBy: { episodeNumber: "asc" } });
    return { series, season: s, episodes };
  }

  /** Re-fetch stale (or wrong-language) films, series and seasons. Returns how many were refreshed. */
  async function refreshStale(limit = 100) {
    const { language } = await tmdb();
    const stale = await db.mediaItem.findMany({
      where: {
        type: { in: ["movie", "series", "season"] },
        OR: [{ refreshedAt: { lt: new Date(Date.now() - STALE_AFTER_MS) } }, { language: { not: language } }],
      },
      include: { externalIds: { where: { system: "tmdb" } }, parent: { include: { externalIds: { where: { system: "tmdb" } } } } },
      orderBy: { refreshedAt: "asc" },
      take: limit,
    });
    let refreshed = 0;
    for (const item of stale) {
      try {
        if (item.type === "season") {
          const seriesTmdb = item.parent?.externalIds[0]?.value;
          if (seriesTmdb && item.parent && item.seasonNumber !== null) await fetchSeason(item.parent, Number(seriesTmdb), item.seasonNumber);
        } else {
          const tmdbId = item.externalIds[0]?.value;
          if (tmdbId) await fetchTitle(item.type as TitleType, Number(tmdbId));
        }
        refreshed++;
      } catch (e) {
        // A title removed from TMDB shouldn't block the rest; try again next run.
        console.error(`refresh ${item.type} ${item.id} failed:`, (e as Error).message);
      }
    }
    return refreshed;
  }

  return { integration, tmdb, tvdb, title, ensureStub, season, refreshStale };
}

export type MetadataService = ReturnType<typeof metadataService>;

const isStale = (i: MediaItem) => Date.now() - i.refreshedAt.getTime() > STALE_AFTER_MS;

function castOf(cast: { name: string; character?: string; profile_path: string | null; order?: number }[] = []) {
  return [...cast]
    .sort((a, b) => (a.order ?? 0) - (b.order ?? 0))
    .slice(0, 15)
    .map((c) => ({ name: c.name, character: c.character || null, profilePath: c.profile_path }));
}

function cards(list: Parameters<typeof toCard>[0][] = [], type: "movie" | "tv") {
  return list.map((r) => toCard(r, type)).filter((c): c is Card => c !== null).slice(0, 12);
}

/** What the browser gets for a film or series. Certification is picked for the viewer's region. */
function detail(item: MediaItem, region: string) {
  const d = item.data as TitleData;
  return {
    id: item.id,
    type: item.type,
    title: item.title,
    originalTitle: item.originalTitle !== item.title ? item.originalTitle : null,
    overview: item.overview,
    year: item.year,
    releaseDate: item.releaseDate,
    runtime: item.runtime,
    posterPath: item.posterPath,
    backdropPath: item.backdropPath,
    rating: item.rating,
    certification: d.certifications?.[region] ?? d.certifications?.US ?? null,
    genres: d.genres ?? [],
    cast: d.cast ?? [],
    directors: d.directors ?? [],
    creators: d.creators ?? [],
    trailerKey: d.trailerKey ?? null,
    recommendations: d.recommendations ?? [],
    seasons: d.seasons ?? [],
    status: d.status ?? null,
    hasTvdb: Boolean(d.tvdbId),
    refreshedAt: item.refreshedAt,
  };
}

/** Poster card for a stored film or series (tmdb external id included by the caller). */
export function cardOf(item: MediaItem & { externalIds: { system: string; value: string }[] }): Card | null {
  const tmdb = item.externalIds.find((x) => x.system === "tmdb")?.value;
  if (!tmdb || (item.type !== "movie" && item.type !== "series")) return null;
  return { type: item.type, tmdbId: Number(tmdb), title: item.title, year: item.year, posterPath: item.posterPath };
}

const episodeView = (e: MediaItem) => ({
  id: e.id,
  number: e.episodeNumber,
  title: e.title,
  overview: e.overview,
  airDate: e.releaseDate,
  runtime: e.runtime,
  stillPath: e.backdropPath,
  rating: e.rating,
});

// --- routes -------------------------------------------------------------------

export function registerMetadataRoutes(
  app: FastifyInstance,
  db: PrismaClient,
  guards: { requireUser: preHandlerHookHandler; requireAdmin: preHandlerHookHandler },
  bases: { tmdb?: string; tvdb?: string; trakt?: string } = {}, // injectable so tests can use a local mock
) {
  const { requireUser, requireAdmin } = guards;
  const svc = metadataService(db, bases);
  const INTEGRATIONS = ["tmdb", "tvdb", "trakt"] as const;
  const idParam = { type: "object", required: ["id"], properties: { id: { type: "integer", minimum: 1 } } } as const;
  const regionOf = (req: { user: { settings: unknown } }) => ((req.user.settings as { region?: string }).region ?? "GB");

  // Integrations (admin). Secrets go in, never come out.
  app.get("/integrations", { preHandler: requireAdmin }, async () => {
    const rows = await db.integration.findMany();
    return INTEGRATIONS.map((id) => {
      const row = rows.find((r) => r.id === id);
      return {
        id,
        connected: Boolean(row),
        settings: row?.settings ?? {},
        lastCheckedAt: row?.lastCheckedAt ?? null,
        lastError: row?.lastError ?? null,
      };
    });
  });

  app.put<{ Body: { token?: string; language?: string } }>(
    "/integrations/tmdb",
    {
      preHandler: requireAdmin,
      schema: {
        body: { type: "object", additionalProperties: false, minProperties: 1, properties: { token: { type: "string", minLength: 20, maxLength: 1000 }, language: LANGUAGE } },
      },
    },
    async (req, reply) => {
      const current = await svc.integration<TmdbSecret>("tmdb");
      const token = req.body.token?.trim() ?? current?.secret.token;
      if (!token) return reply.code(400).send({ error: "Paste your TMDB API Read Access Token." });
      if (req.body.token) {
        try {
          await tmdbClient(token, bases.tmdb ?? TMDB_BASE).check();
        } catch (e) {
          return reply.code(400).send({ error: e instanceof UpstreamError && e.status === 401 ? "TMDB rejected that token. Check you copied the whole API Read Access Token." : (e as Error).message });
        }
      }
      const settings = { ...(current?.settings ?? {}), ...(req.body.language && { language: req.body.language }) };
      await db.integration.upsert({
        where: { id: "tmdb" },
        create: { id: "tmdb", secret: encrypt({ token }), settings, lastCheckedAt: new Date() },
        update: { secret: encrypt({ token }), settings, ...(req.body.token && { lastCheckedAt: new Date(), lastError: null }) },
      });
      return reply.code(204).send();
    },
  );

  app.put<{ Body: { apikey: string; pin?: string } }>(
    "/integrations/tvdb",
    {
      preHandler: requireAdmin,
      schema: {
        body: {
          type: "object",
          required: ["apikey"],
          additionalProperties: false,
          properties: { apikey: { type: "string", minLength: 8, maxLength: 200 }, pin: { type: "string", maxLength: 100 } },
        },
      },
    },
    async (req, reply) => {
      const creds = { apikey: req.body.apikey.trim(), ...(req.body.pin?.trim() && { pin: req.body.pin.trim() }) };
      try {
        await tvdbClient(creds, bases.tvdb ?? TVDB_BASE).check();
      } catch (e) {
        return reply.code(400).send({
          error: e instanceof UpstreamError && e.status === 401 ? "TVDB rejected that key. If your key is user-supported, add your subscriber PIN." : (e as Error).message,
        });
      }
      await db.integration.upsert({
        where: { id: "tvdb" },
        create: { id: "tvdb", secret: encrypt(creds), lastCheckedAt: new Date() },
        update: { secret: encrypt(creds), lastCheckedAt: new Date(), lastError: null },
      });
      return reply.code(204).send();
    },
  );

  app.post<{ Params: { id: string } }>(
    "/integrations/:id/test",
    { preHandler: requireAdmin, schema: { params: { type: "object", properties: { id: { enum: INTEGRATIONS } } } } },
    async (req, reply) => {
      const id = req.params.id as (typeof INTEGRATIONS)[number];
      if (!(await db.integration.findUnique({ where: { id } }))) return reply.code(404).send({ error: "Not connected." });
      let lastError: string | null = null;
      try {
        if (id === "tmdb") await (await svc.tmdb()).client.check();
        else if (id === "tvdb") await (await svc.tvdb())!.check();
        else {
          const app = (await svc.integration<TraktApp>("trakt"))!.secret;
          await traktClient(app, bases.trakt ?? TRAKT_BASE).deviceCode();
        }
      } catch (e) {
        lastError = (e as Error).message;
      }
      await db.integration.update({ where: { id }, data: { lastCheckedAt: new Date(), lastError } });
      return { ok: !lastError, error: lastError };
    },
  );

  app.delete<{ Params: { id: string } }>(
    "/integrations/:id",
    { preHandler: requireAdmin, schema: { params: { type: "object", properties: { id: { enum: INTEGRATIONS } } } } },
    async (req, reply) => {
      await db.integration.deleteMany({ where: { id: req.params.id } });
      return reply.code(204).send();
    },
  );

  // What's connected, for every signed-in user (no settings or secrets).
  app.get("/metadata/status", { preHandler: requireUser }, async () => {
    const rows = await db.integration.findMany({ select: { id: true } });
    return { tmdb: rows.some((r) => r.id === "tmdb"), tvdb: rows.some((r) => r.id === "tvdb") };
  });

  // Browsing (any signed-in user).
  app.get<{ Querystring: { q: string; page?: number } }>(
    "/search",
    {
      preHandler: requireUser,
      schema: { querystring: { type: "object", required: ["q"], properties: { q: { type: "string", minLength: 1, maxLength: 200 }, page: { type: "integer", minimum: 1, maximum: 50 } } } },
    },
    async (req) => {
      const { client, language } = await svc.tmdb();
      const res = await client.search(req.query.q, language, req.query.page ?? 1);
      return {
        results: res.results.map((r) => toCard(r)).filter(Boolean),
        page: res.page,
        totalPages: Math.min(res.total_pages, 50),
      };
    },
  );

  app.get("/discover/trending", { preHandler: requireUser }, async () => {
    const { client, language } = await svc.tmdb();
    return cached(`trending:${language}`, 3_600_000, async () => ({
      results: (await client.trending(language)).results.map((r) => toCard(r)).filter(Boolean),
    }));
  });

  app.get<{ Params: { id: number } }>("/titles/movie/:id", { preHandler: requireUser, schema: { params: idParam } }, async (req) =>
    detail(await svc.title("movie", req.params.id), regionOf(req)),
  );

  app.get<{ Params: { id: number } }>("/titles/series/:id", { preHandler: requireUser, schema: { params: idParam } }, async (req) =>
    detail(await svc.title("series", req.params.id), regionOf(req)),
  );

  app.get<{ Params: { id: number; n: number } }>(
    "/titles/series/:id/seasons/:n",
    {
      preHandler: requireUser,
      schema: { params: { type: "object", required: ["id", "n"], properties: { id: { type: "integer", minimum: 1 }, n: { type: "integer", minimum: 0, maximum: 500 } } } },
    },
    async (req) => {
      const { season, episodes } = await svc.season(req.params.id, req.params.n);
      return { number: season.seasonNumber, title: season.title, overview: season.overview, airDate: season.releaseDate, episodes: episodes.map(episodeView) };
    },
  );

  // Alternate episode orders straight from TVDB, grouped by season.
  // ponytail: not linked to canonical episodes yet; episode mapping arrives with anime support (Phase 7)
  app.get<{ Params: { id: number }; Querystring: { order: TvdbOrder } }>(
    "/titles/series/:id/tvdb-episodes",
    {
      preHandler: requireUser,
      schema: { params: idParam, querystring: { type: "object", required: ["order"], properties: { order: { enum: TVDB_ORDERS } } } },
    },
    async (req, reply) => {
      const client = await svc.tvdb();
      if (!client) throw new NotConfigured("TVDB");
      const tvdbId = ((await svc.title("series", req.params.id)).data as TitleData).tvdbId;
      if (!tvdbId) return reply.code(404).send({ error: "TVDB doesn't list this series." });
      const episodes = await cached(`tvdb:${tvdbId}:${req.query.order}`, 3_600_000, () => client.episodes(tvdbId, req.query.order));
      const seasons = new Map<number, TvdbEpisode[]>();
      for (const e of episodes) seasons.set(e.seasonNumber, [...(seasons.get(e.seasonNumber) ?? []), e]);
      return {
        order: req.query.order,
        seasons: [...seasons.entries()]
          .sort(([a], [b]) => a - b)
          .map(([number, eps]) => ({
            number,
            episodes: eps
              .sort((a, b) => a.number - b.number)
              .map((e) => ({ id: e.id, number: e.number, absoluteNumber: e.absoluteNumber ?? null, title: e.name, overview: e.overview ?? null, airDate: e.aired ?? null, runtime: e.runtime ?? null })),
          })),
      };
    },
  );

  return svc;
}
