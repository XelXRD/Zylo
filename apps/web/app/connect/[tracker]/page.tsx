"use client";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, TRACKER_NAMES, type Tracker } from "@/lib/api";
import { TopBar } from "@/lib/topbar";

// AniList and MyAnimeList send the browser back here after sign-in. AniList puts a token in the #fragment;
// MAL puts a one-time code in the query. Either way it goes straight to Zylo and out of the address bar.
export default function ConnectPage() {
  const { tracker } = useParams<{ tracker: Tracker }>();
  const router = useRouter();
  const [error, setError] = useState<string>();
  const name = TRACKER_NAMES[tracker] ?? "the tracker";

  useEffect(() => {
    const hash = new URLSearchParams(location.hash.slice(1));
    const query = new URLSearchParams(location.search);
    history.replaceState(null, "", location.pathname);
    const refused = hash.get("error_description") ?? hash.get("error") ?? query.get("error_description") ?? query.get("error");
    let send: Promise<unknown> | null = null;
    if (refused) setError(refused === "access_denied" ? `Access was declined on ${name}.` : `${name} said: ${refused}`);
    else if (tracker === "anilist" && hash.get("access_token"))
      send = api("/anime/anilist/connect", { method: "POST", body: { accessToken: hash.get("access_token"), ...(Number(hash.get("expires_in")) > 0 && { expiresIn: Number(hash.get("expires_in")) }) } });
    else if (tracker === "mal" && query.get("code")) send = api("/anime/mal/connect", { method: "POST", body: { code: query.get("code"), state: query.get("state") ?? "" } });
    else setError(`${name} didn't send a sign-in back. Start again from Settings.`);
    send?.then(
      () => router.replace(`/settings?connected=${tracker}#anime`),
      (e: Error) => setError(e.message),
    );
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      <TopBar />
      <main className="mx-auto max-w-xl px-6 py-16" aria-live="polite">
        {error ? (
          <>
            <h1 className="text-2xl font-semibold">Couldn't connect {name}</h1>
            <p className="mt-2 text-sm text-ink-2">{error}</p>
            <Link href="/settings#anime" className="mt-6 inline-block text-sm font-medium text-accent">
              ← Back to Settings
            </Link>
          </>
        ) : (
          <p className="text-sm text-ink-3">Connecting {name}…</p>
        )}
      </main>
    </>
  );
}
