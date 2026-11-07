export type Settings = { language: string; region: string; theme: "system" | "light" | "dark" };
export type User = { id: string; email: string; isAdmin: boolean; settings: Settings };
export type Profile = { id: string; name: string; color: ProfileColor };
export const PROFILE_COLORS = ["teal", "amber", "rose", "violet", "sky", "lime", "orange", "slate"] as const;
export type ProfileColor = (typeof PROFILE_COLORS)[number];

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export async function api<T = unknown>(path: string, init?: { method?: string; body?: unknown }): Promise<T> {
  const res = await fetch(`/api${path}`, {
    method: init?.method ?? "GET",
    headers: init?.body ? { "content-type": "application/json" } : {},
    body: init?.body ? JSON.stringify(init.body) : undefined,
  });
  if (res.status === 204) return undefined as T;
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(res.status, friendly(res.status, data));
  return data as T;
}

// Zylo's own errors are { error } in plain English; framework errors also carry `message`.
function friendly(status: number, data: { error?: string; message?: string }) {
  if (data.error && !data.message) return data.error;
  if (status === 429) return "Too many attempts. Wait a minute and try again.";
  if (status === 400) return "Some of those details aren't valid. Check them and try again.";
  if (status >= 500) return "Zylo couldn't reach its server. Check zylo-core is running.";
  return "Something went wrong.";
}

export function applyTheme(theme: Settings["theme"]) {
  if (theme === "system") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.dataset.theme = theme;
  try {
    localStorage.setItem("zylo-theme", theme);
  } catch {}
}

export function activeProfileId() {
  try {
    return localStorage.getItem("zylo-profile");
  } catch {
    return null;
  }
}

export function setActiveProfileId(id: string | null) {
  try {
    if (id) localStorage.setItem("zylo-profile", id);
    else localStorage.removeItem("zylo-profile");
  } catch {}
}
