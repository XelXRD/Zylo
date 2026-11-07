"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { activeProfileId, api, type Profile } from "@/lib/api";
import { useUser } from "@/lib/session";
import { Loading } from "@/lib/ui";
import { TopBar } from "@/lib/topbar";

export default function HomePage() {
  const router = useRouter();
  const { user, error } = useUser();
  const [profile, setProfile] = useState<Profile>();

  useEffect(() => {
    if (!user) return;
    api<Profile[]>("/profiles").then((all) => {
      const p = all.find((x) => x.id === activeProfileId());
      if (p) setProfile(p);
      else router.replace("/");
    });
  }, [user, router]);

  if (error) return <main className="p-8 text-sm text-danger">{error}</main>;
  if (!profile) return <Loading />;

  return (
    <>
      <TopBar profile={profile} />
      <main className="mx-auto max-w-6xl px-6 py-12">
        <h1 className="text-2xl font-semibold leading-tight tracking-tight">Hi, {profile.name}</h1>
        <section className="mt-10 max-w-2xl rounded-2xl border border-line bg-surface px-8 py-10">
          <h2 className="text-lg font-semibold">Nothing to browse yet</h2>
          <p className="mt-2 max-w-[60ch] text-sm leading-relaxed text-ink-2">
            Films, series and anime appear here once metadata is connected. That arrives in the next update, along with Trakt sign-in.
            For now you can set up profiles and preferences for everyone at home.
          </p>
          <div className="mt-6 flex flex-wrap gap-3">
            <Link
              href="/settings"
              className="inline-flex h-10 items-center rounded-lg bg-accent px-4 text-sm font-medium text-accent-ink transition-colors duration-150 hover:bg-accent-hover"
            >
              Open settings
            </Link>
          </div>
        </section>
      </main>
    </>
  );
}
