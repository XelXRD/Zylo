"use client";
import Link from "next/link";
import { img, titleHref, type AddonCard, type Card } from "./api";

export function Poster({ path, title, size = "w342", className = "" }: { path: string | null; title: string; size?: string; className?: string }) {
  const src = img(path, size);
  return (
    <span className={`relative block aspect-[2/3] overflow-hidden rounded-lg border border-line bg-sunken ${className}`}>
      {src ? (
        <img src={src} alt="" loading="lazy" className="size-full object-cover" />
      ) : (
        // Thumbnails sit beside the title already: a single initial reads better than a clipped name.
        <span className="flex size-full items-center justify-center p-3 text-center text-xs text-ink-3">{size === "w92" ? title.trim().charAt(0).toUpperCase() : title}</span>
      )}
    </span>
  );
}

export function PosterCard({ card }: { card: Card }) {
  return (
    <Link href={titleHref(card)} className="group flex flex-col gap-2 rounded-lg">
      <Poster path={card.posterPath} title={card.title} className="transition-[border-color,transform] duration-150 ease-out group-hover:border-line-strong group-active:scale-[0.99]" />
      <span className="flex flex-col">
        <span className="truncate text-sm font-medium text-ink">{card.title}</span>
        <span className="text-xs text-ink-3 tabular-nums">
          {card.year ?? "—"} · {card.type === "movie" ? "Film" : "Series"}
        </span>
      </span>
    </Link>
  );
}

export function PosterGrid({ cards }: { cards: Card[] }) {
  return (
    <ul className="grid grid-cols-2 gap-x-4 gap-y-6 sm:grid-cols-4 lg:grid-cols-6">
      {cards.map((c) => (
        <li key={`${c.type}-${c.tmdbId}`}>
          <PosterCard card={c} />
        </li>
      ))}
    </ul>
  );
}

/** Required by TMDB's API terms wherever their data or images appear. */
export function TmdbAttribution() {
  return (
    <footer className="mx-auto mt-24 max-w-6xl px-6 text-xs leading-relaxed text-ink-3">
      <div className="border-t border-line py-8">
      <a href="https://www.themoviedb.org" target="_blank" rel="noreferrer" className="font-semibold text-ink-2 hover:text-ink">
        TMDB
      </a>{" "}
      · This product uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB.
      </div>
    </footer>
  );
}

export function SkeletonGrid({ count = 12 }: { count?: number }) {
  return (
    <ul aria-hidden className="grid grid-cols-2 gap-x-4 gap-y-6 sm:grid-cols-4 lg:grid-cols-6">
      {Array.from({ length: count }, (_, i) => (
        <li key={i} className="flex flex-col gap-2">
          <span className="aspect-[2/3] rounded-lg bg-sunken" />
          <span className="h-3 w-3/4 rounded bg-sunken" />
        </li>
      ))}
    </ul>
  );
}

/** A catalogue item from an addon: its poster comes from the addon, its page from Zylo (resolved by IMDb id). */
export function AddonPosterCard({ card }: { card: AddonCard }) {
  return (
    <Link href={`/title/resolve?imdb=${card.imdbId}&type=${card.type}`} className="group flex flex-col gap-2 rounded-lg">
      <span className="relative block aspect-[2/3] overflow-hidden rounded-lg border border-line bg-sunken transition-[border-color,transform] duration-150 ease-out group-hover:border-line-strong group-active:scale-[0.99]">
        {card.poster ? (
          <img src={card.poster} alt="" loading="lazy" referrerPolicy="no-referrer" className="size-full object-cover" />
        ) : (
          <span className="flex size-full items-center justify-center p-3 text-center text-xs text-ink-3">{card.title}</span>
        )}
      </span>
      <span className="flex flex-col">
        <span className="truncate text-sm font-medium text-ink">{card.title}</span>
        <span className="text-xs text-ink-3 tabular-nums">
          {card.year ?? "—"} · {card.type === "movie" ? "Film" : "Series"}
        </span>
      </span>
    </Link>
  );
}
