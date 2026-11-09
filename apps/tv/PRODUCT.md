# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

Android TV / Google TV, with NVIDIA Shield as the reference device. The app is navigated with a D-pad remote and viewed from the sofa (10-foot UI). Built with Kotlin and Jetpack Compose, minSdk 24 and targetSdk 36.

## Users

Self-hosters and media power users who already run some or all of: Stremio addons (Torrentio, Comet, MediaFusion and others), a debrid service (TorBox, Real-Debrid, Premiumize), Trakt, AniList or MyAnimeList, Plex or Jellyfin, and an Xtream Codes Live TV provider. They usually juggle several apps for these services. They are comfortable pasting API keys and Client IDs, but they use Zylo at the TV with a remote, often with other people in the room.

Some households have several viewers. Each person has a profile, and children use rating-limited kids profiles.

## Product Purpose

Zylo is a standalone Android TV app. It brings discovery, tracking and playback for a viewer's whole media world into one interface, so they can find something worth watching and press Play without switching apps. Success means the user stops opening Stremio, the Trakt app, the Live TV app and the Plex app separately, and that discovery feels like a reason to open Zylo rather than a menu to get past.

## Positioning

Zylo competes on two things together:

1. **One place for everything.** Addon and Syncler sources, debrid playback, Plex and Jellyfin libraries, Live TV with a programme guide and sports fixtures, Trakt sync and scrobbling, and AniList/MyAnimeList progress all sit behind one interface. Settings are configured once on the TV and work across profiles.
2. **Discovery that delights.** Purpose-built ways to find something to watch: Movie Night Match, Rabbit Hole, Deep Cuts, Franchise Journeys, Double Feature, Moodboard, Help Me Choose (Pick for Me / This or That), Watch Challenges, Time Machine, Blind Date and Zylo Wrapped. Generic aggregators and players don't treat discovery as a feature in its own right.

## Operating Context

- Used from the sofa with a D-pad remote: focus, Up/Down/Left/Right, OK and Back. Text entry is painful, so long values (addon links, tokens) can come from a phone through a QR code, or from the Google TV phone keyboard.
- Often a shared screen. Movie Night Match has everyone in the room vote on their phones.
- There is no Zylo server. The app talks directly to TMDB, fanart.tv, Trakt, AniList, MyAnimeList, addons, debrid services, Plex, Jellyfin, Xtream providers, epgshare01, ESPN, IntroDB, AniSkip and Wikipedia. Keys and sign-ins are stored on the TV.
- Distributed as sideloaded APKs from the repository, with an in-app update check.
- A first-launch guided setup covers TMDB, accounts, debrid, addons and Live TV.

## Capabilities and Constraints

- Shipped features are listed in `apps/tv/README.md` (Watching, Playing, Discover, Live TV, Your accounts, Everything else). That README is the authoritative feature list.
- TMDB is required. Everything else is optional, and the UI has to behave well when a service isn't connected.
- Users bring their own keys and accounts. Zylo ships with no addons and hosts no content.
- Playback uses Media3 and sources resolve through debrid. Choose Source shows only what Zylo actually knows about each source.
- Kids profiles hide Live TV and filter by rating (U, PG, 12, 15). Grown-up profiles and Settings can be protected by 4-digit PINs.
- Navigation conventions are committed: rows keep the focused title at the left, the active row is centred vertically, Up/Down keep the horizontal position, every row ends in Show More, and Back goes to the top bar on main pages and one step back everywhere else.
- Not yet built: Emby, notifications, sharing, and Xtream films and series.
- A sibling self-hosted web app lives in `apps/web` with its own server stack. This record doesn't cover it.

## Brand Commitments

- Name: **Zylo**. Tagline in the repository: "One interface. Your entire media world."
- The mark and wordmark are Alex's own artwork (`res/drawable/zylo_mark.xml`, `zylo_wordmark.xml`), along with the app icon and TV banner. Use them as they are.
- Visual direction comes from an external document, *Zylo – Design System & Android TV UI Guidelines*, and a concept image. Neither file is in this repository. The current tokens live in `Theme.kt` (`object Z`), and the font is Inter.
- Voice: plain, direct, second-person British English ("Choose Source", "My List", "Off means off"). Copy explains limits honestly instead of hiding them.

## Evidence on Hand

- TMDB attribution is required wherever TMDB data appears.
- There are no testimonials, user counts, press coverage or benchmarks. Don't invent any.

## Product Principles

1. **One remote, one app.** Anything a user would otherwise switch apps for belongs inside Zylo, reachable with a D-pad.
2. **Discovery is a destination.** Discovery features should feel worth opening Zylo for, not like extra rows bolted on.
3. **Honest about sources.** Show only what is actually known about a stream or service, and say plainly when something can't play or isn't connected.
4. **The user's keys, the user's TV.** Credentials stay on the device and go only to the service they belong to.
5. **Focus is never lost.** Predictable focus movement and Back behaviour come before visual flourish.

## Accessibility & Inclusion

- Readable at TV distance. Every focusable element needs an unmistakable focus state, because focus is the only cursor.
- Subtitles are configurable (size, background, position, timing offset). Audio language is set per profile, and anime offers dubs or the original audio with subtitles.
- Kids profiles and PINs protect younger viewers.
