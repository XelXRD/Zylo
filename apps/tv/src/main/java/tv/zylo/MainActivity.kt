package tv.zylo

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface Screen {
  data object Home : Screen
  data object Settings : Screen
  data class Title(val item: Item) : Screen
  data class Sources(val item: Item, val videoId: String, val season: Int?, val episode: Int?, val label: String) : Screen
  data class Play(val item: Item, val season: Int?, val episode: Int?, val url: String, val headers: Map<String, String>) : Screen
}

private val Pad = 48.dp
private val Dim = Color(0xFFB8B8B8)

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val store = Store(this)
    val home = HomeState()
    setContent {
      MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFF2B33D), onPrimary = Color.Black)) {
        Surface(Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = Color.Black)) {
          val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
          val saved = rememberSaveableStateHolder()
          val open: (Screen) -> Unit = { stack.add(it) }
          BackHandler(stack.size > 1) { saved.removeState("${stack.lastIndex}${stack.last()}"); stack.removeAt(stack.lastIndex) }
          val s = stack.last()
          saved.SaveableStateProvider("${stack.lastIndex}$s") {
            when (s) {
              Screen.Home -> Home(store, home, open)
              Screen.Settings -> Settings(store)
              is Screen.Title -> TitlePage(store, s.item, open)
              is Screen.Sources -> SourcesPage(store, s, open)
              is Screen.Play -> PlayerPage(store, s)
            }
          }
        }
      }
    }
  }
}

// --- Home ----------------------------------------------------------------------------

class HomeState {
  var rows by mutableStateOf<List<Pair<String, List<Item>>>>(emptyList())
  var error by mutableStateOf<String?>(null)
  var hero by mutableStateOf<Item?>(null)
  var loadedFor = ""
  var focus = "" to 0 // row name, index: where focus goes back to
  val list = LazyListState()
  val rowLists = mutableMapOf<String, LazyListState>()

  suspend fun load(t: Tmdb, key: String) = withContext(Dispatchers.IO) {
    loadedFor = key
    error = null
    val got = ROWS.map { (name, path, type) ->
      async { name to runCatching { t.list(path, type) }.getOrElse { if (it is HttpError && it.code == 401) error = "TMDB didn't accept your key. Check it in Settings."; emptyList() } }
    }.awaitAll()
    rows = got.filter { it.second.isNotEmpty() }
    if (rows.isEmpty() && error == null) error = "Couldn't reach TMDB. Check the Shield's internet connection."
  }
}

@Composable
fun Home(store: Store, home: HomeState, open: (Screen) -> Unit) {
  val key = store.tmdb
  if (key.isEmpty()) return Welcome(open)
  LaunchedEffect(key) { if (home.loadedFor != key) home.load(Tmdb(key), key) }
  val rows = remember(home.rows) { listOfNotNull(store.continueWatching().takeIf { it.isNotEmpty() }?.let { "Continue watching" to it }) + home.rows }
  val requester = remember { FocusRequester() }
  LaunchedEffect(rows.isNotEmpty()) { if (rows.isNotEmpty()) { delay(100); runCatching { requester.requestFocus() } } }

  // Trailer: once focus has settled on a poster for a moment, look up its trailer and start it behind the hero.
  val hero = home.hero
  var trailer by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(hero) {
    trailer = null
    if (hero == null) return@LaunchedEffect
    delay(1200)
    trailer = withContext(Dispatchers.IO) { runCatching { Tmdb(key).trailer(hero.type, hero.id) }.getOrNull() }
  }

  Column(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxWidth().weight(0.47f)) {
      Hero(hero, trailer)
      Button(onClick = { open(Screen.Settings) }, Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = Pad)) { Text("Settings") }
    }
    if (home.error != null && rows.isEmpty()) Text(home.error!!, Modifier.padding(Pad), color = Dim)
    else if (rows.isEmpty()) Text("Loading…", Modifier.padding(Pad), color = Dim)
    LazyColumn(Modifier.weight(0.53f), state = home.list, contentPadding = PaddingValues(bottom = 40.dp)) {
      items(rows, key = { it.first }) { (name, items) ->
        Column {
          Text(name, Modifier.padding(start = Pad, top = 6.dp), style = MaterialTheme.typography.titleMedium)
          LazyRow(
            state = home.rowLists.getOrPut(name) { LazyListState() },
            contentPadding = PaddingValues(horizontal = Pad, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            itemsIndexed(items, key = { _, it -> "${it.type}${it.id}" }) { i, item ->
              Poster(
                item,
                if (home.focus == name to i || (home.focus.first.isEmpty() && name == rows[0].first && i == 0)) Modifier.focusRequester(requester) else Modifier,
                onFocus = { home.focus = name to i; home.hero = item },
                onClick = { open(Screen.Title(item)) },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun Welcome(open: (Screen) -> Unit) {
  val r = remember { FocusRequester() }
  LaunchedEffect(Unit) { r.requestFocus() }
  Column(Modifier.fillMaxSize().padding(Pad * 2), verticalArrangement = Arrangement.Center) {
    Text("Welcome to Zylo", style = MaterialTheme.typography.displayMedium)
    Spacer(Modifier.height(16.dp))
    Text(
      "Zylo gets film and TV details from TMDB, which needs your own free key: sign up at themoviedb.org, then Settings > API. " +
        "Paste the API key (32 characters) or the read access token into Zylo's settings.",
      Modifier.fillMaxWidth(0.6f), color = Dim,
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = { open(Screen.Settings) }, Modifier.focusRequester(r)) { Text("Open settings") }
  }
}

@Composable
fun Hero(item: Item?, trailer: String?) {
  Box(Modifier.fillMaxSize()) {
    AsyncImage(img(item?.backdrop, "w1280"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    if (trailer != null) key(trailer) { Trailer(trailer, Modifier.fillMaxSize()) }
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black, 0.6f to Color.Transparent)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black)))
    if (item != null) Column(Modifier.padding(start = Pad, top = 36.dp).fillMaxWidth(0.42f)) {
      Text(item.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(listOf(item.year, if (item.type == "tv") "Series" else "Film").filter { it.isNotEmpty() }.joinToString(" · "), Modifier.padding(vertical = 6.dp), color = Dim)
      Text(item.overview, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Dim)
    }
  }
}

/** YouTube trailer in a WebView that never takes focus. Fades in once it's actually playing; fades out when it ends. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Trailer(youtubeKey: String, modifier: Modifier) {
  var playing by remember { mutableStateOf(false) }
  val alpha by animateFloatAsState(if (playing) 1f else 0f, tween(700), label = "trailer")
  AndroidView(
    modifier = modifier.alpha(alpha),
    factory = { ctx ->
      WebView(ctx).apply {
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = WebChromeClient()
        addJavascriptInterface(object {
          @JavascriptInterface fun state(s: Int) = post { if (s == 1) playing = true else if (s == 0) playing = false }
        }, "Zylo")
        // An https base URL gives YouTube the referrer it now requires for embeds.
        loadDataWithBaseURL("https://zylo.tv/", trailerHtml(youtubeKey), "text/html", "utf-8", null)
      }
    },
    onRelease = { it.destroy() },
  )
}

private fun trailerHtml(key: String) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;background:#000;overflow:hidden;height:100%}#p{position:absolute;left:0;top:50%;width:100vw;height:56.25vw;transform:translateY(-50%);border:0}</style>
</head><body><div id="p"></div>
<script>
function onYouTubeIframeAPIReady(){new YT.Player('p',{videoId:'$key',playerVars:{autoplay:1,controls:0,rel:0,playsinline:1,iv_load_policy:3,disablekb:1,fs:0,modestbranding:1,origin:'https://zylo.tv'},
events:{onReady:function(e){e.target.playVideo()},onStateChange:function(e){Zylo.state(e.data)}}})}
</script><script src="https://www.youtube.com/iframe_api"></script></body></html>
"""

@Composable
fun Poster(item: Item, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
  Card(onClick, modifier.width(124.dp).aspectRatio(2f / 3f).onFocusChanged { if (it.isFocused) onFocus() }, scale = CardDefaults.scale(focusedScale = 1.1f)) {
    AsyncImage(img(item.poster, "w342"), item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
  }
}

// --- Title page -------------------------------------------------------------------------

@Composable
fun TitlePage(store: Store, item: Item, open: (Screen) -> Unit) {
  val tmdb = remember { Tmdb(store.tmdb) }
  var info by remember { mutableStateOf<JSONObject?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var season by rememberSaveable { mutableStateOf<Int?>(null) }
  var lastEpisode by rememberSaveable { mutableIntStateOf(0) }
  var episodes by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
  val first = remember { FocusRequester() }

  LaunchedEffect(item) {
    runCatching { withContext(Dispatchers.IO) { tmdb.details(item.type, item.id) } }
      .onSuccess { d ->
        info = d
        val numbers = d.optJSONArray("seasons")?.objects().orEmpty().map { it.optInt("season_number") }
        if (season == null) season = numbers.firstOrNull { it > 0 } ?: numbers.firstOrNull()
      }
      .onFailure { error = it.message }
  }
  LaunchedEffect(season) {
    val n = season ?: return@LaunchedEffect
    episodes = runCatching { withContext(Dispatchers.IO) { tmdb.season(item.id, n).optJSONArray("episodes")?.objects().orEmpty() } }.getOrDefault(emptyList())
  }
  LaunchedEffect(info, episodes) { if (info != null && (item.type == "movie" || episodes.isNotEmpty())) { delay(100); runCatching { first.requestFocus() } } }

  val d = info
  val imdb = d?.optJSONObject("external_ids")?.str("imdb_id") ?: "tmdb:${item.id}"
  Box(Modifier.fillMaxSize()) {
    AsyncImage(img(item.backdrop, "w1280"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.95f), 0.7f to Color.Transparent)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black)))
    Column(Modifier.fillMaxSize().padding(Pad)) {
      Text(item.title, style = MaterialTheme.typography.displayMedium, maxLines = 2)
      if (d != null) {
        val meta = listOfNotNull(
          item.year.ifEmpty { null },
          d.optInt("runtime").takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m" },
          d.optInt("number_of_seasons").takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" },
          d.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { "★ %.1f".format(it) },
          d.optJSONArray("genres")?.objects()?.take(3)?.joinToString(", ") { it.optString("name") }?.ifEmpty { null },
        )
        Text(meta.joinToString(" · "), Modifier.padding(vertical = 8.dp), color = Dim)
      }
      Text(d?.str("overview") ?: item.overview, Modifier.fillMaxWidth(0.55f), maxLines = 5, overflow = TextOverflow.Ellipsis, color = Dim)
      error?.let { Text(it, Modifier.padding(top = 12.dp), color = Color(0xFFFF8A80)) }
      Spacer(Modifier.height(24.dp))
      if (d != null && item.type == "movie") {
        val p = store.progress(progressKey(item, null, null))
        val resume = p?.optLong("pos")?.takeIf { it > 60_000 && it < p.optLong("dur") * 0.9 }
        Button(onClick = { open(Screen.Sources(item, imdb, null, null, "")) }, Modifier.focusRequester(first)) {
          Text(if (resume != null) "Resume from ${clock(resume)}" else "Play")
        }
      }
      if (d != null && item.type == "tv") {
        val seasons = d.optJSONArray("seasons")?.objects().orEmpty()
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          items(seasons) { s ->
            val n = s.optInt("season_number")
            val label = if (n == 0) "Specials" else "Season $n"
            if (n == season) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { season = n; lastEpisode = 0 }) { Text(label) }
          }
        }
        Spacer(Modifier.height(16.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
          itemsIndexed(episodes) { i, e ->
            val s = season ?: 0
            val n = e.optInt("episode_number")
            Column(Modifier.width(240.dp)) {
              Card(
                onClick = { lastEpisode = i; open(Screen.Sources(item, "$imdb:$s:$n", s, n, "S$s:E$n · ${e.optString("name")}")) },
                modifier = (if (i == lastEpisode) Modifier.focusRequester(first) else Modifier).fillMaxWidth().aspectRatio(16f / 9f),
              ) { AsyncImage(img(e.str("still_path"), "w300"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
              Text("$n. ${e.optString("name")}", Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }
  }
}

fun clock(ms: Long): String {
  val s = ms / 1000
  return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// --- Sources ----------------------------------------------------------------------------

@Composable
fun SourcesPage(store: Store, s: Screen.Sources, open: (Screen) -> Unit) {
  var found by remember { mutableStateOf<Found?>(null) }
  var status by remember { mutableStateOf<String?>(null) }
  val scope = rememberCoroutineScope()
  val first = remember { FocusRequester() }
  val addons = remember { store.addons }

  LaunchedEffect(s) { if (addons.isNotEmpty()) found = findSources(store, s.item.type, s.videoId) }
  LaunchedEffect(found) { if (found?.sources?.isNotEmpty() == true) { delay(100); runCatching { first.requestFocus() } } }

  fun play(src: Source) {
    val url = src.url
    if (url != null) return open(Screen.Play(s.item, s.season, s.episode, url, src.headers))
    val service = src.debrid ?: return
    scope.launch {
      status = "Getting the link from ${SERVICES[service]}…"
      runCatching { withContext(Dispatchers.IO) { debrid(service, store.debrid(service)).resolve(src.infoHash!!, Hint(src.filename, src.fileIdx, s.season, s.episode)) } }
        .onSuccess { status = null; open(Screen.Play(s.item, s.season, s.episode, it, emptyMap())) }
        .onFailure { status = it.message ?: "That didn't work. Try another source." }
    }
  }

  Column(Modifier.fillMaxSize().padding(Pad)) {
    Text(s.item.title, style = MaterialTheme.typography.displaySmall)
    if (s.label.isNotEmpty()) Text(s.label, color = Dim)
    status?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.primary) }
    Spacer(Modifier.height(16.dp))
    val f = found
    when {
      addons.isEmpty() -> Text("Add a Stremio addon in Settings to find sources.", color = Dim)
      f == null -> Text("Finding sources…", color = Dim)
      f.sources.isEmpty() -> Text(
        "No playable sources found." + when {
          f.waiting > 0 && SERVICES.keys.none { store.debrid(it).isNotEmpty() } -> " ${f.waiting} torrent sources need a debrid service: connect one in Settings."
          f.waiting > 0 -> " ${f.waiting} torrent sources aren't ready on your debrid service."
          else -> ""
        },
        color = Dim,
      )
      else -> LazyColumn(Modifier.fillMaxWidth(0.8f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(f.sources) { i, src ->
          ListItem(
            selected = false,
            onClick = { play(src) },
            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            headlineContent = { Text(listOfNotNull(src.quality, src.hdr.ifEmpty { null }, src.size).joinToString(" · ").ifEmpty { src.label }, maxLines = 1) },
            supportingContent = { Text("${src.addon} · ${src.filename ?: src.detail.lineSequence().firstOrNull().orEmpty()}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingContent = { Text(src.debrid?.let { SERVICES[it] + if (src.cached) " ✓" else "" } ?: "Direct") },
          )
        }
      }
    }
    f?.errors?.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Dim) }
  }
}

// --- Player -------------------------------------------------------------------------------

@OptIn(UnstableApi::class)
@Composable
fun PlayerPage(store: Store, s: Screen.Play) {
  val ctx = LocalContext.current
  val key = progressKey(s.item, s.season, s.episode)
  var error by remember { mutableStateOf<String?>(null) }
  val player = remember {
    val http = DefaultHttpDataSource.Factory().setUserAgent("Zylo/0.8.0").setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(s.headers)
    ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(http)).build().apply {
      setMediaItem(MediaItem.fromUri(s.url))
      store.progress(key)?.let { p -> p.optLong("pos").takeIf { it > 60_000 && it < p.optLong("dur") * 0.9 }?.let { seekTo(it) } }
      prepare()
      playWhenReady = true
    }
  }
  fun save() {
    if (player.duration > 0) store.saveProgress(key, JSONObject().put("item", s.item.toJson()).put("pos", player.currentPosition).put("dur", player.duration).put("at", System.currentTimeMillis()))
  }
  LaunchedEffect(player) { while (true) { delay(10_000); save() } }
  DisposableEffect(player) {
    val l = object : Player.Listener {
      override fun onPlayerError(e: PlaybackException) { error = "Couldn't play this source (${e.errorCodeName}). Go back and try another." }
    }
    player.addListener(l)
    onDispose { save(); player.release() }
  }
  Box(Modifier.fillMaxSize()) {
    AndroidView(
      factory = { PlayerView(it).apply { this.player = player; keepScreenOn = true; setShowSubtitleButton(true); post { requestFocus() } } },
      modifier = Modifier.fillMaxSize(),
    )
    error?.let { Text(it, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.8f)).padding(24.dp)) }
  }
}

// --- Settings -------------------------------------------------------------------------------

@Composable
fun Settings(store: Store) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var tick by remember { mutableIntStateOf(0) } // bump to re-read the store
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }

  key(tick) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = Pad), contentPadding = PaddingValues(vertical = Pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      item { Text("Settings", style = MaterialTheme.typography.displaySmall) }
      item { Heading("Film and TV info") }
      item {
        Setting("TMDB key", if (store.tmdb.isEmpty()) "Not set" else "Set", Modifier.focusRequester(first)) {
          ask(ctx, "TMDB API key or read access token", store.tmdb) { store.tmdb = it; tick++ }
        }
      }
      item { Heading("Stremio addons") }
      items(store.addons, key = { it.url }) { a ->
        Setting(a.name, "Remove") { store.addons = store.addons.filter { it.url != a.url }; tick++ }
      }
      item {
        Setting("Add an addon", "Paste its manifest link") {
          ask(ctx, "Addon manifest link", "") { url ->
            scope.launch {
              withContext(Dispatchers.IO) { runCatching { Addon.load(url) } }
                .onSuccess { a -> store.addons = store.addons.filter { it.url != a.url } + a; toast(ctx, "Added ${a.name}"); tick++ }
                .onFailure { toast(ctx, it.message ?: "Couldn't add that addon.") }
            }
          }
        }
      }
      item { Heading("Debrid") }
      items(SERVICES.toList()) { (s, name) ->
        Setting(name, if (store.debrid(s).isEmpty()) "Not connected" else "Connected") { ask(ctx, "$name API key (leave empty to disconnect)", store.debrid(s)) { store.setDebrid(s, it); tick++ } }
      }
    }
  }
}

@Composable
fun Heading(text: String) = Text(text, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
fun Setting(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
  ListItem(selected = false, onClick = onClick, modifier = modifier.fillMaxWidth(0.6f), headlineContent = { Text(title) }, trailingContent = { Text(value, color = Dim) })

/** Native text dialog: the TV keyboard (and the Google TV phone remote's keyboard) works best with a plain EditText. */
fun ask(ctx: Context, title: String, current: String, done: (String) -> Unit) {
  val input = EditText(ctx).apply { setText(current); setSingleLine(); setSelectAllOnFocus(true) }
  AlertDialog.Builder(ctx).setTitle(title).setView(input).setPositiveButton("Save") { _, _ -> done(input.text.toString()) }.setNegativeButton("Cancel", null).show()
}

fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
