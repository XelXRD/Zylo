"use client";
import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { activeProfileId, api, clock, DEBRID_NAMES, img, watchHref, type PlaybackOption, type PlaybackStart, type RankedOption, type Title } from "@/lib/api";
import { useUser } from "@/lib/session";

type Sub = { id: string; lang: string; label: string | null; url: string };
type Phase = "loading" | "choose" | "resume" | "playing" | "failed" | "ended";

const PROGRESS_EVERY_MS = 15_000;
const START_TIMEOUT_MS = 20_000;

export default function WatchPage() {
  return (
    <Suspense fallback={<Shell>{null}</Shell>}>
      <Watch />
    </Suspense>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  // The player is always dark: it's a cinema, not a document.
  return (
    <div data-theme="dark" className="min-h-dvh bg-[#0b0b0d] text-[#ececef]">
      {children}
    </div>
  );
}

function Watch() {
  const { type, id } = useParams<{ type: "movie" | "series"; id: string }>();
  const params = useSearchParams();
  const router = useRouter();
  const { user } = useUser();
  const season = params.get("s") !== null ? Number(params.get("s")) : undefined;
  const episode = params.get("e") !== null ? Number(params.get("e")) : undefined;
  const ep = type === "series" && season !== undefined && episode !== undefined ? { season, episode } : null;

  const [title, setTitle] = useState<Title>();
  const [epTitle, setEpTitle] = useState<string | null>(null);
  const [start, setStart] = useState<PlaybackStart>();
  const [current, setCurrent] = useState<RankedOption>();
  const [phase, setPhase] = useState<Phase>("loading");
  const [problem, setProblem] = useState<string>();
  const [error, setError] = useState<string>();
  const [subs, setSubs] = useState<Sub[]>([]);
  const [showSources, setShowSources] = useState(false);
  const resumeFrom = useRef(0);

  const base = { type, id: Number(id), ...(ep && { season: ep.season, episode: ep.episode }) };
  const q = new URLSearchParams({ type, id, ...(ep && { season: String(ep.season), episode: String(ep.episode) }) });

  // Load the title, the episode name and the ranked sources.
  useEffect(() => {
    if (!user) return;
    setPhase("loading");
    setCurrent(undefined);
    api<Title>(`/titles/${type}/${id}`).then(setTitle, (e) => setError(e.message));
    if (ep) api<{ episodes: { number: number; title: string }[] }>(`/titles/series/${id}/seasons/${ep.season}`).then((s) => setEpTitle(s.episodes.find((x) => x.number === ep.episode)?.title ?? null), () => {});
    const bg = params.get("bg");
    api<PlaybackStart>(`/playback/start?${q}${bg ? `&bingeGroup=${encodeURIComponent(bg)}` : ""}`).then(
      (s) => {
        setStart(s);
        const wanted = params.get("option");
        const pick = (wanted && s.ranked.find((o) => o.id === wanted)) || (s.prefs.autoPlay ? s.ranked[0] : undefined);
        if (!pick) return setPhase("choose");
        setCurrent(pick);
        resumeFrom.current = 0;
        setPhase(s.resumeAt ? "resume" : "playing");
      },
      (e) => setError(e.message),
    );
  }, [user, type, id, season, episode]); // eslint-disable-line react-hooks/exhaustive-deps

  // Subtitles: the stream's own, plus subtitle addons (asked with the file name when we know it).
  useEffect(() => {
    if (!current) return;
    const own = current.subtitles.map((s) => ({ id: `s:${s.id}`, lang: s.lang, label: s.label ?? null, url: s.url }));
    setSubs(own);
    api<Sub[]>(`/playback/subtitles?${q}${current.filename ? `&filename=${encodeURIComponent(current.filename)}` : ""}`).then((extra) => setSubs([...own, ...extra]), () => {});
  }, [current?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const choose = (o: RankedOption) => {
    // A hand-picked source is remembered for this title.
    api("/playback/choice", { method: "POST", body: { type, id: Number(id), addonId: o.source.id, quality: o.quality } }).catch(() => {});
    setCurrent(o);
    setProblem(undefined);
    setShowSources(false);
    setPhase("playing");
  };

  // VLC gets a playlist file; a torrent source needs its link first, and a failure shows like a playback one.
  const vlc = (o: RankedOption) =>
    openInVlc(o, title?.title ?? "Zylo", base).catch((e: Error) => {
      setCurrent(o);
      setProblem(e.message);
      setPhase("failed");
    });

  const next = start && current ? start.ranked[start.ranked.findIndex((o) => o.id === current.id) + 1] : undefined;
  const nextEp = nextEpisode(title, ep);
  const titleHref = `/title/${type}/${id}`;

  if (error)
    return (
      <Shell>
        <main className="mx-auto max-w-2xl px-6 py-16">
          <h1 className="text-2xl font-semibold">Can't play this</h1>
          <p className="mt-2 text-sm text-[#a3a3ad]">{error}</p>
          <Link href={titleHref} className="mt-6 inline-block text-sm font-medium text-[#52c4b3]">
            ← Back
          </Link>
        </main>
      </Shell>
    );

  return (
    <Shell>
      <header className="flex items-center gap-4 px-4 py-3 sm:px-6">
        <Link href={titleHref} className="rounded-lg px-2 py-1 text-sm text-[#a3a3ad] transition-colors duration-150 hover:text-[#ececef]">
          ← Back
        </Link>
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold">{title?.title ?? " "}</p>
          {ep && (
            <p className="truncate text-xs text-[#a3a3ad] tabular-nums">
              Season {ep.season}, episode {ep.episode}
              {epTitle ? ` · ${epTitle}` : ""}
            </p>
          )}
        </div>
        {current && (
          <button
            type="button"
            onClick={() => setShowSources((v) => !v)}
            aria-expanded={showSources}
            className="hidden max-w-xs truncate rounded-lg border border-[#3b3b44] px-3 py-1.5 text-xs text-[#a3a3ad] transition-[border-color,color] duration-150 hover:border-[#74747e] hover:text-[#ececef] sm:block"
          >
            {current.source.name}
            {current.quality ? ` · ${current.quality}` : ""} · Change
          </button>
        )}
      </header>

      <main className="mx-auto max-w-6xl px-0 sm:px-6">
        <div className="relative aspect-video w-full overflow-hidden bg-black sm:rounded-xl">
          {phase === "playing" && current && (
            <Resolved
              key={current.id}
              option={current}
              where={base}
              poster={img(title?.backdropPath, "w1280")}
              subs={subs}
              startAt={resumeFrom.current}
              event={(event, position, duration) => sendProgress({ ...base, event, position, duration })}
              onFail={(why) => {
                setProblem(why);
                setPhase("failed");
              }}
              onEnded={() => setPhase("ended")}
            />
          )}

          {phase !== "playing" && (
            <div className="absolute inset-0">
              {title?.backdropPath && <img src={img(title.backdropPath, "w1280")!} alt="" className="size-full object-cover opacity-30" />}
              <div className="absolute inset-0 flex items-center justify-center p-6">
                {phase === "loading" && <p className="text-sm text-[#a3a3ad]">Finding the best source…</p>}

                {phase === "resume" && start?.resumeAt && (
                  <div className="flex flex-col items-center gap-3 text-center">
                    <p className="text-sm text-[#a3a3ad]">You stopped at {clock(start.resumeAt)}</p>
                    <div className="flex flex-wrap justify-center gap-2">
                      <BigButton
                        autoFocus
                        onClick={() => {
                          resumeFrom.current = start.resumeAt!;
                          setPhase("playing");
                        }}
                      >
                        ▶ Resume from {clock(start.resumeAt)}
                      </BigButton>
                      <BigButton quiet onClick={() => ((resumeFrom.current = 0), setPhase("playing"))}>
                        Start from the beginning
                      </BigButton>
                    </div>
                  </div>
                )}

                {phase === "choose" && (
                  <p className="max-w-md text-center text-sm text-[#a3a3ad]">
                    {start?.ranked.length ? (
                      "Pick a source below to start."
                    ) : start?.waiting ? (
                      <>
                        Your addons only found torrents here. A debrid service plays them once it has them ready:{" "}
                        <Link href="/settings#debrid" className="font-medium text-[#ececef] underline underline-offset-2">
                          connect one in Settings
                        </Link>
                        .
                      </>
                    ) : (
                      "None of your addons have a stream Zylo can play here. Other options are listed below."
                    )}
                  </p>
                )}

                {phase === "failed" && (
                  <div className="flex max-w-lg flex-col items-center gap-3 text-center" role="alert">
                    <p className="font-semibold">This source won't play here</p>
                    <p className="text-sm text-[#a3a3ad]">{problem}</p>
                    <div className="mt-2 flex flex-wrap justify-center gap-2">
                      {next && (
                        <BigButton autoFocus onClick={() => choose(next)}>
                          Try the next source
                        </BigButton>
                      )}
                      {current && (
                        <BigButton quiet onClick={() => vlc(current)}>
                          Open in VLC
                        </BigButton>
                      )}
                      <BigButton quiet onClick={() => setShowSources(true)}>
                        Choose another
                      </BigButton>
                    </div>
                  </div>
                )}

                {phase === "ended" && (
                  <div className="flex flex-col items-center gap-3 text-center">
                    {nextEp ? (
                      <>
                        <p className="text-sm text-[#a3a3ad]">Up next: season {nextEp.season}, episode {nextEp.episode}</p>
                        <BigButton autoFocus onClick={() => router.push(watchHref("series", id, nextEp, current?.bingeGroup ? { bg: current.bingeGroup } : {}))}>
                          ▶ Play next episode
                        </BigButton>
                      </>
                    ) : (
                      <p className="text-sm text-[#a3a3ad]">Finished.</p>
                    )}
                    <Link href={titleHref} className="text-sm font-medium text-[#52c4b3]">
                      Back to {title?.title}
                    </Link>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {(phase === "playing" || phase === "ended") && nextEp && phase === "playing" && (
          <div className="flex justify-end px-4 pt-3 sm:px-0">
            <Link href={watchHref("series", id, nextEp, current?.bingeGroup ? { bg: current.bingeGroup } : {})} className="text-sm text-[#a3a3ad] transition-colors duration-150 hover:text-[#ececef]">
              Next episode →
            </Link>
          </div>
        )}

        {start && (showSources || phase === "choose" || phase === "failed") && (
          <Sources start={start} current={current} failed={phase === "failed"} onPick={choose} onVlc={vlc} />
        )}
        {current && phase === "playing" && !showSources && (
          <p className="px-4 py-4 text-xs text-[#74747e] sm:hidden">
            {current.source.name}
            {current.quality ? ` · ${current.quality}` : ""} ·{" "}
            <button type="button" className="underline" onClick={() => setShowSources(true)}>
              Change source
            </button>
          </p>
        )}
      </main>
    </Shell>
  );
}

function BigButton({ quiet, children, ...props }: React.ButtonHTMLAttributes<HTMLButtonElement> & { quiet?: boolean }) {
  return (
    <button
      type="button"
      {...props}
      className={`inline-flex h-11 items-center rounded-lg px-5 text-sm font-medium transition-[background-color,border-color] duration-150 active:translate-y-px ${
        quiet ? "border border-[#3b3b44] bg-[#18181c]/80 text-[#ececef] hover:border-[#74747e]" : "bg-[#3cb5a3] text-[#0b1f1c] hover:bg-[#52c4b3]"
      }`}
    >
      {children}
    </button>
  );
}

// --- the <video> itself -----------------------------------------------------------

function Player({
  option,
  where,
  poster,
  subs,
  startAt,
  event,
  onFail,
  onEnded,
}: {
  option: RankedOption;
  where: Where;
  poster: string | null;
  subs: Sub[];
  startAt: number;
  event: (e: "start" | "progress" | "pause" | "stop", position: number, duration: number) => void;
  onFail: (why: string) => void;
  onEnded: () => void;
}) {
  const video = useRef<HTMLVideoElement>(null);
  const lastSent = useRef(0);
  const started = useRef(false);
  const report = useCallback(
    (e: "start" | "progress" | "pause" | "stop") => {
      const v = video.current;
      if (!v || !Number.isFinite(v.duration) || v.duration <= 0) return;
      lastSent.current = Date.now();
      event(e, v.currentTime, v.duration);
    },
    [event],
  );

  useEffect(() => {
    const v = video.current!;
    // If nothing has loaded in 20 seconds, call it.
    const t = setTimeout(() => !started.current && onFail("It took too long to start. The link may have expired, or the server is slow."), START_TIMEOUT_MS);
    // Leaving the page (closing the tab, navigating away): report where we got to.
    const leave = () => {
      if (!started.current || !Number.isFinite(v.duration)) return;
      const body = { ...where, event: "stop", position: v.currentTime, duration: v.duration, profileId: activeProfileId() };
      navigator.sendBeacon("/api/playback/progress", new Blob([JSON.stringify(body)], { type: "application/json" }));
    };
    window.addEventListener("pagehide", leave);
    return () => {
      clearTimeout(t);
      window.removeEventListener("pagehide", leave);
      if (started.current && !v.ended) report("stop");
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <video
      ref={video}
      src={option.url}
      poster={poster ?? undefined}
      controls
      autoPlay
      playsInline
      className="size-full bg-black"
      onLoadedMetadata={(e) => {
        if (startAt > 0) e.currentTarget.currentTime = startAt; // resume
      }}
      onLoadedData={() => (started.current = true)}
      onPlay={() => report("start")}
      onPause={(e) => !e.currentTarget.ended && report("pause")}
      onTimeUpdate={() => Date.now() - lastSent.current > PROGRESS_EVERY_MS && report("progress")}
      onEnded={() => {
        report("stop");
        onEnded();
      }}
      onError={(e) => {
        const code = e.currentTarget.error?.code;
        // Browsers report a dead link and an unplayable format with the same code (4), so judge by the file type.
        onFail(
          code === 4
            ? option.browserFriendly
              ? "The file couldn't be loaded. The link may have expired, or the server refused it."
              : `Your browser can't play this kind of file${option.filename ? ` (${option.filename.split(".").pop()?.toUpperCase()})` : ""}. MKV and HEVC files usually need a desktop player like VLC.`
            : code === 2
              ? "The stream couldn't be loaded. The link may have expired."
              : code === 3
                ? "The file is damaged or uses a format your browser can't decode."
                : "Something stopped it from playing.",
        );
      }}
    >
      {subs.map((s, i) => (
        <track key={s.id} kind="subtitles" srcLang={s.lang.slice(0, 2)} label={s.label ?? languageName(s.lang, i)} src={`/api/playback/subtitle.vtt?url=${encodeURIComponent(s.url)}`} />
      ))}
    </video>
  );
}

// --- source list -------------------------------------------------------------------

function Sources({ start, current, failed, onPick, onVlc }: { start: PlaybackStart; current?: RankedOption; failed: boolean; onPick: (o: RankedOption) => void; onVlc: (o: RankedOption) => void }) {
  return (
    <section className="px-4 py-6 sm:px-0" aria-labelledby="sources">
      <h2 id="sources" className="text-sm font-semibold">
        Sources
      </h2>
      <p className="mt-1 text-xs text-[#74747e]">Ordered by your addon order, what your browser can play, and your quality limit. Zylo remembers the one you pick for this title.</p>
      {start.ranked.length > 0 && (
        <ul className="mt-4 divide-y divide-[#2a2a31] rounded-xl border border-[#2a2a31] bg-[#18181c]">
          {start.ranked.map((o) => (
            <li key={o.id} className="flex flex-wrap items-center gap-3 p-3">
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium">
                  {o.label.split("\n").join(" ")}
                  {o.id === current?.id && (failed ? <span className="ml-2 text-xs font-normal text-[#ef7a70]">Didn't play</span> : <span className="ml-2 text-xs font-normal text-[#52c4b3]">Playing</span>)}
                </p>
                <p className="mt-0.5 truncate text-xs text-[#74747e]">
                  {[o.source.name, o.debrid && `via ${DEBRID_NAMES[o.debrid.service]}${o.debrid.cached ? "" : " (if it has it)"}`, o.quality, ...o.hdr, o.size, o.browserFriendly ? null : "may not play in a browser"].filter(Boolean).join(" · ")}
                </p>
              </div>
              <div className="flex gap-2">
                <button type="button" onClick={() => onPick(o)} className="h-9 rounded-lg border border-[#3b3b44] px-3 text-sm transition-[border-color] duration-150 hover:border-[#74747e]">
                  Play
                </button>
                <button type="button" onClick={() => onVlc(o)} className="h-9 rounded-lg px-3 text-sm text-[#a3a3ad] transition-colors duration-150 hover:text-[#ececef]">
                  VLC
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {start.waiting > 0 && (
        <p className="mt-3 text-xs text-[#74747e]">
          {start.waiting === 1 ? "1 torrent source is hidden: no connected debrid service has it" : `${start.waiting} torrent sources are hidden: no connected debrid service has them`} ready to stream.
        </p>
      )}
      {start.others.length > 0 && (
        <>
          <h3 className="mt-6 text-xs font-semibold text-[#a3a3ad]">Elsewhere</h3>
          <ul className="mt-2 flex flex-wrap gap-2">
            {start.others.map((o) => (
              <li key={o.id}>
                <a
                  href={externalHref(o)}
                  target="_blank"
                  rel="noreferrer noopener"
                  className="inline-flex h-9 items-center rounded-lg border border-[#3b3b44] px-3 text-sm transition-[border-color] duration-150 hover:border-[#74747e]"
                >
                  {o.label.split("\n")[0]} · {o.kind === "youtube" ? "YouTube" : "website"} ↗
                </a>
              </li>
            ))}
          </ul>
        </>
      )}
      {start.addonErrors.length > 0 && (
        <p className="mt-4 text-xs text-[#74747e]">
          {start.addonErrors.map((a) => a.name).join(", ")} didn't answer.
        </p>
      )}
    </section>
  );
}

// --- helpers -----------------------------------------------------------------------

function sendProgress(body: object) {
  api("/playback/progress", { method: "POST", body }).catch(() => {});
}

const externalHref = (o: PlaybackOption) => (o.kind === "youtube" ? `https://www.youtube.com/watch?v=${o.ytId}` : o.externalUrl);

type Where = { type: string; id: number; season?: number; episode?: number };

/** Torrent sources become a link only now, right before playing: debrid links expire. */
async function linkFor(o: RankedOption, where: Where) {
  if (o.url) return o.url;
  const body = { service: o.debrid!.service, infoHash: o.infoHash, filename: o.filename, fileIdx: o.fileIdx, season: where.season, episode: where.episode };
  return (await api<{ url: string }>("/playback/resolve", { method: "POST", body })).url;
}

/** Plays a torrent source once its debrid service hands over the link; failures show like any other. */
function Resolved(props: React.ComponentProps<typeof Player>) {
  const [url, setUrl] = useState(props.option.url);
  useEffect(() => {
    if (!url) linkFor(props.option, props.where).then(setUrl, (e: Error) => props.onFail(e.message));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  if (url) return <Player {...props} option={{ ...props.option, url }} />;
  return (
    <p role="status" className="grid size-full place-items-center text-sm text-[#a3a3ad]">
      Getting the link from {DEBRID_NAMES[props.option.debrid!.service]}…
    </p>
  );
}

/** A tiny playlist file VLC (or any desktop player) opens. Progress isn't tracked there. */
async function openInVlc(o: RankedOption, title: string, where: Where) {
  const m3u = `#EXTM3U\n#EXTINF:-1,${title.replace(/[\r\n]/g, " ")}\n${await linkFor(o, where)}\n`;
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([m3u], { type: "audio/x-mpegurl" }));
  a.download = `${title.replace(/[^\w\s-]/g, "").trim() || "zylo"}.m3u`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 10_000);
}

const langNames = new Intl.DisplayNames(["en-GB"], { type: "language" });
function languageName(code: string, i: number) {
  for (const c of [code, code.slice(0, 2)]) {
    try {
      const name = langNames.of(c);
      if (name && name.toLowerCase() !== c.toLowerCase()) return name;
    } catch {}
  }
  return `Subtitles ${i + 1}`;
}

function nextEpisode(title: Title | undefined, ep: { season: number; episode: number } | null) {
  if (!title || !ep) return null;
  const s = title.seasons.find((x) => x.number === ep.season);
  if (s && ep.episode < s.episodeCount) return { season: ep.season, episode: ep.episode + 1 };
  const following = title.seasons.find((x) => x.number === ep.season + 1 && x.episodeCount > 0);
  return following ? { season: following.number, episode: 1 } : null;
}
