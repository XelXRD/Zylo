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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
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
  // A sign-in panel (Trakt code, AniList/MAL QR). Kept here, above the pages, so nothing can switch it away mid-sign-in.
  var panel by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
  Row(Modifier.fillMaxSize()) {
    Column(Modifier.width(250.dp).fillMaxHeight().background(Z.Surface).padding(horizontal = 18.dp, vertical = 32.dp)) {
      Box(Modifier.padding(start = 12.dp, bottom = 28.dp)) { Logo(20.dp) }
      Text("Settings", Modifier.padding(start = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineSmall)
      SettingsPage.entries.forEach { p ->
        // Moving through the sidebar shows each page; Right goes into it.
        ZSurface({ page = p }, (if (p == page) Modifier.focusRequester(first) else Modifier).fillMaxWidth().padding(vertical = 2.dp).onFocusChanged { if (it.isFocused && panel == null) page = p }, selected = p == page) {
          Text(p.label, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
        }
      }
    }
    Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 40.dp, vertical = 36.dp)) {
      when (page) {
        SettingsPage.Playback -> Playback(store)
        SettingsPage.Integrations -> panel?.let { it() } ?: Integrations(store) { p -> panel = p?.let { content -> { content { panel = null } } } }
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
  Toggle("Trailer sound", "Play preview trailers with sound instead of muted.", store.trailerSound) { store.trailerSound = !store.trailerSound }
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
  AccountRow(tile, name, failure ?: text, failure != null, label, color) {
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

/** The shared look of every integration: icon tile, name, description, honest status, actions. */
@Composable
private fun AccountRow(tile: String, name: String, text: String, warn: Boolean, status: String, color: androidx.compose.ui.graphics.Color, actions: @Composable RowScope.() -> Unit) =
  Row(Modifier.fillMaxWidth().background(Z.Card, Z.Medium).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
    Tile(tile, 44.dp)
    Spacer(Modifier.width(16.dp))
    Column(Modifier.weight(1f)) {
      Text(name, style = MaterialTheme.typography.titleMedium)
      Text(text, style = MaterialTheme.typography.bodySmall, color = if (warn) Z.Warning else Z.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.width(12.dp))
    StatusChip(status, color)
    Spacer(Modifier.width(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
  }

// --- Integrations ------------------------------------------------------------------------------------

/** open(panel): show a sign-in panel; it gets a close() to call when done or cancelled. */
@Composable
private fun Integrations(store: Store, open: ((@Composable (close: () -> Unit) -> Unit)?) -> Unit) {
  Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Header("Integrations", "Connect your accounts. Zylo tests each one before showing it as connected.")
    ServiceCard(store, "tmdb", "TMDB", "Film and TV details, artwork and trailers. Required.", "TM", { store.tmdb }, { store.tmdb = it })
    ServiceCard(store, "fanart", "fanart.tv", "Title logos, clean backgrounds and landscape art. Optional: a free personal API key from fanart.tv.", "FA",
      { store.str("fanart_key") }, { store.put("fanart_key", it) })
    TraktCard(store) { clientId -> open { close -> TraktConnect(store, clientId, close) } }
    Anime.NAMES.forEach { (t, name) -> AnimeCard(store, t, name) { url -> open { close -> PasteConnect(store, t, name, url, close) } } }
  }
}

@Composable
private fun TraktCard(store: Store, connect: (String) -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var tick by remember { mutableIntStateOf(0) }
  var busy by remember { mutableStateOf(false) }
  val connected = remember(tick) { Trakt.connected(store) }
  val clientId = remember(tick) { store.str("trakt_client") }
  val failure = remember(tick) { store.status("trakt").takeIf { it.startsWith("fail:") }?.removePrefix("fail:") }
  val synced = remember(tick) { store.long("trakt_synced") }
  fun sync() = scope.launch {
    busy = true
    val r = withContext(Dispatchers.IO) { runCatching { Trakt.sync(store, force = true) } }
    store.setStatus("trakt", if (r.isSuccess) "ok" else "fail:${r.exceptionOrNull()?.message}")
    busy = false
    tick++
  }
  val (label, color) = when {
    busy -> "Syncing…" to Z.Info
    failure != null -> "Needs attention" to Z.Warning
    connected -> "Connected" to Z.Success
    clientId.isNotEmpty() -> "Not signed in" to Z.Warning
    else -> "Not connected" to Z.Muted
  }
  val text = when {
    failure != null -> failure
    connected -> "@${store.str("trakt_user")} · " + if (synced > 0) "synced ${ago(synced)}" else "not synced yet"
    clientId.isNotEmpty() -> "Client ID saved. Sign in to sync your lists, watchlist and history."
    else -> "Lists you made or follow, your watchlist, watch history and scrobbling."
  }
  fun enterClientId() = ask(ctx, "Trakt Client ID (trakt.tv > Settings > Your API Apps; not the Client Secret)", clientId) { entered ->
    val id = entered.trim()
    when {
      id.isEmpty() -> return@ask
      !Trakt.validClientId(id) -> {
        store.setStatus("trakt", "fail:That doesn't look like a Trakt Client ID (it has spaces or is too short). Copy it again from trakt.tv > Settings > Your API Apps.")
        tick++
      }
      else -> {
        store.put("trakt_client", id) // saved now, even before sign-in finishes
        store.setStatus("trakt", "")
        tick++
        connect(id)
      }
    }
  }
  AccountRow("TR", "Trakt", text, failure != null, label, color) {
    if (!connected && clientId.isEmpty()) ZButton("Connect", { enterClientId() }, primary = true)
    else if (!connected) {
      ZButton("Sign In", { store.setStatus("trakt", ""); tick++; connect(clientId) }, primary = true)
      ZButton("Change Client ID", { enterClientId() })
    } else {
      ZButton("Sync Now", { sync() })
      ZButton("Disconnect", { Trakt.disconnect(store); store.setStatus("trakt", ""); tick++ })
    }
  }
}

@Composable
private fun AnimeCard(store: Store, t: String, name: String, connect: (String) -> Unit) {
  val ctx = LocalContext.current
  var tick by remember { mutableIntStateOf(0) }
  val connected = remember(tick) { Anime.connected(store, t) }
  val help = if (t == "anilist") "AniList client ID (anilist.co > Settings > Developer; redirect URL https://anilist.co/api/v2/oauth/pin)"
  else "MyAnimeList client ID (myanimelist.net/apiconfig; app type: other)"
  AccountRow(if (t == "anilist") "AL" else "MAL", name, if (connected) "Connected as ${store.str("${t}_user")} · finishing an anime episode updates your progress." else "Updates your anime progress and shows your lists in My Lists.",
    false, if (connected) "Connected" else "Not connected", if (connected) Z.Success else Z.Muted) {
    if (!connected) ZButton("Connect", {
      ask(ctx, help, store.str("${t}_client")) { id ->
        if (id.isBlank()) return@ask
        store.put("${t}_client", id.trim())
        connect(if (t == "anilist") Anime.anilistUrl(id.trim()) else Anime.malUrl(store, id.trim()))
      }
    }, primary = true)
    else ZButton("Disconnect", { Anime.disconnect(store, t); tick++ })
  }
}

/** Trakt device code: shown here, entered at trakt.tv/activate on any phone or computer. */
@Composable
private fun TraktConnect(store: Store, clientId: String, close: () -> Unit) {
  var code by remember { mutableStateOf<Trakt.DeviceCode?>(null) }
  var message by remember { mutableStateOf("Asking Trakt for a code…") }
  val cancel = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    delay(80); runCatching { cancel.requestFocus() }
    val dc = withContext(Dispatchers.IO) { runCatching { Trakt.deviceCode(clientId) } }.getOrElse {
      message = it.message ?: "Trakt didn't answer."
      store.setStatus("trakt", "fail:$message") // also shown on the Trakt card after you go back
      return@LaunchedEffect
    }
    code = dc
    message = "Waiting for you to approve on Trakt…"
    val end = System.currentTimeMillis() + dc.expiresIn * 1000
    while (System.currentTimeMillis() < end) {
      delay(dc.interval * 1000)
      val r = withContext(Dispatchers.IO) { runCatching { Trakt.poll(store, clientId, dc.device) } }
      r.exceptionOrNull()?.let {
        message = it.message ?: "That didn't work."
        store.setStatus("trakt", "fail:$message")
        return@LaunchedEffect
      }
      if (r.getOrNull() == true) {
        message = "Connected. Syncing your history…"
        val s = withContext(Dispatchers.IO) { runCatching { Trakt.sync(store, force = true) } }
        store.setStatus("trakt", if (s.isSuccess) "ok" else "fail:${s.exceptionOrNull()?.message}")
        close()
        return@LaunchedEffect
      }
    }
    message = "The code expired. Try connecting again."
  }
  Column {
    Header("Connect Trakt", "On your phone or computer, open the address below (or scan the code) and enter this code.")
    Row(verticalAlignment = Alignment.CenterVertically) {
      code?.let { Qr(it.url, 168.dp) }
      Spacer(Modifier.width(28.dp))
      Column {
        Text(code?.url?.removePrefix("https://") ?: "", style = MaterialTheme.typography.titleLarge, color = Z.Text2)
        Text(code?.user ?: "", Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.displayMedium.copy(letterSpacing = 6.sp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
      }
    }
    ZButton(if (code == null || message.startsWith("Waiting")) "Cancel" else "Back", close, Modifier.padding(top = 24.dp).focusRequester(cancel))
  }
}

/** AniList / MyAnimeList: approve on the phone, then paste what it shows back here. */
@Composable
private fun PasteConnect(store: Store, t: String, name: String, url: String, close: () -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var message by remember { mutableStateOf<String?>(null) }
  val paste = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(80); runCatching { paste.requestFocus() } }
  val steps = if (t == "anilist") listOf("Scan the code with your phone and sign in to AniList.", "Approve Zylo. AniList then shows a long token.", "Copy it, choose Paste Token below and paste it (the Google TV app's keyboard can paste).")
  else listOf("Scan the code with your phone and sign in to MyAnimeList.", "Approve Zylo. Your phone then opens a page that won't load: that's fine.", "Copy that page's address, choose Paste Address below and paste it (the Google TV app's keyboard can paste).")
  Column {
    Header("Connect $name", "Sign in on your phone, then bring the result back here.")
    Row {
      Qr(url, 168.dp)
      Spacer(Modifier.width(28.dp))
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEachIndexed { i, s -> Text("${i + 1}.  $s", style = MaterialTheme.typography.bodyMedium, color = Z.Text2) }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Z.Warning) }
      }
    }
    Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      ZButton(if (t == "anilist") "Paste Token" else "Paste Address", {
        ask(ctx, if (t == "anilist") "AniList token" else "Address from your phone", "") { text ->
          scope.launch {
            message = "Checking…"
            withContext(Dispatchers.IO) { runCatching { if (t == "anilist") Anime.anilistConnect(store, text) else Anime.malConnect(store, text) } }
              .onSuccess { toast(ctx, "$name connected"); close() }
              .onFailure { message = "That didn't work: ${it.message}" }
          }
        }
      }, Modifier.focusRequester(paste), primary = true)
      ZButton("Cancel", close)
    }
  }
}

@Composable
fun Qr(text: String, size: androidx.compose.ui.unit.Dp) {
  val bitmap = remember(text) {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
    val px = IntArray(m.width * m.height) { if (m.get(it % m.width, it / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
  }
  androidx.compose.foundation.Image(bitmap, "QR code", Modifier.size(size), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
}

private fun ago(t: Long): String {
  val m = (System.currentTimeMillis() - t) / 60_000
  return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} days ago" }
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
  Text("Version 0.12.0", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
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
