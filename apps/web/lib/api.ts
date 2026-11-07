export type Settings = { region: string; theme: "system" | "light" | "dark" };
export type User = { id: string; email: string; isAdmin: boolean; settings: Settings };
export type Profile = { id: string; name: string; color: ProfileColor };
export const PROFILE_COLORS = ["teal", "amber", "rose", "violet", "sky", "lime", "orange", "slate"] as const;
export type ProfileColor = (typeof PROFILE_COLORS)[number];

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function api<T = unknown>(path: string, init?: { method?: string; body?: unknown }): Promise<T> {
  const profile = activeProfileId();
  const res = await fetch(`/api${path}`, {
    method: init?.method ?? "GET",
    // The active profile scopes history, ratings, lists and Trakt.
    headers: { ...(init?.body ? { "content-type": "application/json" } : {}), ...(profile && { "x-zylo-profile": profile }) },
    body: init?.body ? JSON.stringify(init.body) : undefined,
  });
  if (res.status === 204) return undefined as T;
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, friendly(res.status, data));
  return data as T;
}

// Zylo's own errors are { error } in plain English; framework errors also carry `message`.
function friendly(status: number, data: { error?: string; message?: string }) {
  if (data.error && !data.message) return data.error;
  if (status === 429) return "Too many attempts. Wait a minute and try again.";
  if (status === 400) return "Some of those details aren't valid. Check them and try again.";
  // A JSON 5xx came from zylo-core itself: show its reason. No JSON means the web app couldn't reach it at all.
  if (status >= 500 && data.message) return `Zylo's server hit a problem: ${data.message}`;
  if (status >= 500) return "Zylo couldn't reach its server. Run: docker compose logs zylo-core";
  return "Something went wrong.";
}

export function applyTheme(theme: Settings["theme"]) {
  if (theme === "system") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem("zylo-theme", theme);
  } catch {}
}

export function activeProfileId() {
  try {
    return localStorage.getItem("zylo-profile");
  } catch {
    return null;
  }
}

export function setActiveProfileId(id: string | null) {
  try {
    if (id) localStorage.setItem("zylo-profile", id);
    else localStorage.removeItem("zylo-profile");
  } catch {}
}

// --- metadata ---

export type Card = { type: "movie" | "series"; tmdbId: number; title: string; year: number | null; posterPath: string | null };
export type CastMember = { name: string; character: string | null; profilePath: string | null };
export type Title = {
  id: string;
  type: "movie" | "series";
  title: string;
  originalTitle: string | null;
  overview: string | null;
  year: number | null;
  releaseDate: string | null;
  runtime: number | null;
  posterPath: string | null;
  backdropPath: string | null;
  rating: number | null;
  certification: string | null;
  genres: string[];
  cast: CastMember[];
  directors: string[];
  creators: string[];
  trailerKey: string | null;
  recommendations: Card[];
  seasons: { number: number; name: string; episodeCount: number; airDate: string | null; posterPath: string | null }[];
  status: string | null;
  hasTvdb: boolean;
};
export type Episode = {
  id: string | number;
  number: number | null;
  absoluteNumber?: number | null;
  title: string | null;
  overview: string | null;
  airDate: string | null;
  runtime: number | null;
  stillPath?: string | null;
};
export type Integration = { id: "tmdb" | "tvdb" | "trakt"; connected: boolean; settings: { language?: string }; lastCheckedAt: string | null; lastError: string | null };

/** TMDB image URL. Sizes: w92 w154 w185 w342 w500 w780 original (posters); w300 w780 w1280 (backdrops). */
export const img = (path: string | null | undefined, size: string) => (path ? `https://image.tmdb.org/t/p/${size}${path}` : null);

export const titleHref = (c: { type: "movie" | "series"; tmdbId: number }) => `/title/${c.type}/${c.tmdbId}`;

// --- tracking ---

export type TitleState = {
  plays: number;
  lastWatchedAt: string | null;
  rating: number | null;
  inWatchlist: boolean;
  watchedEpisodes: string[]; // "season:episode"
  episodeRatings: Record<string, number>;
};
export type TraktStatus = {
  available: boolean;
  connected: boolean;
  username: string | null;
  syncEnabled: boolean;
  syncState: "idle" | "queued" | "running";
  lastSyncAt: string | null;
  lastError: string | null;
  pendingChanges: number;
};
export type Library = {
  watchlist: Card[];
  lists: { id: string; name: string; description: string | null; count: number }[];
  history: { card: Card; season: number | null; episode: number | null; watchedAt: string }[];
  stats: { filmsWatched: number; episodesWatched: number; ratings: number };
};

export const RATING_LABELS: Record<number, string> = {
  10: "Masterpiece", 9: "Superb", 8: "Great", 7: "Good", 6: "Fair", 5: "Meh", 4: "Poor", 3: "Bad", 2: "Terrible", 1: "Awful",
};

export const shortDate = (iso: string) => new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
