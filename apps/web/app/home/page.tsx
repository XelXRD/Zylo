"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { activeProfileId, api, type Card, type Profile } from "@/lib/api";
import { PosterGrid, SkeletonGrid, TmdbAttribution } from "@/lib/media";
import { useUser } from "@/lib/session";
import { TopBar } from "@/lib/topbar";
import { Loading } from "@/lib/ui";

export default function HomePage() {
  const router = useRouter();
  const { user, error } = useUser();
  const [profile, setProfile] = useState<Profile>();
  const [connected, setConnected] = useState<boolean>();
  const [trending, setTrending] = useState<Card[]>();
  const [trendingError, setTrendingError] = useState<string>();
  const [recommended, setRecommended] = useState<Card[]>([]);

  useEffect(() => {
    if (!user) return;
    api<Profile[]>("/profiles").then((all) => {
      const p = all.find((x) => x.id === activeProfileId());
      if (p) setProfile(p);
      else router.replace("/");
    });
    api<{ tmdb: boolean }>("/metadata/status").then(({ tmdb }) => {
      setConnected(tmdb);
      if (!tmdb) return;
      api<{ results: Card[] }>("/discover/trending").then((r) => setTrending(r.results), (e) => setTrendingError(e.message));
      // Only shows when this profile has Trakt connected; quietly empty otherwise.
      api<{ results: Card[] }>("/discover/recommended").then((r) => setRecommended(r.results), () => {});
    });
  }, [user, router]);

  if (error) return <main className="p-8 text-sm text-danger">{error}</main>;
  if (!profile || !user || connected === undefined) return <Loading />;

  return (
    <>
      <TopBar />
      <main className="mx-auto max-w-6xl px-6 py-12">
        <h1 className="text-2xl font-semibold leading-tight tracking-tight">Hi, {profile.name}</h1>

        {!connected ? (
          <section className="mt-10 max-w-2xl rounded-2xl border border-line bg-surface px-8 py-10">
            <h2 className="text-lg font-semibold">Connect TMDB to start browsing</h2>
            <p className="mt-2 max-w-[60ch] text-sm leading-relaxed text-ink-2">
              Zylo gets film and series details, posters and trailers from TMDB.{" "}
              {user.isAdmin ? "It takes a free TMDB account and about two minutes." : "Ask whoever runs this Zylo server to connect it."}
            </p>
            {user.isAdmin && (
              <Link
                href="/settings#integrations"
                className="mt-6 inline-flex h-10 items-center rounded-lg bg-accent px-4 text-sm font-medium text-accent-ink transition-colors duration-150 hover:bg-accent-hover"
              >
                Connect TMDB
              </Link>
            )}
          </section>
        ) : (
          <>
          {recommended.length > 0 && (
            <section className="mt-10" aria-labelledby="recommended">
              <h2 id="recommended" className="text-lg font-semibold">
                Recommended for you
              </h2>
              <p className="mt-1 text-sm text-ink-3">From your Trakt history.</p>
              <div className="mt-5">
                <PosterGrid cards={recommended.slice(0, 12)} />
              </div>
            </section>
          )}
          <section className={recommended.length ? "mt-16" : "mt-10"} aria-labelledby="trending">
            <h2 id="trending" className="text-lg font-semibold">
              Trending this week
            </h2>
            <div className="mt-5">
              {trendingError ? (
                <p className="text-sm text-danger">{trendingError}</p>
              ) : trending ? (
                <PosterGrid cards={trending} />
              ) : (
                <SkeletonGrid />
              )}
            </div>
          </section>
          </>
        )}
      </main>
      {connected && <TmdbAttribution />}
    </>
  );
}
