"use client";
import Link from "next/link";
import type { Profile } from "./api";
import { Avatar, Wordmark } from "./ui";

export function TopBar({ profile }: { profile?: Profile }) {
  return (
    <header className="sticky top-0 z-10 border-b border-line bg-bg/90 backdrop-blur">
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-6">
        <Wordmark />
        <nav className="flex items-center gap-1">
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
