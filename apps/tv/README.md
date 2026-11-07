# Zylo for Android TV

A tiny app (17 KB) that runs Zylo's web app full screen on Android TV / NVIDIA Shield, driven by the remote.

- First launch asks for your Zylo address, e.g. `http://192.168.1.20:3000` (the PC running Docker; find its IP with `ipconfig`). Hold **Back** any time to change it.
- D-pad moves between controls, OK presses them. On a playing video: OK pauses/plays, left/right skip 10 seconds. Back leaves full screen, then goes back a page.
- Build: `./build.sh` (needs Ubuntu packages `aapt dalvik-exchange apksigner zipalign android-sdk-platform-23` and a JDK). Signed with the debug key in this folder so updates install over the old version.
- Limits: "Open in VLC" downloads a playlist, which a TV WebView can't open; video formats are whatever the Shield's Android WebView plays (MP4/H.264 and HEVC yes, MKV varies).
