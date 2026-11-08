// App updates without a store: Zylo checks a GitHub repository's latest release (or a small JSON file) when it opens,
// and offers to download and install a newer APK. Updates install over the old app because every build is signed with
// the same key.
package tv.zylo

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class Release(val version: String, val apk: String, val notes: String)

object Updates {
  /**
   * Where releases are published: BuildConfig.UPDATE_FEED, either "owner/repo" (GitHub releases: upload the APK to a
   * release tagged with its version, e.g. v0.19.0) or a link to a JSON file {"version":"0.19.0","apk":"https://…","notes":"…"}.
   */
  val feed get() = BuildConfig.UPDATE_FEED

  /** Bumped by "Check for Updates" in Settings. */
  var asked by mutableIntStateOf(0)

  fun latest(feed: String, fetch: (String) -> String = { http(it, headers = mapOf("Accept" to "application/vnd.github+json")) }): Release? {
    if (feed.isBlank()) return null
    val github = !feed.startsWith("http")
    val o = JSONObject(fetch(if (github) "https://api.github.com/repos/$feed/releases/latest" else feed))
    return if (github || o.has("assets")) {
      val apk = o.optJSONArray("assets")?.objects()?.firstOrNull { it.optString("name").endsWith(".apk", true) }?.str("browser_download_url") ?: return null
      Release(o.optString("tag_name").removePrefix("v").removePrefix("V"), apk, o.optString("body"))
    } else Release(o.optString("version"), o.str("apk") ?: return null, o.optString("notes"))
  }

  /** Whether version [a] is newer than [b] ("0.19.0" > "0.18.10" > "0.18.2"). */
  fun newer(a: String, b: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val (x, y) = parts(a) to parts(b)
    for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
    return false
  }

  fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File {
    val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
    val file = File(dir, "zylo-${r.version}.apk")
    val total = runCatching { okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(r.apk).head().build()).execute().use { it.header("Content-Length")?.toLong() } }.getOrNull() ?: 0L
    openStream(r.apk).use { input -> file.outputStream().use { out ->
      val buf = ByteArray(1 shl 16); var done = 0L
      while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; if (total > 0) progress(done.toFloat() / total) }
    } }
    return file
  }

  /** Hands the APK to Android's installer (it asks to allow installs from Zylo the first time). */
  fun install(ctx: Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
  }
}

/** Checks for a newer version on launch (at most every 6 hours, or when asked) and offers it. */
@Composable
fun UpdatePrompt(store: Store, check: Int = 0) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var release by remember { mutableStateOf<Release?>(null) }
  var progress by remember { mutableStateOf<Float?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(check) {
    val last = store.str("update_checked").toLongOrNull() ?: 0
    if (check == 0 && System.currentTimeMillis() - last < 6 * 3_600_000L) return@LaunchedEffect
    store.put("update_checked", System.currentTimeMillis().toString())
    val r = withContext(Dispatchers.IO) { runCatching { Updates.latest(Updates.feed) }.getOrNull() } ?: return@LaunchedEffect
    if (Updates.newer(r.version, BuildConfig.VERSION_NAME) && (check > 0 || store.str("update_skipped") != r.version)) release = r
    else if (check > 0) toast(ctx, "Zylo ${BuildConfig.VERSION_NAME} is the latest version.")
  }
  val r = release ?: return
  val first = remember { FocusRequester() }
  LaunchedEffect(r) { delay(80); runCatching { first.requestFocus() } }
  androidx.activity.compose.BackHandler { release = null }
  Box(Modifier.fillMaxSize().background(Z.Bg.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
    Column(Modifier.width(520.dp).background(Z.Surface, Z.Large).padding(32.dp)) {
      Text("UPDATE AVAILABLE", style = MaterialTheme.typography.labelLarge, color = Z.Red)
      Text("Zylo ${r.version}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.headlineMedium)
      Text("You have ${BuildConfig.VERSION_NAME}.", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
      if (r.notes.isNotBlank()) Text(r.notes.trim(), Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 8, overflow = TextOverflow.Ellipsis)
      error?.let { Text(it, Modifier.padding(top = 12.dp), color = Z.Error) }
      progress?.let { ProgressBar(it, Modifier.padding(top = 18.dp).fillMaxWidth()) }
      Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ZButton(if (progress != null) "Downloading…" else "Update Now", {
          if (progress != null) return@ZButton
          progress = 0f; error = null
          scope.launch {
            runCatching { withContext(Dispatchers.IO) { Updates.download(ctx, r) { p -> progress = p } } }
              .onSuccess { f -> progress = null; Updates.install(ctx, f) }
              .onFailure { progress = null; error = "Couldn't download the update: ${it.message}" }
          }
        }, Modifier.focusRequester(first), primary = true)
        ZButton("Not Now", { store.put("update_skipped", r.version); release = null })
      }
    }
  }
}
