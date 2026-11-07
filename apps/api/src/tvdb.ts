// TheTVDB v4 adapter. Spec: https://github.com/thetvdb/v4-api (docs/swagger.yml)
// Keys are per project; user-supported keys also need the subscriber's PIN.
import { fetchJson } from "./http.js";

export const TVDB_BASE = "https://api4.thetvdb.com/v4";
export const TVDB_ORDERS = ["default", "official", "dvd", "absolute", "alternate", "regional"] as const;
export type TvdbOrder = (typeof TVDB_ORDERS)[number];

export type TvdbEpisode = {
  id: number;
  name: string | null;
  overview?: string | null;
  seasonNumber: number;
  number: number;
  absoluteNumber?: number | null;
  aired?: string | null;
  runtime?: number | null;
  image?: string | null;
};

const TOKEN_TTL = 25 * 86_400_000; // TVDB tokens last a month; renew early.

export function tvdbClient(creds: { apikey: string; pin?: string }, base = TVDB_BASE) {
  let token: { value: string; until: number } | null = null;

  const login = async () => {
    const body = creds.pin ? { apikey: creds.apikey, pin: creds.pin } : { apikey: creds.apikey };
    const res = await fetchJson<{ data: { token: string } }>("TVDB", `${base}/login`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body),
    });
    token = { value: res.data.token, until: Date.now() + TOKEN_TTL };
    return token.value;
  };

  const get = async <T>(path: string) => {
    const t = token && token.until > Date.now() ? token.value : await login();
    return fetchJson<T>("TVDB", base + path, { headers: { authorization: `Bearer ${t}` } });
  };

  return {
    check: login,
    /** Every episode of a series in one of TVDB's orderings. */
    async episodes(seriesId: number, order: TvdbOrder) {
      const all: TvdbEpisode[] = [];
      // ponytail: sequential pages, capped at 20 (TVDB pages hold 500 episodes, so 10,000 max)
      for (let page = 0; page < 20; page++) {
        const res = await get<{ data: { episodes: TvdbEpisode[] | null }; links?: { next?: string | null } }>(
          `/series/${seriesId}/episodes/${order}?page=${page}`,
        );
        const batch = res.data.episodes ?? [];
        all.push(...batch);
        if (!batch.length || !res.links?.next) break;
      }
      return all;
    },
  };
}

export type TvdbClient = ReturnType<typeof tvdbClient>;
