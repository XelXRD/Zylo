// Live TV from an Xtream Codes account: categories, a channel guide (EPG) and full-screen playback with channel zapping.
// Xtream Codes is a plain HTTP API on the provider's server (player_api.php); everything is called from the TV.
package tv.zylo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Channel(val id: Int, val num: Int, val name: String, val logo: String?, val category: String)
data class Category(val id: String, val name: String)
/** A programme in the guide. Times in epoch milliseconds. */
data class Programme(val title: String, val description: String, val start: Long, val end: Long)

/** An Xtream Codes account. [formats]: the stream formats the provider allows ("m3u8", "ts"). */
class Xtream(val server: String, val user: String, val pass: String, val formats: List<String> = emptyList()) {
  private fun api(action: String = "", extra: String = "") =
    "$server/player_api.php?username=${enc(user)}&password=${enc(pass)}" + (if (action.isNotEmpty()) "&action=$action" else "") + extra

  /** Checks the account. Returns the provider's account details; throws a readable error when it isn't usable. */
  fun login(fetch: (String) -> String = { http(it) }): JSONObject {
    val o = runCatching { JSONObject(fetch(api())) }.getOrElse { throw Exception(if (it is HttpError) it.message else "That server didn't answer like an Xtream Codes server. Check the address.") }
    val info = o.optJSONObject("user_info") ?: throw Exception("That server didn't answer like an Xtream Codes server. Check the address.")
    if (info.optInt("auth", 0) != 1) throw Exception("The provider didn't accept that username and password.")
    val status = info.optString("status")
    if (status.isNotEmpty() && !status.equals("Active", true)) throw Exception("The provider says this account is $status.")
    return o
  }

  fun categories(fetch: (String) -> String = { http(it) }) =
    JSONArray(fetch(api("get_live_categories"))).objects().map { Category(it.optString("category_id"), it.optString("category_name").trim()) }

  fun channels(category: String?, fetch: (String) -> String = { http(it, timeout = 15_000) }) =
    JSONArray(fetch(api("get_live_streams", category?.let { "&category_id=${enc(it)}" } ?: ""))).objects().mapNotNull {
      val id = it.optInt("stream_id").takeIf { n -> n > 0 } ?: return@mapNotNull null
      Channel(id, it.optInt("num"), it.optString("name").trim(), it.str("stream_icon")?.takeIf { u -> u.startsWith("http") }, it.optString("category_id"))
    }

  /** What's on now and next on one channel (the provider's guide, base64-encoded text). */
  fun guide(id: Int, fetch: (String) -> String = { http(it) }) =
    JSONObject(fetch(api("get_short_epg", "&stream_id=$id&limit=12"))).optJSONArray("epg_listings")?.objects()?.mapNotNull {
      val start = it.optString("start_timestamp").toLongOrNull() ?: return@mapNotNull null
      val end = (it.optString("stop_timestamp").ifEmpty { it.optString("end_timestamp") }).toLongOrNull() ?: return@mapNotNull null
      Programme(b64(it.optString("title")), b64(it.optString("description")), start * 1000, end * 1000)
    }?.sortedBy { it.start }.orEmpty()

  /** HLS when the provider allows it (steadier on TV), else MPEG-TS. */
  fun streamUrl(c: Channel) = "$server/live/${enc(user)}/${enc(pass)}/${c.id}." + if (formats.isEmpty() || "m3u8" in formats) "m3u8" else "ts"

  companion object {
    fun from(store: Store) = store.str("xc_server").takeIf { it.isNotEmpty() }?.let {
      Xtream(it, store.str("xc_user"), store.str("xc_pass"), store.str("xc_formats").split(',').filter { f -> f.isNotBlank() })
    }

    /**
     * The server from whatever the provider gave you: "host:port", a full URL, or the M3U playlist link
     * (get.php?username=…&password=…), which carries the username and password too.
     */
    fun parse(input: String): Triple<String, String?, String?> {
      val t = input.trim().let { if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it)) it else "http://$it" }
      val q = t.substringAfter('?', "").split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
      val base = Regex("^(https?://[^/?#]+)", RegexOption.IGNORE_CASE).find(t)!!.groupValues[1]
      return Triple(base, q["username"], q["password"])
    }

    fun connect(store: Store, server: String, user: String, pass: String) {
      val x = Xtream(server, user.trim(), pass.trim())
      val o = x.login()
      val formats = strings(o.optJSONObject("user_info")?.optJSONArray("allowed_output_formats"))
      store.put("xc_server", server); store.put("xc_user", user.trim()); store.put("xc_pass", pass.trim()); store.put("xc_formats", formats.joinToString(","))
      store.put("xc_info", o.toString())
    }

    fun disconnect(store: Store) = listOf("xc_server", "xc_user", "xc_pass", "xc_formats", "xc_info", "xc_cat").forEach { store.put(it, "") }

    private fun b64(s: String): String = runCatching {
      val bytes = try { java.util.Base64.getDecoder().decode(s.trim()) } catch (e: NoClassDefFoundError) { android.util.Base64.decode(s, 0) }
      String(bytes, Charsets.UTF_8).takeIf { d -> d.isNotBlank() && d.none { it == '�' } }
    }.getOrNull() ?: s // some panels send plain text
  }
}

/** Live TV page state; kept across visits so the category, list and guide are where you left them. */
class LiveState {
  var categories by mutableStateOf<List<Category>>(emptyList())
  var category by mutableStateOf<Category?>(null)
  var channels by mutableStateOf<List<Channel>>(emptyList())
  var error by mutableStateOf<String?>(null)
  var loading by mutableStateOf(false)
  val guide: SnapshotStateMap<Int, List<Programme>> = mutableStateMapOf()
  var focused by mutableStateOf<Pair<Channel, Programme?>?>(null)
  var playing by mutableIntStateOf(0) // index into channels for the player
  val focus = FocusMemory()
  internal val gate = Semaphore(4) // guide requests at once
}

private val ALL = Category("", "All Channels")
private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.UK).format(Date(t))

@Composable
fun LivePage(store: Store, s: LiveState, nav: Nav, restore: Boolean) {
  val x = remember { Xtream.from(store) }
  if (x == null) {
    Column(Modifier.fillMaxSize().padding(start = Z.Pad, top = 110.dp)) {
      Text("Live TV", style = MaterialTheme.typography.displaySmall)
      Text("Add your Xtream Codes details in Settings > Live TV to see your channels and guide here.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyLarge, color = Z.Text2)
      ZButton("Open Settings", { nav.push(Screen.Settings) }, Modifier.padding(top = 20.dp), primary = true)
    }
    return
  }
  LaunchedEffect(Unit) {
    if (s.categories.isEmpty()) runCatching { withContext(Dispatchers.IO) { x.categories() } }.onSuccess { list ->
      s.categories = list
      if (s.category == null) s.category = list.find { it.id == store.str("xc_cat") } ?: ALL
    }.onFailure { s.error = it.message; if (s.category == null) s.category = ALL }
  }
  LaunchedEffect(s.category) {
    val c = s.category ?: return@LaunchedEffect
    if (s.channels.isNotEmpty() && s.channels.all { c == ALL || it.category == c.id }) return@LaunchedEffect // already showing it
    s.loading = true
    s.error = null
    runCatching { withContext(Dispatchers.IO) { x.channels(c.id.ifEmpty { null }) } }.onSuccess { s.channels = it }.onFailure { s.error = it.message }
    s.loading = false
  }
  RestoreFocus(s.focus, s.channels.isNotEmpty() && restore)
  var picking by remember { mutableStateOf(false) }

  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(top = 84.dp)) {
      Details(s)
      // Category dropdown and the time ruler.
      val now = remember { System.currentTimeMillis() }
      val windowStart = now - now % (30 * 60_000) // the guide starts at the current half hour
      Row(Modifier.padding(start = Z.Pad, end = Z.Pad, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(CHANNEL_W)) {
          ZSurface({ picking = true }, Modifier.focusMemory(s.focus, "category"), container = Z.Card) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(s.category?.name ?: "Categories", Modifier.widthIn(max = 180.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Spacer(Modifier.width(6.dp))
              Glyph(Icons.Filled.KeyboardArrowDown, 18.dp)
            }
          }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
          val perMin = maxWidth / WINDOW_MIN.toFloat()
          repeat(WINDOW_MIN / 30) { i ->
            Text(hm(windowStart + i * 30 * 60_000L), Modifier.offset(x = perMin * (i * 30f)), style = MaterialTheme.typography.labelMedium, color = Z.Muted)
          }
        }
      }
      when {
        s.channels.isEmpty() && s.loading -> Loading("Loading channels…")
        s.channels.isEmpty() -> Text(s.error ?: "No channels in ${s.category?.name ?: "this category"}.", Modifier.padding(start = Z.Pad, top = 24.dp), color = if (s.error != null) Z.Error else Z.Text2)
        else -> Guide(x, s, nav, windowStart, now)
      }
    }
    if (picking) CategoryPicker(s.categories, s.category, { picking = false }) { c ->
      picking = false
      store.put("xc_cat", c.id)
      s.focused = null
      s.category = c
    }
  }
}

private val CHANNEL_W = 250.dp
private const val WINDOW_MIN = 180 // the guide shows three hours

/** What's focused: the programme's title, time, a progress bar if it's on now, and its description. */
@Composable
private fun Details(s: LiveState) {
  val (c, p) = s.focused ?: return Spacer(Modifier.height(150.dp))
  Row(Modifier.height(150.dp).padding(horizontal = Z.Pad), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(96.dp).background(Z.Card, Z.Medium), contentAlignment = Alignment.Center) {
      if (c.logo != null) AsyncImage(c.logo, null, Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
      else Text(c.name.take(2).uppercase(), style = MaterialTheme.typography.titleLarge, color = Z.Text2)
    }
    Spacer(Modifier.width(24.dp))
    Column(Modifier.weight(1f)) {
      Text(c.name, style = MaterialTheme.typography.labelLarge, color = Z.Text2, maxLines = 1)
      Text(p?.title ?: "No guide information", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (p != null) {
        val now = System.currentTimeMillis()
        val on = now in p.start until p.end
        MetaLine(listOf("${hm(p.start)} – ${hm(p.end)}", if (on) "On now" else "${(p.end - p.start) / 60_000} min"), Modifier.padding(top = 4.dp), color = if (on) Z.Text else Z.Text2)
        if (on) ProgressBar((now - p.start).toFloat() / (p.end - p.start), Modifier.padding(top = 6.dp).width(240.dp))
        if (p.description.isNotBlank()) Text(p.description, Modifier.padding(top = 6.dp).fillMaxWidth(0.8f), style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

/** The channel guide: one row per channel, its programmes laid out on a three-hour timeline, with a line at "now". */
@Composable
private fun Guide(x: Xtream, s: LiveState, nav: Nav, windowStart: Long, now: Long) {
  val windowEnd = windowStart + WINDOW_MIN * 60_000L
  val list = rememberLazyListState()
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val perMin = (maxWidth - CHANNEL_W - Z.Pad * 2) / WINDOW_MIN.toFloat()
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 40.dp)) {
      itemsIndexed(s.channels, key = { _, c -> c.id }) { i, c ->
        // Each row loads its own guide when it scrolls into view, a few at a time.
        LaunchedEffect(c.id) {
          if (c.id !in s.guide) s.guide[c.id] = s.gate.withPermit { withContext(Dispatchers.IO) { runCatching { x.guide(c.id) }.getOrDefault(emptyList()) } }
        }
        val shows = s.guide[c.id].orEmpty().filter { it.end > windowStart && it.start < windowEnd }
        fun play() { s.playing = i; nav.push(Screen.LivePlay) }
        Row(Modifier.padding(start = Z.Pad, end = Z.Pad).height(64.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
          // Channel: number, logo, name.
          Row(Modifier.width(CHANNEL_W).fillMaxHeight().padding(end = 6.dp).background(Z.Surface, Z.Small).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (c.num > 0) "${c.num}" else "", Modifier.width(38.dp), style = MaterialTheme.typography.labelMedium, color = Z.Muted)
            Box(Modifier.size(40.dp, 30.dp), contentAlignment = Alignment.Center) {
              if (c.logo != null) AsyncImage(c.logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Spacer(Modifier.width(10.dp))
            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          Box(Modifier.weight(1f).fillMaxHeight()) {
            if (shows.isEmpty()) {
              Cell(c.name.takeIf { s.guide.containsKey(c.id) }?.let { "No guide information" } ?: "", Modifier.fillMaxSize(), live = true, s, c, null, "row/${c.id}/none", ::play)
            } else shows.forEach { p ->
              val from = maxOf(p.start, windowStart)
              val to = minOf(p.end, windowEnd)
              Cell(p.title, Modifier.offset(x = perMin * ((from - windowStart) / 60_000f)).width((perMin * ((to - from) / 60_000f) - 4.dp).coerceAtLeast(6.dp)).fillMaxHeight(),
                now in p.start until p.end, s, c, p, "row/${c.id}/${p.start}", ::play)
            }
          }
        }
      }
    }
    // "Now" line across the timeline.
    val nowX = CHANNEL_W + Z.Pad + perMin * ((now - windowStart) / 60_000f)
    Box(Modifier.offset(x = nowX).width(2.dp).fillMaxHeight().background(Brush.verticalGradient(listOf(Z.Red, Z.Red.copy(alpha = 0.15f)))))
  }
}

@Composable
private fun Cell(text: String, modifier: Modifier, live: Boolean, s: LiveState, c: Channel, p: Programme?, key: String, play: () -> Unit) =
  ZSurface(play, modifier.focusMemory(s.focus, key, default = s.focus.key == null && live && s.channels.firstOrNull() == c)
    .onFocusChanged { if (it.isFocused) s.focused = c to p }, container = if (live) Z.Elevated else Z.Card) {
    Text(text, Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge, color = if (live) Z.Text else Z.Text2,
      maxLines = 1, overflow = TextOverflow.Ellipsis)
  }

/** The category dropdown: a panel over the guide; Back closes it. */
@Composable
private fun CategoryPicker(categories: List<Category>, current: Category?, close: () -> Unit, pick: (Category) -> Unit) {
  BackHandler(onBack = close)
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
  Box(Modifier.fillMaxSize().background(Z.Bg.copy(alpha = 0.7f))) {
    Column(Modifier.padding(start = Z.Pad, top = 84.dp).width(360.dp).fillMaxHeight().background(Z.Surface, Z.Large).padding(vertical = 14.dp)) {
      Text("Categories", Modifier.padding(start = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleLarge)
      LazyColumn(contentPadding = PaddingValues(horizontal = 10.dp)) {
        itemsIndexed(listOf(ALL) + categories) { _, c ->
          val selected = c.id == (current ?: ALL).id
          ZSurface({ pick(c) }, (if (selected) Modifier.focusRequester(first) else Modifier).fillMaxWidth().padding(vertical = 2.dp), selected = selected) {
            Text(c.name, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }
  }
}

/** Full-screen live channel. Up/Down (or Channel +/-) changes channel; the banner shows what's on. */
@Composable
fun LivePlayer(store: Store, s: LiveState, nav: Nav) {
  val ctx = LocalContext.current
  val x = remember { Xtream.from(store) } ?: return
  var index by remember { mutableIntStateOf(s.playing.coerceIn(0, (s.channels.size - 1).coerceAtLeast(0))) }
  val channel = s.channels.getOrNull(index) ?: return
  var error by remember { mutableStateOf<String?>(null) }
  var banner by remember { mutableIntStateOf(0) } // bumps to show the banner again
  var showBanner by remember { mutableStateOf(true) }
  val player = remember {
    ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(DefaultHttpDataSource.Factory().setUserAgent(UA).setAllowCrossProtocolRedirects(true))).build()
      .apply { playWhenReady = true }
  }
  DisposableEffect(Unit) {
    val l = object : Player.Listener { override fun onPlayerError(e: PlaybackException) { error = "This channel didn't start (${e.errorCodeName}). Try another, or try again later." } }
    player.addListener(l)
    onDispose { player.removeListener(l); player.release() }
  }
  LaunchedEffect(channel.id) {
    error = null
    player.setMediaItem(MediaItem.fromUri(x.streamUrl(channel)))
    player.prepare()
    s.playing = index
    if (channel.id !in s.guide) s.guide[channel.id] = withContext(Dispatchers.IO) { runCatching { x.guide(channel.id) }.getOrDefault(emptyList()) }
  }
  LaunchedEffect(index, banner) { showBanner = true; delay(5_000); showBanner = false }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
  fun zap(step: Int) { if (s.channels.isNotEmpty()) index = (index + step + s.channels.size) % s.channels.size }
  Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (e.key) {
      Key.DirectionUp, Key.ChannelUp -> { zap(-1); true }
      Key.DirectionDown, Key.ChannelDown -> { zap(1); true }
      Key.DirectionCenter, Key.Enter, Key.DirectionLeft, Key.DirectionRight, Key.Info -> { banner++; true }
      else -> false
    }
  }) {
    AndroidView({ PlayerView(it).apply { useController = false; this.player = player; keepScreenOn = true } }, Modifier.fillMaxSize())
    error?.let { Text(it, Modifier.align(Alignment.Center).padding(40.dp), style = MaterialTheme.typography.titleMedium, color = Z.Text) }
    if (showBanner) {
      val now = System.currentTimeMillis()
      val shows = s.guide[channel.id].orEmpty()
      val on = shows.find { now in it.start until it.end }
      val next = shows.firstOrNull { it.start >= (on?.end ?: now) }
      Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Z.Bg.copy(alpha = 0.95f))))
        .padding(start = Z.Pad, end = Z.Pad, top = 60.dp, bottom = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(84.dp).background(Z.Card, Z.Medium), contentAlignment = Alignment.Center) {
          if (channel.logo != null) AsyncImage(channel.logo, null, Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit)
          else Text(channel.name.take(2).uppercase(), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
          MetaLine(listOfNotNull(channel.num.takeIf { it > 0 }?.toString(), channel.name, "Live"))
          Text(on?.title ?: "No guide information", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (on != null) ProgressBar((now - on.start).toFloat() / (on.end - on.start), Modifier.padding(top = 8.dp).width(320.dp))
          next?.let { Text("Next  ${hm(it.start)}  ${it.title}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 1) }
        }
        Text("▲ ▼  Change channel", style = MaterialTheme.typography.labelMedium, color = Z.Muted)
      }
    }
  }
}
