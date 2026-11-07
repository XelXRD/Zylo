"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, setActiveProfileId, type Profile } from "@/lib/api";
import { useUser } from "@/lib/session";
import { Avatar, Loading } from "@/lib/ui";

export default function ProfilePicker() {
  const router = useRouter();
  const { user, error } = useUser();
  const [profiles, setProfiles] = useState<Profile[]>();

  useEffect(() => {
    if (user) api<Profile[]>("/profiles").then(setProfiles);
  }, [user]);

  if (error) return <main className="p-8 text-sm text-danger">{error}</main>;
  if (!profiles) return <Loading />;

  const pick = (id: string) => {
    setActiveProfileId(id);
    router.push("/home");
  };

  return (
    <main className="flex min-h-dvh flex-col items-center justify-center px-4 py-16">
      <h1 className="text-3xl font-semibold leading-tight tracking-tight sm:text-4xl">Who's watching?</h1>
      <ul className="mt-12 flex max-w-3xl flex-wrap justify-center gap-6 sm:gap-8">
        {profiles.map((p) => (
          <li key={p.id}>
            <button
              onClick={() => pick(p.id)}
              className="group flex w-28 flex-col items-center gap-3 rounded-2xl p-2 sm:w-32"
            >
              <span className="rounded-xl outline-2 outline-offset-4 outline-transparent transition-[outline-color,transform] duration-150 ease-out group-hover:outline-line-strong group-active:scale-[0.98]">
                <Avatar name={p.name} color={p.color} size="lg" />
              </span>
              <span className="max-w-full truncate text-sm text-ink-2 transition-colors duration-150 group-hover:text-ink">{p.name}</span>
            </button>
          </li>
        ))}
        {profiles.length < 8 && (
          <li>
            <Link href="/settings#profiles" className="group flex w-28 flex-col items-center gap-3 rounded-2xl p-2 sm:w-32">
              <span className="flex size-28 items-center justify-center rounded-xl border border-dashed border-line-strong text-3xl text-ink-3 transition-[border-color,color] duration-150 group-hover:border-ink-2 group-hover:text-ink sm:size-32">
                +
              </span>
              <span className="text-sm text-ink-3 transition-colors duration-150 group-hover:text-ink">Add profile</span>
            </Link>
          </li>
        )}
      </ul>
      <Link href="/settings" className="mt-14 rounded-lg border border-line px-4 py-2 text-sm text-ink-2 transition-[border-color,color] duration-150 hover:border-line-strong hover:text-ink">
        Manage profiles
      </Link>
    </main>
  );
}
