# Zylo

Media hub for Android TV: films, series, anime and live TV.

## Updates

Zylo checks [`update.json`](update.json) when it opens. When the version there is newer than the one installed,
it offers to download the APK from [`releases/`](releases) and install it.

To publish a version: put the APK in `releases/`, then set `version`, `apk` (its raw link) and `notes` in `update.json`.
