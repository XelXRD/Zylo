# Zylo for Android TV

A standalone native app for Android TV / NVIDIA Shield. No server: it talks to TMDB, your Stremio addons and your debrid service straight from the TV.

- **Home:** rows of films and TV from TMDB (in cinemas, trending, popular, top rated, genres) plus Continue watching. Rest on a poster for a second and its trailer starts playing behind the details.
- **Title page:** details, Play / Resume for films, seasons and episodes for series.
- **Sources:** asks every Stremio addon you've added, marks torrents your debrid service (TorBox, Real-Debrid, Premiumize) can stream, best first.
- **Player:** Media3 ExoPlayer, so MKV, HEVC, HDR and surround passthrough work as the Shield allows. Remembers where you got to.

First launch: Settings > TMDB key (free from themoviedb.org > Settings > API; the 32-character API key is easiest to type). Then add an addon's manifest link and a debrid key. Typing is easiest with the Google TV app's keyboard on your phone.

## Build

Needs JDK 17+ and the Android SDK (Android Studio installs both). `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`), then the APK is in `build/outputs/apk/debug/`. Tests: `./gradlew testDebugUnitTest`.

Signed with `debug.keystore` (same key as the old WebView build), so it installs over it. Install from a PC with `adb install -r zylo-tv-debug.apk`, or copy it to the Shield and open it with a file manager.

## Not yet

Trakt and AniList/MyAnimeList sync, search, profiles, subtitles from addons, a quality cap (it picks the best quality first, 4K included).
