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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SettingsPage(val label: String) {
  Profiles("Profiles"), Playback("Playback"), Integrations("Integrations"), Addons("Addons"), Debrid("Debrid Services"), Servers("Media Servers"), Live("Live TV"), About("About Zylo")
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(store: Store) {
  var page by rememberSaveable { mutableStateOf(if (store.tmdb.isEmpty()) SettingsPage.Integrations else SettingsPage.Playback) }
  // A sign-in panel (Trakt code, AniList/MAL QR). Kept here, above the pages, so nothing can switch it away mid-sign-in.
  var panel by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
  Row(Modifier.fillMaxSize()) {
    // Left from a page always lands on that page's own entry: landing on the nearest one switched pages under you.
    Column(Modifier.width(250.dp).fillMaxHeight().background(Z.Surface).padding(horizontal = 18.dp, vertical = 32.dp)
      .focusProperties { @Suppress("DEPRECATION") enter = { first } }.focusGroup()) {
      Box(Modifier.padding(start = 12.dp, bottom = 28.dp)) { Logo(28.dp) }
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
        SettingsPage.Debrid -> Debrid(store)
        SettingsPage.Live -> LiveTvSettings(store)
        SettingsPage.About -> About(store)
        SettingsPage.Profiles -> ProfilesSettings(store)
        SettingsPage.Servers -> panel?.let { it() } ?: MediaServers(store) { p -> panel = p?.let { content -> { content { panel = null } } } }
      }
    }
  }
}

@Composable
private fun Debrid(store: Store) = Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
  Header("Debrid Services", "Stream torrent sources from your debrid account. Zylo uses the first connected service that has a title ready.")
  ServiceCard(store, "torbox", "TorBox", "Checks what's cached before you press Play.", "TB", { store.debrid("torbox") }, { store.setDebrid("torbox", it) })
  ServiceCard(store, "realdebrid", "Real-Debrid", "Can't report its cache: a source is checked when you play it.", "RD", { store.debrid("realdebrid") }, { store.setDebrid("realdebrid", it) })
  ServiceCard(store, "premiumize", "Premiumize", "Checks what's cached before you press Play.", "PM", { store.debrid("premiumize") }, { store.setDebrid("premiumize", it) })
}

/**
 * First launch: TMDB key, then Trakt and anime trackers, debrid, addons (from your phone) and Live TV, one at a time.
 * Every step but the first can be skipped; all of it stays in Settings afterwards.
 */
@Composable
fun SetupScreen(store: Store, done: () -> Unit) {
  val ctx = LocalContext.current
  var step by rememberSaveable { mutableIntStateOf(0) }
  var panel by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
  val steps = listOf("Your TMDB key", "Accounts", "Debrid", "Addons", "Live TV")
  val next = remember { FocusRequester() }
  LaunchedEffect(step) { delay(120); runCatching { next.requestFocus() } }
  androidx.activity.compose.BackHandler(step > 0 && panel == null) { step-- }
  Column(Modifier.fillMaxSize().padding(horizontal = Z.Pad, vertical = 32.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Logo(28.dp)
      Spacer(Modifier.width(24.dp))
      steps.forEachIndexed { i, name ->
        Text("${i + 1}. $name", Modifier.padding(end = 18.dp), style = MaterialTheme.typography.labelLarge, color = if (i == step) Z.Text else Z.Muted)
      }
    }
    Box(Modifier.weight(1f).fillMaxWidth().padding(top = 24.dp)) {
      panel?.let { it(); return@Box }
      when (step) {
        0 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Header("Welcome to Zylo", "Start with a TMDB key: it's free and gives Zylo its film and TV details and artwork. Sign up at themoviedb.org, then go to Settings > API and copy the 32-character API key. The Google TV app on your phone works as a keyboard.")
          ServiceCard(store, "tmdb", "TMDB", "Film and TV details, artwork and trailers. Required.", "TM", { store.tmdb }, { store.tmdb = it })
        }
        1 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Header("Accounts", "Optional. Trakt keeps your watchlist, history and progress in step; AniList or MyAnimeList track anime.")
          TraktCard(store) { id -> panel = { TraktConnect(store, id) { panel = null } } }
          Anime.NAMES.forEach { (t, name) -> AnimeCard(store, t, name) { url -> panel = { PasteConnect(store, t, name, url) { panel = null } } } }
        }
        2 -> Debrid(store)
        3 -> Addons(store)
        else -> LiveTvSettings(store)
      }
    }
    if (panel == null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      if (step > 0) ZButton("Back", { step-- })
      val last = step == steps.lastIndex
      ZButton(if (last) "Finish" else if (step == 0) "Next" else "Next (or skip)", {
        when {
          step == 0 && store.tmdb.isBlank() -> toast(ctx, "Add your TMDB key first: Zylo needs it for everything else.")
          last -> done()
          else -> step++
        }
      }, Modifier.focusRequester(next), primary = true)
    }
  }
}

/** Settings on a kids profile: locked behind a grown-up's PIN (when one has been set). */
@Composable
fun SettingsGate(store: Store, nav: Nav, content: @Composable () -> Unit) {
  val ctx = LocalContext.current
  val pins = remember { Pins.forSettings(store) }
  var open by rememberSaveable { mutableStateOf(pins.isEmpty()) }
  if (open) return content()
  fun askPin() = ask(ctx, "Grown-up PIN to open Settings", "", pin = true) { if (it in pins) open = true else toast(ctx, "Wrong PIN") }
  val r = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(80); runCatching { r.requestFocus() }; askPin() }
  Column(Modifier.fillMaxSize().padding(horizontal = Z.Pad * 2), verticalArrangement = Arrangement.Center) {
    Text("Settings are locked", style = MaterialTheme.typography.displaySmall)
    Text("This is a kids profile. A grown-up's PIN opens Settings.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyLarge, color = Z.Text2)
    Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      ZButton("Enter PIN", { askPin() }, Modifier.focusRequester(r), primary = true)
      ZButton("Back", { nav.back() })
    }
  }
}

@Composable
private fun Header(title: String, text: String) = Column(Modifier.padding(bottom = 8.dp)) {
  Text(title, style = MaterialTheme.typography.displaySmall)
  Text(text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
}

@Composable
private fun Playback(store: Store) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
  Header("Playback", "How browsing and previews behave.")
  Toggle("Trailer previews", "Play a muted trailer when you rest on a title.", store.previews) { store.previews = !store.previews }
  Toggle("Trailer sound", "Play preview trailers with sound instead of muted.", store.trailerSound) { store.trailerSound = !store.trailerSound }
  var cap by remember { mutableStateOf(store.maxQuality) }
  Choice("Maximum quality", "The highest quality Play picks on its own. Higher sources stay in Choose Source.", cap) {
    cap = QUALITY_STEPS[(QUALITY_STEPS.indexOf(cap) + 1) % QUALITY_STEPS.size]
    store.maxQuality = cap
  }
  var lang by remember { mutableStateOf(store.subLang) }
  Choice("Subtitles", "Turned on automatically in this language (each profile has its own). You can always pick others in the player.",
    if (lang.isEmpty()) "Off" else languageName(lang)) {
    lang = SUB_LANGS[(SUB_LANGS.indexOf(lang) + 1) % SUB_LANGS.size]
    store.subLang = lang
  }
  var audio by remember { mutableStateOf(store.str("audiolang")) }
  Choice("Audio language", "Played in this language whenever the file has it (each profile has its own).", when (audio) {
    "" -> "As the TV (${languageName(java.util.Locale.getDefault().language.ifEmpty { "en" })})"; "file" -> "The file's default"; else -> languageName(audio)
  }) {
    val all = listOf("") + SUB_LANGS.drop(1) + "file"
    audio = all[(all.indexOf(audio) + 1) % all.size]
    store.put("audiolang", audio)
  }
  var dubs by remember { mutableStateOf(store.animeDubs) }
  Choice("Anime audio", "Dubbed: English-dubbed and dual-audio releases first, in your audio language. Original: Japanese audio with subtitles.", if (dubs) "Prefer dubs" else "Original + subtitles") {
    dubs = !dubs
    store.animeDubs = dubs
  }
  var autoSource by remember { mutableIntStateOf(AutoSource.min(store)) }
  Choice("Pick a source for me", "Play chooses the best source once this many are found, so it has more to choose from. Off: you always choose from the list.", AutoSource.label(autoSource)) {
    autoSource = AutoSource.CHOICES[(AutoSource.CHOICES.indexOf(autoSource) + 1) % AutoSource.CHOICES.size]
    store.put("autosource", autoSource.toString())
  }
  var size by remember { mutableStateOf(SubStyle.size(store).first) }
  Choice("Subtitle size", "How big subtitles are in the player.", size) {
    size = SubStyle.SIZES[(SubStyle.SIZES.indexOfFirst { it.first == size } + 1) % SubStyle.SIZES.size].first
    store.put("sub_size", size)
  }
  var bg by remember { mutableStateOf(SubStyle.background(store)) }
  Choice("Subtitle background", "A shadow, an outline or a dark box behind the words, for reading over bright scenes.", bg) {
    bg = SubStyle.BACKGROUNDS[(SubStyle.BACKGROUNDS.indexOf(bg) + 1) % SubStyle.BACKGROUNDS.size]
    store.put("sub_bg", bg)
  }
  var pos by remember { mutableStateOf(SubStyle.position(store).first) }
  Choice("Subtitle position", "Raised keeps them clear of burned-in captions and the TV's edge.", pos) {
    pos = SubStyle.POSITIONS[(SubStyle.POSITIONS.indexOfFirst { it.first == pos } + 1) % SubStyle.POSITIONS.size].first
    store.put("sub_pos", pos)
  }
  Toggle("Reduce motion", "Turn off card growth, fades and focus zoom.", store.reduceMotion) { store.reduceMotion = !store.reduceMotion }
}

private val SUB_LANGS = listOf("", "en", "es", "fr", "de", "it", "pt", "nl", "pl", "sv", "da", "no", "fi", "ja", "ko", "zh", "ar", "hi", "tr", "ru")

/** A setting with a few values: OK steps to the next one. */
@Composable
private fun Choice(title: String, text: String, value: String, next: () -> Unit) = ZSurface(next, Modifier.fillMaxWidth(0.8f), container = Z.Card) {
  Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(text, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.75f))
    }
    Spacer(Modifier.width(16.dp))
    Text(value, style = MaterialTheme.typography.labelLarge)
  }
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
    SyncActivity(store)
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
    val r = withContext(Dispatchers.IO) { runCatching { Trakt.sync(store, force = true) }.also { Outbox.flush(store) } }
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

// --- Media servers ------------------------------------------------------------------------------------

@Composable
private fun MediaServers(store: Store, open: ((@Composable (close: () -> Unit) -> Unit)?) -> Unit) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var tick by remember { mutableIntStateOf(0) }
  var busy by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }
  val plex = remember(tick) { Plex.from(store) }
  val jellyfin = remember(tick) { Jellyfin.from(store) }
  Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Header("Media Servers", "Play films and episodes from your own server. They're listed first in Choose Source as Personal Library.")
    AccountRow("PX", "Plex", if (plex != null) "Connected to ${plex.name}." else "Sign in with a code at plex.tv/link; Zylo finds your server.",
      false, if (plex != null) "Connected" else "Not connected", if (plex != null) Z.Success else Z.Muted) {
      if (plex == null) ZButton("Connect", { open { close -> PlexConnect(store) { close(); tick++ } } }, primary = true)
      else ZButton("Disconnect", { Plex.disconnect(store); tick++ })
    }
    AccountRow("JF", "Jellyfin", message ?: if (jellyfin != null) "Connected to ${jellyfin.name}." else "Your server's address, username and password.",
      message != null, when { busy -> "Checking…"; jellyfin != null -> "Connected"; else -> "Not connected" }, when { busy -> Z.Info; jellyfin != null -> Z.Success; else -> Z.Muted }) {
      if (jellyfin == null) ZButton("Connect", {
        ask(ctx, "Jellyfin server address (e.g. 192.168.1.20:8096)", store.str("jf_last")) { address ->
          if (address.isBlank()) return@ask
          store.put("jf_last", address.trim())
          ask(ctx, "Jellyfin username", "") { user ->
            ask(ctx, "Jellyfin password", "", secret = true) { password ->
              scope.launch {
                busy = true
                message = null
                withContext(Dispatchers.IO) { runCatching { Jellyfin.connect(store, address, user, password) } }.onFailure { message = it.message }
                busy = false
                tick++
              }
            }
          }
        }
      }, primary = true)
      else ZButton("Disconnect", { Jellyfin.disconnect(store); message = null; tick++ })
    }
  }
}

/** Settings > Live TV: an Xtream Codes account (server, username, password — or the provider's M3U link, which has all three). */
@Composable
private fun LiveTvSettings(store: Store) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var tick by remember { mutableIntStateOf(0) }
  var busy by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }
  val x = remember(tick) { Xtream.from(store) }
  val info = remember(tick) { runCatching { JSONObject(store.str("xc_info")).optJSONObject("user_info") }.getOrNull() }
  fun connect(server: String, user: String, pass: String) = scope.launch {
    busy = true
    message = null
    withContext(Dispatchers.IO) { runCatching { Xtream.connect(store, server, user, pass) } }.onFailure { message = it.message }
    busy = false
    tick++
  }
  Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Header("Live TV", "Watch your TV provider's channels with a programme guide. Zylo uses the Xtream Codes details your provider gave you.")
    val details = info?.let { i ->
      listOfNotNull(
        i.optString("exp_date").toLongOrNull()?.let { "expires " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.UK).format(java.util.Date(it * 1000)) },
        i.optString("max_connections").takeIf { it.isNotEmpty() && it != "0" }?.let { "$it connection" + if (it == "1") "" else "s" },
      ).joinToString(", ")
    }.orEmpty()
    AccountRow("XC", "Xtream Codes", message ?: if (x != null) "Connected to ${x.server.substringAfter("://")} as ${x.user}" + (if (details.isNotEmpty()) " ($details)." else ".")
      else "Enter the server address, username and password, or paste the M3U link (it includes all three).",
      message != null, when { busy -> "Checking…"; x != null -> "Connected"; else -> "Not connected" }, when { busy -> Z.Info; x != null -> Z.Success; else -> Z.Muted }) {
      if (x == null) ZButton("Connect", {
        ask(ctx, "Server address or M3U link", store.str("xc_last")) { input ->
          if (input.isBlank()) return@ask
          store.put("xc_last", input.trim())
          val (server, user, pass) = Xtream.parse(input)
          if (user != null && pass != null) connect(server, user, pass)
          else ask(ctx, "Username", "") { u -> ask(ctx, "Password", "", secret = true) { p -> connect(server, u, p) } }
        }
      }, primary = true)
      else ZButton("Disconnect", { Xtream.disconnect(store); message = null; tick++ })
    }
    Text("Live TV appears in the top bar. It isn't shown on kids profiles.", style = MaterialTheme.typography.bodySmall, color = Z.Muted)
    if (x != null) {
      var preview by remember { mutableStateOf(store.str("live_preview") != "off") }
      Toggle("Preview channels in the guide", "The channel you rest on plays, muted, above the guide. Uses one of your provider's connections.", preview) {
        preview = !preview; store.put("live_preview", if (preview) "" else "off")
      }
      BetterGuide(store)
    }
  }
}

/** Settings > Live TV > Better TV guide: the provider's full guide plus free per-country guides from epgshare01. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BetterGuide(store: Store) {
  val scope = rememberCoroutineScope()
  var provider by remember { mutableStateOf(store.str("epg_provider") != "off") }
  var picked by remember { mutableStateOf(store.str("epg_share").split(',').filter { it.isNotBlank() }.toSet()) }
  var status by remember { mutableStateOf(store.str("epg_status")) }
  var busy by remember { mutableStateOf(false) }
  Text("Better TV guide", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleLarge)
  Text("Fills gaps in your provider's guide and lets Sports find which channels show a match. Guides download once a day, after your channels, and keep only the channels you have.",
    style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
  Toggle("Your provider's full guide", "Every channel's listings in one download (xmltv.php)", provider) {
    provider = !provider; store.put("epg_provider", if (provider) "" else "off")
  }
  Text("Extra guides by country — free from epgshare01.online", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleSmall)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Epg.SHARE.forEach { (code, name) ->
      Chip(name, code in picked) {
        picked = if (code in picked) picked - code else picked + code
        store.put("epg_share", Epg.SHARE.map { it.first }.filter { it in picked }.joinToString(","))
      }
    }
  }
  Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    ZButton(if (busy) "Downloading…" else "Update Guide Now", {
      if (busy || Epg.busy) return@ZButton
      busy = true; Epg.busy = true
      scope.launch {
        status = withContext(Dispatchers.IO) { runCatching { Epg.refresh(store, Epg.sources(store), ChannelCache.load()?.third) }.getOrElse { "Couldn't update: ${it.message}" } }
        Epg.busy = false; busy = false
      }
    }, primary = true)
    Spacer(Modifier.width(16.dp))
    Text(if (status.isEmpty()) "Not downloaded yet." else (store.str("epg_at").toLongOrNull()?.let { "Updated ${ago(it)}: " } ?: "") + status,
      style = MaterialTheme.typography.bodySmall, color = Z.Text2)
  }
  Text("epgshare01 is run by volunteers; if it helps you, they take donations at ko-fi.com/W7W0YGC4T.", style = MaterialTheme.typography.bodySmall, color = Z.Muted)
}

/** Plex link code: entered at plex.tv/link on any phone or computer. */
@Composable
private fun PlexConnect(store: Store, close: () -> Unit) {
  var code by remember { mutableStateOf<String?>(null) }
  var message by remember { mutableStateOf("Asking Plex for a code…") }
  val cancel = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    delay(80); runCatching { cancel.requestFocus() }
    val cid = Library.deviceId(store)
    val pin = withContext(Dispatchers.IO) { runCatching { Plex.pin(cid) } }.getOrElse { message = "Couldn't reach Plex: ${it.message}"; return@LaunchedEffect }
    code = pin.code
    message = "Waiting for you to enter the code on plex.tv/link…"
    val end = System.currentTimeMillis() + 10 * 60_000
    while (System.currentTimeMillis() < end) {
      delay(2_000)
      val token = withContext(Dispatchers.IO) { runCatching { Plex.poll(cid, pin.id) }.getOrNull() } ?: continue
      message = "Signed in. Looking for your server…"
      withContext(Dispatchers.IO) { runCatching { Plex.connect(store, cid, token) } }
        .onSuccess { close() }.onFailure { message = it.message ?: "Couldn't find your server." }
      return@LaunchedEffect
    }
    message = "The code expired. Try connecting again."
  }
  Column {
    Header("Connect Plex", "On your phone or computer, open plex.tv/link (or scan the code) and enter this code.")
    Row(verticalAlignment = Alignment.CenterVertically) {
      Qr("https://plex.tv/link", 168.dp)
      Spacer(Modifier.width(28.dp))
      Column {
        Text("plex.tv/link", style = MaterialTheme.typography.titleLarge, color = Z.Text2)
        Text(code ?: "", Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.displayMedium.copy(letterSpacing = 6.sp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
      }
    }
    ZButton("Cancel", close, Modifier.padding(top = 24.dp).focusRequester(cancel))
  }
}

/** What was sent to Trakt, AniList and MyAnimeList, and why anything failed. */
@Composable
private fun SyncActivity(store: Store) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var tick by remember { mutableIntStateOf(0) }
  var sending by remember { mutableStateOf(false) }
  val entries = remember(tick) { Outbox.entries(store).take(10) }
  val pending = remember(tick) { Outbox.pending(store) }
  if (entries.isEmpty() && pending == 0) return
  Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Sync activity", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
      if (pending > 0) StatusChip("$pending waiting to send", Z.Warning)
      Spacer(Modifier.width(12.dp))
      ZButton(if (sending) "Sending…" else "Send Now", {
        if (!sending) scope.launch {
          sending = true
          withContext(Dispatchers.IO) { Outbox.flush(store) }
          sending = false
          tick++
        }
      })
    }
    entries.forEach { e ->
      Row(Modifier.fillMaxWidth().background(Z.Card, Z.Small).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(if (e.ok) Z.Success else Z.Warning, androidx.compose.foundation.shape.CircleShape))
        Spacer(Modifier.width(12.dp))
        Text(when (e.service) { "trakt" -> "Trakt"; "anilist" -> "AniList"; "mal" -> "MyAnimeList"; else -> e.service }, Modifier.width(96.dp), style = MaterialTheme.typography.labelLarge, color = Z.Text2)
        Column(Modifier.weight(1f)) {
          Text(e.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(e.result, style = MaterialTheme.typography.bodySmall, color = if (e.ok) Z.Text2 else Z.Warning, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(ago(e.at), style = MaterialTheme.typography.labelMedium, color = Z.Muted)
      }
    }
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
  /** Blocking (call off the main thread). One link per line; returns what happened, for a toast or the phone. */
  var packs by remember { mutableStateOf(store.syncler) }
  fun stremio(url: String) = Addon.load(url).let { a -> store.addons = store.addons.filter { it.url != a.url } + a; addons = store.addons; "Added ${a.name}." }
  fun syncler(url: String) = Syncler.install(store, url).let { p -> packs = store.syncler; "Added " + p.joinToString { "${it.name} (${it.sites.size} sites)" } + "." }
  /** Blocking (call off the main thread). One link per line, Stremio addon or Syncler vendor/package; returns what happened. */
  fun install(text: String): String = text.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString("\n") { url ->
    // A Stremio link ends in manifest.json; try the likelier kind first, then the other.
    val tries = if (url.substringBefore('?').endsWith("manifest.json", ignoreCase = true)) listOf(::stremio, ::syncler) else listOf(::syncler, ::stremio)
    val first = runCatching { tries[0](url) }
    first.getOrNull() ?: runCatching { tries[1](url) }.getOrNull() ?: (first.exceptionOrNull()?.message ?: "Couldn't add that.")
  }.ifEmpty { "Paste a link first." }
  var phone by remember { mutableStateOf<PhoneDrop?>(null) }
  DisposableEffect(Unit) { onDispose { phone?.stop() } }
  Column {
    Header("Addons", "Stremio addons and Syncler Express packages find streams for Zylo. Add one by pasting its link (a Stremio manifest, or a Syncler vendor or package) — easiest from your phone.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      ZButton(if (phone == null) "Add from Phone" else "Close Phone Link", {
        phone = phone?.let { it.stop(); null } ?: runCatching { PhoneDrop { t -> install(t).also { msg -> scope.launch { toast(ctx, msg) } } } }.getOrNull()
      }, primary = true, icon = Icons.Filled.Add)
      ZButton("Type a Link", {
        ask(ctx, "Addon or Syncler link", "") { url -> scope.launch { toast(ctx, withContext(Dispatchers.IO) { install(url) }) } }
      })
    }
    phone?.let { p ->
      Row(Modifier.padding(top = 16.dp).background(Z.Card, Z.Medium).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Qr(p.url, 150.dp)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
          Text("Scan with your phone", style = MaterialTheme.typography.titleMedium)
          Text("Your phone must be on the same Wi-Fi as the TV. Paste the addon's manifest link on the page that opens; it's added here straight away. You can send several.",
            Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
          Text(p.url.substringBefore("?"), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = Z.Muted)
        }
      }
    }
    packs.forEach { p ->
      Row(Modifier.padding(top = 16.dp).fillMaxWidth().background(Z.Card, Z.Medium).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Tile("S", 44.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
          Text(p.name, style = MaterialTheme.typography.titleMedium)
          Text("Syncler Express package · ${p.sites.size} sites: " + p.sites.joinToString { it.optString("name") }, style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
          MetaLine(listOf("Torrents play through your debrid service"), color = Z.Muted)
        }
        Spacer(Modifier.width(16.dp))
        ZButton("Remove", { store.syncler = store.syncler.filter { it.url != p.url }; packs = store.syncler })
      }
    }
    if (addons.isEmpty() && packs.isEmpty()) Text("No addons yet.", Modifier.padding(top = 16.dp), color = Z.Text2)
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
private fun About(store: Store) = Column(Modifier.verticalScroll(rememberScrollState())) {
  Logo(40.dp)
  Text("One interface. Your entire media world.", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleLarge, color = Z.Text2)
  Text("Version ${BuildConfig.VERSION_NAME}", Modifier.padding(top = 24.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
  Text(
    "Film and TV information and artwork come from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.",
    Modifier.padding(top = 8.dp).fillMaxWidth(0.8f), style = MaterialTheme.typography.bodySmall, color = Z.Muted,
  )
  if (Updates.feed.isNotBlank()) ZButton("Check for Updates", { Updates.asked++ }, Modifier.padding(top = 16.dp))
  // The last crash, so it can be photographed and sent for fixing.
  var crash by remember { mutableStateOf(store.str("last_crash")) }
  if (crash.isNotEmpty()) {
    Text("Last crash", Modifier.padding(top = 28.dp), style = MaterialTheme.typography.titleMedium)
    Text("Take a photo of this and send it over: it says exactly where Zylo went wrong.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text2)
    Text(crash.lines().take(14).joinToString("\n"), Modifier.padding(top = 10.dp).fillMaxWidth().background(Z.Card, Z.Medium).padding(14.dp),
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = Z.Text2)
    ZButton("Clear", { store.put("last_crash", ""); crash = "" }, Modifier.padding(top = 10.dp))
  }
}

/** Native text dialog: the TV keyboard (and the Google TV phone app's keyboard) works best with a plain EditText. */
fun ask(ctx: Context, title: String, current: String, secret: Boolean = false, pin: Boolean = false, done: (String) -> Unit) {
  val input = EditText(ctx).apply {
    setText(current); setSingleLine(); setSelectAllOnFocus(true)
    if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    if (pin) { inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD; filters = arrayOf(android.text.InputFilter.LengthFilter(4)) }
  }
  AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title).setView(input)
    .setPositiveButton("Save") { _, _ -> done(input.text.toString()) }.setNegativeButton("Cancel", null).show()
}

fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
