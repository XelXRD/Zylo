import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.application") version "8.13.2"
  id("org.jetbrains.kotlin.android") version "2.2.21"
  id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
}

android {
  namespace = "tv.zylo"
  compileSdk = 36
  defaultConfig {
    applicationId = "tv.zylo"
    minSdk = 24
    targetSdk = 36
    versionCode = 22
    versionName = "0.19.0"
    // Where Zylo looks for updates: a public GitHub repo's releases ("owner/repo") or a JSON file link. Empty: no checks.
    buildConfigField("String", "UPDATE_FEED", "\"${project.findProperty("zylo.updates") ?: ""}\"")
  }
  // Same key as the old WebView build, so this installs over it.
  signingConfigs { getByName("debug") { storeFile = file("debug.keystore"); storePassword = "android"; keyAlias = "zylo"; keyPassword = "android" } }
  buildTypes { release { signingConfig = signingConfigs.getByName("debug") } }
  buildFeatures { compose = true; buildConfig = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
  implementation(platform("androidx.compose:compose-bom:2025.10.01"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
  implementation("androidx.activity:activity-compose:1.11.0")
  implementation("androidx.tv:tv-material:1.0.1")
  implementation("androidx.media3:media3-exoplayer:1.8.0")
  implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
  implementation("androidx.media3:media3-ui:1.8.0")
  implementation("io.coil-kt:coil-compose:2.7.0")
  implementation("com.squareup.okhttp3:okhttp:4.12.0") // already pulled in by Coil
  implementation("com.google.zxing:core:3.5.3") // QR codes for signing in from a phone
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.json:json:20250517") // android.jar's org.json is stubbed in unit tests
  testImplementation("net.sf.kxml:kxml2:2.3.0") // the XML parser Android has built in
}
