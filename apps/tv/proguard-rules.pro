# sherpa-onnx (Live TV subtitles): its native library reads these classes' fields by name over JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# The trailer player's page calls these methods by name (TrailerBridge in MainActivity.kt).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
