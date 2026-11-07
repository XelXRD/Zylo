"use client";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api, img, type Episode, type Title } from "@/lib/api";
import { Poster, PosterGrid, TmdbAttribution } from "@/lib/media";
import { useUser } from "@/lib/session";
import { TopBar } from "@/lib/topbar";
import { TitleActions, EpisodeTick, useTitleState } from "@/lib/tracking";
import { Loading, Select } from "@/lib/ui";

const hm = (mins: number) => (mins >= 60 ? `${Math.floor(mins / 60)}h ${mins % 60 ? `${mins % 60}m` : ""}`.trim() : `${mins}m`);
const longDate = (iso: string | null) =>
  iso ? new Date(`${iso}T12:00:00Z`).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" }) : null;

export default function TitlePage() {
  const { type, id } = useParams<{ type: string; id: string }>();
  const { user } = useUser();
  const [title, setTitle] = useState<Title>();
  const [error, setError] = useState<string>();
  const [trailer, setTrailer] = useState(false);
  const { state, reload } = useTitleState(type, id);

  useEffect(() => {
    if (!user) return;
    if (type !== "movie" && type !== "series") return setError("We couldn't find that title.");
    setTitle(undefined);
    setTrailer(false);
    api<Title>(`/titles/${type}/${id}`).then(setTitle, (e) => setError(e.message));
  }, [user, type, id]);

  if (!user) return <Loading />;

  return (
    <>
      <TopBar />
      {error ? (
        <main className="mx-auto max-w-6xl px-6 py-16">
          <h1 className="text-2xl font-semibold">Can't show this title</h1>
          <p className="mt-2 text-sm text-ink-2">{error}</p>
        </main>
      ) : !title ? (
        <Loading />
      ) : (
        <>
          <div className="h-48 w-full overflow-hidden bg-sunken sm:h-72">
            {title.backdropPath && <img src={img(title.backdropPath, "w1280")!} alt="" className="size-full object-cover" />}
          </div>

          <main className="mx-auto max-w-6xl px-6">
            <div className="flex flex-col gap-8 sm:flex-row">
              <Poster path={title.posterPath} title={title.title} size="w500" className="-mt-24 w-36 shrink-0 self-start shadow-[0_1px_2px_rgb(0_0_0/0.2),0_8px_24px_-8px_rgb(0_0_0/0.4)] sm:-mt-40 sm:w-56" />

              <div className="min-w-0 pt-2 sm:pt-6">
                <h1 className="text-3xl font-semibold leading-tight tracking-tight sm:text-4xl">{title.title}</h1>
                {title.originalTitle && <p className="mt-1 text-sm text-ink-3">{title.originalTitle}</p>}

                <p className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-ink-2 tabular-nums">
                  {title.year && <span>{title.year}</span>}
                  {title.certification && (
                    <span className="rounded border border-line-strong px-1.5 py-px text-xs font-medium text-ink" title="Age rating for your region">
                      {title.certification}
                    </span>
                  )}
                  {title.runtime && <span>{title.type === "series" ? `${hm(title.runtime)} per episode` : hm(title.runtime)}</span>}
                  {title.type === "series" && title.seasons.length > 0 && (
                    <span>
                      {title.seasons.filter((s) => s.number > 0).length} season{title.seasons.filter((s) => s.number > 0).length === 1 ? "" : "s"}
                    </span>
                  )}
                  {title.status && title.type === "series" && <span>{title.status}</span>}
                  {title.rating ? <span>★ {title.rating.toFixed(1)}</span> : null}
                </p>
                {title.genres.length > 0 && <p className="mt-2 text-sm text-ink-3">{title.genres.join(", ")}</p>}

                {title.overview && <p className="mt-6 max-w-[65ch] leading-relaxed text-ink">{title.overview}</p>}

                {(title.directors.length > 0 || title.creators.length > 0) && (
                  <p className="mt-4 text-sm text-ink-2">
                    {title.directors.length > 0 ? "Directed by " : "Created by "}
                    <span className="text-ink">{(title.directors.length ? title.directors : title.creators).join(", ")}</span>
                  </p>
                )}

                <TitleActions type={title.type} id={id} state={state} reload={reload} />
                {title.trailerKey && (
                  <button
                    type="button"
                    onClick={() => setTrailer((t) => !t)}
                    aria-expanded={trailer}
                    className="mt-4 rounded text-sm font-medium text-accent transition-colors duration-150 hover:text-accent-hover"
                  >
                    {trailer ? "Hide trailer" : "▶ Watch trailer"}
                  </button>
                )}
              </div>
            </div>

            {trailer && title.trailerKey && (
              <div className="mt-8 aspect-video w-full max-w-4xl overflow-hidden rounded-xl border border-line bg-sunken">
                <iframe
                  src={`https://www.youtube-nocookie.com/embed/${title.trailerKey}?autoplay=1&rel=0`}
                  title={`${title.title} trailer`}
                  allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
                  allowFullScreen
                  className="size-full"
                />
              </div>
            )}

            {title.cast.length > 0 && (
              <section className="mt-16" aria-labelledby="cast">
                <h2 id="cast" className="text-lg font-semibold">
                  Cast
                </h2>
                <ul className="mt-5 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
                  {title.cast.slice(0, 10).map((c) => (
                    <li key={c.name} className="flex items-center gap-3">
                      <span className="size-12 shrink-0 overflow-hidden rounded-full border border-line bg-sunken">
                        {c.profilePath && <img src={img(c.profilePath, "w185")!} alt="" loading="lazy" className="size-full object-cover" />}
                      </span>
                      <span className="min-w-0">
                        <span className="block truncate text-sm font-medium">{c.name}</span>
                        {c.character && <span className="block truncate text-xs text-ink-3">{c.character}</span>}
                      </span>
                    </li>
                  ))}
                </ul>
              </section>
            )}

            {title.type === "series" && title.seasons.length > 0 && <Episodes seriesId={id} title={title} watched={new Set(state?.watchedEpisodes)} onWatchedChange={reload} />}

            {title.recommendations.length > 0 && (
              <section className="mt-16" aria-labelledby="more">
                <h2 id="more" className="text-lg font-semibold">
                  More like this
                </h2>
                <div className="mt-5">
                  <PosterGrid cards={title.recommendations} />
                </div>
              </section>
            )}
          </main>
          <TmdbAttribution />
        </>
      )}
    </>
  );
}

const ORDERS = [
  { value: "tmdb", label: "TMDB (as aired)" },
  { value: "official", label: "TVDB: aired order" },
  { value: "dvd", label: "TVDB: DVD order" },
  { value: "absolute", label: "TVDB: absolute order" },
  { value: "alternate", label: "TVDB: alternate order" },
  { value: "regional", label: "TVDB: regional order" },
];

type TvdbSeasons = { seasons: { number: number; episodes: Episode[] }[] };

function Episodes({ seriesId, title, watched, onWatchedChange }: { seriesId: string; title: Title; watched: Set<string>; onWatchedChange: () => void }) {
  const regular = title.seasons.filter((s) => s.number > 0);
  const [order, setOrder] = useState("tmdb");
  const [tvdbReady, setTvdbReady] = useState(false);
  const [tvdb, setTvdb] = useState<TvdbSeasons>();
  const [seasonNo, setSeasonNo] = useState((regular[0] ?? title.seasons[0]).number);
  const [episodes, setEpisodes] = useState<Episode[]>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (title.hasTvdb) api<{ tvdb: boolean }>("/metadata/status").then((s) => setTvdbReady(s.tvdb), () => {});
  }, [title.hasTvdb]);

  // TMDB order: one season at a time.
  useEffect(() => {
    if (order !== "tmdb") return;
    setEpisodes(undefined);
    setError(undefined);
    api<{ episodes: Episode[] }>(`/titles/series/${seriesId}/seasons/${seasonNo}`).then((s) => setEpisodes(s.episodes), (e) => setError(e.message));
  }, [order, seriesId, seasonNo]);

  // TVDB orders: the whole series in one go, then pick a season locally.
  useEffect(() => {
    if (order === "tmdb") return setTvdb(undefined);
    setTvdb(undefined);
    setError(undefined);
    api<TvdbSeasons>(`/titles/series/${seriesId}/tvdb-episodes?order=${order}`).then(
      (r) => {
        setTvdb(r);
        if (r.seasons.length && !r.seasons.some((s) => s.number === seasonNo)) setSeasonNo((r.seasons.find((s) => s.number > 0) ?? r.seasons[0]).number);
      },
      (e) => setError(e.message),
    );
  }, [order, seriesId]); // eslint-disable-line react-hooks/exhaustive-deps

  const seasonOptions =
    order === "tmdb"
      ? title.seasons.map((s) => ({ number: s.number, label: `${s.name} · ${s.episodeCount} episodes` }))
      : (tvdb?.seasons ?? []).map((s) => ({ number: s.number, label: `${s.number === 0 ? "Specials" : `Season ${s.number}`} · ${s.episodes.length} episodes` }));
  const shown = order === "tmdb" ? episodes : tvdb?.seasons.find((s) => s.number === seasonNo)?.episodes;

  return (
    <section className="mt-16" aria-labelledby="episodes">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <h2 id="episodes" className="text-lg font-semibold">
          Episodes
        </h2>
        <div className="flex flex-wrap gap-3">
          {tvdbReady && (
            <div className="w-52">
              <Select label="Episode order" value={order} onChange={(e) => setOrder(e.target.value)}>
                {ORDERS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </Select>
            </div>
          )}
          <div className="w-60">
            <Select label="Season" value={seasonNo} onChange={(e) => setSeasonNo(Number(e.target.value))} disabled={!seasonOptions.length}>
              {seasonOptions.map((s) => (
                <option key={s.number} value={s.number}>
                  {s.label}
                </option>
              ))}
            </Select>
          </div>
        </div>
      </div>

      <div className="mt-6">
        {error ? (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        ) : !shown ? (
          <p className="text-sm text-ink-3" aria-busy>
            Loading episodes…
          </p>
        ) : shown.length === 0 ? (
          <p className="text-sm text-ink-2">No episodes listed for this season yet.</p>
        ) : (
          <ol className="divide-y divide-line rounded-xl border border-line bg-surface">
            {shown.map((e) => (
              <li key={e.id} className="flex gap-4 p-4">
                {order === "tmdb" && (
                  <span className="hidden aspect-video w-40 shrink-0 overflow-hidden rounded-md bg-sunken sm:block">
                    {e.stillPath && <img src={img(e.stillPath, "w300")!} alt="" loading="lazy" className="size-full object-cover" />}
                  </span>
                )}
                <div className="min-w-0 flex-1">
                  <p className="font-medium">
                    <span className="mr-2 text-ink-3 tabular-nums">{e.number}.</span>
                    {e.title ?? `Episode ${e.number}`}
                  </p>
                  <p className="mt-0.5 text-xs text-ink-3 tabular-nums">
                    {[longDate(e.airDate), e.runtime ? hm(e.runtime) : null, e.absoluteNumber ? `Absolute ${e.absoluteNumber}` : null].filter(Boolean).join(" · ") || "Air date not announced"}
                  </p>
                  {e.overview && <p className="mt-2 line-clamp-2 max-w-[75ch] text-sm leading-relaxed text-ink-2">{e.overview}</p>}
                </div>
                {/* Ticks use TMDB numbering, which is what Trakt and Zylo's history use. */}
                {order === "tmdb" && e.number !== null && (
                  <EpisodeTick seriesId={seriesId} season={seasonNo} episode={e.number} watched={watched.has(`${seasonNo}:${e.number}`)} onChange={onWatchedChange} />
                )}
              </li>
            ))}
          </ol>
        )}
      </div>
    </section>
  );
}
