"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { api, shortDate, titleHref, TRACKER_NAMES, type AnimeListEntry, type Library, type Tracker } from "@/lib/api";
import { Poster, PosterGrid, TmdbAttribution } from "@/lib/media";
import { useUser } from "@/lib/session";
import { TopBar } from "@/lib/topbar";
import { Loading } from "@/lib/ui";

export default function LibraryPage() {
  const { user } = useUser();
  const [lib, setLib] = useState<Library>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (user) api<Library>("/library").then(setLib, (e) => setError(e.message));
  }, [user]);

  if (!user) return <Loading />;

  return (
    <>
      <TopBar />
      <main className="mx-auto max-w-6xl px-6 py-12">
        <h1 className="text-2xl font-semibold leading-tight tracking-tight">Library</h1>
        {error ? (
          <p role="alert" className="mt-6 text-sm text-danger">
            {error}
          </p>
        ) : !lib ? (
          <p className="mt-6 text-sm text-ink-3">Loading…</p>
        ) : (
          <>
            <dl className="mt-8 grid max-w-xl grid-cols-3 gap-px overflow-hidden rounded-xl border border-line bg-line">
              {[
                ["Films watched", lib.stats.filmsWatched],
                ["Episodes watched", lib.stats.episodesWatched],
                ["Ratings", lib.stats.ratings],
              ].map(([label, n]) => (
                <div key={label} className="bg-surface px-4 py-3">
                  <dt className="text-xs text-ink-3">{label}</dt>
                  <dd className="mt-1 text-2xl font-semibold tabular-nums">{Number(n).toLocaleString("en-GB")}</dd>
                </div>
              ))}
            </dl>

            <section className="mt-14" aria-labelledby="watchlist">
              <h2 id="watchlist" className="text-lg font-semibold">
                Watchlist
              </h2>
              <div className="mt-5">
                {lib.watchlist.length ? (
                  <PosterGrid cards={lib.watchlist} />
                ) : (
                  <p className="text-sm text-ink-2">Nothing here yet. Use “Add to watchlist” on any film or series.</p>
                )}
              </div>
            </section>

            {lib.lists.length > 0 && (
              <section className="mt-14" aria-labelledby="lists">
                <h2 id="lists" className="text-lg font-semibold">
                  Your lists
                </h2>
                <ul className="mt-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {lib.lists.map((l) => (
                    <li key={l.id}>
                      <Link
                        href={`/library/lists/${l.id}`}
                        className="flex h-full flex-col rounded-xl border border-line bg-surface p-4 transition-[border-color] duration-150 hover:border-line-strong"
                      >
                        <span className="font-medium">{l.name}</span>
                        {l.description && <span className="mt-1 line-clamp-2 text-sm text-ink-2">{l.description}</span>}
                        <span className="mt-3 text-xs text-ink-3 tabular-nums">
                          {l.count} {l.count === 1 ? "title" : "titles"}
                        </span>
                      </Link>
                    </li>
                  ))}
                </ul>
              </section>
            )}

            <AnimeShelf />

            <section className="mt-14" aria-labelledby="history">
              <h2 id="history" className="text-lg font-semibold">
                Recently watched
              </h2>
              {lib.history.length ? (
                <ol className="mt-5 divide-y divide-line rounded-xl border border-line bg-surface">
                  {lib.history.map((h) => (
                    <li key={`${h.card.type}-${h.card.tmdbId}-${h.season}-${h.episode}-${h.watchedAt}`}>
                      <Link href={titleHref(h.card)} className="flex items-center gap-4 p-3 transition-[background-color] duration-150 hover:bg-sunken">
                        <Poster path={h.card.posterPath} title={h.card.title} size="w92" className="w-10 shrink-0" />
                        <span className="min-w-0 flex-1">
                          <span className="block truncate font-medium">{h.card.title}</span>
                          <span className="block text-xs text-ink-3 tabular-nums">
                            {h.season !== null ? `Season ${h.season}, episode ${h.episode}` : h.card.year ?? "Film"}
                          </span>
                        </span>
                        <time dateTime={h.watchedAt} className="shrink-0 text-xs text-ink-3 tabular-nums">
                          {shortDate(h.watchedAt)}
                        </time>
                      </Link>
                    </li>
                  ))}
                </ol>
              ) : (
                <p className="mt-5 text-sm text-ink-2">No watch history yet. Mark something as watched, or connect Trakt in Settings to bring yours in.</p>
              )}
            </section>
          </>
        )}
      </main>
      <TmdbAttribution />
    </>
  );
}

const STATUS_TABS = [
  ["watching", "Watching"],
  ["planning", "Planning"],
  ["completed", "Completed"],
  ["paused", "Paused"],
  ["dropped", "Dropped"],
] as const;

/** The profile's AniList or MyAnimeList list (whichever it updates from Zylo), by status. */
function AnimeShelf() {
  const [data, setData] = useState<{ tracker: Tracker | null; entries: AnimeListEntry[] }>();
  const [tab, setTab] = useState<AnimeListEntry["status"]>("watching");
  useEffect(() => {
    api<{ tracker: Tracker | null; entries: AnimeListEntry[] }>("/anime/list").then(setData, () => {});
  }, []);
  if (!data?.tracker) return null;
  const name = TRACKER_NAMES[data.tracker];
  const shown = data.entries.filter((e) => e.status === tab);
  return (
    <section className="mt-14" aria-labelledby="anime">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="anime" className="text-lg font-semibold">
          Anime
        </h2>
        <span className="text-xs text-ink-3">From {name}</span>
      </div>
      <div role="tablist" aria-label="Anime by status" className="mt-4 flex flex-wrap gap-1">
        {STATUS_TABS.map(([key, label]) => {
          const n = data.entries.filter((e) => e.status === key).length;
          return (
            <button
              key={key}
              role="tab"
              aria-selected={tab === key}
              onClick={() => setTab(key)}
              className="h-8 rounded-lg px-3 text-sm text-ink-2 transition-[background-color,color] duration-150 hover:text-ink aria-selected:bg-sunken aria-selected:font-medium aria-selected:text-ink"
            >
              {label} <span className="tabular-nums text-ink-3">{n}</span>
            </button>
          );
        })}
      </div>
      {shown.length ? (
        <ul className="mt-4 divide-y divide-line rounded-xl border border-line bg-surface">
          {shown.map((e) => {
            const body = (
              <>
                <Poster path={e.card?.posterPath ?? null} title={e.title} size="w92" className="w-10 shrink-0" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{e.title}</span>
                  <span className="mt-1 flex items-center gap-2 text-xs text-ink-3 tabular-nums">
                    {e.episodes ? (
                      <span className="h-1 w-24 overflow-hidden rounded-full bg-sunken" aria-hidden>
                        <span className="block h-full bg-accent" style={{ width: `${Math.min(100, (e.progress / e.episodes) * 100)}%` }} />
                      </span>
                    ) : null}
                    {e.progress}
                    {e.episodes ? ` of ${e.episodes}` : ""} {e.episodes === 1 ? "episode" : "episodes"}
                    {e.score ? ` · scored ${e.score}` : ""}
                    {!e.card && " · not matched to a Zylo title"}
                  </span>
                </span>
              </>
            );
            return (
              <li key={e.id}>
                {e.card ? (
                  <Link href={titleHref(e.card)} className="flex items-center gap-4 p-3 transition-[background-color] duration-150 hover:bg-sunken">
                    {body}
                  </Link>
                ) : (
                  <a href={e.url} target="_blank" rel="noreferrer" className="flex items-center gap-4 p-3 transition-[background-color] duration-150 hover:bg-sunken">
                    {body}
                    <span className="shrink-0 text-xs text-ink-3">{name} ↗</span>
                  </a>
                )}
              </li>
            );
          })}
        </ul>
      ) : (
        <p className="mt-4 text-sm text-ink-2">Nothing here on your {name} list.</p>
      )}
    </section>
  );
}
