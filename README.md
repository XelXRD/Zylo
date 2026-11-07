# Zylo

One interface. Your entire media world.

Self-hosted media discovery, tracking and playback. Built so far: **Phase 1, Core** (accounts, profiles, settings, Docker stack) and **Phase 2, Metadata** (TMDB search, film and series pages, seasons and episodes, optional TheTVDB episode orders). The full plan is in the master specification.

## Run it

Needs Docker with Compose.

```sh
cp .env.example .env        # then set POSTGRES_PASSWORD and ZYLO_SECRET
docker compose up -d --build
```

Open http://localhost:3000. The first visit asks you to create the admin account; your name becomes the first profile.

Then connect metadata: **Settings > Integrations > TMDB**. Create a free account at themoviedb.org, open Settings > API, and paste the *API Read Access Token*. TheTVDB is optional and adds DVD, absolute and other episode orders.

Update later with `docker compose up -d --build`. Database migrations run automatically when `zylo-core` starts.

**Upgrading from Phase 1:** add a `ZYLO_SECRET` line to your `.env` first (see `.env.example`), or Compose will refuse to start.

## What's running

| Service | Role |
|---|---|
| `zylo-web` | Next.js app on port 3000. Proxies `/api/*` to `zylo-core`, so the browser only talks to one address |
| `zylo-core` | Fastify API: accounts, profiles, settings, integrations, metadata. OpenAPI at `/api/openapi.json` |
| `zylo-worker` | BullMQ background worker (same image as `zylo-core`). Hourly: refreshes titles older than 7 days, or in the wrong language |
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
