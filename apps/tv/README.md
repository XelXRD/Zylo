# Zylo for Android TV

A standalone native app for Android TV / NVIDIA Shield. No server: it talks to TMDB, your Stremio addons and your debrid service straight from the TV. Visual direction: *Zylo – Design System & Android TV UI Guidelines* and the concept image.

## Connecting your services

Everything is set up on the TV, under **Settings** (the gear in the top bar). A new install walks you through the main ones. Typing is easiest with the Google TV app's keyboard on your phone, and anything long (addon links) can be sent from your phone instead.

| Service | Needed? | Where in Zylo | Where to get it |
| --- | --- | --- | --- |
| **TMDB** | Yes: all film and TV details, artwork and trailers | Settings › Integrations › TMDB | Free account at [themoviedb.org](https://www.themoviedb.org/signup) › Settings › API › create a key, then copy the 32-character **API Key** |
| **fanart.tv** | Optional: title logos and clean artwork | Settings › Integrations › fanart.tv | Free account at [fanart.tv](https://fanart.tv) › your profile › API key (personal key) |
| **Trakt** | Optional: history, progress, watchlist, lists, ratings, Calendar, Wrapped | Settings › Integrations › Trakt | Create an app at [trakt.tv › Settings › Your API Apps](https://trakt.tv/oauth/applications) (Redirect URI: `urn:ietf:wg:oauth:2.0:oob`), paste its **Client ID** (not the secret), then sign in with the code shown at [trakt.tv/activate](https://trakt.tv/activate). Each profile signs in separately. |
| **AniList** | Optional: anime progress and lists | Settings › Integrations › AniList | Create a client at [anilist.co › Settings › Developer](https://anilist.co/settings/developer) with redirect URL `https://anilist.co/api/v2/oauth/pin`, paste the **Client ID**, scan the QR code on your phone and paste back the token it shows |
| **MyAnimeList** | Optional: anime progress and lists | Settings › Integrations › MyAnimeList | Create an app at [myanimelist.net/apiconfig](https://myanimelist.net/apiconfig) (type: *other*, any redirect URL such as `http://localhost`), paste the **Client ID**, scan the QR code and paste back the address the page lands on |
| **TorBox** | One debrid service to play torrent sources | Settings › Debrid Services | API key from [torbox.app/settings](https://torbox.app/settings) |
| **Real-Debrid** | As above | Settings › Debrid Services | API token from [real-debrid.com/apitoken](https://real-debrid.com/apitoken) |
| **Premiumize** | As above | Settings › Debrid Services | API key from [premiumize.me/account](https://www.premiumize.me/account) |
| **Stremio addons** | For sources and subtitles | Settings › Addons › Add from Phone (scan the QR code and paste) or type the link | Configure the addon on its own website (Torrentio, Comet, MediaFusion, OpenSubtitles…), then copy its install link (the one ending in `manifest.json`) |
| **Syncler packages** | Optional, more sources | Settings › Addons, same as above | A Syncler Express package or vendor link |
| **Plex** | Optional: your own library as a source | Settings › Media Servers › Plex | Sign in with the code shown at [plex.tv/link](https://plex.tv/link); your server is found automatically |
| **Jellyfin** | Optional: your own library as a source | Settings › Media Servers › Jellyfin | Your server's address, username and password |
| **Live TV (Xtream Codes)** | Optional | Settings › Live TV | The server address, username and password from your provider (or paste their M3U link and Zylo reads them from it) |
| **Better TV guide** | Optional | Settings › Live TV › Better Guide | Nothing to sign up for: choose your regions from the free [epgshare01](https://epgshare01.online) guides |

Needs nothing from you: sports fixtures (ESPN), Skip Intro times (IntroDB, AniSkip), Time Machine's music and news (Wikipedia), updates (this repository).

**Profiles and PINs:** Settings › Profiles. Each profile has its own history, lists and sign-ins; set a kids profile's rating limit, and give grown-up profiles a 4-digit PIN.

**Your keys stay on the TV.** Zylo has no server: keys and sign-ins are stored in the app and only sent to the service they belong to.

## Features

### Watching
- **Home** with a featured hero (trailer plays after a moment, a new set of titles every 4 hours), Continue Watching (where you stopped plus the next episode of every series you're watching), Starting Soon and On Live TV Now, My List, your Trakt watchlist and recommendations, Because You Watched / Because You Liked, Comfort Rewatch, Under 90 Minutes, Hidden Gems, Something Different, trending and top rated rows.
- **Previews:** rest on a poster and it widens into a card with artwork, then a muted trailer.
- **Rows** keep the focused title at the left; the row you're on sits in the middle of the screen; Up and Down keep your position; every row ends in **Show More**.
- **Movies, Series and Anime** grids with Popular / Top Rated / New and genre filters. Anime series live only in the Anime section.
- **Title pages:** Play / Resume, Choose Source, My List, Trailer, Mark as Watched; Episodes, Overview, Cast & Crew and More Like This tabs; thumbs up or down.
- **Cast & crew pages:** everything a person has been in, with what you've seen ticked.
- **One search** for films, series, anime, your live channels, the TV guide and today's fixtures.

### Playing
- **Sources** from Stremio addons, Syncler Express packages, Plex and Jellyfin, played through TorBox, Real-Debrid or Premiumize. Choose Source shows only what Zylo actually knows about each one.
- **Pick a source for me:** Play picks the best once 1, 3, 5, 10 or 20 sources are found, or never (you always choose). Remembers what played well and prefers the same kind next time. Quality cap (4K / 1080p / 720p).
- **Player:** Media3, controls on OK, Back returns to Choose Source to try another.
- **Skip Intro, Skip Recap and Skip Credits** from IntroDB (and AniSkip for anime).
- **Up Next** with a countdown; the next episode's sources are found during the last few minutes so it starts at once.
- **Pause screen:** paused for a few seconds, the picture dims and shows what you're watching.
- **Subtitles** from addons and the file; size, background and position; move them 0.5 s at a time when they're out of step (press Up); your language turns on automatically. Off means off.
- **Audio language** per profile; **anime audio**: dubs first, or the original Japanese with subtitles.
- **After a film:** a quick thumbs up or down, sent to Trakt as a rating.

### Discover
- **Movie Night Match:** everyone scans a QR code and swipes on their phone; the first film or series you all say yes to wins.
- **Rabbit Hole:** an entertainment web. Jump from a film to its director, cast, composer, genres, franchise and themes, and on from each of those. Back retraces the trail.
- **Deep Cuts:** case files of things almost nobody has seen: acclaimed but overlooked, tiny but brilliant, world cinema, forgotten classics, cancelled series.
- **Franchise Journeys:** Marvel, Star Wars, Alien, Predator, DC, Middle-earth, Wizarding World, Jurassic, Bond, Mission: Impossible, Fast & Furious, Dragon Ball, Attack on Titan, Gundam or any franchise you search for, in recommended, story or release order, with spin-off series and what's coming.
- **Double Feature:** two films that belong together (same director, original and remake, two sides of a subject, light then dark), with a name and the reason.
- **Moodboard:** pick a feeling (Rainy Sunday, Late-Night Paranoia, Nostalgic Childhood, Dystopian Future, Psychological Mind Games, Summer Adventure, Existential Sci-Fi and more), not a genre.
- **Help Me Choose:** *Pick for Me* (a five-question quiz with a slot-machine reveal, trailer and re-roll) or *This or That* (two at a time; it learns what you're drawn to until one is left).
- **Watch Challenges:** curated lists such as the 10 greatest sci-fi films, 7 days of horror, Nolan, Kubrick, Tarantino, Ghibli and Pixar, ticked off automatically from your history (Trakt included).
- **Time Machine:** any year from 1950: its biggest films, TV, anime, faces, music and news, and hidden gems.
- **Blind Date:** a film picked for you with only clues. Commit, then it's revealed.
- **Calendar:** new episodes and films over the next four weeks, with reminders on the day.
- **Zylo Wrapped:** your year from Trakt: hours watched, films, episodes, biggest binge, top genres, busiest month.

### Live TV
- **Xtream Codes** provider with categories, a programme guide, favourites (hold OK), reminders and catch-up.
- **Better guide** from your provider plus free epgshare01 guides, refreshed daily.
- **Player:** Up/Down change channel, Left for the last channel, number keys, OK for the info bar then the channel list.
- **Sports:** fixtures from ESPN with where to watch, matched to your channels from the guide; tap Watch to go straight there.
- Hidden on kids profiles.

### Your accounts
- **Trakt:** watch history, progress and paused titles sync in; playback scrobbles; watchlist, your lists, ratings and Mark as Watched go back up. **My List** can save to Zylo, your Trakt watchlist or any of your Trakt lists.
- **AniList and MyAnimeList:** anime progress moves forward as you watch; their lists appear in My Lists.
- **Profiles** with their own history, lists, sign-ins and languages; **kids profiles** filtered by rating (U, PG, 12, 15); **4-digit PINs** on grown-up profiles and on Settings from a kids profile.

### Everything else
- **Guided setup** on first launch: TMDB, accounts, debrid, addons, Live TV.
- **Add addons from your phone:** scan a QR code and paste the link (long links break when typed on a TV).
- **Updates** checked when Zylo opens, downloaded and installed from this repository.
- **Background activity** shown in the top bar (channels, guide, Trakt, update).
- **Back** goes up to the top bar on the main pages, and one step back everywhere else.

Design tokens (colours, type scale, radii, timings) are in `Theme.kt` (`object Z`). Font: Inter. The mark and wordmark are Alex's artwork (`res/drawable/zylo_mark.xml`, `zylo_wordmark.xml`).

## Build

Needs JDK 17+ and the Android SDK (Android Studio installs both). `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`), then the APK is in `build/outputs/apk/debug/`. Tests: `./gradlew testDebugUnitTest`.

Signed with `debug.keystore` (same key as earlier builds), so it installs over them. Install with `adb install -r zylo-tv-debug.apk`, or copy it to the Shield and open it with a file manager.

## Not yet

Emby (Jellyfin's API is close, but untested), notifications, sharing, Xtream films and series.
