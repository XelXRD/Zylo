// Zylo: sherpa-onnx's native library isn't shipped in the APK. It's downloaded (and checked against its SHA-256) when
// Live TV subtitles are turned on in Settings, then loaded from there; System.load of a library already loaded is a no-op.
package com.k2fsa.sherpa.onnx

object SherpaNative {
  @Volatile var path: String? = null
  fun load() = System.load(path ?: throw UnsatisfiedLinkError("Live TV subtitles aren't downloaded"))
}
