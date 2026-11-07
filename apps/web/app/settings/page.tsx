"use client";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { activeProfileId, api, applyTheme, PROFILE_COLORS, setActiveProfileId, type Integration, type Profile, type ProfileColor, type Settings } from "@/lib/api";
import { useUser } from "@/lib/session";
import { TopBar } from "@/lib/topbar";
import { Avatar, Button, Field, FormError, Loading, Select } from "@/lib/ui";

const LANGUAGES = ["en-GB", "en-US", "de-DE", "es-ES", "fr-FR", "it-IT", "ja-JP", "pt-BR"];
const REGIONS = ["GB", "IE", "US", "CA", "AU", "DE", "ES", "FR", "IT", "JP", "BR"];
const langName = new Intl.DisplayNames(["en-GB"], { type: "language" });
const regionName = new Intl.DisplayNames(["en-GB"], { type: "region" });

export default function SettingsPage() {
  const { user, setUser, error } = useUser();
  const [profiles, setProfiles] = useState<Profile[]>();

  useEffect(() => {
    if (user) api<Profile[]>("/profiles").then(setProfiles);
  }, [user]);

  if (error) return <main className="p-8 text-sm text-danger">{error}</main>;
  if (!user || !profiles) return <Loading />;

  return (
    <>
      <TopBar />
      <main className="mx-auto max-w-3xl px-6 pb-24 pt-12">
        <h1 className="text-2xl font-semibold leading-tight tracking-tight">Settings</h1>

        <Section id="profiles" title="Profiles" lead="Everyone at home gets their own watch history and lists. Up to 8 profiles.">
          <ul className="divide-y divide-line rounded-xl border border-line bg-surface">
            {profiles.map((p) => (
              <ProfileRow
                key={p.id}
                profile={p}
                canDelete={profiles.length > 1}
                onChange={(next) => setProfiles(profiles.map((x) => (x.id === p.id ? next : x)))}
                onDelete={() => {
                  if (activeProfileId() === p.id) setActiveProfileId(null);
                  setProfiles(profiles.filter((x) => x.id !== p.id));
                }}
              />
            ))}
          </ul>
          {profiles.length < 8 && <AddProfile onAdd={(p) => setProfiles([...profiles, p])} />}
        </Section>

        <Section title="Preferences" lead="Your region decides which age ratings you see, and later what's available where you live.">
          <Preferences settings={user.settings} onSaved={(settings) => setUser({ ...user, settings })} />
        </Section>

        {user.isAdmin && (
          <Section id="integrations" title="Integrations" lead="Server-wide connections. Keys are encrypted and never shown again after saving.">
            <Integrations />
          </Section>
        )}

        <Section title="Account" lead={`Signed in as ${user.email}${user.isAdmin ? " · server admin" : ""}.`}>
          <ChangePassword />
          <SignOut />
        </Section>
      </main>
    </>
  );
}

function Section({ id, title, lead, children }: { id?: string; title: string; lead: string; children: React.ReactNode }) {
  return (
    <section id={id} className="mt-14 scroll-mt-20">
      <h2 className="text-lg font-semibold">{title}</h2>
      <p className="mt-1 max-w-[65ch] text-sm leading-relaxed text-ink-2">{lead}</p>
      <div className="mt-5 flex flex-col gap-4">{children}</div>
    </section>
  );
}

function ColorPicker({ value, onChange, name }: { value: ProfileColor; onChange: (c: ProfileColor) => void; name: string }) {
  return (
    <fieldset>
      <legend className="mb-2 text-sm font-medium">Colour</legend>
      <div className="flex flex-wrap gap-2">
        {PROFILE_COLORS.map((c) => (
          <label key={c} className="cursor-pointer">
            <input type="radio" name={name} value={c} checked={value === c} onChange={() => onChange(c)} className="peer sr-only" />
            <span className="block rounded-lg outline-2 outline-offset-2 outline-transparent transition-[outline-color] duration-150 peer-checked:outline-ink peer-focus-visible:outline-[var(--focus)]">
              <Avatar name=" " color={c} size="sm" />
            </span>
            <span className="sr-only">{c}</span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}

function ProfileRow({ profile, canDelete, onChange, onDelete }: { profile: Profile; canDelete: boolean; onChange: (p: Profile) => void; onDelete: () => void }) {
  const [mode, setMode] = useState<"view" | "edit" | "confirm">("view");
  const [name, setName] = useState(profile.name);
  const [color, setColor] = useState(profile.color);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError(undefined);
    try {
      await fn();
    } catch (e) {
      setError((e as Error).message);
    }
    setBusy(false);
  };

  if (mode === "edit")
    return (
      <li className="flex flex-col gap-4 p-4">
        <Field label="Name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={32} />
        <ColorPicker value={color} onChange={setColor} name={`color-${profile.id}`} />
        <FormError message={error} />
        <div className="flex gap-2">
          <Button
            busy={busy}
            disabled={!name.trim()}
            onClick={() =>
              run(async () => {
                onChange(await api<Profile>(`/profiles/${profile.id}`, { method: "PATCH", body: { name, color } }));
                setMode("view");
              })
            }
          >
            Save
          </Button>
          <Button variant="quiet" onClick={() => (setName(profile.name), setColor(profile.color), setError(undefined), setMode("view"))}>
            Cancel
          </Button>
        </div>
      </li>
    );

  return (
    <li className="flex items-center gap-4 p-4">
      <Avatar name={profile.name} color={profile.color} />
      <span className="min-w-0 flex-1 truncate font-medium">{profile.name}</span>
      {mode === "confirm" ? (
        <div className="flex items-center gap-2">
          <span className="hidden text-sm text-ink-2 sm:inline">Delete {profile.name} and their history?</span>
          <Button variant="danger" busy={busy} onClick={() => run(async () => (await api(`/profiles/${profile.id}`, { method: "DELETE" }), onDelete()))}>
            Delete
          </Button>
          <Button variant="quiet" onClick={() => setMode("view")}>
            Keep
          </Button>
        </div>
      ) : (
        <div className="flex gap-2">
          <Button variant="quiet" onClick={() => setMode("edit")}>
            Edit
          </Button>
          {canDelete && (
            <Button variant="quiet" onClick={() => setMode("confirm")} aria-label={`Delete ${profile.name}`}>
              Delete
            </Button>
          )}
        </div>
      )}
      {error && <FormError message={error} />}
    </li>
  );
}

function AddProfile({ onAdd }: { onAdd: (p: Profile) => void }) {
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [color, setColor] = useState<ProfileColor>("amber");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  if (!open)
    return (
      <div>
        <Button variant="quiet" onClick={() => setOpen(true)}>
          Add profile
        </Button>
      </div>
    );

  return (
    <form
      className="flex flex-col gap-4 rounded-xl border border-line bg-surface p-4"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setError(undefined);
        try {
          onAdd(await api<Profile>("/profiles", { method: "POST", body: { name, color } }));
          setName("");
          setOpen(false);
        } catch (err) {
          setError((err as Error).message);
        }
        setBusy(false);
      }}
    >
      <Field label="Name" value={name} onChange={(e) => setName(e.target.value)} required maxLength={32} autoFocus />
      <ColorPicker value={color} onChange={setColor} name="new-color" />
      <FormError message={error} />
      <div className="flex gap-2">
        <Button type="submit" busy={busy}>
          Add profile
        </Button>
        <Button type="button" variant="quiet" onClick={() => setOpen(false)}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

function Preferences({ settings, onSaved }: { settings: Settings; onSaved: (s: Settings) => void }) {
  const [status, setStatus] = useState<string>();

  const save = async (patch: Partial<Settings>) => {
    setStatus("Saving…");
    try {
      const next = await api<Settings>("/settings", { method: "PUT", body: patch });
      if (patch.theme) applyTheme(next.theme);
      onSaved(next);
      setStatus("Saved");
    } catch (e) {
      setStatus((e as Error).message);
    }
  };

  return (
    <div className="grid gap-4 rounded-xl border border-line bg-surface p-4 sm:grid-cols-2">
      <Select label="Theme" value={settings.theme} onChange={(e) => save({ theme: e.target.value as Settings["theme"] })}>
        <option value="system">Match device</option>
        <option value="dark">Dark</option>
        <option value="light">Light</option>
      </Select>
      <Select label="Region" value={settings.region} onChange={(e) => save({ region: e.target.value })}>
        {REGIONS.map((r) => (
          <option key={r} value={r}>
            {regionName.of(r)}
          </option>
        ))}
      </Select>
      <p role="status" className="text-xs text-ink-3 sm:col-span-2">
        {status ?? "Changes save automatically."}
      </p>
    </div>
  );
}

function ChangePassword() {
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string }>();

  return (
    <form
      className="flex flex-col gap-4 rounded-xl border border-line bg-surface p-4"
      onSubmit={async (e) => {
        e.preventDefault();
        const form = e.currentTarget;
        const f = new FormData(form);
        setBusy(true);
        setMsg(undefined);
        try {
          await api("/auth/password", { method: "PUT", body: { currentPassword: f.get("current"), newPassword: f.get("next") } });
          form.reset();
          setMsg({ ok: true, text: "Password changed. Other devices have been signed out." });
        } catch (err) {
          setMsg({ ok: false, text: (err as Error).message });
        }
        setBusy(false);
      }}
    >
      <h3 className="text-sm font-semibold">Change password</h3>
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Current password" name="current" type="password" required autoComplete="current-password" />
        <Field label="New password" name="next" type="password" required minLength={8} autoComplete="new-password" hint="At least 8 characters." />
      </div>
      {msg && (msg.ok ? <p role="status" className="text-sm text-accent">{msg.text}</p> : <FormError message={msg.text} />)}
      <div>
        <Button type="submit" variant="quiet" busy={busy}>
          Change password
        </Button>
      </div>
    </form>
  );
}

function SignOut() {
  const router = useRouter();
  return (
    <div>
      <Button
        variant="danger"
        onClick={async () => {
          await api("/auth/logout", { method: "POST" }).catch(() => {});
          setActiveProfileId(null);
          router.replace("/login");
        }}
      >
        Sign out
      </Button>
    </div>
  );
}

function Integrations() {
  const [list, setList] = useState<Integration[]>();
  const reload = () => api<Integration[]>("/integrations").then(setList);
  useEffect(() => {
    reload();
  }, []);
  if (!list) return <p className="text-sm text-ink-3">Loading…</p>;
  const tmdb = list.find((i) => i.id === "tmdb")!;
  const tvdb = list.find((i) => i.id === "tvdb")!;
  return (
    <>
      <TmdbCard integration={tmdb} onChange={reload} />
      <TvdbCard integration={tvdb} onChange={reload} />
    </>
  );
}

function StatusLine({ integration }: { integration: Integration }) {
  if (!integration.connected) return <span className="text-xs text-ink-3">Not connected</span>;
  if (integration.lastError) return <span className="text-xs text-danger">Connected, but the last check failed: {integration.lastError}</span>;
  const when = integration.lastCheckedAt ? new Date(integration.lastCheckedAt).toLocaleString("en-GB", { dateStyle: "medium", timeStyle: "short" }) : null;
  return <span className="text-xs text-accent">Connected{when ? ` · checked ${when}` : ""}</span>;
}

function IntegrationCard({ name, blurb, integration, children }: { name: string; blurb: React.ReactNode; integration: Integration; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-4 rounded-xl border border-line bg-surface p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="font-semibold">{name}</h3>
        <StatusLine integration={integration} />
      </div>
      <p className="max-w-[65ch] text-sm leading-relaxed text-ink-2">{blurb}</p>
      {children}
    </div>
  );
}

function useAction(onDone: () => void) {
  const [busy, setBusy] = useState<string>();
  const [msg, setMsg] = useState<{ ok: boolean; text: string }>();
  const run = async (key: string, fn: () => Promise<string | void>) => {
    setBusy(key);
    setMsg(undefined);
    try {
      const text = await fn();
      if (text) setMsg({ ok: true, text });
      onDone();
    } catch (e) {
      setMsg({ ok: false, text: (e as Error).message });
    }
    setBusy(undefined);
  };
  const feedback = msg && (msg.ok ? <p role="status" className="text-sm text-accent">{msg.text}</p> : <FormError message={msg.text} />);
  return { busy, run, feedback };
}

function DisconnectButtons({ id, connected, run, busy }: { id: string; connected: boolean; run: ReturnType<typeof useAction>["run"]; busy?: string }) {
  if (!connected) return null;
  return (
    <>
      <Button
        type="button"
        variant="quiet"
        busy={busy === "test"}
        onClick={() =>
          run("test", async () => {
            const r = await api<{ ok: boolean; error: string | null }>(`/integrations/${id}/test`, { method: "POST" });
            if (!r.ok) throw new Error(r.error ?? "The check failed.");
            return "Connection works.";
          })
        }
      >
        Test connection
      </Button>
      <Button type="button" variant="danger" busy={busy === "remove"} onClick={() => run("remove", async () => void (await api(`/integrations/${id}`, { method: "DELETE" })))}>
        Disconnect
      </Button>
    </>
  );
}

function TmdbCard({ integration, onChange }: { integration: Integration; onChange: () => void }) {
  const [token, setToken] = useState("");
  const [language, setLanguage] = useState(integration.settings.language ?? "en-GB");
  const { busy, run, feedback } = useAction(onChange);
  return (
    <IntegrationCard
      name="TMDB"
      integration={integration}
      blurb={
        <>
          Films, series, posters and trailers. Free for personal use: create an account at themoviedb.org, then copy the{" "}
          <strong className="font-medium text-ink">API Read Access Token</strong> from Settings &gt; API.
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          run("save", async () => {
            await api("/integrations/tmdb", { method: "PUT", body: token ? { token, language } : { language } });
            setToken("");
            return token ? "TMDB connected." : "Language saved. Titles update in the background over the next hour.";
          });
        }}
      >
        <div className="grid gap-4 sm:grid-cols-[1fr_14rem]">
          <Field
            label={integration.connected ? "Replace token" : "API Read Access Token"}
            type="password"
            autoComplete="off"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            required={!integration.connected}
            minLength={20}
            hint={integration.connected ? "Leave blank to keep the current token." : undefined}
          />
          <Select label="Metadata language" value={language} onChange={(e) => setLanguage(e.target.value)}>
            {LANGUAGES.map((l) => (
              <option key={l} value={l}>
                {langName.of(l)}
              </option>
            ))}
          </Select>
        </div>
        {feedback}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" busy={busy === "save"} disabled={integration.connected && !token && language === (integration.settings.language ?? "en-GB")}>
            {integration.connected ? "Save changes" : "Connect TMDB"}
          </Button>
          <DisconnectButtons id="tmdb" connected={integration.connected} run={run} busy={busy} />
        </div>
      </form>
    </IntegrationCard>
  );
}

function TvdbCard({ integration, onChange }: { integration: Integration; onChange: () => void }) {
  const [apikey, setApikey] = useState("");
  const [pin, setPin] = useState("");
  const { busy, run, feedback } = useAction(onChange);
  return (
    <IntegrationCard
      name="TheTVDB"
      integration={integration}
      blurb="Optional. Adds DVD, absolute and other episode orders to series. TVDB issues keys per project: paste your project key, plus your subscriber PIN if your key is user-supported."
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          run("save", async () => {
            await api("/integrations/tvdb", { method: "PUT", body: { apikey, ...(pin && { pin }) } });
            setApikey("");
            setPin("");
            return "TheTVDB connected.";
          });
        }}
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={integration.connected ? "Replace API key" : "API key"} type="password" autoComplete="off" value={apikey} onChange={(e) => setApikey(e.target.value)} required minLength={8} />
          <Field label="Subscriber PIN" type="password" autoComplete="off" value={pin} onChange={(e) => setPin(e.target.value)} hint="Only for user-supported keys." />
        </div>
        {feedback}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" busy={busy === "save"}>
            {integration.connected ? "Save key" : "Connect TheTVDB"}
          </Button>
          <DisconnectButtons id="tvdb" connected={integration.connected} run={run} busy={busy} />
        </div>
      </form>
    </IntegrationCard>
  );
}
