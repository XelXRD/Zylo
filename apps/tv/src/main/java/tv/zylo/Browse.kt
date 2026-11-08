// Home, Discover, Movies / Series / Anime grids, My List and Search.
package tv.zylo

import android.graphics.drawable.GradientDrawable
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Rows pages (Home, Discover) ---------------------------------------------------------------

class RowsState(private val specs: () -> List<RowSpec>, private val hero: ((Tmdb) -> List<Item>)? = null) {
  var heroItems by mutableStateOf<List<Item>>(emptyList())
  private var heroPool = emptyList<Item>() // all the hero could show; a fresh 8 each launch and every 4 hours

  /** A new set of hero titles from the pool. */
  fun reshuffle() { if (heroPool.isNotEmpty()) { heroItems = heroPool.shuffled().take(8); heroIndex = 0 } }
  var heroIndex by mutableIntStateOf(0)
  var heroFocused by mutableStateOf(false)
  var heroPlaying by mutableStateOf(false)
  var nextUps by mutableStateOf<List<NextUp>>(emptyList()) // next episodes of series you're watching (Home only)
  var rows by mutableStateOf<List<Pair<String, List<Item>>>>(emptyList())
  var error by mutableStateOf<String?>(null)
  var loadedFor = ""
  val list = LazyListState()
  val rowLists = HashMap<String, LazyListState>()
  val focus = FocusMemory()
  var focused by mutableStateOf<String?>(null) // card that has focus now
  var expanded by mutableStateOf<String?>(null) // card that has been focused long enough to preview

  suspend fun load(t: Tmdb, key: String) = withContext(Dispatchers.IO) {
    loadedFor = key
    error = null
    val got = specs().map { spec ->
      async {
        spec.title to runCatching { spec.load?.invoke(t) ?: t.list(spec.path, spec.type) }.getOrElse {
          if (it is HttpError && it.code == 401) error = "TMDB didn't accept your key. Check it in Settings > Integrations."
          emptyList()
        }
      }
    }.awaitAll()
    val full = got.filter { it.second.isNotEmpty() } // only rows that have something in them
    // The hero is set before the rows: added afterwards, the list kept the rows in place and pushed the hero off the top.
    heroPool = hero?.let { h -> runCatching { h(t) }.getOrDefault(emptyList()).filter { it.backdrop != null } }.orEmpty()
      .ifEmpty { if (hero != null) full.firstOrNull()?.second?.filter { it.backdrop != null }.orEmpty() else emptyList() }
    heroItems = heroPool.shuffled().take(8)
    rows = full
    if (rows.isEmpty() && error == null) error = "Couldn't reach TMDB. Check the internet connection."
  }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RowsPage(store: Store, s: RowsState, nav: Nav, restore: Boolean, hero: Boolean = false, title: String? = null) {
  val key = store.tmdb
  LaunchedEffect(key) { if (s.loadedFor != key) s.load(Tmdb(key), key) }
  // The app stays open for days on a TV: new hero titles every 4 hours.
  if (hero) LaunchedEffect(Unit) { while (true) { delay(4 * 3_600_000L); s.reshuffle() } }
  // Preview timer: restarts on every focus move, so scrolling quickly through a row never starts a trailer.
  LaunchedEffect(s.focused) {
    s.expanded = null
    val k = s.focused ?: return@LaunchedEffect
    delay(Z.PreviewDelay)
    s.expanded = k
  }
  RestoreFocus(s.focus, s.rows.isNotEmpty() && restore)
  if (s.rows.isEmpty()) return if (s.error != null) Loading(s.error) else RowsSkeleton(top = if (hero) 440.dp else 120.dp)

  // Continue Watching: what you stopped partway through, plus the next episode of each series you're watching.
  LaunchedEffect(hero) { if (hero) s.nextUps = withContext(Dispatchers.IO) { runCatching { nextUps(store, Tmdb(key)) }.getOrDefault(s.nextUps) } }
  val inProgress = remember { if (hero) store.continueWatching() else emptyList() }
  val watching = (inProgress.map { ContinueEntry(it.key, it.item, it.season, it.episode, listOfNotNull(it.season?.let { n -> "S$n E${it.episode}" }, it.left).joinToString("  ·  "), it.fraction, it.at) } +
    s.nextUps.map { ContinueEntry("next/${it.item.key}", it.item, it.season, it.episode, "S${it.season} E${it.episode} · " + if (it.isNew) "New episode" else "Next episode", null, it.at, it.name, it.isNew) })
    .sortedByDescending { it.at }
  val mine = remember { if (hero) store.myList else emptyList() }
  val rows = (if (mine.isNotEmpty()) listOf("My List" to mine) else emptyList()) + s.rows
  val featured = if (hero) s.heroItems.getOrNull(s.heroIndex.coerceIn(0, (s.heroItems.size - 1).coerceAtLeast(0))) else null
  // Focus in the hero keeps the page at the very top, so the hero is never cut off.
  LaunchedEffect(s.heroFocused, watching.size) { if (s.heroFocused) s.list.animateScrollToItem(0) }
  val density = LocalDensity.current
  // The hero's artwork sits behind the whole page and fades away as the rows scroll up over it.
  val artAlpha by remember {
    derivedStateOf {
      if (s.list.firstVisibleItemIndex > 0) 0f
      else 1f - (s.list.firstVisibleItemScrollOffset / with(density) { 300.dp.toPx() }).coerceIn(0f, 1f)
    }
  }
  Box(Modifier.fillMaxSize()) {
    if (featured != null) HeroBackdrop(store, s, featured, Modifier.fillMaxSize().alpha(artAlpha))
    // Rows scroll just into view, clear of the top bar.
    CompositionLocalProvider(LocalBringIntoViewSpec provides keepInView(100.dp, 32.dp)) {
    LazyColumn(Modifier.fillMaxSize(), state = s.list, contentPadding = PaddingValues(top = if (hero) 0.dp else 84.dp, bottom = 56.dp)) {
      if (title != null) item("title") { Text(title, Modifier.padding(start = Z.Pad, bottom = 14.dp), style = MaterialTheme.typography.displaySmall) }
      if (featured != null) item("hero") { HeroInfo(store, s, featured, nav) }
      if (watching.isNotEmpty()) item("continue") { ContinueRow(store, watching, s, nav) }
      if (hero) item("live") { LiveHome(store, s.focus, nav) }
      items(rows, key = { it.first }) { (name, items) -> PosterRow(store, name, items, s, nav, default = !hero && name == rows.first().first) }
    }
    }
  }
}

/** Placeholder rows in the shape of what's loading. */
@Composable
fun RowsSkeleton(top: androidx.compose.ui.unit.Dp) = Column(Modifier.fillMaxSize().padding(start = Z.Pad, top = top), verticalArrangement = Arrangement.spacedBy(16.dp)) {
  repeat(2) {
    Skeleton(Modifier.width(160.dp).height(16.dp))
    Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) { repeat(8) { Skeleton(Modifier.width(124.dp).height(186.dp)) } }
  }
}

private fun RowsState.moveHero(step: Int) {
  if (heroItems.isNotEmpty()) heroIndex = (heroIndex.coerceIn(0, heroItems.lastIndex) + step + heroItems.size) % heroItems.size
}

/**
 * The featured title's artwork, full-bleed behind the page (and the top bar). While the hero has focus, its trailer
 * starts after Z.HeroTrailerDelay and the next title follows when it ends; with no trailer it moves on after a while.
 */
@Composable
private fun HeroBackdrop(store: Store, s: RowsState, item: Item, modifier: Modifier) {
  val look = rememberLook(store, item)
  val trailer = Tmdb.trailer(look?.details)
  var trailerOn by remember(item) { mutableStateOf(false) }
  LaunchedEffect(item, s.heroFocused, trailer) {
    trailerOn = false
    s.heroPlaying = false
    if (!s.heroFocused) return@LaunchedEffect
    delay(Z.HeroTrailerDelay)
    if (trailer != null && store.previews) trailerOn = true
    else { delay(8_000); s.moveHero(1) }
  }
  Box(modifier) {
    Crossfade(look?.background ?: img(item.backdrop, "w1280"), animationSpec = tween(ms(500), easing = Z.EaseInOut), label = "heroArt") { url ->
      AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
    if (trailerOn) key(trailer) {
      Trailer(trailer!!, Modifier.fillMaxSize(), store.trailerSound, cover = true, onPlaying = { s.heroPlaying = it }, onEnded = { s.moveHero(1) }, onFailed = { s.moveHero(1) })
    }
    // Readability without a panel: a vignette from the left where the text sits, and a long fade into the rows below.
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Z.Bg.copy(alpha = 0.92f), 0.42f to Z.Bg.copy(alpha = 0.55f), 0.75f to Color.Transparent)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.42f to Color.Transparent, 0.82f to Z.Bg.copy(alpha = 0.92f), 1f to Z.Bg)))
  }
}

/**
 * The hero's text and buttons. Left on Play / Right on My List moves between titles. On a change, the title block
 * makes one short rise-and-fade; the buttons stay exactly where they are, so focus never jumps.
 */
@Composable
private fun HeroInfo(store: Store, s: RowsState, item: Item, nav: Nav) {
  var inList by remember(item) { mutableStateOf(store.inMyList(item)) }
  fun Modifier.edge(key: Key, step: Int) = onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && e.key == key) { s.moveHero(step); true } else false
  }
  val fade by animateFloatAsState(if (s.heroPlaying) 0f else 1f, tween(ms(400), easing = Z.EaseOut), label = "synopsis")
  val enter = ms(300)
  Column(Modifier.fillMaxWidth().height(440.dp).padding(start = Z.Pad, bottom = 22.dp), verticalArrangement = Arrangement.Bottom) {
    Box(Modifier.fillMaxWidth(0.48f).height(206.dp), contentAlignment = Alignment.BottomStart) {
      AnimatedContent(item, transitionSpec = {
        (fadeIn(tween(enter, delayMillis = enter / 6, easing = Z.EaseOut)) + slideInVertically(tween(enter, easing = Z.EaseOut)) { it / 14 }) togetherWith
          fadeOut(tween(enter / 3)) using SizeTransform(clip = false)
      }, contentAlignment = Alignment.BottomStart, label = "heroText") { shown ->
        val look = rememberLook(store, shown)
        Column {
          TitleLogo(look, shown.title, MaterialTheme.typography.displayLarge, 360.dp, 100.dp)
          Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            MetaLine(Tmdb.meta(shown, look?.details), color = Z.Text.copy(alpha = 0.8f))
            Spacer(Modifier.width(14.dp))
            Rating(shown.rating)
          }
          // The synopsis steps aside once the trailer is playing.
          Text(shown.overview, Modifier.padding(top = 10.dp).alpha(fade), style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
      }
    }
    Row(Modifier.padding(top = 18.dp).onFocusChanged { s.heroFocused = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      ZButton("Play", { nav.play(item) }, Modifier.focusMemory(s.focus, "hero/play", default = true).edge(Key.DirectionLeft, -1), primary = true, icon = Icons.Filled.PlayArrow)
      ZButton("More Info", { nav.push(Screen.Title(item)) }, Modifier.focusMemory(s.focus, "hero/info"), icon = Icons.Filled.Info)
      ZButton("My List", { inList = toggleList(store, item) }, Modifier.focusMemory(s.focus, "hero/list").edge(Key.DirectionRight, 1), icon = if (inList) Icons.Filled.Check else Icons.Filled.Add)
    }
    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      val index = s.heroIndex.coerceIn(0, (s.heroItems.size - 1).coerceAtLeast(0))
      s.heroItems.indices.forEach { i ->
        val w by animateDpAsState(if (i == index) 20.dp else 6.dp, tween(ms(240), easing = Z.EaseOut), label = "dot")
        Box(Modifier.width(w).height(4.dp).background(if (i == index) Z.Text else Z.Text.copy(alpha = 0.3f), Z.Small))
      }
    }
  }
}

/** My List, mirrored to the Trakt watchlist when Trakt is connected. Returns true if it's now on the list. */
fun toggleList(store: Store, item: Item) = store.toggleMyList(item).also { Trakt.setWatchlisted(store, item, it) }

@Composable
fun Rating(rating: Double, modifier: Modifier = Modifier) {
  if (rating <= 0) return
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Glyph(Icons.Filled.Star, 14.dp, Z.Red)
    Spacer(Modifier.width(5.dp))
    Text("%.1f".format(rating), style = MaterialTheme.typography.labelLarge, color = Z.Text)
    Spacer(Modifier.width(5.dp))
    Text("TMDB", style = MaterialTheme.typography.labelSmall, color = Z.Muted)
  }
}

/** Films released for home viewing (digital or physical) in the last three months, most popular first. */
fun latestReleases(t: Tmdb): List<Item> {
  val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.UK)
  val now = Date()
  val from = Date(now.time - 90L * 86_400_000)
  val path = "/discover/movie?region=GB&with_release_type=4%7C5&release_date.gte=${fmt.format(from)}&release_date.lte=${fmt.format(now)}&sort_by=popularity.desc&vote_count.gte=20"
  return (t.list(path, "movie") + runCatching { t.list("$path&page=2", "movie") }.getOrDefault(emptyList())).distinctBy { it.key } // ~40 to choose from
}

@Composable
fun PosterRow(store: Store, name: String, items: List<Item>, s: RowsState, nav: Nav, default: Boolean) = Column {
  Text(name, Modifier.padding(start = Z.Pad, top = 10.dp), style = MaterialTheme.typography.titleLarge)
  PivotRow {
  LazyRow(
    state = s.rowLists.getOrPut(name) { LazyListState() },
    contentPadding = PaddingValues(start = Z.Pad, end = 400.dp, top = 14.dp, bottom = 14.dp), // end room so the last card can reach the pivot
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
      val k = "$name/${item.key}"
      PreviewCard(
        store, item, s.expanded == k, Modifier.focusMemory(s.focus, k, default && i == 0),
        onFocus = { f -> if (f) s.focused = k else if (s.focused == k) s.focused = null },
      ) { nav.push(Screen.Title(item)) }
    }
  }
  }
}

/**
 * Netflix-style rows: the focused card always sits at the left edge, so moving along a row scrolls it by one card
 * instead of the card sliding about (that, plus collapsing instantly, is what stops the bounce between previews).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PivotRow(content: @Composable () -> Unit) {
  val pad = with(LocalDensity.current) { Z.Pad.toPx() }
  val spec = remember(pad) { object : BringIntoViewSpec { override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - pad } }
  CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** Poster that widens into a 16:9 preview (artwork, then muted trailer) once focus rests on it. OK opens the title. */
@Composable
fun PreviewCard(store: Store, item: Item, expanded: Boolean, modifier: Modifier, onFocus: (Boolean) -> Unit, onClick: () -> Unit) {
  // Grows smoothly, shrinks at once: an animated shrink drags the next card sideways while the row scrolls.
  val width by animateDpAsState(if (expanded) 330.dp else 124.dp, if (expanded) tween(ms(280), easing = Z.EaseInOut) else snap(), label = "card")
  val look = rememberLook(store, item, enabled = expanded)
  val d = look?.details
  var grown by remember { mutableStateOf(false) } // the trailer only starts once the card is full size
  val growMs = ms(300).toLong()
  LaunchedEffect(expanded) {
    grown = false
    if (expanded) { delay(growMs); grown = true }
  }
  var playing by remember { mutableStateOf(false) }
  val overlay by animateFloatAsState(if (playing) 0f else 1f, tween(ms(220), easing = Z.EaseOut), label = "overlay")
  ZCard(onClick, modifier.width(width).height(186.dp).onFocusChanged { onFocus(it.isFocused) }) {
    Crossfade(expanded, animationSpec = tween(ms(220), easing = Z.EaseOut), label = "preview") { open ->
      Box(Modifier.fillMaxSize()) {
        if (!open) {
          Artwork(img(item.poster, "w342"), item.title)
          Marks(store, item)
        } else {
          AsyncImage(img(item.backdrop ?: item.poster, "w780"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
          val trailer = Tmdb.trailer(d)
          if (trailer != null && grown && store.previews) Trailer(trailer, Modifier.fillMaxSize(), store.trailerSound, onPlaying = { playing = it })
          // Details over the artwork; they fade away once the trailer is playing so it fills the whole card.
          Box(Modifier.fillMaxSize().alpha(overlay).background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
          Column(Modifier.align(Alignment.BottomStart).padding(12.dp).alpha(overlay)) {
            TitleLogo(look, item.title, MaterialTheme.typography.titleMedium.copy(color = Z.Text), 170.dp, 44.dp)
            MetaLine(Tmdb.meta(item, d) + listOfNotNull(item.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) }), Modifier.padding(top = 3.dp))
            Text(item.overview, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }
  }
}

class ContinueEntry(
  val key: String, val item: Item, val season: Int?, val episode: Int?, val meta: String, val fraction: Float?, val at: Long,
  val name: String = "", val isNew: Boolean = false,
)

@Composable
fun ContinueRow(store: Store, list: List<ContinueEntry>, s: RowsState, nav: Nav) = Column {
  Text("Continue Watching", Modifier.padding(start = Z.Pad, top = 10.dp), style = MaterialTheme.typography.titleLarge)
  PivotRow {
  LazyRow(
    state = s.rowLists.getOrPut("continue") { LazyListState() },
    contentPadding = PaddingValues(start = Z.Pad, end = 400.dp, top = 14.dp, bottom = 14.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    items(list, key = { it.key }) { w ->
      val look = rememberLook(store, w.item)
      Column(Modifier.width(232.dp)) {
        ZCard({ nav.push(Screen.Sources(w.item, w.season, w.episode, w.name, autoplay = true)) }, Modifier.focusMemory(s.focus, "continue/${w.key}").fillMaxWidth().aspectRatio(16f / 9f)) {
          Box(Modifier.fillMaxSize()) {
            Artwork(look?.thumb ?: img(w.item.backdrop ?: w.item.poster, "w780"), w.item.title)
            if (w.fraction != null) ProgressBar(w.fraction, Modifier.align(Alignment.BottomCenter))
            if (w.isNew) Text("New episode", Modifier.align(Alignment.TopStart).padding(8.dp).background(Z.Red, Z.Small).padding(horizontal = 7.dp, vertical = 3.dp),
              style = MaterialTheme.typography.labelSmall, color = Color.White)
          }
        }
        Text(w.item.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        MetaLine(listOf(w.meta))
      }
    }
  }
  }
}

// --- Movies / Series / Anime ----------------------------------------------------------------------

class BrowseState(val type: String, val anime: Boolean = false) {
  var sort by mutableIntStateOf(0)
  var genre by mutableStateOf<Int?>(null)
  var items by mutableStateOf<List<Item>>(emptyList())
  var genres by mutableStateOf<List<Pair<Int, String>>>(emptyList())
  var error by mutableStateOf<String?>(null)
  private var page = 0
  private var done = false
  private var loading = false
  val grid = LazyGridState()
  val focus = FocusMemory()
  val chips = FocusRequester() // the filter row: where Down from the top bar lands

  fun reset() {
    items = emptyList()
    page = 0
    done = false
    error = null
  }

  suspend fun more(t: Tmdb) {
    if (loading || done) return
    loading = true
    try {
      val next = withContext(Dispatchers.IO) { t.list(url(page + 1), type) }
      page++
      if (next.isEmpty() || page >= 50) done = true
      items = (items + next).distinctBy { it.key }
    } catch (e: Exception) {
      if (e is kotlinx.coroutines.CancellationException) throw e
      error = e.message
      done = true
    } finally {
      loading = false
    }
  }

  private fun url(p: Int): String {
    val date = if (type == "movie") "primary_release_date" else "first_air_date"
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date())
    val order = when (sort) {
      1 -> "sort_by=vote_average.desc&vote_count.gte=${if (anime) 150 else 300}"
      2 -> "sort_by=$date.desc&$date.lte=$today&vote_count.gte=20"
      else -> "sort_by=popularity.desc"
    }
    val genres = listOfNotNull(if (anime) 16 else null, genre).joinToString(",")
    return "/discover/$type?$order&page=$p" + (if (genres.isNotEmpty()) "&with_genres=$genres" else "") + if (anime) "&with_origin_country=JP" else ""
  }
}

@Composable
fun BrowsePage(store: Store, b: BrowseState, nav: Nav, title: String, restore: Boolean) {
  val t = remember { Tmdb(store.tmdb) }
  val scope = rememberCoroutineScope()
  LaunchedEffect(b.sort, b.genre) { if (b.items.isEmpty()) b.more(t) }
  LaunchedEffect(Unit) {
    if (b.genres.isEmpty()) b.genres = withContext(Dispatchers.IO) { runCatching { t.genres(b.type) }.getOrDefault(emptyList()) }.filter { !b.anime || it.first != 16 }
  }
  RestoreFocus(b.focus, b.items.isNotEmpty() && restore)
  fun pick(sort: Int, genre: Int?) {
    b.sort = sort
    b.genre = genre
    b.reset()
    b.focus.key = null
    scope.launch { b.grid.scrollToItem(0) }
  }
  Column(Modifier.fillMaxSize().padding(top = 76.dp)) {
    Text(title, Modifier.padding(horizontal = Z.Pad), style = MaterialTheme.typography.displaySmall)
    LazyRow(Modifier.focusRequester(b.chips).focusRestorer(), contentPadding = PaddingValues(horizontal = Z.Pad, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      // Can't decide? A few questions, then a pick (Quiz.kt).
      item { Chip("✦ Pick for Me", false) { nav.push(Screen.Quiz(if (b.anime) "anime" else b.type)) } }
      item { Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(26.dp).background(Z.Border)) }
      itemsIndexed(listOf("Popular", "Top Rated", "New Releases")) { i, label -> Chip(label, b.sort == i) { pick(i, b.genre) } }
      item { Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(26.dp).background(Z.Border)) }
      item { Chip("All Genres", b.genre == null) { pick(b.sort, null) } }
      items(b.genres) { (id, name) -> Chip(name, b.genre == id) { pick(b.sort, id) } }
    }
    if (b.items.isEmpty()) {
      if (b.error != null) Text(b.error!!, Modifier.padding(Z.Pad), color = Z.Text2)
      else Row(Modifier.padding(start = Z.Pad, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) { repeat(6) { Skeleton(Modifier.width(128.dp).aspectRatio(2f / 3f)) } }
    }
    PosterGrid(store, b.items, b.focus, nav, b.grid) { scope.launch { b.more(t) } }
  }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) = Surface(
  onClick,
  shape = ClickableSurfaceDefaults.shape(Z.Small),
  // Selected is a quiet red outline, not a red fill: red marks the one key action, not every chosen filter.
  colors = ClickableSurfaceDefaults.colors(
    containerColor = if (selected) Color.White.copy(alpha = 0.08f) else Z.Card, contentColor = if (selected) Z.Text else Z.Text2,
    focusedContainerColor = Z.Text, focusedContentColor = Z.Bg,
  ),
  border = ClickableSurfaceDefaults.border(border = if (selected) Border(androidx.compose.foundation.BorderStroke(1.5.dp, Z.Red), shape = Z.Small) else Border.None),
  scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = Z.Press),
) { Text(label, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge) }

@Composable
fun PosterGrid(store: Store, items: List<Item>, focus: FocusMemory, nav: Nav, state: LazyGridState = rememberLazyGridState(), nearEnd: (() -> Unit)? = null) =
  LazyVerticalGrid(
    GridCells.Fixed(6), state = state,
    contentPadding = PaddingValues(start = Z.Pad, end = Z.Pad, top = 8.dp, bottom = 48.dp),
    horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
      if (nearEnd != null && i >= items.size - 12) LaunchedEffect(items.size) { nearEnd() }
      PosterCard(store, item, Modifier.focusMemory(focus, item.key, i == 0)) { nav.push(Screen.Title(item)) }
    }
  }

/** One tab on My List: Zylo's own list, the Trakt watchlist, your Trakt lists, anime tracker lists. */
class ListSource(val id: String, val label: String, val load: () -> List<Item>)

private val listCache = java.util.concurrent.ConcurrentHashMap<String, List<Item>>()

@Composable
fun MyListPage(store: Store, nav: Nav, focus: FocusMemory, restore: Boolean) {
  val ctx = LocalContext.current
  val tmdb = remember { Tmdb(store.tmdb) }
  var sources by remember { mutableStateOf(listOf(ListSource("mine", "My List") { store.myList })) }
  LaunchedEffect(Unit) {
    val more = withContext(Dispatchers.IO) {
      val trakt = if (Trakt.connected(store)) listOf(ListSource("trakt-watchlist", "Trakt Watchlist") { Trakt.items(tmdb, Trakt.watchlist(store), 200) }) +
        runCatching { Trakt.lists(store) }.getOrDefault(emptyList()).map { l -> ListSource("trakt-${l.id}", l.name) { Trakt.items(tmdb, Trakt.listItems(store, l), 200) } }
      else emptyList()
      val anime = Anime.NAMES.filterKeys { Anime.connected(store, it) }.flatMap { (t, name) ->
        listOf(ListSource("$t-watching", "$name · Watching") { Anime.items(ctx, store, t, "watching") }, ListSource("$t-planning", "$name · Plan to Watch") { Anime.items(ctx, store, t, "planning") })
      }
      trakt + anime
    }
    sources = sources + more
  }
  val tab = nav.ui.listTab
  val source = sources.find { it.id == tab } ?: sources.first()
  var items by remember(source.id) { mutableStateOf(if (source.id == "mine") store.myList else listCache[source.id]) }
  var error by remember(source.id) { mutableStateOf<String?>(null) }
  LaunchedEffect(source.id) {
    if (source.id == "mine") return@LaunchedEffect
    runCatching { withContext(Dispatchers.IO) { source.load() } }.onSuccess { items = it; listCache[source.id] = it }.onFailure { error = it.message }
  }
  RestoreFocus(focus, items?.isNotEmpty() == true && restore)
  Column(Modifier.fillMaxSize().padding(top = 76.dp)) {
    Text("My Lists", Modifier.padding(horizontal = Z.Pad), style = MaterialTheme.typography.displaySmall)
    LazyRow(contentPadding = PaddingValues(horizontal = Z.Pad, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      items(sources, key = { it.id }) { src -> Chip(src.label, src.id == source.id) { nav.ui.listTab = src.id; focus.key = null } }
    }
    val list = items
    when {
      error != null && list == null -> Text(error!!, Modifier.padding(Z.Pad), color = Z.Warning)
      list == null -> Text("Loading…", Modifier.padding(Z.Pad), color = Z.Text2)
      list.isEmpty() && source.id == "mine" -> Text("Nothing here yet. Choose My List on any film or series to save it here.", Modifier.padding(Z.Pad), color = Z.Text2)
      list.isEmpty() -> Text("This list is empty.", Modifier.padding(Z.Pad), color = Z.Text2)
      else -> key(source.id) { PosterGrid(store, list, focus, nav) }
    }
  }
}

// --- Search -----------------------------------------------------------------------------------------

@Composable
fun SearchPage(store: Store, nav: Nav, focus: FocusMemory) {
  var query by rememberSaveable { mutableStateOf("") }
  var results by remember { mutableStateOf<List<Item>?>(null) }
  var live by remember { mutableStateOf(emptyList<LiveHit>()) }
  // One search for everything: films, series and anime from TMDB, plus your live channels, guide listings and fixtures.
  val withLive = remember { store.profile.kids == null && Xtream.from(store) != null }
  if (withLive) LaunchedEffect(Unit) { withContext(Dispatchers.IO) { LiveSearch.warm(nav.ui.live) } }
  LaunchedEffect(query) {
    val q = query.trim()
    if (q.length < 2) {
      results = null
      live = emptyList()
      return@LaunchedEffect
    }
    delay(400) // wait for typing to pause
    if (withLive) launch { live = withContext(Dispatchers.Default) { runCatching { LiveSearch.find(nav.ui.live, q) }.getOrDefault(emptyList()) } }
    results = withContext(Dispatchers.IO) { runCatching { Tmdb(store.tmdb).list("/search/multi?query=${enc(q)}&include_adult=false", null) }.getOrDefault(emptyList()) }
  }
  RestoreFocus(focus, results?.isNotEmpty() == true && focus.key != null)
  Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
    Row(Modifier.padding(horizontal = Z.Pad), verticalAlignment = Alignment.CenterVertically) {
      Glyph(Icons.Filled.Search, 26.dp, Z.Text2)
      Spacer(Modifier.width(14.dp))
      SearchBox(query, { query = it }, autoFocus = focus.key == null, modifier = Modifier.width(540.dp).height(52.dp))
    }
    val r = results
    when {
      r == null -> Text(if (withLive) "Search films, series, anime, channels, the TV guide and fixtures." else "Search films, series and anime.", Modifier.padding(Z.Pad), color = Z.Text2)
      r.isEmpty() && live.isEmpty() -> Text("Nothing found for “${query.trim()}”.", Modifier.padding(Z.Pad), color = Z.Text2)
      else -> Column(Modifier.padding(top = 12.dp)) {
        if (live.isNotEmpty()) LiveHitRow(store, "On Live TV", live, focus, nav)
        if (r.isNotEmpty()) Box(Modifier.padding(top = 8.dp)) { PosterGrid(store, r, focus, nav) }
      }
    }
  }
}

/** A plain EditText: the TV keyboard and the Google TV phone keyboard both work with it reliably. */
@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, autoFocus: Boolean, modifier: Modifier) = AndroidView(modifier = modifier, factory = { ctx ->
  EditText(ctx).apply {
    val density = resources.displayMetrics.density
    fun box(focused: Boolean) = GradientDrawable().apply {
      cornerRadius = 10 * density
      setColor(Z.Card.toArgb())
      setStroke((2 * density).toInt(), (if (focused) Z.Text else Z.Border).toArgb())
    }
    setSingleLine()
    hint = "Films, series, anime"
    setText(value)
    setSelection(value.length)
    textSize = 17f
    setTextColor(Z.Text.toArgb())
    setHintTextColor(Z.Muted.toArgb())
    background = box(false)
    setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
    setOnFocusChangeListener { _, f -> background = box(f) }
    imeOptions = EditorInfo.IME_ACTION_SEARCH
    doAfterTextChanged { onChange(it?.toString().orEmpty()) }
    if (autoFocus) post { requestFocus() }
  }
})
