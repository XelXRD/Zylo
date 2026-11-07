#!/bin/sh
# Builds zylo-tv.apk with Ubuntu's Android tools (apt: aapt dalvik-exchange apksigner zipalign android-sdk-platform-23).
# No Gradle, no Android Studio. Output: build/zylo-tv.apk, signed with a local debug key.
set -e
cd "$(dirname "$0")"
SDK=/usr/lib/android-sdk/platforms/android-23/android.jar
rm -rf build && mkdir -p build/gen build/classes
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -A assets -I "$SDK" -F build/unsigned.apk
javac -source 8 -target 8 -bootclasspath "$SDK" -classpath "$SDK" -d build/classes build/gen/tv/zylo/R.java src/tv/zylo/*.java 2>&1 | grep -v "bootstrap class path\|^1 warning\|warning: \[options\]" || true
dalvik-exchange --dex --output=build/classes.dex build/classes
(cd build && cp unsigned.apk with-dex.apk && zip -q with-dex.apk classes.dex)
zipalign -f 4 build/with-dex.apk build/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias zylo -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Zylo Debug" >/dev/null 2>&1
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out build/zylo-tv.apk build/aligned.apk
apksigner verify build/zylo-tv.apk && echo "built build/zylo-tv.apk"
