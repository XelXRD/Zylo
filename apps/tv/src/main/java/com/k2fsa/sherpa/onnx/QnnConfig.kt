// From k2-fsa/sherpa-onnx v2.0.1, sherpa-onnx/kotlin-api/QnnConfig.kt (Apache License 2.0, github.com/k2-fsa/sherpa-onnx).
// Zylo: the package and class names must stay as they are (the native library finds its methods by them); the only
// change is loading the library from where Live TV subtitles downloaded it (SherpaNative), not from the APK.
package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)
