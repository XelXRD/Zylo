// Settings: the only screen with a sidebar. Connections only show as Connected after a real test.
package tv.zylo

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SettingsPage(val label: String) { Playback("Playback"), Integrations("Integrations"), Addons("Addons"), Debrid("Debrid Services"), About("About Zylo") }

@Composable
fun SettingsScreen(store: Store) {
  var page by rememberSaveable { mutableStateOf(if (store.tmdb.isEmpty()) SettingsPage.Integrations else SettingsPage.Playback) }
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
  Row(Modifier.fillMaxSize()) {
    Column(Modifier.width(250.dp).fillMaxHeight().background(Z.Surface).padding(horizontal = 18.dp, vertical = 32.dp)) {
      Box(Modifier.padding(start = 12.dp, bottom = 28.dp)) { Logo(20.dp) }
      Text("Settings", Modifier.padding(start = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall)
      SettingsPage.entries.forEach { p ->
        // Moving through the sidebar shows each page; Right goes into it.
        ZSurface({ page = p }, (if (p == page) Modifier.focusRequester(first) else Modifier).fillMaxWidth().padding(vertical = 2.dp).onFocusChanged { if (it.isFocused) page = p }, selected = p == page) {
          Text(p.label, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
        }
      }
    }
    Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 40.dp, vertical = 36.dp)) {
      when (page) {
        SettingsPage.Playback -> Playback(store)
        SettingsPage.Integrations -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Header("Integrations", "Connect your accounts. Zylo tests each one before showing it as connected.")
          ServiceCard(store, "tmdb", "TMDB", "Film and TV details, artwork and trailers. Required.", "TM", { store.tmdb }, { store.tmdb = it })
          Text("Trakt, AniList, MyAnimeList and media servers (Plex, Jellyfin, Emby) are coming in later builds.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = Z.Muted)
        }
        SettingsPage.Addons -> Addons(store)
        SettingsPage.Debrid -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Header("Debrid Services", "Stream torrent sources from your debrid account. Zylo uses the first connected service that has a title ready.")
          ServiceCard(store, "torbox", "TorBox", "Checks what's cached before you press Play.", "TB", { store.debrid("torbox") }, { store.setDebrid("torbox", it) })
          ServiceCard(store, "realdebrid", "Real-Debrid", "Can't report its cache: a source is checked when you play it.", "RD", { store.debrid("realdebrid") }, { store.setDebrid("realdebrid", it) })
          ServiceCard(store, "premiumize", "Premiumize", "Checks what's cached before you press Play.", "PM", { store.debrid("premiumize") }, { store.setDebrid("premiumize", it) })
        }
        SettingsPage.About -> About()
      }
    }
  }
}

@Composable
private fun Header(title: String, text: String) = Column(Modifier.padding(bottom = 8.dp)) {
  Text(title, style = MaterialTheme.typography.displaySmall)
  Text(text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
}

@Composable
private fun Playback(store: Store) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
  Header("Playback", "How browsing and previews behave.")
  Toggle("Trailer previews", "Play a muted trailer when you rest on a title.", store.previews) { store.previews = !store.previews }
  Toggle("Reduce motion", "Turn off card growth, fades and focus zoom.", store.reduceMotion) { store.reduceMotion = !store.reduceMotion }
}

@Composable
private fun Toggle(title: String, text: String, on: Boolean, flip: () -> Unit) = ZSurface(flip, Modifier.fillMaxWidth(0.8f), container = Z.Card) {
  Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(text, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.75f))
    }
    StatusChip(if (on) "On" else "Off", if (on) Z.Success else Z.Muted)
  }
}

@Composable
private fun ServiceCard(store: Store, id: String, name: String, text: String, tile: String, key: () -> String, setKey: (String) -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var tick by remember { mutableIntStateOf(0) }
  var checking by remember { mutableStateOf(false) }
  val status = remember(tick) { store.status(id) }
  val current = remember(tick) { key() }
  fun test(k: String) = scope.launch {
    checking = true
    val r = withContext(Dispatchers.IO) { runCatching { testService(id, k) } }
    store.setStatus(id, if (r.isSuccess) "ok" else "fail:${r.exceptionOrNull()?.message ?: "it didn't work."}")
    checking = false
    tick++
  }
  val failure = status.removePrefix("fail:").takeIf { status.startsWith("fail:") && current.isNotEmpty() && !checking }
  val (label, color) = when {
    checking -> "Checking…" to Z.Info
    current.isEmpty() -> "Not connected" to Z.Muted
    status == "ok" -> "Connected" to Z.Success
    failure != null -> "Needs attention" to Z.Warning
    else -> "Not tested" to Z.Warning
  }
  Row(Modifier.fillMaxWidth().background(Z.Card, Z.Medium).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
    Tile(tile, 44.dp)
    Spacer(Modifier.width(16.dp))
    Column(Modifier.weight(1f)) {
      Text(name, style = MaterialTheme.typography.titleMedium)
      Text(failure ?: text, style = MaterialTheme.typography.bodySmall, color = if (failure != null) Z.Warning else Z.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.width(12.dp))
    StatusChip(label, color)
    Spacer(Modifier.width(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      ZButton(if (current.isEmpty()) "Connect" else "Change Key", {
        ask(ctx, "$name API key", current) {
          setKey(it)
          store.setStatus(id, "")
          tick++
          if (it.isNotBlank()) test(it.trim())
        }
      }, primary = current.isEmpty())
      if (current.isNotEmpty()) {
        ZButton("Test", { test(current) })
        ZButton("Disconnect", { setKey(""); store.setStatus(id, ""); tick++ })
      }
    }
  }
}

@Composable
private fun Addons(store: Store) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var addons by remember { mutableStateOf(store.addons) }
  Column {
    Header("Addons", "Stremio addons find streams for Zylo. Add one by pasting its manifest link.")
    ZButton("Add Addon", {
      ask(ctx, "Addon manifest link", "") { url ->
        scope.launch {
          withContext(Dispatchers.IO) { runCatching { Addon.load(url) } }
            .onSuccess { a -> store.addons = store.addons.filter { it.url != a.url } + a; addons = store.addons; toast(ctx, "Added ${a.name}") }
            .onFailure { toast(ctx, it.message ?: "Couldn't add that addon.") }
        }
      }
    }, primary = true, icon = Icons.Filled.Add)
    if (addons.isEmpty()) Text("No addons yet.", Modifier.padding(top = 16.dp), color = Z.Text2)
    LazyColumn(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      items(addons, key = { it.url }) { a ->
        Row(Modifier.fillMaxWidth().background(Z.Card, Z.Medium).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
          Tile(a.name.take(1).uppercase(), 44.dp)
          Spacer(Modifier.width(16.dp))
          Column(Modifier.weight(1f)) {
            Text(a.name, style = MaterialTheme.typography.titleMedium)
            Text(a.manifest.optString("description"), style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetaLine(listOf("v" + a.manifest.optString("version"), strings(a.manifest.optJSONArray("types")).joinToString(", ")), color = Z.Muted)
          }
          Spacer(Modifier.width(12.dp))
          StatusChip("Installed", Z.Success)
          Spacer(Modifier.width(16.dp))
          ZButton("Remove", { store.addons = store.addons.filter { it.url != a.url }; addons = store.addons })
        }
      }
    }
  }
}

@Composable
private fun About() = Column(Modifier.verticalScroll(rememberScrollState())) {
  Logo(40.dp)
  Text("One interface. Your entire media world.", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleLarge, color = Z.Text2)
  Text("Version 0.9.0", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
  Text(
    "Film and TV information and artwork come from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.",
    Modifier.padding(top = 8.dp).fillMaxWidth(0.8f), style = MaterialTheme.typography.bodySmall, color = Z.Muted,
  )
}

/** Native text dialog: the TV keyboard (and the Google TV phone app's keyboard) works best with a plain EditText. */
fun ask(ctx: Context, title: String, current: String, done: (String) -> Unit) {
  val input = EditText(ctx).apply { setText(current); setSingleLine(); setSelectAllOnFocus(true) }
  AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(input)
    .setPositiveButton("Save") { _, _ -> done(input.text.toString()) }.setNegativeButton("Cancel", null).show()
}

fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
