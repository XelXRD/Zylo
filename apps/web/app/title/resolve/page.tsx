"use client";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { api } from "@/lib/api";
import { TopBar } from "@/lib/topbar";
import { Loading } from "@/lib/ui";

// Addon catalogues use IMDb ids; Zylo's title pages use TMDB ids. Look it up, then move on.
function Resolve() {
  const router = useRouter();
  const params = useSearchParams();
  const [error, setError] = useState<string>();
  useEffect(() => {
    api<{ type: string; tmdbId: number }>(`/titles/resolve?imdb=${encodeURIComponent(params.get("imdb") ?? "")}&type=${encodeURIComponent(params.get("type") ?? "")}`).then(
      (r) => router.replace(`/title/${r.type}/${r.tmdbId}`),
      (e) => setError(e.message),
    );
  }, [params, router]);
  if (!error) return <Loading />;
  return (
    <>
      <TopBar />
      <main className="mx-auto max-w-6xl px-6 py-16">
        <h1 className="text-2xl font-semibold">Can't show this title</h1>
        <p className="mt-2 text-sm text-ink-2">{error}</p>
      </main>
    </>
  );
}

export default function ResolvePage() {
  return (
    <Suspense fallback={<Loading />}>
      <Resolve />
    </Suspense>
  );
}
