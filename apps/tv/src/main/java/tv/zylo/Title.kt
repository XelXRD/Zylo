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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
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
import androidx.media3.common.C
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
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
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
  val look = rememberLook(store, item)

  // Where you're up to (Zylo + Trakt history): the series opens on that season and episode.
  var next by remember { mutableStateOf<Target?>(null) }
  var nextKnown by remember { mutableStateOf(!tv) }
  LaunchedEffect(item, tick) {
    if (tv) { next = withContext(Dispatchers.IO) { runCatching { target(store, t, item) }.getOrNull() }; nextKnown = true }
  }
  LaunchedEffect(item) { runCatching { withContext(Dispatchers.IO) { t.details(item.type, item.id) } }.onSuccess { d = it }.onFailure { error = it.message } }
  val seasons = d?.optJSONArray("seasons")?.objects().orEmpty()
  LaunchedEffect(d, nextKnown) {
    if (d != null && nextKnown && season == null) season = next?.season ?: seasons.map { it.optInt("season_number") }.let { n -> n.firstOrNull { it > 0 } ?: n.firstOrNull() }
  }
  LaunchedEffect(season) {
    val n = season ?: return@LaunchedEffect
    episodes = runCatching { withContext(Dispatchers.IO) { t.season(item.id, n).optJSONArray("episodes")?.objects().orEmpty() } }.getOrDefault(emptyList())
  }
  RestoreFocus(focus, d != null)

  var recs by remember(item) { mutableStateOf<List<Item>>(emptyList()) }
  LaunchedEffect(d) { d?.let { dd -> recs = withContext(Dispatchers.IO) { t.safe(dd.optJSONObject("recommendations")?.optJSONArray("results")?.objects()?.mapNotNull { Tmdb.item(it, null) }.orEmpty()) } } }
  // Kids profiles: anything above the profile's rating (or unrated) can't be opened, whichever way you reached it.
  if (d != null && !Ratings.allows(Tmdb.certification(d!!))) {
    val cert = Tmdb.certification(d!!)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
      Text(item.title, style = MaterialTheme.typography.displaySmall)
      Text(if (cert == null) "This title has no age rating, so it isn't available on ${store.profile.name}'s profile." else "Rated $cert, so it isn't available on ${store.profile.name}'s profile.",
        Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge, color = Z.Text2)
      val back = remember { FocusRequester() }
      LaunchedEffect(Unit) { delay(80); runCatching { back.requestFocus() } }
      ZButton("Go Back", { nav.back() }, Modifier.padding(top = 24.dp).focusRequester(back), primary = true)
    }
    return
  }
  val tabs = listOfNotNull("Episodes".takeIf { tv }, "Overview", "More Like This".takeIf { recs.isNotEmpty() })
  val trailer = Tmdb.trailer(d)
  // Where Play starts: a film where you stopped; a series at your next episode (or where you stopped in it).
  val playSeason = if (tv) next?.season ?: 1 else null
  val playEpisode = if (tv) next?.episode ?: 1 else null
  val resuming = if (tv) next?.resume == true else latest?.started == true
  val playLabel = (if (resuming) "Resume" else "Play") + if (tv) " S$playSeason E$playEpisode" else ""

  var popup by remember { mutableStateOf(false) } // trailer pop-out
  BackHandler(popup) { popup = false }
  val scroll = rememberScrollState()
  val scope = rememberCoroutineScope()
  val selectedTab = remember { FocusRequester() }
  Box(Modifier.fillMaxSize()) {
    AsyncImage(look?.background ?: img(item.backdrop, "w1280"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Z.Bg.copy(alpha = 0.95f), 0.45f to Z.Bg.copy(alpha = 0.72f), 1f to Z.Bg.copy(alpha = 0.12f))))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 0.85f to Z.Bg)))
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(start = Z.Pad, end = Z.Pad, top = 36.dp, bottom = 40.dp)) {
      Row {
        Artwork(img(item.poster, "w342"), item.title, Modifier.width(168.dp).aspectRatio(2f / 3f).shadow(24.dp, Z.Medium).clip(Z.Medium))
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
          TitleLogo(look, item.title, MaterialTheme.typography.displayMedium, 380.dp, 100.dp)
          MetaLine(Tmdb.meta(item, d).let { m -> if (tv) m + listOfNotNull(d?.optInt("number_of_episodes")?.takeIf { it > 0 }?.let { "$it Episodes" }) else m }, Modifier.padding(top = 8.dp))
          Rating(d?.optDouble("vote_average", 0.0) ?: item.rating, Modifier.padding(top = 8.dp))
          Text(d?.str("overview") ?: item.overview, Modifier.padding(top = 12.dp).fillMaxWidth(0.92f), style = MaterialTheme.typography.bodyLarge, color = Z.Text2, maxLines = 4, overflow = TextOverflow.Ellipsis)
          error?.let { Text(it, Modifier.padding(top = 8.dp), color = Z.Error) }
          // Any action button focused: the page goes back to the very top so all the details show.
          Row(Modifier.padding(top = 20.dp).onFocusChanged { if (it.hasFocus) scope.launch { scroll.animateScrollTo(0) } }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ZButton(playLabel, { nav.push(Screen.Sources(item, playSeason, playEpisode, autoplay = true)) }, Modifier.focusMemory(focus, "play", default = true), primary = true, icon = Icons.Filled.PlayArrow)
            ZButton("Choose Source", { nav.push(Screen.Sources(item, playSeason, playEpisode)) }, Modifier.focusMemory(focus, "source"))
            ZButton("My List", { inList = toggleList(store, item) }, Modifier.focusMemory(focus, "list"), icon = if (inList) Icons.Filled.Check else Icons.Filled.Add)
            if (trailer != null) ZButton("Trailer", { popup = true }, Modifier.focusMemory(focus, "trailer"))
            if (!tv) {
              val watched = remember(tick) { store.watched(progressKey(item, null, null)) }
              ZButton(if (watched) "Watched" else "Mark as Watched", {
                if (watched) store.forget(progressKey(item, null, null)) else store.saveWatch(item, null, null, 1, 1)
                Trakt.setWatched(store, item, null, null, !watched)
                if (!watched) Anime.watched(ctx, store, item, null, null)
                tick++
              }, Modifier.focusMemory(focus, "watched"), icon = if (watched) Icons.Filled.Check else null)
            }
          }
        }
      }
      if (d != null) {
        val selected = tab.coerceIn(0, tabs.lastIndex)
        // Coming down into the tabs lands on the selected one (not whichever tab is nearest, which switched tabs on arrival).
        Box(Modifier.padding(top = 28.dp).focusRestorer(selectedTab).focusGroup()) {
        TabRow(selectedTabIndex = selected) {
          tabs.forEachIndexed { i, name ->
            Tab(selected = i == selected, onFocus = { tab = i }, modifier = if (i == selected) Modifier.focusRequester(selectedTab) else Modifier) {
              Text(name, Modifier.padding(horizontal = 16.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge)
            }
          }
        }
        }
        when (tabs[selected]) {
          "Episodes" -> Episodes(store, item, seasons, season, episodes, focus, nav, next) { season = it }
          "Overview" -> Overview(d!!, tv)
          else -> LazyRow(Modifier.padding(top = 8.dp).bleed(Z.Pad), contentPadding = PaddingValues(horizontal = Z.Pad, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(recs, key = { it.key }) { r -> PosterCard(store, r, Modifier.width(124.dp).focusMemory(focus, "rec/${r.key}")) { nav.push(Screen.Title(r)) } }
          }
        }
      }
    }
    if (popup && trailer != null) TrailerPopup(trailer, item.title) { popup = false }
  }
}

@Composable
private fun Episodes(store: Store, item: Item, seasons: List<JSONObject>, season: Int?, episodes: List<JSONObject>, focus: FocusMemory, nav: Nav, next: Target?, pick: (Int) -> Unit) = Column(Modifier.padding(top = 16.dp)) {
  // The row starts on your next episode, and moving down into it lands there.
  val row = rememberLazyListState()
  val upTo = if (next?.season == season) episodes.indexOfFirst { it.optInt("episode_number") == next?.episode } else -1
  LaunchedEffect(upTo) { if (upTo > 0) row.scrollToItem(upTo) }
  val upToCard = remember { FocusRequester() }
  LazyRow(Modifier.bleed(Z.Pad), contentPadding = PaddingValues(horizontal = Z.Pad, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    items(seasons) { s ->
      val n = s.optInt("season_number")
      Chip(if (n == 0) "Specials" else "Season $n", n == season) { pick(n) }
    }
  }
  LazyRow(Modifier.padding(top = 12.dp).bleed(Z.Pad).focusRestorer(if (upTo >= 0) upToCard else FocusRequester.Default), state = row, contentPadding = PaddingValues(horizontal = Z.Pad, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    itemsIndexed(episodes, key = { _, it -> it.optInt("id") }) { i, e ->
      val s = season ?: 0
      val n = e.optInt("episode_number")
      val w = store.watch(progressKey(item, s, n))
      val watched = store.watched(progressKey(item, s, n))
      Column(Modifier.width(220.dp)) {
        ZCard({ nav.push(Screen.Sources(item, s, n, e.optString("name"), autoplay = true)) },
          Modifier.then(if (i == upTo) Modifier.focusRequester(upToCard) else Modifier).focusMemory(focus, "ep/$s/$n").fillMaxWidth().aspectRatio(16f / 9f)) {
          Box(Modifier.fillMaxSize()) {
            Artwork(img(e.str("still_path"), "w300") ?: img(item.backdrop, "w300"), e.optString("name"))
            WatchMarks(w, watched)
          }
        }
        Text("$n. ${e.optString("name")}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        MetaLine(listOfNotNull(e.optInt("runtime").takeIf { it > 0 }?.let { "${it}m" }, e.str("air_date"), "Watched".takeIf { watched }))
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

/** The trailer in a window over the page, with sound. Back or Close shuts it; it closes itself when the trailer ends. */
@Composable
private fun TrailerPopup(key: String, title: String, close: () -> Unit) {
  val button = remember { FocusRequester() }
  var failed by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { delay(60); runCatching { button.requestFocus() } }
  Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Box(Modifier.width(740.dp).aspectRatio(16f / 9f).clip(Z.Medium).background(Color.Black), contentAlignment = Alignment.Center) {
        Text(if (failed) "This trailer couldn't play here." else "Loading trailer…", color = Z.Text2)
        if (!failed) Trailer(key, Modifier.fillMaxSize(), sound = true, onEnded = close, onFailed = { failed = true })
      }
      Row(Modifier.padding(top = 16.dp).width(740.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Focus stays on Close while the window is open, so the page underneath can't be moved about.
        ZButton("Close", close, Modifier.focusRequester(button).focusProperties {
          left = FocusRequester.Cancel; right = FocusRequester.Cancel; up = FocusRequester.Cancel; down = FocusRequester.Cancel
        })
      }
    }
  }
}

fun clock(ms: Long): String {
  val s = ms / 1000
  return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

// --- Choose Source ------------------------------------------------------------------------------------

/** The last source list, so coming back from the player doesn't search again. */
object SourcesMemo {
  private var last: Triple<Screen.Sources, Pair<Found, List<Sub>?>, Long>? = null
  fun get(s: Screen.Sources) = last?.takeIf { it.first.item == s.item && it.first.season == s.season && it.first.episode == s.episode && System.currentTimeMillis() - it.third < 30 * 60_000 }?.second
  fun put(s: Screen.Sources, f: Found, subs: List<Sub>?) { last = Triple(s, f to subs, System.currentTimeMillis()) }
}

@Composable
fun SourcesPage(store: Store, s: Screen.Sources, nav: Nav) {
  // Back from the player lands here again: the list is kept (for half an hour) and Play doesn't start on its own twice.
  val memo = remember { SourcesMemo.get(s) }
  var found by remember { mutableStateOf(memo?.first) }
  var autoTried by rememberSaveable { mutableStateOf(false) }
  var notes by remember { mutableStateOf(listOf<String>()) }
  var failed by remember { mutableStateOf(setOf<Source>()) }
  var busy by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()
  val first = remember { FocusRequester() }
  val addons = remember { store.addons }
  val servers = remember { Library.connected(store) }
  var subs by remember { mutableStateOf(memo?.second) }
  val pref = remember { store.sourcePref(s.item) }
  val cap = store.maxQuality
  val label = listOfNotNull(s.season?.let { "S$it · E${s.episode}" }, s.episodeName.ifEmpty { null }).joinToString("   ")

  /** Get a playable link and open the player. On failure, say why and return false. */
  suspend fun start(src: Source): Boolean {
    val name = src.debrid?.let { SERVICES[it] } ?: src.addon
    val url = src.url ?: runCatching {
      withContext(Dispatchers.IO) { debrid(src.debrid!!, store.debrid(src.debrid)).resolve(src.infoHash!!, Hint(src.filename, src.fileIdx, s.season, s.episode)) }
    }.getOrElse {
      failed = failed + src
      notes = notes + "$name: ${it.message ?: "that source didn't work."}"
      return false
    }
    // Subtitles come from addons in parallel; wait briefly for them, but never hold up playback for long.
    val subtitles = withTimeoutOrNull(4_000) { snapshotFlow { subs }.first { it != null } } ?: emptyList()
    val play = Screen.Play(s.item, s.season, s.episode, url, src.headers, label, listOfNotNull(name, src.quality, src.hdr.ifEmpty { null }).joinToString(" · "), subtitles, src.signature())
    nav.push(play) // Back from the player comes back to this list, to pick another source
    return true
  }

  LaunchedEffect(s) {
    if (addons.isEmpty() && servers.isEmpty()) return@LaunchedEffect
    val f = found ?: run {
      val d = withContext(Dispatchers.IO) { Tmdb(store.tmdb).preview(s.item) }
      val imdb = d?.optJSONObject("external_ids")?.str("imdb_id") ?: "tmdb:${s.item.id}"
      val videoId = if (s.season != null) "$imdb:${s.season}:${s.episode}" else imdb
      launch { subs = runCatching { findSubtitles(store, s.item.type, videoId) }.getOrDefault(emptyList()) }
      findSources(store, s.item, s.season, s.episode, videoId).also { found = it }
    }
    if (!s.autoplay || autoTried || f.sources.isEmpty()) return@LaunchedEffect
    autoTried = true
    // Play never picks a source above the quality cap on its own; those stay in the list to choose by hand.
    val candidates = f.sources.filter { !overCap(it, cap) }.take(4)
    if (candidates.isEmpty()) {
      notes = notes + "Every source is above your $cap limit. Pick one below, or change it in Settings > Playback."
      return@LaunchedEffect
    }
    busy = true
    for (src in candidates) {
      if (start(src)) return@LaunchedEffect
      notes = notes + "Trying the next source…"
    }
    notes = notes + "Couldn't start the best sources automatically. Pick one below."
    busy = false
  }
  LaunchedEffect(found, subs) { found?.let { SourcesMemo.put(s, it, subs) } }
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
          addons.isEmpty() && servers.isEmpty() -> StatusChip("Requires configuration", Z.Warning)
          f == null -> StatusChip("Checking addons…", Z.Info)
          busy -> StatusChip("Starting…", Z.Info)
          else -> StatusChip("${f.sources.size} sources", Z.Text2)
        }
      }
      notes.takeLast(3).forEach { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = Z.Warning, maxLines = 1, overflow = TextOverflow.Ellipsis) }
      Spacer(Modifier.height(14.dp))
      val f = found
      when {
        addons.isEmpty() && servers.isEmpty() -> Text("Add a Stremio addon (Settings > Addons) or connect Plex or Jellyfin (Settings > Media Servers) to find sources.", color = Z.Text2)
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
            SourceRow(src, best = i == 0, failed = src in failed, last = src.likeness(pref) == 0, overCap = overCap(src, cap), modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
              if (!busy) scope.launch {
                busy = true
                start(src)
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
private fun SourceRow(src: Source, best: Boolean, failed: Boolean, last: Boolean, overCap: Boolean, modifier: Modifier, onClick: () -> Unit) = Surface(
  onClick, modifier.fillMaxWidth(),
  shape = ClickableSurfaceDefaults.shape(Z.Medium),
  colors = ClickableSurfaceDefaults.colors(containerColor = if (best) Z.Card else Color.Transparent, contentColor = Z.Text, focusedContainerColor = Z.Elevated, focusedContentColor = Z.Text),
  border = ClickableSurfaceDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, Z.Text), shape = Z.Medium)),
  scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
) {
  Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
    Tile(when { src.library == "plex" -> "PX"; src.library == "jellyfin" -> "JF"; src.debrid == "torbox" -> "TB"; src.debrid == "realdebrid" -> "RD"; src.debrid == "premiumize" -> "PM"; else -> "A" })
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
      Text(src.debrid?.let { SERVICES[it] } ?: src.addon, style = MaterialTheme.typography.titleSmall, maxLines = 1)
      MetaLine(listOfNotNull(
        when { src.library != null -> "Personal Library"; src.debrid != null -> "Cloud · via ${src.addon}"; else -> "Addon" },
        src.quality, src.hdr.ifEmpty { null }, src.size, "Last played".takeIf { last }, "Above your limit".takeIf { overCap },
      ))
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
fun PlayerPage(store: Store, s: Screen.Play, nav: Nav) {
  val ctx = LocalContext.current
  val key = progressKey(s.item, s.season, s.episode)
  var error by remember { mutableStateOf<String?>(null) }
  var controls by remember { mutableStateOf(false) }
  var view by remember { mutableStateOf<PlayerView?>(null) }
  val player = remember {
    val http = DefaultHttpDataSource.Factory().setUserAgent(UA).setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(s.headers)
    ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(http)).build().apply {
      // Addon subtitles ride along as extra text tracks; subtitles inside the file are found automatically.
      setMediaItem(MediaItem.Builder().setUri(s.url).setSubtitleConfigurations(s.subs.map { sub ->
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url)).setMimeType(subtitleMime(sub.url)).setLanguage(sub.lang).setLabel(sub.label).build()
      }).build())
      store.subLang.takeIf { it.isNotEmpty() }?.let { lang -> trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredTextLanguage(lang).build() }
      // Audio in your language when the file has it (files often mark a dub as their default track).
      store.audioLang?.let { lang -> trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredAudioLanguage(lang).build() }
      store.watch(key)?.takeIf { it.started && !it.percent }?.let { seekTo(it.pos) }
      prepare()
      playWhenReady = true
    }
  }
  val resumeAt = remember { store.watch(key)?.takeIf { it.started && it.percent }?.fraction } // from Trakt: a percentage, seek once the length is known
  var finishedSent by remember { mutableStateOf(store.watch(key)?.finished == true) }
  fun progress() = if (player.duration > 0) player.currentPosition * 100f / player.duration else 0f
  fun save() {
    if (player.duration <= 0) return
    store.saveWatch(s.item, s.season, s.episode, player.currentPosition, player.duration)
    if (!finishedSent && player.currentPosition >= player.duration * 0.9) {
      finishedSent = true
      Anime.watched(ctx, store, s.item, s.season, s.episode)
    }
  }

  // --- Up Next: in the last 30 seconds of an episode, offer the next one; it plays after a 10-second countdown. ---
  var next by remember { mutableStateOf<NextEp?>(null) }
  var upNext by remember { mutableStateOf(false) }
  var dismissed by remember { mutableStateOf(false) }
  var countdown by remember { mutableIntStateOf(UP_NEXT_SECONDS) }
  var left by remember { mutableStateOf(false) }
  // Settings > Playback: play the next episode on its own always, up to a few in a row, or never.
  val auto = remember { Autoplay.started(); Autoplay.streak < Autoplay.limit(store) }
  /** byItself: the countdown ran out or the episode ended (counts towards the limit); else you chose it. */
  fun playNext(byItself: Boolean = false) {
    val n = next ?: return
    if (left) return
    left = true
    if (byItself) { Autoplay.streak++; Autoplay.pending = true }
    nav.back() // the player, then this episode's sources become the next one's: Back still means "pick another source"
    nav.replace(Screen.Sources(s.item, n.season, n.episode, n.name, autoplay = true))
  }
  /** Back to the title page's episode list. */
  fun episodes() {
    if (left) return
    left = true
    nav.back(); nav.back()
    if ((nav.top as? Screen.Title)?.item != s.item) nav.push(Screen.Title(s.item))
  }
  LaunchedEffect(Unit) {
    if (s.season != null && s.episode != null) next = withContext(Dispatchers.IO) { runCatching { nextEpisode(Tmdb(store.tmdb), s.item, s.season, s.episode) }.getOrNull() }
  }
  LaunchedEffect(next) {
    if (next == null) return@LaunchedEffect
    while (!upNext) {
      delay(500)
      val d = player.duration
      if (d > 0 && player.currentPosition > 0 && d - player.currentPosition <= 30_000 && !dismissed) upNext = true
    }
  }
  LaunchedEffect(upNext) {
    if (!upNext || !auto) return@LaunchedEffect
    countdown = UP_NEXT_SECONDS
    while (countdown > 0) {
      delay(1_000)
      if (dismissed) return@LaunchedEffect
      if (player.isPlaying) countdown-- // paused: the countdown waits too
    }
    playNext(byItself = true)
  }

  // --- Skip Intro / Recap / Credits (IntroDB; AniSkip for anime). ---
  var segments by remember { mutableStateOf(emptyList<Segment>()) }
  var skip by remember { mutableStateOf<Segment?>(null) }
  val skipped = remember { mutableSetOf<Segment>() } // passed on with Back: not offered again
  LaunchedEffect(Unit) { segments = withContext(Dispatchers.IO) { runCatching { Skip.find(ctx, store, s.item, s.season, s.episode) }.getOrDefault(emptyList()) } }
  LaunchedEffect(segments) {
    if (segments.isEmpty()) return@LaunchedEffect
    while (true) {
      delay(500)
      skip = Skip.at(segments, player.currentPosition, player.duration).takeIf { it !in skipped }
    }
  }
  fun doSkip(seg: Segment) {
    skipped += seg
    skip = null
    if (seg.kind == "credits" && next != null) playNext()
    else player.seekTo(if (seg.end > 0) seg.end else player.duration)
    view?.requestFocus()
  }

  // --- Subtitle timing: Up (with the controls hidden) opens it; addon subtitles are moved and reloaded in place. ---
  var timing by remember { mutableStateOf(false) }
  var offset by remember { mutableLongStateOf(0L) }
  val originals = remember { HashMap<String, String>() }
  LaunchedEffect(offset) {
    if (!timing && offset == 0L) return@LaunchedEffect
    delay(700) // wait for the presses to stop
    val text = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.mediaTrackGroup?.getFormat(0)
    val sub = s.subs.firstOrNull { it.label == text?.label } ?: s.subs.firstOrNull { text != null && it.lang == text.language }
    if (sub == null) { toast(ctx, "Timing works on addon subtitles. Pick one with the subtitles button first."); return@LaunchedEffect }
    val file = withContext(Dispatchers.IO) {
      runCatching {
        val raw = originals.getOrPut(sub.url) { http(sub.url, timeout = 15_000) }
        val ext = if (subtitleMime(sub.url) == androidx.media3.common.MimeTypes.TEXT_VTT) "vtt" else "srt"
        java.io.File(ctx.cacheDir, "subs").apply { deleteRecursively(); mkdirs() }.let { java.io.File(it, "shift-${System.nanoTime()}.$ext") }.apply { writeText(shiftSubs(raw, offset)) }
      }.getOrNull()
    } ?: run { toast(ctx, "Couldn't load that subtitle to move it."); return@LaunchedEffect }
    val configs = s.subs.map { x ->
      val b = if (x == sub) MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file)).setMimeType(if (file.extension == "vtt") androidx.media3.common.MimeTypes.TEXT_VTT else androidx.media3.common.MimeTypes.APPLICATION_SUBRIP).setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
      else MediaItem.SubtitleConfiguration.Builder(Uri.parse(x.url)).setMimeType(subtitleMime(x.url))
      b.setLanguage(x.lang).setLabel(x.label).build()
    }
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setPreferredTextLanguage(sub.lang).build()
    player.setMediaItem(MediaItem.Builder().setUri(s.url).setSubtitleConfigurations(configs).build(), player.currentPosition)
    player.prepare()
  }

  LaunchedEffect(player) {
    while (true) {
      delay(10_000)
      if (player.isPlaying) save()
    }
  }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(player) {
    var seeked = false
    var remembered = false
    var scrobbling = false
    val listener = object : Player.Listener {
      override fun onPlayerError(e: PlaybackException) { error = "Couldn't play this source (${e.errorCodeName}). Press Back and choose another." }
      override fun onPlaybackStateChanged(state: Int) {
        if (state == Player.STATE_READY) {
          if (!seeked && resumeAt != null && player.duration > 0) { seeked = true; player.seekTo((player.duration * resumeAt).toLong()) }
          // It plays: remember this source (and its kind, for the next episode).
          if (!remembered && s.signature.isNotEmpty()) { remembered = true; store.rememberSource(s.item, s.signature) }
          // Trakt "now watching" starts once, when the video is actually ready.
          if (!scrobbling && player.duration > 0) { scrobbling = true; Trakt.scrobble(store, "start", s.item, s.season, s.episode, progress()) }
        }
        if (state == Player.STATE_ENDED) { if (auto && !dismissed) playNext(byItself = true) else if (next != null) { dismissed = false; upNext = true } }
      }
      // Pause and resume by you (not every buffering stall, which would flood Trakt) update "now watching".
      override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (scrobbling && player.duration > 0) Trakt.scrobble(store, if (playWhenReady) "start" else "pause", s.item, s.season, s.episode, progress())
      }
    }
    val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { save(); player.pause() } }
    player.addListener(listener)
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      save()
      if (player.duration > 0) Trakt.scrobble(store, "stop", s.item, s.season, s.episode, progress()) // 80%+ records the play
      player.release()
    }
  }
  BackHandler(controls) { view?.hideController() } // first Back hides the controls, the next one leaves
  BackHandler(upNext && !dismissed) { dismissed = true; view?.requestFocus() } // Back on Up Next keeps watching
  BackHandler(skip != null && !upNext) { skip?.let { skipped += it }; skip = null; view?.requestFocus() } // Back: no thanks
  BackHandler(timing) { timing = false; view?.requestFocus() }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    AndroidView(
      factory = {
        object : PlayerView(it) {
          // Up with the controls hidden: subtitle timing (PlayerView would only show its controls).
          override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && !isControllerFullyVisible && s.subs.isNotEmpty()) {
              if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) timing = true
              return true
            }
            return super.dispatchKeyEvent(event)
          }
        }.apply {
          this.player = player
          subtitleView?.let { v -> SubStyle.apply(store, v) }
          keepScreenOn = true
          setShowSubtitleButton(true)
          isFocusable = true
          setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
            controls = v == View.VISIBLE
            // The controls hide with their button focused, and focus wandered off the player: OK did nothing after
            // the first time. Take it back (unless Skip, Up Next or subtitle timing has it).
            if (v != View.VISIBLE && skip == null && !upNext && !timing) post { requestFocus() }
          })
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
      MetaLine(listOf(s.subtitle, s.source) + listOfNotNull(s.subs.size.takeIf { it > 0 }?.let { "$it subtitle tracks  ·  Up: subtitle timing" }), Modifier.padding(top = 4.dp))
    }
    val sk = skip
    if (sk != null && !upNext && !timing) SkipButton(Skip.label(sk.kind).let { if (sk.kind == "credits" && next != null) "Next Episode" else it }, Modifier.align(Alignment.BottomEnd)) { doSkip(sk) }
    if (timing) SubtitleTiming(offset, Modifier.align(Alignment.BottomStart), { offset += it }) { timing = false; view?.requestFocus() }
    val n = next
    if (upNext && !dismissed && n != null) UpNextCard(s.item, n, countdown, auto, Modifier.align(Alignment.BottomEnd), { playNext() }, ::episodes) { dismissed = true; view?.requestFocus() }
    error?.let {
      Text(it, Modifier.align(Alignment.Center).background(Z.Elevated, Z.Medium).padding(24.dp), style = MaterialTheme.typography.bodyLarge)
    }
  }
}

private const val UP_NEXT_SECONDS = 10

/** Next-episode autoplay: how many play on their own in a row before Zylo waits for you. */
object Autoplay {
  val CHOICES = listOf(-1 to "Always", 3 to "3 episodes in a row", 5 to "5 episodes in a row", 10 to "10 episodes in a row", 0 to "Off")
  var streak = 0
  var pending = false // the episode starting now was played by autoplay
  fun limit(store: Store) = store.str("autonext").toIntOrNull()?.let { if (it < 0) Int.MAX_VALUE else it } ?: 5
  fun label(store: Store) = CHOICES.find { it.first == (store.str("autonext").toIntOrNull() ?: 5) }?.second ?: "5 episodes in a row"
  /** A player opened: by autoplay keeps the count going, by you starts it again. */
  fun started() { if (!pending) streak = 0; pending = false }
}

/** Takes focus once when it appears, so OK skips; it goes when the part is over. */
@Composable
private fun SkipButton(text: String, modifier: Modifier, onClick: () -> Unit) {
  val r = remember { FocusRequester() }
  LaunchedEffect(text) { delay(60); runCatching { r.requestFocus() } }
  Box(Modifier.padding(Z.Pad).padding(bottom = 60.dp).then(modifier)) {
    ZButton(text, onClick, Modifier.focusRequester(r), primary = true, icon = Icons.Filled.PlayArrow)
  }
}

@Composable
private fun SubtitleTiming(offset: Long, modifier: Modifier, move: (Long) -> Unit, close: () -> Unit) {
  val r = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(60); runCatching { r.requestFocus() } }
  Column(Modifier.padding(Z.Pad).then(modifier).background(Z.Surface.copy(alpha = 0.95f), Z.Large).padding(18.dp)) {
    Text("Subtitle timing", style = MaterialTheme.typography.titleMedium)
    Text(when {
      offset == 0L -> "In step with the file"
      offset > 0 -> "Shown %.1f s later".format(offset / 1000f)
      else -> "Shown %.1f s earlier".format(-offset / 1000f)
    }, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      ZButton("Earlier −0.5 s", { move(-500) }, Modifier.focusRequester(r))
      ZButton("Later +0.5 s", { move(500) })
      ZButton("Reset", { move(-offset) })
      ZButton("Done", close, primary = true)
    }
  }
}

/** Subtitle format from the link; addon links often have no extension, and those are almost always SubRip. */
private fun subtitleMime(url: String): String {
  val path = url.substringBefore('?').lowercase()
  return when {
    path.endsWith(".vtt") -> androidx.media3.common.MimeTypes.TEXT_VTT
    path.endsWith(".ass") || path.endsWith(".ssa") -> androidx.media3.common.MimeTypes.TEXT_SSA
    path.endsWith(".ttml") || path.endsWith(".xml") -> androidx.media3.common.MimeTypes.APPLICATION_TTML
    else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
  }
}

@Composable
/** auto: counts down and plays on its own; otherwise it waits, with Episodes to pick from the list. */
fun UpNextCard(item: Item, n: NextEp, countdown: Int, auto: Boolean, modifier: Modifier, play: () -> Unit, episodes: () -> Unit, cancel: () -> Unit) {
  val button = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(60); runCatching { button.requestFocus() } }
  val fraction by animateFloatAsState(countdown / UP_NEXT_SECONDS.toFloat(), tween(ms(900), easing = androidx.compose.animation.core.LinearEasing), label = "countdown")
  Column(Modifier.padding(Z.Pad).then(modifier).width(400.dp).background(Z.Surface.copy(alpha = 0.97f), Z.Large).padding(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(Z.Small)) { Artwork(img(n.still, "w300") ?: img(item.backdrop, "w300"), n.name) }
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        Text("Up Next", style = MaterialTheme.typography.labelMedium, color = Z.Text2)
        Text("S${n.season} E${n.episode}", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.titleSmall)
        Text(n.name, style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
    if (auto) ProgressBar(fraction, Modifier.padding(top = 14.dp).clip(Z.Small))
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      ZButton(if (auto) "Play Now" else "Play", play, Modifier.focusRequester(button), primary = true, icon = Icons.Filled.PlayArrow)
      if (!auto) ZButton("Episodes", episodes)
      ZButton(if (auto) "Keep Watching" else "Not Now", cancel)
    }
  }
}
