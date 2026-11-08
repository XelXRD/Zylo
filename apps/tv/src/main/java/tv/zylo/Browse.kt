// Home, Discover, Movies / Series / Anime grids, My List and Search.
package tv.zylo

import android.graphics.drawable.GradientDrawable
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.ui.focus.onFocusChanged
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

class RowsState(private val specs: () -> List<RowSpec>) {
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
        spec.title to runCatching { t.list(spec.path, spec.type) }.getOrElse {
          if (it is HttpError && it.code == 401) error = "TMDB didn't accept your key. Check it in Settings > Integrations."
          emptyList()
        }
      }
    }.awaitAll()
    rows = got.filter { it.second.isNotEmpty() } // only rows that have something in them
    if (rows.isEmpty() && error == null) error = "Couldn't reach TMDB. Check the internet connection."
  }
}

@Composable
fun RowsPage(store: Store, s: RowsState, nav: Nav, restore: Boolean, hero: Boolean = false, title: String? = null) {
  val key = store.tmdb
  LaunchedEffect(key) { if (s.loadedFor != key) s.load(Tmdb(key), key) }
  // Preview timer: restarts on every focus move, so scrolling quickly through a row never starts a trailer.
  LaunchedEffect(s.focused) {
    s.expanded = null
    val k = s.focused ?: return@LaunchedEffect
    delay(Z.PreviewDelay)
    s.expanded = k
  }
  RestoreFocus(s.focus, s.rows.isNotEmpty() && restore)
  if (s.rows.isEmpty()) return Loading(s.error)

  val watching = remember { if (hero) store.continueWatching() else emptyList() }
  val mine = remember { if (hero) store.myList else emptyList() }
  val rows = (if (mine.isNotEmpty()) listOf("My List" to mine) else emptyList()) + s.rows
  val featured = if (hero) s.rows.first().second.firstOrNull { it.backdrop != null } else null
  LazyColumn(Modifier.fillMaxSize(), state = s.list, contentPadding = PaddingValues(top = if (hero) 0.dp else 76.dp, bottom = 56.dp)) {
    if (title != null) item("title") { Text(title, Modifier.padding(start = Z.Pad, bottom = 4.dp), style = MaterialTheme.typography.displaySmall) }
    if (featured != null) item("hero") { HomeHero(store, featured, nav, s.focus) }
    if (watching.isNotEmpty()) item("continue") { ContinueRow(watching, s, nav) }
    items(rows, key = { it.first }) { (name, items) -> PosterRow(store, name, items, s, nav, default = !hero && name == rows.first().first) }
  }
}

@Composable
fun HomeHero(store: Store, item: Item, nav: Nav, focus: FocusMemory) {
  var d by remember(item) { mutableStateOf(Tmdb.cached(item)) }
  LaunchedEffect(item) { if (d == null) d = withContext(Dispatchers.IO) { Tmdb(store.tmdb).preview(item) } }
  var inList by remember(item) { mutableStateOf(store.inMyList(item)) }
  Box(Modifier.fillMaxWidth().height(360.dp)) {
    AsyncImage(img(item.backdrop, "w1280"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Z.Bg, 0.38f to Z.Bg.copy(alpha = 0.7f), 0.72f to Color.Transparent)))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Z.Bg)))
    Column(Modifier.align(Alignment.BottomStart).padding(start = Z.Pad, bottom = 18.dp).fillMaxWidth(0.46f)) {
      Text(item.title, style = MaterialTheme.typography.displayLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
      MetaLine(Tmdb.meta(item, d), Modifier.padding(top = 8.dp))
      Rating(item.rating, Modifier.padding(top = 6.dp))
      Text(item.overview, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
      Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ZButton("Play", { nav.play(item) }, Modifier.focusMemory(focus, "hero/play", default = true), primary = true, icon = Icons.Filled.PlayArrow)
        ZButton("More Info", { nav.push(Screen.Title(item)) }, Modifier.focusMemory(focus, "hero/info"), icon = Icons.Filled.Info)
        ZButton("My List", { inList = store.toggleMyList(item) }, Modifier.focusMemory(focus, "hero/list"), icon = if (inList) Icons.Filled.Check else Icons.Filled.Add)
      }
    }
  }
}

@Composable
fun Rating(rating: Double, modifier: Modifier = Modifier) {
  if (rating <= 0) return
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Glyph(Icons.Filled.Star, 15.dp, Z.Red)
    Spacer(Modifier.width(5.dp))
    Text("%.1f/10".format(rating), style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.width(6.dp))
    Text("TMDB", style = MaterialTheme.typography.labelSmall, color = Z.Muted)
  }
}

@Composable
fun PosterRow(store: Store, name: String, items: List<Item>, s: RowsState, nav: Nav, default: Boolean) = Column {
  Text(name, Modifier.padding(start = Z.Pad, top = 10.dp), style = MaterialTheme.typography.titleLarge)
  LazyRow(
    state = s.rowLists.getOrPut(name) { LazyListState() },
    contentPadding = PaddingValues(horizontal = Z.Pad, vertical = 14.dp),
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

/** Poster that widens into a 16:9 preview (artwork, then muted trailer) once focus rests on it. OK opens the title. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PreviewCard(store: Store, item: Item, expanded: Boolean, modifier: Modifier, onFocus: (Boolean) -> Unit, onClick: () -> Unit) {
  val width by animateDpAsState(if (expanded) 330.dp else 124.dp, tween(ms(300), easing = FastOutSlowInEasing), label = "card")
  val bring = remember { BringIntoViewRequester() }
  var d by remember { mutableStateOf(Tmdb.cached(item)) }
  LaunchedEffect(expanded) { if (expanded && d == null) d = withContext(Dispatchers.IO) { Tmdb(store.tmdb).preview(item) } }
  val grow = ms(320).toLong()
  LaunchedEffect(expanded) {
    if (!expanded) return@LaunchedEffect
    delay(grow)
    bring.bringIntoView() // keep the wider card on screen
  }
  ZCard(onClick, modifier.bringIntoViewRequester(bring).width(width).height(186.dp).onFocusChanged { onFocus(it.isFocused) }) {
    Crossfade(expanded, animationSpec = tween(ms(250)), label = "preview") { open ->
      Box(Modifier.fillMaxSize()) {
        if (!open) {
          Artwork(img(item.poster, "w342"), item.title)
          WatchMarks(store.latest(item)?.takeIf { item.type == "movie" || !it.finished })
        } else {
          AsyncImage(img(item.backdrop ?: item.poster, "w780"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
          val trailer = Tmdb.trailer(d)
          if (trailer != null && store.previews) Trailer(trailer, Modifier.fillMaxSize())
          Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
          Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, color = Z.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MetaLine(Tmdb.meta(item, d) + listOfNotNull(item.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) }), Modifier.padding(top = 3.dp))
            Text(item.overview, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
          }
        }
      }
    }
  }
}

@Composable
fun ContinueRow(list: List<Watch>, s: RowsState, nav: Nav) = Column {
  Text("Continue Watching", Modifier.padding(start = Z.Pad, top = 10.dp), style = MaterialTheme.typography.titleLarge)
  LazyRow(
    state = s.rowLists.getOrPut("continue") { LazyListState() },
    contentPadding = PaddingValues(horizontal = Z.Pad, vertical = 14.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    items(list, key = { it.key }) { w ->
      Column(Modifier.width(232.dp)) {
        ZCard({ nav.push(Screen.Sources(w.item, w.season, w.episode, autoplay = true)) }, Modifier.focusMemory(s.focus, "continue/${w.key}").fillMaxWidth().aspectRatio(16f / 9f)) {
          Box(Modifier.fillMaxSize()) {
            Artwork(img(w.item.backdrop ?: w.item.poster, "w780"), w.item.title)
            ProgressBar(w.fraction, Modifier.align(Alignment.BottomCenter))
          }
        }
        Text(w.item.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        MetaLine(listOfNotNull(w.season?.let { "S$it E${w.episode}" }, "${w.minutesLeft} min left"))
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
    LazyRow(contentPadding = PaddingValues(horizontal = Z.Pad, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      itemsIndexed(listOf("Popular", "Top Rated", "New Releases")) { i, label -> Chip(label, b.sort == i) { pick(i, b.genre) } }
      item { Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(26.dp).background(Z.Border)) }
      item { Chip("All Genres", b.genre == null) { pick(b.sort, null) } }
      items(b.genres) { (id, name) -> Chip(name, b.genre == id) { pick(b.sort, id) } }
    }
    if (b.items.isEmpty()) Text(b.error ?: "Loading…", Modifier.padding(Z.Pad), color = Z.Text2)
    PosterGrid(store, b.items, b.focus, nav, b.grid) { scope.launch { b.more(t) } }
  }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) = Surface(
  onClick,
  shape = ClickableSurfaceDefaults.shape(Z.Small),
  colors = ClickableSurfaceDefaults.colors(
    containerColor = if (selected) Z.Red else Z.Card, contentColor = if (selected) Color.White else Z.Text2,
    focusedContainerColor = Z.Text, focusedContentColor = Z.Bg,
  ),
  scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
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

@Composable
fun MyListPage(store: Store, nav: Nav, focus: FocusMemory, restore: Boolean) {
  val list = remember { store.myList }
  RestoreFocus(focus, list.isNotEmpty() && restore)
  Column(Modifier.fillMaxSize().padding(top = 76.dp)) {
    Text("My List", Modifier.padding(horizontal = Z.Pad), style = MaterialTheme.typography.displaySmall)
    if (list.isEmpty()) Text("Nothing here yet. Choose My List on any film or series to save it here.", Modifier.padding(Z.Pad), color = Z.Text2)
    else PosterGrid(store, list, focus, nav)
  }
}

// --- Search -----------------------------------------------------------------------------------------

@Composable
fun SearchPage(store: Store, nav: Nav, focus: FocusMemory) {
  var query by rememberSaveable { mutableStateOf("") }
  var results by remember { mutableStateOf<List<Item>?>(null) }
  LaunchedEffect(query) {
    val q = query.trim()
    if (q.length < 2) {
      results = null
      return@LaunchedEffect
    }
    delay(400) // wait for typing to pause
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
      r == null -> Text("Search films, series and anime.", Modifier.padding(Z.Pad), color = Z.Text2)
      r.isEmpty() -> Text("Nothing found for “${query.trim()}”.", Modifier.padding(Z.Pad), color = Z.Text2)
      else -> Box(Modifier.padding(top = 20.dp)) { PosterGrid(store, r, focus, nav) }
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
