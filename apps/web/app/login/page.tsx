"use client";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { api } from "@/lib/api";
import { AuthCard, Button, Field, FormError } from "@/lib/ui";

export default function LoginPage() {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setBusy(true);
    setError(undefined);
    try {
      await api("/auth/login", { method: "POST", body: { email: f.get("email"), password: f.get("password") } });
      router.replace("/");
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <AuthCard title="Sign in" lead="Welcome back. Sign in to pick up where you left off.">
      <form onSubmit={submit} className="flex flex-col gap-5">
        <Field label="Email" name="email" type="email" required autoComplete="email" autoFocus />
        <Field label="Password" name="password" type="password" required autoComplete="current-password" />
        <FormError message={error} />
        <Button type="submit" busy={busy} className="mt-2">
          Sign in
        </Button>
      </form>
    </AuthCard>
  );
}
