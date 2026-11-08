// Detail page, Choose Source and the player.
package tv.zylo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// --- Detail page ---------------------------------------------------------------------------------

@Composable
fun TitlePage(store: Store, item: Item, nav: Nav) {
  val t = remember { Tmdb(store.tmdb) }
  val ctx = LocalContext.current
  val focus = remember { nav.ui.titles.getOrPut(item.key) { FocusMemory() } }
  val tv = item.type == "tv"
  var d by remember { mutableStateOf(Tmdb.cached(item)) }
  var error by remember { mutableStateOf<String?>(null) }
  var tab by rememberSaveable { mutableIntStateOf(0) }
  var season by rememberSaveable { mutableStateOf<Int?>(null) }
  var episodes by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
  var inList by remember { mutableStateOf(store.inMyList(item)) }
  var tick by remember { mutableIntStateOf(0) }
  val latest = remember(tick) { store.latest(item) }

  LaunchedEffect(item) { runCatching { withContext(Dispatchers.IO) { t.details(item.type, item.id) } }.onSuccess { d = it }.onFailure { error = it.message } }
  val seasons = d?.optJSONArray("seasons")?.objects().orEmpty()
  LaunchedEffect(d) {
    if (d != null && season == null) season = latest?.season ?: seasons.map { it.optInt("season_number") }.let { n -> n.firstOrNull { it > 0 } ?: n.firstOrNull() }
  }
  LaunchedEffect(season) {
    val n = season ?: return@LaunchedEffect
    episodes = runCatching { withContext(Dispatchers.IO) { t.season(item.id, n).optJSONArray("episodes")?.objects().orEmpty() } }.getOrDefault(emptyList())
  }
  RestoreFocus(focus, d != null)

  val recs = d?.optJSONObject("recommendations")?.optJSONArray("results")?.objects()?.mapNotNull { Tmdb.item(it, null) }.orEmpty()
  val tabs = listOfNotNull("Episodes".takeIf { tv }, "Overview", "More Like This".takeIf { recs.isNotEmpty() })
  val trailer = Tmdb.trailer(d)
  // Where Play starts: resume, next episode after a finished one, or the beginning.
  val playSeason = if (tv) latest?.season ?: season ?: 1 else null
  val playEpisode = if (tv) latest?.episode?.let { if (latest.finished) it + 1 else it } ?: 1 else null
  val resuming = latest?.takeIf { it.started && (!tv || it.episode == playEpisode) }
  val playLabel = (if (resuming != null) "Resume" else "Play") + if (tv) " S$playSeason E$playEpisode" else ""

  Box(Modifier.fillMaxSize()) {
    AsyncImage(img(item.backdrop, "w1280"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Z.Bg, 0.5f to Z.Bg.copy(alpha = 0.85f), 1f to Z.Bg.copy(alpha = 0.35f))))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 0.8f to Z.Bg)))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = Z.Pad, end = Z.Pad, top = 36.dp, bottom = 40.dp)) {
      Row {
        Artwork(img(item.poster, "w342"), item.title, Modifier.width(168.dp).aspectRatio(2f / 3f).clip(Z.Medium))
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
          Text(item.title, style = MaterialTheme.typography.displayMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
          MetaLine(Tmdb.meta(item, d).let { m -> if (tv) m + listOfNotNull(d?.optInt("number_of_episodes")?.takeIf { it > 0 }?.let { "$it Episodes" }) else m }, Modifier.padding(top = 8.dp))
          Rating(d?.optDouble("vote_average", 0.0) ?: item.rating, Modifier.padding(top = 8.dp))
          Text(d?.str("overview") ?: item.overview, Modifier.padding(top = 12.dp).fillMaxWidth(0.92f), style = MaterialTheme.typography.bodyLarge, color = Z.Text2, maxLines = 4, overflow = TextOverflow.Ellipsis)
          error?.let { Text(it, Modifier.padding(top = 8.dp), color = Z.Error) }
          Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ZButton(playLabel, { nav.push(Screen.Sources(item, playSeason, playEpisode, autoplay = true)) }, Modifier.focusMemory(focus, "play", default = true), primary = true, icon = Icons.Filled.PlayArrow)
            ZButton("Choose Source", { nav.push(Screen.Sources(item, playSeason, playEpisode)) }, Modifier.focusMemory(focus, "source"))
            ZButton("My List", { inList = store.toggleMyList(item) }, Modifier.focusMemory(focus, "list"), icon = if (inList) Icons.Filled.Check else Icons.Filled.Add)
            if (trailer != null) ZButton("Trailer", { openTrailer(ctx, trailer) }, Modifier.focusMemory(focus, "trailer"))
            if (!tv) {
              val watched = latest?.finished == true
              ZButton(if (watched) "Watched" else "Mark as Watched", {
                if (watched) store.forget(progressKey(item, null, null)) else store.saveWatch(item, null, null, 1, 1)
                tick++
              }, Modifier.focusMemory(focus, "watched"), icon = if (watched) Icons.Filled.Check else null)
            }
          }
        }
      }
      if (d != null) {
        val selected = tab.coerceIn(0, tabs.lastIndex)
        TabRow(selectedTabIndex = selected, modifier = Modifier.padding(top = 28.dp)) {
          tabs.forEachIndexed { i, name ->
            Tab(selected = i == selected, onFocus = { tab = i }, modifier = Modifier.focusMemory(focus, "tab/$name")) {
              Text(name, Modifier.padding(horizontal = 16.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge)
            }
          }
        }
        when (tabs[selected]) {
          "Episodes" -> Episodes(store, item, seasons, season, episodes, focus, nav) { season = it }
          "Overview" -> Overview(d!!, tv)
          else -> LazyRow(Modifier.padding(top = 8.dp), contentPadding = PaddingValues(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(recs, key = { it.key }) { r -> PosterCard(store, r, Modifier.width(124.dp).focusMemory(focus, "rec/${r.key}")) { nav.push(Screen.Title(r)) } }
          }
        }
      }
    }
  }
}

@Composable
private fun Episodes(store: Store, item: Item, seasons: List<JSONObject>, season: Int?, episodes: List<JSONObject>, focus: FocusMemory, nav: Nav, pick: (Int) -> Unit) = Column(Modifier.padding(top = 16.dp)) {
  LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(seasons) { s ->
      val n = s.optInt("season_number")
      Chip(if (n == 0) "Specials" else "Season $n", n == season) { pick(n) }
    }
  }
  LazyRow(Modifier.padding(top = 12.dp), contentPadding = PaddingValues(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    items(episodes, key = { it.optInt("id") }) { e ->
      val s = season ?: 0
      val n = e.optInt("episode_number")
      val w = store.watch(progressKey(item, s, n))
      Column(Modifier.width(220.dp)) {
        ZCard({ nav.push(Screen.Sources(item, s, n, e.optString("name"), autoplay = true)) }, Modifier.focusMemory(focus, "ep/$s/$n").fillMaxWidth().aspectRatio(16f / 9f)) {
          Box(Modifier.fillMaxSize()) {
            Artwork(img(e.str("still_path"), "w300") ?: img(item.backdrop, "w300"), e.optString("name"))
            WatchMarks(w)
          }
        }
        Text("$n. ${e.optString("name")}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        MetaLine(listOfNotNull(e.optInt("runtime").takeIf { it > 0 }?.let { "${it}m" }, e.str("air_date"), "Watched".takeIf { w?.finished == true }))
        Text(e.optString("overview"), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun Overview(d: JSONObject, tv: Boolean) {
  val credits = d.optJSONObject("credits")
  fun names(a: List<JSONObject>?, n: Int = 8) = a?.take(n)?.joinToString(", ") { it.optString("name") }
  val rows = listOf(
    "Cast" to names(credits?.optJSONArray("cast")?.objects()),
    (if (tv) "Created by" else "Director") to if (tv) names(d.optJSONArray("created_by")?.objects()) else names(credits?.optJSONArray("crew")?.objects()?.filter { it.optString("job") == "Director" }),
    "Genres" to names(d.optJSONArray("genres")?.objects()),
    (if (tv) "First aired" else "Released") to d.str(if (tv) "first_air_date" else "release_date"),
    "Networks" to if (tv) names(d.optJSONArray("networks")?.objects(), 3) else null,
    "Language" to d.optJSONArray("spoken_languages")?.objects()?.firstOrNull()?.str("english_name"),
    "Status" to d.str("status"),
  ).filter { !it.second.isNullOrBlank() }
  Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
    rows.forEach { (k, v) ->
      Row {
        Text(k, Modifier.width(130.dp), style = MaterialTheme.typography.labelLarge, color = Z.Text2)
        Text(v!!, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

/** Hand the trailer to the YouTube app (or anything that opens YouTube links). */
fun openTrailer(ctx: Context, key: String) {
  runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key"))) }
    .recoverCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key"))) }
    .onFailure { toast(ctx, "There's no app on this TV that can play YouTube trailers.") }
}

fun clock(ms: Long): String {
  val s = ms / 1000
  return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// --- Choose Source ------------------------------------------------------------------------------------

@Composable
fun SourcesPage(store: Store, s: Screen.Sources, nav: Nav) {
  var found by remember { mutableStateOf<Found?>(null) }
  var notes by remember { mutableStateOf(listOf<String>()) }
  var failed by remember { mutableStateOf(setOf<Source>()) }
  var busy by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val first = remember { FocusRequester() }
  val addons = remember { store.addons }
  val label = listOfNotNull(s.season?.let { "S$it · E${s.episode}" }, s.episodeName.ifEmpty { null }).joinToString("   ")

  /** Get a playable link and open the player. On failure, say why and return false. */
  suspend fun start(src: Source, replace: Boolean): Boolean {
    val name = src.debrid?.let { SERVICES[it] } ?: src.addon
    val url = src.url ?: runCatching {
      withContext(Dispatchers.IO) { debrid(src.debrid!!, store.debrid(src.debrid)).resolve(src.infoHash!!, Hint(src.filename, src.fileIdx, s.season, s.episode)) }
    }.getOrElse {
      failed = failed + src
      notes = notes + "$name: ${it.message ?: "that source didn't work."}"
      return false
    }
    val play = Screen.Play(s.item, s.season, s.episode, url, src.headers, label, listOfNotNull(name, src.quality, src.hdr.ifEmpty { null }).joinToString(" · "))
    if (replace) nav.replace(play) else nav.push(play)
    return true
  }

  LaunchedEffect(s) {
    if (addons.isEmpty()) return@LaunchedEffect
    val d = withContext(Dispatchers.IO) { Tmdb(store.tmdb).preview(s.item) }
    val imdb = d?.optJSONObject("external_ids")?.str("imdb_id") ?: "tmdb:${s.item.id}"
    val f = findSources(store, s.item.type, if (s.season != null) "$imdb:${s.season}:${s.episode}" else imdb)
    found = f
    if (!s.autoplay || f.sources.isEmpty()) return@LaunchedEffect
    busy = true
    for (src in f.sources.take(4)) {
      if (start(src, replace = true)) return@LaunchedEffect
      notes = notes + "Trying the next source…"
    }
    notes = notes + "Couldn't start the best sources automatically. Pick one below."
    busy = false
  }
  LaunchedEffect(found, busy) { if (found?.sources?.isNotEmpty() == true && !busy) { delay(80); runCatching { first.requestFocus() } } }

  Box(Modifier.fillMaxSize()) {
    AsyncImage(img(s.item.backdrop, "w1280"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(Z.Bg.copy(alpha = 0.84f)))
    Column(Modifier.align(Alignment.Center).width(780.dp).fillMaxHeight(0.9f).background(Z.Surface, Z.Large).padding(28.dp)) {
      Text("Choose Source", style = MaterialTheme.typography.headlineSmall)
      Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(img(s.item.poster, "w185"), null, Modifier.width(58.dp).aspectRatio(2f / 3f).clip(Z.Small), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
          Text(s.item.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 1)
        }
        val f = found
        when {
          addons.isEmpty() -> StatusChip("Requires configuration", Z.Warning)
          f == null -> StatusChip("Checking addons…", Z.Info)
          busy -> StatusChip("Starting…", Z.Info)
          else -> StatusChip("${f.sources.size} sources", Z.Text2)
        }
      }
      notes.takeLast(3).forEach { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = Z.Warning, maxLines = 1, overflow = TextOverflow.Ellipsis) }
      Spacer(Modifier.height(14.dp))
      val f = found
      when {
        addons.isEmpty() -> Text("Add a Stremio addon in Settings > Addons to find sources.", color = Z.Text2)
        f == null -> {}
        f.sources.isEmpty() -> Text(
          "No playable sources found." + when {
            f.waiting > 0 && SERVICES.keys.none { store.debrid(it).isNotEmpty() } -> " ${f.waiting} torrent sources need a debrid service: connect one in Settings > Debrid Services."
            f.waiting > 0 -> " ${f.waiting} torrent sources aren't ready on your debrid service."
            else -> ""
          },
          color = Z.Text2,
        )
        else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
          itemsIndexed(f.sources) { i, src ->
            SourceRow(src, best = i == 0, failed = src in failed, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
              if (!busy) scope.launch {
                busy = true
                start(src, replace = false)
                busy = false
              }
            }
          }
        }
      }
      f?.errors?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString("  ·  "), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelSmall, color = Z.Muted, maxLines = 2) }
    }
  }
}

@Composable
private fun SourceRow(src: Source, best: Boolean, failed: Boolean, modifier: Modifier, onClick: () -> Unit) = Surface(
  onClick, modifier.fillMaxWidth(),
  shape = ClickableSurfaceDefaults.shape(Z.Medium),
  colors = ClickableSurfaceDefaults.colors(containerColor = if (best) Z.Card else Color.Transparent, contentColor = Z.Text, focusedContainerColor = Z.Elevated, focusedContentColor = Z.Text),
  border = ClickableSurfaceDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, Z.Text), shape = Z.Medium)),
  scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
) {
  Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Tile(when (src.debrid) { "torbox" -> "TB"; "realdebrid" -> "RD"; "premiumize" -> "PM"; else -> "A" })
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
      Text(src.debrid?.let { SERVICES[it] } ?: src.addon, style = MaterialTheme.typography.titleSmall, maxLines = 1)
      MetaLine(listOfNotNull(if (src.debrid != null) "Cloud · via ${src.addon}" else "Addon", src.quality, src.hdr.ifEmpty { null }, src.size))
      Text(src.filename ?: src.detail.lineSequence().firstOrNull().orEmpty(), style = MaterialTheme.typography.labelSmall, color = Z.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.width(12.dp))
    // Only what we actually know: a direct link or a cache hit is available; Real-Debrid can't say until it's tried.
    when {
      failed -> StatusChip("Unavailable", Z.Error)
      src.url != null -> StatusChip("Available", Z.Success)
      src.cached -> StatusChip("Ready", Z.Success)
      else -> StatusChip("Not checked", Z.Muted)
    }
    Spacer(Modifier.width(12.dp))
    Row(Modifier.background(if (best) Z.Red else Z.Elevated, Z.Small).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
      Glyph(Icons.Filled.PlayArrow, 14.dp, Color.White)
      Spacer(Modifier.width(4.dp))
      Text("Play", style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
  }
}

// --- Player ------------------------------------------------------------------------------------------------

@OptIn(UnstableApi::class)
@Composable
fun PlayerPage(store: Store, s: Screen.Play) {
  val ctx = LocalContext.current
  val key = progressKey(s.item, s.season, s.episode)
  var error by remember { mutableStateOf<String?>(null) }
  var controls by remember { mutableStateOf(false) }
  var view by remember { mutableStateOf<PlayerView?>(null) }
  val player = remember {
    val http = DefaultHttpDataSource.Factory().setUserAgent("Zylo/0.9.0").setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(s.headers)
    ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(http)).build().apply {
      setMediaItem(MediaItem.fromUri(s.url))
      store.watch(key)?.takeIf { it.started }?.let { seekTo(it.pos) }
      prepare()
      playWhenReady = true
    }
  }
  fun save() {
    if (player.duration > 0) store.saveWatch(s.item, s.season, s.episode, player.currentPosition, player.duration)
  }
  LaunchedEffect(player) {
    while (true) {
      delay(10_000)
      if (player.isPlaying) save()
    }
  }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(player) {
    val listener = object : Player.Listener {
      override fun onPlayerError(e: PlaybackException) { error = "Couldn't play this source (${e.errorCodeName}). Press Back and choose another." }
    }
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { save(); player.pause() } }
    player.addListener(listener)
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      save()
      player.release()
    }
  }
  BackHandler(controls) { view?.hideController() } // first Back hides the controls, the next one leaves

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    AndroidView(
      factory = {
        PlayerView(it).apply {
          this.player = player
          keepScreenOn = true
          setShowSubtitleButton(true)
          setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == View.VISIBLE })
          findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(Z.Red.toArgb())
            setScrubberColor(Z.Red.toArgb())
          }
          view = this
          post { requestFocus() }
        }
      },
      modifier = Modifier.fillMaxSize(),
    )
    if (controls) Column(
      Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent))).padding(horizontal = Z.Pad, vertical = 28.dp),
    ) {
      Text(s.item.title, style = MaterialTheme.typography.headlineSmall)
      MetaLine(listOf(s.subtitle, s.source), Modifier.padding(top = 4.dp))
    }
    error?.let {
      Text(it, Modifier.align(Alignment.Center).background(Z.Elevated, Z.Medium).padding(24.dp), style = MaterialTheme.typography.bodyLarge)
    }
  }
}
