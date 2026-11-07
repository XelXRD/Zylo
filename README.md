# Zylo

One interface. Your entire media world.

Self-hosted media discovery, tracking and playback. Built so far: **Phase 1, Core** (accounts, profiles, settings, Docker stack), **Phase 2, Metadata** (TMDB search, film and series pages, seasons and episodes, optional TheTVDB episode orders) and **Phase 3, Trakt** (per-profile history, ratings, watchlist and lists, synced both ways). The full plan is in the master specification.

## Run it

Needs Docker with Compose.

```sh
cp .env.example .env        # then set POSTGRES_PASSWORD and ZYLO_SECRET
docker compose up -d --build
```

Open http://localhost:3000. The first visit asks you to create the admin account; your name becomes the first profile.

Then connect metadata: **Settings > Integrations > TMDB**. Create a free account at themoviedb.org, open Settings > API, and paste the *API Read Access Token*. TheTVDB is optional and adds DVD, absolute and other episode orders.

To use Trakt: create an app at trakt.tv/oauth/applications with the redirect URI `urn:ietf:wg:oauth:2.0:oob`, paste its Client ID and Secret under **Settings > Integrations > Trakt**, then each profile connects its own account under **Settings > Trakt** with a short code.

Update later with `docker compose up -d --build`. Database migrations run automatically when `zylo-core` starts.

**Upgrading from Phase 1:** add a `ZYLO_SECRET` line to your `.env` first (see `.env.example`), or Compose will refuse to start.

## What's running

| Service | Role |
|---|---|
| `zylo-web` | Next.js app on port 3000. Proxies `/api/*` to `zylo-core`, so the browser only talks to one address |
| `zylo-core` | Fastify API: accounts, profiles, settings, integrations, metadata. OpenAPI at `/api/openapi.json` |
| `zylo-worker` | BullMQ background worker (same image as `zylo-core`). Every 15 minutes: syncs Trakt for each connected profile, and fills in or refreshes title details. Waits for `zylo-core` to be healthy |
| `postgres`, `redis` | Data and queue |

Only port 3000 is exposed. Put Zylo behind HTTPS (for example Caddy) before exposing it to the internet, then set `COOKIE_SECURE=true`.

## Develop

```sh
pnpm install
cp .env.example .env          # point DATABASE_URL / REDIS_URL at local services
cd apps/api && pnpm exec prisma migrate deploy && pnpm generate && cd ../..   # needs ZYLO_SECRET in .env too
pnpm dev:api & pnpm dev:worker & pnpm dev:web
```

Tests (need an empty, migrated Postgres database; it is wiped):

```sh
TEST_DATABASE_URL=postgresql://zylo:zylo@localhost:5432/zylo_test pnpm test
```

## Phase 1 decisions

- Sessions are random 256-bit tokens in an `HttpOnly`, `SameSite=Strict` cookie; only their SHA-256 is stored. Passwords use scrypt from Node's standard library.
- The first account is the admin, and setup closes after that. Everyone else in the home uses profiles (up to 8).
- Sign-in, setup and password change are rate-limited to 10 attempts a minute.
- Changing your password signs out every other device.
- shadcn/ui isn't installed yet: Phase 1 needs only a handful of components, which live in `apps/web/lib/ui.tsx`. Add it when a screen needs dialogs, menus or comboboxes.

## Phase 2 decisions

- **You bring your own keys.** Zylo is self-hosted, so the admin pastes their own TMDB token (free for personal use) and, optionally, a TheTVDB project key plus subscriber PIN. Keys are encrypted with AES-256-GCM using `ZYLO_SECRET` and never sent back to the browser.
- **Metadata language is server-wide** (set on the TMDB card), because titles are stored once and shared by every profile. Changing it re-fetches everything in the background. Region stays per user and picks which age rating you see.
- Films, series, seasons and episodes are stored once as canonical records with their TMDB, TVDB and IMDb IDs. A title is fetched on first view, then served from the database.
- Search and trending go straight to TMDB; trending is cached for an hour.
- TVDB episode orders are shown as TVDB lists them. Linking them to Zylo's own episode records arrives with anime support (Phase 7), where numbering matters most.
- TMDB attribution appears at the foot of every page that shows TMDB data, as their terms require. Their terms also ask for the TMDB logo: drop the official logo from themoviedb.org/about/logos-attribution into the footer before sharing Zylo with anyone.
- Cast and crew live inside each title's record for now; a separate `people` table arrives when person pages do.

## Phase 3 decisions

- **Each profile links its own Trakt account** (device-code sign-in: a short code entered at trakt.tv/activate, no password shared). The admin registers one Trakt app for the server.
- **Zylo keeps its own history, ratings, watchlist and lists** for every profile, so everything works with Trakt disconnected. Disconnecting keeps them.
- **First sync merges both ways:** Trakt data comes in, and anything rated, watched or watchlisted in Zylo beforehand goes up. **After that, Trakt wins** for ratings, watchlist and lists, so removals on trakt.tv disappear here too. Watch history is only ever added to.
- **Changes made in Zylo go to Trakt straight away.** If Trakt is unreachable they wait in an outbox and retry on each sync (up to 20 tries), shown as "changes waiting" in Settings.
- Trakt access tokens last 24 hours; Zylo refreshes them automatically an hour before expiry.
- Imported titles start as placeholders and fill in from TMDB straight after each sync (100 at a time), or instantly when opened.
- Trakt lists are read-only in Zylo; edit them on trakt.tv. Seasons and people in lists are skipped.
- Scrobbling (live progress while playing) arrives with the player in Phase 5.
