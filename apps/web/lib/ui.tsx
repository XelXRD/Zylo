"use client";
import Link from "next/link";
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from "react";
import { useId } from "react";
import type { ProfileColor } from "./api";

const cx = (...c: (string | false | undefined)[]) => c.filter(Boolean).join(" ");

const focusMotion = "transition-[background-color,border-color,color,transform] duration-150 ease-out";

export function Button({
  variant = "primary",
  busy,
  className,
  children,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "quiet" | "danger"; busy?: boolean }) {
  return (
    <button
      {...props}
      disabled={props.disabled || busy}
      aria-busy={busy || undefined}
      className={cx(
        "inline-flex h-10 items-center justify-center gap-2 rounded-lg px-4 text-sm font-medium",
        "active:translate-y-px disabled:cursor-not-allowed disabled:opacity-50",
        focusMotion,
        variant === "primary" && "bg-accent text-accent-ink hover:bg-accent-hover",
        variant === "quiet" && "border border-line bg-surface text-ink hover:border-line-strong hover:bg-sunken",
        variant === "danger" && "border border-line bg-surface text-danger hover:border-danger",
        className,
      )}
    >
      {busy ? "Working…" : children}
    </button>
  );
}

export function Field({
  label,
  hint,
  error,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & { label: string; hint?: string; error?: string }) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-ink">
        {label}
      </label>
      <input
        id={id}
        aria-describedby={hint || error ? `${id}-note` : undefined}
        aria-invalid={error ? true : undefined}
        {...props}
        className={cx(
          "h-10 rounded-lg border bg-surface px-3 text-sm text-ink placeholder:text-ink-3",
          "hover:border-line-strong disabled:opacity-50",
          error ? "border-danger" : "border-line",
          focusMotion,
        )}
      />
      {(error || hint) && (
        <p id={`${id}-note`} className={cx("text-xs", error ? "text-danger" : "text-ink-3")}>
          {error ?? hint}
        </p>
      )}
    </div>
  );
}

export function Select({ label, children, ...props }: SelectHTMLAttributes<HTMLSelectElement> & { label: string }) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-sm font-medium text-ink">
        {label}
      </label>
      <select
        id={id}
        {...props}
        className={cx("h-10 rounded-lg border border-line bg-surface px-3 text-sm text-ink hover:border-line-strong", focusMotion)}
      >
        {children}
      </select>
    </div>
  );
}

export function FormError({ message }: { message?: string }) {
  if (!message) return null;
  return (
    <p role="alert" className="rounded-lg border border-danger/40 px-3 py-2 text-sm text-danger">
      {message}
    </p>
  );
}

const avatarBg: Record<ProfileColor, string> = {
  teal: "bg-p-teal",
  amber: "bg-p-amber",
  rose: "bg-p-rose",
  violet: "bg-p-violet",
  sky: "bg-p-sky",
  lime: "bg-p-lime",
  orange: "bg-p-orange",
  slate: "bg-p-slate",
};

export function Avatar({ name, color, size = "md" }: { name: string; color: ProfileColor; size?: "sm" | "md" | "lg" }) {
  return (
    <span
      aria-hidden
      className={cx(
        "inline-flex shrink-0 select-none items-center justify-center rounded-xl font-semibold text-[#f7f7f4]",
        avatarBg[color],
        size === "sm" && "size-8 rounded-lg text-sm",
        size === "md" && "size-12 text-lg",
        size === "lg" && "size-28 text-4xl sm:size-32",
      )}
    >
      {name.trim().charAt(0).toUpperCase()}
    </span>
  );
}

export function Wordmark() {
  return (
    <Link href="/home" className="rounded text-lg font-semibold tracking-tight text-ink">
      zylo
    </Link>
  );
}

/** Centred single-card layout for setup and sign-in. */
export function AuthCard({ title, lead, children }: { title: string; lead: string; children: ReactNode }) {
  return (
    <main className="flex min-h-dvh items-center justify-center px-4 py-12">
      <div className="w-full max-w-sm">
        <p className="mb-8 text-lg font-semibold tracking-tight">zylo</p>
        <h1 className="text-2xl font-semibold leading-tight tracking-tight">{title}</h1>
        <p className="mt-2 text-sm leading-relaxed text-ink-2">{lead}</p>
        <div className="mt-8">{children}</div>
      </div>
    </main>
  );
}

export function Loading() {
  return (
    <main className="flex min-h-dvh items-center justify-center" aria-busy>
      <p className="text-sm text-ink-3">Loading…</p>
    </main>
  );
}
