"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { api, type PlaybackOption, type StreamResults } from "./api";

const PER_ADDON = 8;

/** Streams from the user's addons for a film or one episode. Loaded on demand so browsing never pings addons. */
export function StreamsPanel({ type, id, season, episode }: { type: "movie" | "series"; id: string; season?: number; episode?: number }) {
  const [data, setData] = useState<StreamResults>();
  const [error, setError] = useState<string>();
  const q = type === "series" ? `?season=${season}&episode=${episode}` : "";

  useEffect(() => {
    setData(undefined);
    setError(undefined);
    api<StreamResults>(`/titles/${type}/${id}/streams${q}`).then(setData, (e) => setError(e.message));
  }, [type, id, q]);

  if (error)
    return (
      <p role="alert" className="text-sm text-danger">
        {error}
      </p>
    );
  if (!data)
    return (
      <p className="text-sm text-ink-3" aria-busy>
        Asking your addons…
      </p>
    );
  if (!data.addons.length)
    return (
      <p className="text-sm text-ink-2">
        {data.skipped ? "None of your addons cover this." : "You haven't added any addons yet."}{" "}
        <Link href="/settings#addons" className="font-medium text-ink underline decoration-line-strong underline-offset-2 hover:decoration-ink">
          Manage addons
        </Link>
      </p>
    );

  const total = data.addons.reduce((n, a) => n + a.options.length, 0);
  return (
    <div className="flex flex-col gap-6" aria-live="polite">
      {total === 0 && data.addons.every((a) => a.status === "ok") && <p className="text-sm text-ink-2">Your addons didn't find any streams for this.</p>}
      {data.addons.map((a) => (
        <AddonStreams key={a.id} addon={a} />
      ))}
    </div>
  );
}

function AddonStreams({ addon }: { addon: StreamResults["addons"][number] }) {
  const [all, setAll] = useState(false);
  if (addon.status === "error")
    return (
      <p className="text-sm text-ink-2">
        <span className="font-medium text-ink">{addon.name}</span> didn't answer: {addon.error}
      </p>
    );
  if (!addon.options.length) return null;
  const shown = all ? addon.options : addon.options.slice(0, PER_ADDON);
  return (
    <section aria-label={`Streams from ${addon.name}`}>
      <h3 className="mb-2 flex items-center gap-2 text-sm font-semibold">
        {addon.logo && <img src={addon.logo} alt="" className="size-5 rounded object-cover" />}
        {addon.name}
        <span className="font-normal text-ink-3 tabular-nums">· {addon.options.length}</span>
      </h3>
      <ul className="divide-y divide-line rounded-xl border border-line bg-surface">
        {shown.map((o) => (
          <StreamRow key={o.id} option={o} />
        ))}
      </ul>
      {addon.options.length > PER_ADDON && (
        <button type="button" onClick={() => setAll(!all)} className="mt-2 rounded text-sm font-medium text-accent transition-colors duration-150 hover:text-accent-hover">
          {all ? "Show fewer" : `Show all ${addon.options.length}`}
        </button>
      )}
    </section>
  );
}

const KIND_LABEL: Record<PlaybackOption["kind"], string> = {
  url: "Direct",
  youtube: "YouTube",
  torrent: "Torrent",
  external: "Website",
  usenet: "Usenet",
  archive: "Archive",
};

function StreamRow({ option: o }: { option: PlaybackOption }) {
  const [copied, setCopied] = useState(false);
  const [title, ...rest] = o.label.split("\n");
  const href = o.kind === "url" ? o.url : o.kind === "youtube" ? `https://www.youtube.com/watch?v=${o.ytId}` : o.kind === "external" ? o.externalUrl : undefined;
  const badges = [o.quality, ...o.hdr, o.size, KIND_LABEL[o.kind]].filter(Boolean) as string[];

  return (
    <li className={`flex flex-col gap-3 p-3 sm:flex-row sm:items-start ${o.playable ? "" : "text-ink-2"}`}>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium">
          {title}
          {rest.length > 0 && <span className="font-normal text-ink-3"> {rest.join(" ")}</span>}
        </p>
        {o.description && <p className="mt-1 line-clamp-3 whitespace-pre-line break-words text-xs leading-relaxed text-ink-3">{o.description}</p>}
        <p className="mt-2 flex flex-wrap gap-1.5">
          {badges.map((b) => (
            <span key={b} className="rounded border border-line px-1.5 py-px text-[11px] font-medium text-ink-2 tabular-nums">
              {b}
            </span>
          ))}
        </p>
        {o.note && <p className="mt-2 text-xs text-ink-3">{o.note}</p>}
      </div>
      {o.playable && href && (
        <div className="flex shrink-0 gap-2">
          <a
            href={href}
            target="_blank"
            rel="noreferrer noopener"
            className="inline-flex h-9 items-center rounded-lg border border-line-strong px-3 text-sm font-medium text-ink transition-[border-color,background-color] duration-150 hover:border-ink-2 hover:bg-sunken"
          >
            {o.kind === "external" ? "Open site" : o.kind === "youtube" ? "Watch on YouTube" : "Open"}
          </a>
          {o.kind === "url" && (
            <button
              type="button"
              onClick={async () => {
                await navigator.clipboard.writeText(o.url!).catch(() => {});
                setCopied(true);
                setTimeout(() => setCopied(false), 1500);
              }}
              className="inline-flex h-9 items-center rounded-lg border border-line px-3 text-sm text-ink transition-[border-color] duration-150 hover:border-line-strong"
            >
              {copied ? "Copied" : "Copy link"}
            </button>
          )}
        </div>
      )}
    </li>
  );
}
