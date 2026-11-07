"use client";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { activeProfileId, api, type Profile } from "./api";
import { Avatar, Wordmark } from "./ui";

export function TopBar() {
  const [profile, setProfile] = useState<Profile>();

  useEffect(() => {
    api<Profile[]>("/profiles").then((all) => setProfile(all.find((p) => p.id === activeProfileId())), () => {});
  }, []);

  return (
    <header className="sticky top-0 z-10 border-b border-line bg-bg/90 backdrop-blur">
      <div className="mx-auto flex h-14 max-w-6xl items-center gap-4 px-6">
        <Wordmark />
        <Suspense>
          <SearchBox />
        </Suspense>
        <nav className="ml-auto flex items-center gap-1">
          <Link href="/library" className="rounded-lg px-3 py-2 text-sm text-ink-2 transition-colors duration-150 hover:text-ink">
            Library
          </Link>
          <Link href="/settings" className="rounded-lg px-3 py-2 text-sm text-ink-2 transition-colors duration-150 hover:text-ink">
            Settings
          </Link>
          {profile && (
            <Link href="/" aria-label={`Switch profile (current: ${profile.name})`} className="ml-2 rounded-lg">
              <Avatar name={profile.name} color={profile.color} size="sm" />
            </Link>
          )}
        </nav>
      </div>
    </header>
  );
}

function SearchBox() {
  const router = useRouter();
  const params = useSearchParams();
  const [q, setQ] = useState(params.get("q") ?? "");

  return (
    <form
      role="search"
      className="ml-4 hidden w-full max-w-sm sm:block"
      onSubmit={(e) => {
        e.preventDefault();
        if (q.trim()) router.push(`/search?q=${encodeURIComponent(q.trim())}`);
      }}
    >
      <label htmlFor="global-search" className="sr-only">
        Search films and series
      </label>
      <input
        id="global-search"
        type="search"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        placeholder="Search films and series"
        className="h-9 w-full rounded-lg border border-line bg-surface px-3 text-sm text-ink placeholder:text-ink-3 transition-[border-color] duration-150 hover:border-line-strong"
      />
    </form>
  );
}
