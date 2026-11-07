# Zylo

One interface. Your entire media world.

Self-hosted media discovery, tracking and playback. Built so far: **Phase 1, Core** (accounts, profiles, settings, Docker stack), **Phase 2, Metadata** (TMDB search, film and series pages, seasons and episodes, optional TheTVDB episode orders) **Phase 3, Trakt** (per-profile history, ratings, watchlist and lists, synced both ways) **Phase 4, Addons** (Stremio-compatible addons: streams on film and episode pages, addon catalogues on the home page) **Phase 5, Playback** (built-in player, automatic source choice, resume on any device, Continue watching, Trakt scrobbling, subtitles) and **Phase 6, Debrid** (TorBox, Real-Debrid and Premiumize play torrent sources as ordinary links) and **Phase 7, Anime** (AniList and MyAnimeList lists, kept up to date as you watch). The full plan is in the master specification.

## Run it

Needs Docker with Compose.

```sh
cp .env.example .env        # then set POSTGRES_PASSWORD and ZYLO_SECRET
docker compose up -d --build
```

Open http://localhost:3000. The first visit asks you to create the admin account; your name becomes the first profile.

Then connect metadata: **Settings > Integrations > TMDB**. Create a free account at themoviedb.org, open Settings > API, and paste the *API Read Access Token*. TheTVDB is optional and adds DVD, absolute and other episode orders.

To use Trakt: create an app at app.trakt.tv/settings/apps with `http://localhost` as the Redirect URI (Zylo never visits it), paste its Client ID (there is no secret) under **Settings > Integrations > Trakt**, then each profile connects its own account under **Settings > Trakt** with a short code at auth.trakt.tv/activate. Profiles connected before Trakt's October 2026 sign-in change need to connect once more.

For anime tracking: an admin adds an AniList and/or MyAnimeList app under **Settings > Integrations** (each card shows the exact Redirect URL to register, plus where to create the app), then each profile connects under **Settings > Anime**.

To play torrent sources from your addons, connect a debrid service you subscribe to under **Settings > Debrid**: paste the API key from torbox.app/settings, real-debrid.com/apitoken or premiumize.me/account.

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

- **Each profile links its own Trakt account** (device-code sign-in: a short code entered at auth.trakt.tv/activate, no password shared). The admin registers one Trakt app for the server. Only the Client ID is needed: Trakt's device-code flow, like PKCE, never uses a secret (a secret is still sent if an older app has one). PKCE itself needs a browser redirect back to Zylo, which Trakt only allows to localhost, so code sign-in is the one that works from a TV or another device. All sign-in calls go to auth.trakt.tv; access lasts 7 days and refresh tokens are single-use.
- **Zylo keeps its own history, ratings, watchlist and lists** for every profile, so everything works with Trakt disconnected. Disconnecting keeps them.
- **First sync merges both ways:** Trakt data comes in, and anything rated, watched or watchlisted in Zylo beforehand goes up. **After that, Trakt wins** for ratings, watchlist and lists, so removals on trakt.tv disappear here too. Watch history is only ever added to.
- **Changes made in Zylo go to Trakt straight away.** If Trakt is unreachable they wait in an outbox and retry on each sync (up to 20 tries), shown as "changes waiting" in Settings.
- Trakt access tokens last 24 hours; Zylo refreshes them automatically an hour before expiry.
- Imported titles start as placeholders and fill in from TMDB straight after each sync (100 at a time), or instantly when opened.
- Trakt lists are read-only in Zylo; edit them on trakt.tv. Seasons and people in lists are skipped.
- Scrobbling (live progress while playing) arrives with the player in Phase 5.

## Phase 4 decisions

- **Zylo ships with no addons.** Add your own under **Settings > Addons** by pasting the addon's link: usually ending in `manifest.json`, but any `.json` file name works, as do `stremio://` links. Links that aren't Stremio manifests (such as Syncler vendor lists) are recognised and explained; Syncler support arrives in Phase 8. Addons are shared by every profile on the account.
- **Addon links are treated like passwords.** Configured addons usually carry your account keys in the link, so it's encrypted at rest and only the website's name is ever shown. Reinstalling the same addon replaces its link (that's how reconfiguring works); **Configure** opens the addon's own setup page.
- **Addons can't reach your network.** Every request is checked when it connects, after DNS, and again on each redirect: private, local and cloud-metadata addresses are refused, including tricks where a public name later points somewhere private. Responses are limited to 5 MB, JSON only, with a 10-second limit (15 for streams). No addon code is ever run. Set `ZYLO_ALLOW_PRIVATE_ADDONS=true` only if you host your own addon at home.
- **Streams are fetched only when you ask** ("Find streams" on a film, "Streams" on an episode), from every enabled addon at once, in your chosen order. Each addon is asked with the IDs it says it understands (IMDb `tt…`, or `tmdb:…`). Results are cached for 5 minutes.
- Every stream becomes one standard **playback option** (direct link, YouTube, torrent, website, usenet or archive) with quality, HDR/Dolby Vision and size picked out. Phase 5's player, Phase 6's debrid services and Phase 9's media servers all produce the same shape.
- Direct links, YouTube and websites open now; torrents are listed and wait for a debrid service (Phase 6); usenet and archive sources aren't supported.
- An addon answering "not found" means it has nothing for that title, not that it failed.
- Home shows up to 3 browsable catalogues per addon (search-only catalogues skipped). Items are matched to Zylo's own title pages by IMDb ID; addon-only titles without one aren't shown yet.
- Subtitle addons are used by the player (Phase 5); subtitles attached to streams are already kept.

## Phase 5 decisions

- **The player is the browser's own `<video>`**, so keyboard shortcuts, picture-in-picture, full screen and accessibility come for free. Zylo doesn't transcode: MP4/WebM play everywhere; MKV and HEVC depend on your browser, and Zylo says so plainly when one won't play, with **Try the next source** and **Open in VLC** (a small playlist file VLC opens).
- **Choosing a source:** direct links only (YouTube entries are usually trailers; websites can't be embedded), in this order: the source you last picked for that title; files your browser can play; quality at or under your profile's limit (default 1080p), best first; then your addon order. Each profile sets its limit, and whether to start automatically, under **Settings > Playback**.
- **Progress** is saved every 15 seconds, on pause, and when you leave the page, per profile on the server, so **Resume** works on any device. Over a minute in and not finished = **Continue watching** on Home.
- **Finished = 90% watched.** That's logged once per viewing (credits rolling don't count again; starting over from the beginning begins a new viewing).
- **Trakt scrobbling:** play and pause show live on Trakt; at 90% Zylo sends one "stop", which Trakt records as a play. If Trakt is down, the play goes to the outbox instead. When Trakt's copy comes back on the next sync, it's recognised as the same viewing (within 10 minutes) and not duplicated.
- **Next episode:** when an episode ends, **Play next episode** prefers the same source group (Stremio's `bingeGroup`), so you stay on the same addon and quality.
- **Subtitles** come from the stream itself and from any subtitle addons, fetched through Zylo with the same network protections, converted from SRT to WebVTT, and Latin-1 files decoded properly.
- Not yet: progress from external players (VLC can't report back), streams that need custom headers, and HLS where the browser has no native support.

## Phase 6 decisions

- **Per account, by API key.** Each household account connects TorBox, Real-Debrid and/or Premiumize with the key from the service's own page. Zylo checks the key with the service before saving, stores it encrypted, and never sends it back to the browser. Settings shows Premium status and the expiry date live.
- **Nothing downloads to Zylo's server.** A torrent source becomes playable only when a connected service can stream it. Zylo asks each service which torrents it already has (TorBox and Premiumize can say; Real-Debrid switched that off in 2024, so its sources are tried after known-ready ones, and it says plainly if it has to download first).
- **Links are fetched only when you press Play**, because debrid links expire. Zylo picks the right file in the torrent: the file the addon named, then its file number, then the episode (S01E02 or 1x02), then the largest video. A torrent already in your debrid account is reused rather than added again (TorBox limits adds to 50 an hour).
- **Real-Debrid:** Zylo selects every video file, so a season pack is ready for every episode, not just the one you played first.
- Torrents no connected service has ready are hidden from the player, with a line saying how many.
- Not yet: Real-Debrid's device sign-in (an API key does the same job and never expires), and showing or managing what's in each debrid account's library.

## Phase 7 decisions

- **Per profile, either or both.** Each profile connects AniList and/or MyAnimeList and picks which one Zylo updates (the *primary*); both lists come into Zylo and show under **Library > Anime** by status, with progress.
- **Sign-in comes back to Zylo.** The admin registers one app per service with Zylo's own address (`/connect/anilist`, `/connect/mal`); Settings shows the exact URL to copy. AniList uses its implicit grant (Client ID only; the token lasts a year). MyAnimeList uses PKCE with an "other"-type app (Client ID only; tokens refresh monthly). The PKCE verifier travels inside an encrypted state value, so nothing half-finished is stored, and a sign-in started on one profile can't finish on another.
- **Matching uses Kometa's anime ID list** (AniDB → AniList, MAL, TVDB with season and episode offset, IMDb), refreshed daily from GitHub. Series match by TVDB, films by IMDb, then to TMDB for Zylo's pages. Anime episode numbers are never assumed to equal TMDB or TVDB ones: a split-cour season (Attack on Titan season 3 is two AniList entries, episodes 1-12 and 13-22) maps to the right entry and episode, and long absolute-numbered shows work too.
- **With TheTVDB connected**, Zylo translates TMDB's episode numbering to TVDB's by air date before matching, which covers shows where the two number seasons differently. Without it, Zylo assumes they agree, which holds for most anime.
- **Finishing an episode updates the primary tracker once.** Progress only moves forward to the episode just finished and is sent as an absolute number, so repeats, rewatches of earlier episodes and retries never double-count. Reaching the last episode marks it completed. If the tracker is down, the progress waits in Zylo and goes on the next sync (every 30 minutes, or **Sync now**).
- **The tracker wins on import** for status and score; only progress Zylo hasn't sent yet is kept over it. Removing a title on the tracker removes it here.
- Also fixed: marking the same episode watched twice in one second (a double-click) no longer errors, and the Trakt and Anime sections in Settings update as soon as an admin saves the apps further down the page.
- Not yet: editing anime status or score from Zylo, manga, tracker stats and activity, and a manual "this is the right match" override when Kometa's list is wrong.
