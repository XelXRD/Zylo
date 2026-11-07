// TMDB v3 adapter. Docs: https://developer.themoviedb.org/reference
// Accepts either a v4 "API Read Access Token" (sent as Bearer) or a 32-char v3 API key (sent as ?api_key=).
import { fetchJson } from "./http.js";

export const TMDB_BASE = "https://api.themoviedb.org/3";

type Named = { id: number; name: string };
type Video = { key: string; site: string; type: string; official?: boolean; name?: string };
type CastMember = { id: number; name: string; character?: string; profile_path: string | null; order?: number };
type CrewMember = { id: number; name: string; job: string };
type ListItem = {
  id: number;
  media_type?: "movie" | "tv" | "person";
  title?: string;
  name?: string;
  release_date?: string;
  first_air_date?: string;
  poster_path: string | null;
  backdrop_path?: string | null;
  overview?: string;
};

export type TmdbMovie = ListItem & {
  original_title?: string;
  runtime: number | null;
  genres: Named[];
  vote_average?: number;
  imdb_id?: string | null;
  credits?: { cast: CastMember[]; crew: CrewMember[] };
  videos?: { results: Video[] };
  external_ids?: { imdb_id?: string | null };
  recommendations?: { results: ListItem[] };
  release_dates?: { results: { iso_3166_1: string; release_dates: { certification: string; type?: number }[] }[] };
};

export type TmdbSeries = ListItem & {
  original_name?: string;
  episode_run_time?: number[];
  genres: Named[];
  vote_average?: number;
  number_of_seasons?: number;
  status?: string;
  created_by?: Named[];
  seasons: { season_number: number; name: string; episode_count: number; air_date: string | null; poster_path: string | null; overview?: string }[];
  credits?: { cast: CastMember[]; crew: CrewMember[] };
  videos?: { results: Video[] };
  external_ids?: { imdb_id?: string | null; tvdb_id?: number | null };
  recommendations?: { results: ListItem[] };
  content_ratings?: { results: { iso_3166_1: string; rating: string }[] };
};

export type TmdbSeason = {
  id: number;
  name: string;
  season_number: number;
  overview?: string;
  air_date: string | null;
  poster_path: string | null;
  episodes: {
    id: number;
    name: string;
    overview?: string;
    episode_number: number;
    season_number: number;
    air_date: string | null;
    runtime: number | null;
    still_path: string | null;
    vote_average?: number;
  }[];
};

export type Card = { type: "movie" | "series"; tmdbId: number; title: string; year: number | null; posterPath: string | null };

export const yearOf = (date?: string | null) => (date && /^\d{4}/.test(date) ? Number(date.slice(0, 4)) : null);

export function toCard(r: ListItem, fallbackType?: "movie" | "tv"): Card | null {
  const t = r.media_type ?? fallbackType;
  if (t !== "movie" && t !== "tv") return null;
  return {
    type: t === "movie" ? "movie" : "series",
    tmdbId: r.id,
    title: (t === "movie" ? r.title : r.name) ?? "Untitled",
    year: yearOf(t === "movie" ? r.release_date : r.first_air_date),
    posterPath: r.poster_path ?? null,
  };
}

/** Pick the trailer people expect: official YouTube trailer first, then any YouTube trailer, then a teaser. */
export function pickTrailer(videos: Video[] = []) {
  const yt = videos.filter((v) => v.site === "YouTube");
  return (
    yt.find((v) => v.type === "Trailer" && v.official) ?? yt.find((v) => v.type === "Trailer") ?? yt.find((v) => v.type === "Teaser")
  )?.key ?? null;
}

export function tmdbClient(credential: string, base = TMDB_BASE) {
  const isV3Key = /^[a-f0-9]{32}$/i.test(credential);
  const get = <T>(path: string, params: Record<string, string | number | undefined> = {}) => {
    const url = new URL(base + path);
    for (const [k, v] of Object.entries(params)) if (v !== undefined) url.searchParams.set(k, String(v));
    if (isV3Key) url.searchParams.set("api_key", credential);
    return fetchJson<T>("TMDB", url.toString(), isV3Key ? {} : { headers: { authorization: `Bearer ${credential}` } });
  };

  return {
    /** Cheap authenticated call used by "Test connection". */
    check: () => get<{ images: unknown }>("/configuration"),
    movie: (id: number, language: string) =>
      get<TmdbMovie>(`/movie/${id}`, { language, append_to_response: "credits,videos,external_ids,recommendations,release_dates" }),
    series: (id: number, language: string) =>
      get<TmdbSeries>(`/tv/${id}`, { language, append_to_response: "credits,videos,external_ids,recommendations,content_ratings" }),
    season: (id: number, n: number, language: string) => get<TmdbSeason>(`/tv/${id}/season/${n}`, { language }),
    search: (query: string, language: string, page = 1) =>
      get<{ results: ListItem[]; total_pages: number; page: number }>("/search/multi", { query, language, page, include_adult: "false" }),
    trending: (language: string) => get<{ results: ListItem[] }>("/trending/all/week", { language }),
    /** TMDB ids for an IMDb id (used for addon catalogue items, which are keyed by IMDb). */
    findByImdb: (imdbId: string) => get<{ movie_results: { id: number }[]; tv_results: { id: number }[] }>(`/find/${imdbId}`, { external_source: "imdb_id" }),
  };
}

export type TmdbClient = ReturnType<typeof tmdbClient>;
