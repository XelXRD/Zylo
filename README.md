# Zylo

One interface. Your entire media world.

Self-hosted media discovery, tracking and playback. This is **Phase 1: Core**: accounts, profiles, settings and the Docker Compose stack. The full plan is in the master specification.

## Run it

Needs Docker with Compose.

```sh
cp .env.example .env        # then change POSTGRES_PASSWORD
docker compose up -d --build
```

Open http://localhost:3000. The first visit asks you to create the admin account; your name becomes the first profile.

Update later with `git pull && docker compose up -d --build`. Database migrations run automatically when `zylo-core` starts.

## What's running

| Service | Role |
|---|---|
| `zylo-web` | Next.js app on port 3000. Proxies `/api/*` to `zylo-core`, so the browser only talks to one address |
| `zylo-core` | Fastify API: accounts, sessions, profiles, settings. OpenAPI at `/api/openapi.json` |
| `zylo-worker` | BullMQ background worker. No jobs until Phase 2 |
| `postgres`, `redis` | Data and queue |

Only port 3000 is exposed. Put Zylo behind HTTPS (for example Caddy) before exposing it to the internet, then set `COOKIE_SECURE=true`.

## Develop

```sh
pnpm install
cp .env.example .env          # point DATABASE_URL / REDIS_URL at local services
cd apps/api && pnpm exec prisma migrate deploy && pnpm generate && cd ../..
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
