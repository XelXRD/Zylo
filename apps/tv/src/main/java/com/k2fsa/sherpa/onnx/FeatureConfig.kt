// From k2-fsa/sherpa-onnx v2.0.1, sherpa-onnx/kotlin-api/FeatureConfig.kt (Apache License 2.0, github.com/k2-fsa/sherpa-onnx).
// Zylo: the package and class names must stay as they are (the native library finds its methods by them); the only
// change is loading the library from where Live TV subtitles downloaded it (SherpaNative), not from the APK.
package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}
