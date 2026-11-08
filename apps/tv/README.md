# Zylo for Android TV

A standalone native app for Android TV / NVIDIA Shield. No server: it talks to TMDB, your Stremio addons and your debrid service straight from the TV. Visual direction: *Zylo – Design System & Android TV UI Guidelines* and the concept image.

- **Top navigation** (no global sidebar): Zylo logo, Home, Movies, Series, Anime, Discover, My List, Search, Settings.
- **Home:** featured hero (Play / More Info / My List), Continue Watching, My List, Trending Movies, Popular Series, Popular Anime, Because You Watched…, In Cinemas, New Episodes, Top Rated. Rows only show when they have something in them.
- **Previews:** rest on a poster for 0.8 s (`Z.PreviewDelay`) and it widens into a 16:9 card with the backdrop, title logo and details; then a YouTube trailer (official IFrame Player API) fills the whole card and the text fades away. Muted unless Settings > Playback > Trailer sound is on; previews can be turned off there too. Rows keep the focused card at the left edge, Netflix-style.
- **Movies / Series / Anime:** poster grids with Popular / Top Rated / New Releases and genre filters.
- **Detail page:** poster, age rating, runtime or seasons, rating, Play / Resume, Choose Source, My List, Trailer (opens YouTube), Mark as Watched; tabs for Episodes, Overview (cast, crew, genres) and More Like This.
- **Choose Source:** every addon and debrid source, with only the status Zylo actually knows (Available, Ready, Not checked, Unavailable). Play picks the best and falls back to the next one, saying why.
- **Player:** Media3 ExoPlayer with subtitle/audio track choice, red progress bar, title and source overlay, resume.
- **Settings** (its own sidebar): Playback, Integrations, Addons, Debrid Services, About. A service only shows as Connected after a real test.
- **Trakt:** sign in with a code at trakt.tv/activate (your own Trakt API app's Client ID). Home gets Your Trakt Watchlist, Recommended For You and Popular on Trakt; My Lists shows your watchlist plus lists you made or liked. Watch history and titles paused on other devices sync in (every 15 minutes or Sync Now), playback scrobbles (80%+ counts as watched), Mark as Watched and My List go back up to Trakt.
- **AniList / MyAnimeList:** sign in by scanning a QR code on your phone and pasting the result back (the Google TV phone app's keyboard can paste). Finishing an anime episode moves your progress forward, matched with Kometa's anime ID list (handles split cours). Watching / Plan to Watch lists appear in My Lists.
- **fanart.tv** (optional key): title logos, clean backgrounds and landscape thumbs on Home, cards and detail pages. Without it, TMDB's logos are used where they exist.

Design tokens (colours, type scale, radii, timings) are in `Theme.kt` (`object Z`). Font: Inter. The Z mark is a vector redraw of the concept logo; swap in the original artwork when available.

First launch: Settings > Integrations > TMDB (free key from themoviedb.org > Settings > API; the 32-character API key is easiest to type). Then add an addon and a debrid key. Typing is easiest with the Google TV app's keyboard on your phone.

## Build

Needs JDK 17+ and the Android SDK (Android Studio installs both). `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`), then the APK is in `build/outputs/apk/debug/`. Tests: `./gradlew testDebugUnitTest`.

Signed with `debug.keystore` (same key as earlier builds), so it installs over them. Install with `adb install -r zylo-tv-debug.apk`, or copy it to the Shield and open it with a file manager.

## Not yet

Plex/Jellyfin/Emby, profiles, notifications, rating and sharing, Trakt ratings and custom-list editing, next-episode autoplay, addon subtitles, a quality cap, remembering your last source.
