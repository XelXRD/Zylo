"use client";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { api, type Card } from "@/lib/api";
import { PosterGrid, SkeletonGrid, TmdbAttribution } from "@/lib/media";
import { useUser } from "@/lib/session";
import { TopBar } from "@/lib/topbar";
import { Button, Loading } from "@/lib/ui";

type Page = { results: Card[]; page: number; totalPages: number };

export default function SearchPage() {
  return (
    <Suspense fallback={<Loading />}>
      <Search />
    </Suspense>
  );
}

function Search() {
  const { user } = useUser();
  const q = useSearchParams().get("q")?.trim() ?? "";
  const [results, setResults] = useState<Card[]>();
  const [page, setPage] = useState<Page>();
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!user || !q) return;
    setResults(undefined);
    setError(undefined);
    api<Page>(`/search?q=${encodeURIComponent(q)}`).then((r) => (setResults(r.results), setPage(r)), (e) => setError(e.message));
  }, [user, q]);

  const more = async () => {
    if (!page) return;
    setBusy(true);
    try {
      const r = await api<Page>(`/search?q=${encodeURIComponent(q)}&page=${page.page + 1}`);
      setResults((prev) => [...(prev ?? []), ...r.results]);
      setPage(r);
    } catch (e) {
      setError((e as Error).message);
    }
    setBusy(false);
  };

  if (!user) return <Loading />;

  return (
    <>
      <TopBar />
      <main className="mx-auto max-w-6xl px-6 py-12">
        {/* Phones don't show the header search, so the page carries its own. */}
        <form action="/search" role="search" className="mb-8 sm:hidden">
          <label htmlFor="page-search" className="sr-only">
            Search films and series
          </label>
          <input id="page-search" name="q" type="search" defaultValue={q} placeholder="Search films and series" className="h-10 w-full rounded-lg border border-line bg-surface px-3 text-sm" />
        </form>
        <h1 className="text-2xl font-semibold leading-tight tracking-tight">{q ? <>Results for “{q}”</> : "Search"}</h1>
        <div className="mt-8">
          {!q ? (
            <p className="text-sm text-ink-2">Type a film or series name to search.</p>
          ) : error ? (
            <p role="alert" className="text-sm text-danger">
              {error}
            </p>
          ) : !results ? (
            <SkeletonGrid count={6} />
          ) : results.length === 0 ? (
            <p className="text-sm text-ink-2">No films or series match “{q}”. Check the spelling, or try the original title.</p>
          ) : (
            <>
              <PosterGrid cards={results} />
              {page && page.page < page.totalPages && (
                <div className="mt-10 flex justify-center">
                  <Button variant="quiet" busy={busy} onClick={more}>
                    Show more results
                  </Button>
                </div>
              )}
            </>
          )}
        </div>
      </main>
      <TmdbAttribution />
    </>
  );
}
