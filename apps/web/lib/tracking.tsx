"use client";
import { useCallback, useEffect, useState } from "react";
import { api, RATING_LABELS, shortDate, type TitleState } from "./api";
import { Button } from "./ui";

export function useTitleState(type: string, id: string) {
  const [state, setState] = useState<TitleState>();
  const reload = useCallback(() => api<TitleState>(`/titles/${type}/${id}/state`).then(setState, () => {}), [type, id]);
  useEffect(() => {
    reload();
  }, [reload]);
  return { state, reload };
}

/** Watchlist, watched and rating for a film or series. Every change is saved here and sent to Trakt if connected. */
export function TitleActions({ type, id, state, reload, play }: { type: "movie" | "series"; id: string; state?: TitleState; reload: () => void; play?: React.ReactNode }) {
  const [busy, setBusy] = useState<string>();
  const [error, setError] = useState<string>();

  if (!state) return <div className="mt-6 h-10" aria-hidden />;

  const run = async (key: string, fn: () => Promise<unknown>) => {
    setBusy(key);
    setError(undefined);
    try {
      await fn();
      await reload();
    } catch (e) {
      setError((e as Error).message);
    }
    setBusy(undefined);
  };
  const base = `/titles/${type}/${id}`;

  return (
    <div className="mt-6 flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        {play}
        <Button
          variant="quiet"
          busy={busy === "watchlist"}
          aria-pressed={state.inWatchlist}
          onClick={() => run("watchlist", () => api(`${base}/watchlist`, { method: state.inWatchlist ? "DELETE" : "PUT" }))}
        >
          {state.inWatchlist ? "✓ On your watchlist" : "Add to watchlist"}
        </Button>

        {type === "movie" &&
          (state.plays > 0 ? (
            <>
              <Button variant="quiet" busy={busy === "watched"} onClick={() => run("watched", () => api(`${base}/watched`, { method: "POST", body: {} }))}>
                Watched again
              </Button>
              <Button variant="quiet" busy={busy === "unwatch"} onClick={() => run("unwatch", () => api(`${base}/watched`, { method: "DELETE" }))}>
                Remove from history
              </Button>
            </>
          ) : (
            <Button variant="quiet" busy={busy === "watched"} onClick={() => run("watched", () => api(`${base}/watched`, { method: "POST", body: {} }))}>
              Mark as watched
            </Button>
          ))}

        <label className="ml-1 flex items-center gap-2 text-sm text-ink-2">
          Your rating
          <select
            value={state.rating ?? ""}
            disabled={busy === "rating"}
            onChange={(e) =>
              run("rating", () =>
                e.target.value ? api(`${base}/rating`, { method: "PUT", body: { rating: Number(e.target.value) } }) : api(`${base}/rating`, { method: "DELETE" }),
              )
            }
            className="h-10 rounded-lg border border-line bg-surface px-2 text-sm text-ink transition-[border-color] duration-150 hover:border-line-strong"
          >
            <option value="">Not rated</option>
            {[10, 9, 8, 7, 6, 5, 4, 3, 2, 1].map((n) => (
              <option key={n} value={n}>
                {n} · {RATING_LABELS[n]}
              </option>
            ))}
          </select>
        </label>
      </div>

      {type === "movie" && state.plays > 0 && state.lastWatchedAt && (
        <p className="text-xs text-ink-3">
          Watched {state.plays === 1 ? "once" : `${state.plays} times`}, last on {shortDate(state.lastWatchedAt)}
        </p>
      )}
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  );
}

/** Tick for one episode. */
export function EpisodeTick({ seriesId, season, episode, watched, onChange }: { seriesId: string; season: number; episode: number; watched: boolean; onChange: () => void }) {
  const [busy, setBusy] = useState(false);
  const label = `${watched ? "Unmark" : "Mark"} season ${season} episode ${episode} as watched`;
  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed={watched}
      title={watched ? "Watched" : "Mark as watched"}
      disabled={busy}
      onClick={async () => {
        setBusy(true);
        const q = `season=${season}&episode=${episode}`;
        await (watched
          ? api(`/titles/series/${seriesId}/watched?${q}`, { method: "DELETE" })
          : api(`/titles/series/${seriesId}/watched`, { method: "POST", body: { season, episode } })
        ).catch(() => {});
        await onChange();
        setBusy(false);
      }}
      className={`flex size-9 shrink-0 items-center justify-center rounded-full border text-sm transition-[background-color,border-color,color] duration-150 disabled:opacity-50 ${
        watched ? "border-accent bg-accent text-accent-ink hover:bg-accent-hover" : "border-line-strong text-ink-3 hover:border-ink-2 hover:text-ink"
      }`}
    >
      ✓
    </button>
  );
}
