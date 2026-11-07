"use client";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, ApiError, applyTheme, type User } from "./api";

/** Loads the signed-in user, sending them to /setup or /login when needed. */
export function useUser() {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);
  const [error, setError] = useState<string>();

  useEffect(() => {
    (async () => {
      try {
        const { needsSetup } = await api<{ needsSetup: boolean }>("/setup");
        if (needsSetup) return router.replace("/setup");
        const me = await api<User>("/auth/me");
        applyTheme(me.settings.theme);
        setUser(me);
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) router.replace("/login");
        else setError(e instanceof Error ? e.message : "Something went wrong.");
      }
    })();
  }, [router]);

  return { user, setUser, error };
}
