"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api, type Card } from "@/lib/api";
import { PosterGrid, TmdbAttribution } from "@/lib/media";
import { useUser } from "@/lib/session";
import { TopBar } from "@/lib/topbar";
import { Loading } from "@/lib/ui";

export default function ListPage() {
  const { id } = useParams<{ id: string }>();
  const { user } = useUser();
  const [list, setList] = useState<{ name: string; description: string | null; items: Card[] }>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (user) api<typeof list>(`/library/lists/${id}`).then(setList, (e) => setError(e.message));
  }, [user, id]);

  if (!user) return <Loading />;

  return (
    <>
      <TopBar />
      <main className="mx-auto max-w-6xl px-6 py-12">
        <Link href="/library" className="rounded text-sm text-ink-2 transition-colors duration-150 hover:text-ink">
          ← Library
        </Link>
        {error ? (
          <p role="alert" className="mt-6 text-sm text-danger">
            {error}
          </p>
        ) : !list ? (
          <p className="mt-6 text-sm text-ink-3">Loading…</p>
        ) : (
          <>
            <h1 className="mt-4 text-2xl font-semibold leading-tight tracking-tight">{list.name}</h1>
            {list.description && <p className="mt-2 max-w-[65ch] text-sm leading-relaxed text-ink-2">{list.description}</p>}
            <p className="mt-1 text-xs text-ink-3">From Trakt. Edit it on trakt.tv; changes appear here after the next sync.</p>
            <div className="mt-8">{list.items.length ? <PosterGrid cards={list.items} /> : <p className="text-sm text-ink-2">This list is empty.</p>}</div>
          </>
        )}
      </main>
      <TmdbAttribution />
    </>
  );
}
