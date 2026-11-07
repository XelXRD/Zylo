"use client";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, setActiveProfileId, type Profile } from "@/lib/api";
import { AuthCard, Button, Field, FormError } from "@/lib/ui";

export default function SetupPage() {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  useEffect(() => {
    api<{ needsSetup: boolean }>("/setup").then((r) => !r.needsSetup && router.replace("/login"), () => {});
  }, [router]);

  async function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    setBusy(true);
    setError(undefined);
    try {
      await api("/setup", { method: "POST", body: { name: f.get("name"), email: f.get("email"), password: f.get("password") } });
      const [first] = await api<Profile[]>("/profiles");
      setActiveProfileId(first?.id ?? null);
      router.replace("/home");
    } catch (err) {
      setError((err as Error).message);
      setBusy(false);
    }
  }

  return (
    <AuthCard title="Set up Zylo" lead="Create the admin account for this server. You can add profiles for everyone in your home afterwards.">
      <form onSubmit={submit} className="flex flex-col gap-5">
        <Field label="Your name" name="name" required maxLength={32} autoComplete="given-name" hint="Becomes your first profile." />
        <Field label="Email" name="email" type="email" required autoComplete="email" />
        <Field label="Password" name="password" type="password" required minLength={8} autoComplete="new-password" hint="At least 8 characters." />
        <FormError message={error} />
        <Button type="submit" busy={busy} className="mt-2">
          Create account
        </Button>
      </form>
    </AuthCard>
  );
}
