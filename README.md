# Zylo

Media hub for Android TV: films, series, anime and live TV. Built for the NVIDIA Shield and a D-pad remote; no server needed.

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

## Updates

Zylo checks [`update.json`](update.json) when it opens. When the version there is newer than the one installed,
it offers to download the APK from [`releases/`](releases) and install it.

To publish a version: put the APK in `releases/`, then set `version`, `apk` (its raw link) and `notes` in `update.json`.
