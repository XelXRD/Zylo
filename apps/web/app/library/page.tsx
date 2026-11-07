"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { api, shortDate, titleHref, type Library } from "@/lib/api";
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
