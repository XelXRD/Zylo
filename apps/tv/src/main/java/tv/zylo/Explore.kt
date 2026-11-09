// Explore: the discovery modes on Discover. Deep Cuts, Moodboard, Double Feature, This or That, Time Machine and
// Blind Date. All of it comes from TMDB (plus Wikipedia for a year's music and culture), worked out on the TV.
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package tv.zylo

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

// --- Shared ---------------------------------------------------------------------------------------------

/** TMDB keyword ids by name, looked up once. Only exact names count, so a mood never drifts onto the wrong keyword. */
object Keywords {
  private val ids = ConcurrentHashMap<String, Int>()
  private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
  fun id(t: Tmdb, name: String): Int? {
    ids[name]?.let { return it }
    if (name in missing) return null
    return runCatching {
      t.get("/search/keyword?query=${enc(name)}").optJSONArray("results")?.objects()?.firstOrNull { it.optString("name").equals(name, true) }?.optInt("id")
    }.getOrNull()?.also { ids[name] = it } ?: run { missing += name; null }
  }
}

/** Raw TMDB results with their genre ids (cards don't keep them), kids-profile safe. */
fun rawList(t: Tmdb, path: String, type: String?): List<Pair<Item, JSONObject>> {
  val got = t.get(path).optJSONArray("results")?.objects().orEmpty()
    .filter { Tmdb.keepsAnime(path) || !Tmdb.animeSeries(it, type) }.mapNotNull { o -> Tmdb.item(o, type)?.let { it to o } }.distinctBy { it.first.key }
  val safe = t.safe(got.map { it.first }).map { it.key }.toSet()
  return got.filter { it.first.key in safe }
}

fun genreIds(o: JSONObject) = o.optJSONArray("genre_ids")?.let { g -> (0 until g.length()).map { g.optInt(it) } }.orEmpty()

/** A shelf of titles with an optional caption under each card. */
class Shelf(val title: String, val note: String = "", val items: List<Item>, val captions: Map<String, String> = emptyMap())

/** Shelves down the page, each a row of posters. The focused row sits mid-screen, as on Home. */
@Composable
fun Shelves(store: Store, shelves: List<Shelf>?, nav: Nav, label: @Composable (String) -> Unit = { Text(it, style = MaterialTheme.typography.titleLarge) },
            header: @Composable () -> Unit) {
  val focus = remember { FocusMemory() }
  RestoreFocus(focus, shelves?.any { it.items.isNotEmpty() } == true)
  val centre = remember {
    object : androidx.compose.foundation.gestures.BringIntoViewSpec {
      override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - (containerSize - size) / 2f
    }
  }
  CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides centre) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 36.dp, bottom = 60.dp)) {
      item("header") { header() }
      if (shelves == null) item("loading") { Box(Modifier.fillMaxWidth().height(300.dp)) { Loading(null) } }
      else shelves.filter { it.items.isNotEmpty() }.forEach { shelf ->
        item(shelf.title) {
          Column(Modifier.padding(top = 18.dp)) {
            Box(Modifier.padding(start = Z.Pad)) { label(shelf.title) }
            if (shelf.note.isNotEmpty()) Text(shelf.note, Modifier.padding(start = Z.Pad, top = 4.dp).fillMaxWidth(0.7f), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
            PivotRow {
              LazyRow(contentPadding = PaddingValues(start = Z.Pad, end = 400.dp, top = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(shelf.items, key = { it.key }) { item ->
                  Column(Modifier.width(124.dp)) {
                    PosterCard(store, item, Modifier.focusMemory(focus, "${shelf.title}/${item.key}")) { nav.push(Screen.Title(item)) }
                    Text(item.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    shelf.captions[item.key]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

private fun page(max: Int = 5) = (1..max).random()

// --- Deep Cuts --------------------------------------------------------------------------------------

/** Things almost nobody has seen, as case files. A fresh dig every visit; anything you've seen is left out. */
fun deepCuts(store: Store): List<Shelf> {
  val t = Tmdb(store.tmdb)
  val langs = listOf("ko" to "Korean", "fr" to "French", "it" to "Italian", "es" to "Spanish", "de" to "German", "ja" to "Japanese", "sv" to "Swedish",
    "da" to "Danish", "fa" to "Iranian", "hi" to "Hindi", "pt" to "Portuguese", "zh" to "Chinese", "pl" to "Polish", "tr" to "Turkish", "no" to "Norwegian").shuffled().take(4)
  fun pick(path: String, type: String, caption: (Item, JSONObject) -> String): Pair<List<Item>, Map<String, String>> {
    val got = runCatching { rawList(t, path, type) }.getOrDefault(emptyList()).filter { !seenBefore(store, it.first) }.shuffled().take(14)
    return got.map { it.first } to got.associate { it.first.key to caption(it.first, it.second) }
  }
  val score = { _: Item, o: JSONObject -> "★ %.1f from just %,d ratings".format(o.optDouble("vote_average"), o.optInt("vote_count")) }
  val (acclaimed, c1) = pick("/discover/movie?vote_average.gte=7.6&vote_count.gte=150&vote_count.lte=900&sort_by=vote_average.desc&page=${page(6)}", "movie", score)
  val (whispers, c2) = pick("/discover/movie?vote_average.gte=8&vote_count.gte=25&vote_count.lte=150&sort_by=vote_average.desc&page=${page(6)}", "movie", score)
  val world = langs.flatMap { (code, name) ->
    runCatching { rawList(t, "/discover/movie?with_original_language=$code&vote_average.gte=7.3&vote_count.gte=200&sort_by=vote_average.desc&page=${page(3)}", "movie") }
      .getOrDefault(emptyList()).filter { !seenBefore(store, it.first) }.shuffled().take(4).map { Triple(it.first, name, it.second) }
  }.shuffled()
  val (classics, c4) = pick("/discover/movie?primary_release_date.lte=1979-12-31&vote_average.gte=7.5&vote_count.gte=120&vote_count.lte=1500&sort_by=vote_average.desc&page=${page(6)}", "movie") { i, _ -> "From ${i.year}. Hardly anyone remembers." }
  val (cancelled, c5) = pick("/discover/tv?with_status=4&vote_average.gte=7.4&vote_count.gte=40&sort_by=vote_average.desc&page=${page(3)}", "tv") { i, o -> "Cancelled. ★ %.1f".format(o.optDouble("vote_average")) }
  return listOf(
    Shelf("CASE FILE 01  ·  Acclaimed, then ignored", "Critics loved them. Audiences never turned up.", acclaimed, c1),
    Shelf("CASE FILE 02  ·  Whispers", "Rated brilliantly by a few hundred people at most.", whispers, c2),
    Shelf("CASE FILE 03  ·  Lost in translation", "The best of ${langs.joinToString(", ") { it.second }} cinema, this time.", world.map { it.first }, world.associate { it.first.key to "${it.second}  ·  ★ %.1f".format(it.third.optDouble("vote_average")) }),
    Shelf("CASE FILE 04  ·  Forgotten classics", "Before 1980, well made, barely remembered.", classics, c4),
    Shelf("CASE FILE 05  ·  Gone too soon", "Series that were cancelled anyway.", cancelled, c5),
  )
}

@Composable
fun DeepCutsPage(store: Store, nav: Nav) {
  var shelves by remember { mutableStateOf<List<Shelf>?>(null) }
  LaunchedEffect(Unit) { shelves = withContext(Dispatchers.IO) { deepCuts(store) } }
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF07080A), Color(0xFF130A0C), Color(0xFF07080A))))) {
    Shelves(store, shelves, nav, label = { Text(it, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace), color = Z.Red) }) {
      Column(Modifier.padding(horizontal = Z.Pad)) {
        Text("DEEP CUTS", style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace))
        Text("Things almost nobody has seen. A new dig every time you come back. Handle with care.", Modifier.padding(top = 6.dp),
          style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), color = Z.Text2)
      }
    }
  }
}

// --- Moodboard ------------------------------------------------------------------------------------

/** An atmosphere: TMDB keywords (any of them) plus film genres (any of them). */
class Mood(val name: String, val emoji: String, val line: String, val colour: Long, val keywords: List<String>, val genres: String)

val MOODS = listOf(
  Mood("Rainy Sunday", "🌧️", "Warm, gentle, nothing too heavy", 0xFF3D5A80, listOf("feel-good", "small town", "friendship", "slice of life", "family relationships"), "18|10749|35"),
  Mood("Late-Night Paranoia", "👁️", "Someone is watching. Trust no one", 0xFF2B2D42, listOf("paranoia", "conspiracy", "surveillance", "cover-up"), "53|9648"),
  Mood("Nostalgic Childhood", "🚲", "Bikes, summers and growing up", 0xFFB7791F, listOf("coming of age", "childhood", "nostalgia", "summer camp"), "10751|12|35"),
  Mood("Dystopian Future", "🏙️", "Tomorrow went wrong", 0xFF5C5C5C, listOf("dystopia", "post-apocalyptic future", "totalitarian regime"), "878|18"),
  Mood("Psychological Mind Games", "🧠", "Nothing is what it seems", 0xFF6B2D5C, listOf("psychological thriller", "manipulation", "twist ending", "unreliable narrator"), "53|9648|18"),
  Mood("Summer Adventure", "🏝️", "Sun, roads and somewhere new", 0xFF2F8F83, listOf("summer", "road trip", "treasure hunt", "island"), "12|35"),
  Mood("Existential Sci-Fi", "🪐", "Big questions, deep space", 0xFF1B263B, listOf("existentialism", "artificial intelligence (a.i.)", "consciousness", "space travel"), "878"),
  Mood("Cosy Winter Night", "❄️", "Snow outside, blanket inside", 0xFF8DA9C4, listOf("christmas", "snow", "winter"), "10751|35|10749"),
  Mood("Neon Noir", "🌃", "Rain-slick streets after midnight", 0xFFBD1422, listOf("neo-noir", "femme fatale", "hitman"), "80|53"),
  Mood("Haunted Hours", "🕯️", "Lights off. Don't look behind you", 0xFF3A2E39, listOf("haunted house", "ghost", "possession"), "27"),
)

fun moodShelves(store: Store, m: Mood): List<Shelf> {
  val t = Tmdb(store.tmdb)
  val kw = m.keywords.mapNotNull { Keywords.id(t, it) }.joinToString("|")
  val no = Thumbs.disliked(store)
  fun films(sort: String) = runCatching {
    t.list("/discover/movie?with_genres=${m.genres}" + (if (kw.isNotEmpty()) "&with_keywords=$kw" else "") + "&vote_count.gte=150&sort_by=$sort&page=${page(3)}", "movie")
  }.getOrDefault(emptyList()).filter { it.key !in no }.shuffled()
  val series = if (kw.isEmpty()) emptyList() else runCatching { t.list("/discover/tv?with_keywords=$kw&vote_count.gte=50&sort_by=popularity.desc", "tv") }.getOrDefault(emptyList())
  return listOf(
    Shelf("The feeling", m.line, films("popularity.desc")),
    Shelf("Best of the mood", "The highest rated with this atmosphere.", films("vote_average.desc")),
    Shelf("Series", "To sink into for a while.", series),
  )
}

@Composable
fun MoodboardPage(store: Store, nav: Nav) {
  var mood by remember { mutableStateOf<Mood?>(null) }
  val m = mood
  androidx.activity.compose.BackHandler(m != null) { mood = null }
  if (m == null) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(horizontal = Z.Pad, vertical = 36.dp)) {
      Text("Moodboard", style = MaterialTheme.typography.displaySmall)
      Text("Choose a feeling, not a genre.", Modifier.padding(top = 6.dp, bottom = 20.dp), style = MaterialTheme.typography.bodyLarge, color = Z.Text2)
      LazyVerticalGrid(GridCells.Fixed(5), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(MOODS.size) { i ->
          val it = MOODS[i]
          ZSurface({ mood = it }, Modifier.then(if (i == 0) Modifier.focusRequester(first) else Modifier).height(150.dp), container = Color(it.colour).copy(alpha = 0.55f)) {
            Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))).padding(14.dp), verticalArrangement = Arrangement.Bottom) {
              Text(it.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
              Text(it.line, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text.copy(alpha = 0.78f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }
  } else {
    var shelves by remember(m) { mutableStateOf<List<Shelf>?>(null) }
    LaunchedEffect(m) { shelves = withContext(Dispatchers.IO) { moodShelves(store, m) } }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(m.colour).copy(alpha = 0.45f), Z.Bg, Z.Bg)))) {
      Shelves(store, shelves, nav) {
        Column(Modifier.padding(horizontal = Z.Pad)) {
          Text(m.name, style = MaterialTheme.typography.displaySmall)
          Text(m.line, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyLarge, color = Z.Text2)
        }
      }
    }
  }
}

// --- Double Feature -------------------------------------------------------------------------------

class Pairing(val name: String, val why: String, val a: Item, val b: Item, val aNote: String, val bNote: String)

object DoubleFeature {
  private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

  /** Two films by the same director, one well known, one less so. */
  fun director(t: Tmdb, store: Store): Pairing? {
    val seed = (listOfNotNull(Thumbs.lastLiked(store)?.takeIf { it.type == "movie" }) + t.list("/movie/top_rated?page=${page(8)}", "movie")).shuffled().first()
    val d = t.details("movie", seed.id)
    val dir = d.optJSONObject("credits")?.optJSONArray("crew")?.objects()?.firstOrNull { it.optString("job") == "Director" } ?: return null
    val other = t.get("/person/${dir.optInt("id")}/movie_credits").optJSONArray("crew")?.objects().orEmpty()
      .filter { it.optString("job") == "Director" && it.optInt("id") != seed.id && it.optInt("vote_count") > 150 }
      .sortedByDescending { it.optDouble("vote_average") }.firstNotNullOfOrNull { Tmdb.item(it, "movie") } ?: return null
    val name = dir.optString("name")
    val (first, second) = listOf(seed, other).sortedBy { it.year }
    return Pairing("The ${name.substringAfterLast(' ')} Double", "Two films by $name, ${second.year.toIntOrNull()?.minus(first.year.toIntOrNull() ?: 0)?.let { "$it years apart" } ?: "side by side"}. Watch how the same eye changes.",
      first, second, "${first.year}  ·  the earlier one", "${second.year}  ·  the later one")
  }

  /** An original and its remake. */
  fun remake(t: Tmdb): Pairing? {
    val kw = Keywords.id(t, "remake") ?: return null
    repeat(4) {
      val r = t.list("/discover/movie?with_keywords=$kw&vote_count.gte=300&sort_by=popularity.desc&page=${page(4)}", "movie").randomOrNull() ?: return null
      val y = r.year.toIntOrNull() ?: return@repeat
      val orig = t.list("/search/movie?query=${enc(r.title)}", "movie").firstOrNull { norm(it.title) == norm(r.title) && (it.year.toIntOrNull() ?: 9999) < y - 3 }
      if (orig != null) return Pairing("Then & Now", "The same story told twice, ${y - orig.year.toInt()} years apart. Which one wins?", orig, r, "${orig.year}  ·  the original", "${r.year}  ·  the remake")
    }
    return null
  }

  private val SUBJECTS = listOf("world war ii", "artificial intelligence (a.i.)", "time travel", "heist", "journalism", "vietnam war", "serial killer", "boxing", "cold war", "space mission", "dinosaur", "high school")

  /** Two films about the same thing from completely different angles (different genres). */
  fun perspectives(t: Tmdb): Pairing? {
    val subject = SUBJECTS.random()
    val kw = Keywords.id(t, subject) ?: return null
    val pool = rawList(t, "/discover/movie?with_keywords=$kw&vote_count.gte=400&sort_by=vote_average.desc&page=${page(2)}", "movie").shuffled()
    val genres = t.genres("movie").toMap()
    val a = pool.firstOrNull() ?: return null
    val ga = genreIds(a.second).firstOrNull()
    val b = pool.drop(1).firstOrNull { genreIds(it.second).firstOrNull() != ga } ?: return null
    val (na, nb) = listOf(a, b).map { genres[genreIds(it.second).firstOrNull()]?.lowercase() ?: "film" }
    val topic = subject.substringBefore(" (").replaceFirstChar(Char::uppercase)
    return Pairing("Two Sides of $topic", "${a.first.title} and ${b.first.title} both take on ${subject.substringBefore(" (")}, one as a $na, the other as a $nb.",
      a.first, b.first, "The $na", "The $nb")
  }

  /** Something light, then something dark, from the same year. */
  fun lightThenDark(t: Tmdb): Pairing? {
    val year = (1975..Calendar.getInstance().get(Calendar.YEAR) - 1).random()
    val light = t.list("/discover/movie?with_genres=35&primary_release_year=$year&vote_count.gte=300&sort_by=vote_average.desc", "movie").take(6).randomOrNull() ?: return null
    val dark = t.list("/discover/movie?with_genres=53|27|80&without_genres=35&primary_release_year=$year&vote_count.gte=300&sort_by=vote_average.desc", "movie").take(6).randomOrNull() ?: return null
    return Pairing("Sunshine & Shadows", "Warm up with ${light.title}, then go somewhere much darker with ${dark.title}. Both from $year.", light, dark, "First: the light one", "Then: the dark one")
  }

  fun any(store: Store): Pairing? {
    val t = Tmdb(store.tmdb)
    val makers = listOf<() -> Pairing?>({ director(t, store) }, { remake(t) }, { perspectives(t) }, { lightThenDark(t) }).shuffled()
    for (m in makers + makers) runCatching { m() }.getOrNull()?.let { return it }
    return null
  }
}

@Composable
fun DoubleFeaturePage(store: Store, nav: Nav) {
  var p by remember { mutableStateOf<Pairing?>(null) }
  var round by remember { mutableIntStateOf(0) }
  var busy by remember { mutableStateOf(true) }
  LaunchedEffect(round) { busy = true; p = withContext(Dispatchers.IO) { DoubleFeature.any(store) }; busy = false }
  val first = remember { FocusRequester() }
  LaunchedEffect(p) { if (p != null) { delay(80); runCatching { first.requestFocus() } } }
  val pair = p
  Box(Modifier.fillMaxSize()) {
    pair?.let { AsyncImage(img(it.a.backdrop, "w1280"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    Box(Modifier.fillMaxSize().background(Z.Bg.copy(alpha = 0.86f)))
    Column(Modifier.fillMaxSize().padding(horizontal = Z.Pad, vertical = 36.dp)) {
      Text("DOUBLE FEATURE", style = MaterialTheme.typography.labelLarge, color = Z.Red)
      when {
        busy && pair == null -> Text("Pairing something up…", Modifier.padding(top = 20.dp), color = Z.Text2)
        pair == null -> Text("Couldn't find a pairing right now. Try again.", Modifier.padding(top = 20.dp), color = Z.Text2)
        else -> {
          Text(pair.name, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.displaySmall)
          Text(pair.why, Modifier.padding(top = 8.dp).fillMaxWidth(0.75f), style = MaterialTheme.typography.bodyLarge, color = Z.Text2)
          Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
            listOf(pair.a to pair.aNote, pair.b to pair.bNote).forEachIndexed { i, (item, note) ->
              Row(Modifier.weight(1f)) {
                Column(Modifier.width(140.dp)) {
                  PosterCard(store, item, if (i == 0) Modifier.focusRequester(first) else Modifier) { nav.push(Screen.Title(item)) }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                  Text(if (i == 0) "1" else "2", style = MaterialTheme.typography.displaySmall, color = Z.Red)
                  Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                  Text(note, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
                  Text(item.overview, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
              }
            }
          }
          Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZButton("Play the First", { nav.play(pair.a) }, primary = true)
            ZButton(if (busy) "Pairing…" else "Another Pairing", { if (!busy) round++ })
          }
        }
      }
    }
  }
}

// --- This or That ---------------------------------------------------------------------------------

/**
 * Two at a time; you keep one. Each choice teaches it: genres you pick score up, ones you pass on score down, and
 * the next challenger is the best-scoring title left. After [rounds] the one still standing wins.
 */
class Elimination(private val pool: List<Pair<Item, List<Int>>>, private val rounds: Int = 8, private val rnd: kotlin.random.Random = kotlin.random.Random) {
  val score = HashMap<Int, Double>()
  private val used = HashSet<String>()
  var round = 1; private set
  var champ: Pair<Item, List<Int>> = pool[0].also { used += it.first.key }; private set
  var challenger: Pair<Item, List<Int>>? = next(); private set
  val done get() = challenger == null || round > rounds

  private fun next(): Pair<Item, List<Int>>? = pool.filter { it.first.key !in used }
    .maxByOrNull { p -> p.second.sumOf { score[it] ?: 0.0 } + rnd.nextDouble() * 0.9 }?.also { used += it.first.key }

  /** keepChamp: the one already on screen won. */
  fun pick(keepChamp: Boolean) {
    val c = challenger ?: return
    val (win, lose) = if (keepChamp) champ to c else c to champ
    win.second.forEach { score.merge(it, 1.0, Double::plus) }
    lose.second.forEach { score.merge(it, -0.5, Double::plus) }
    champ = win
    round++
    challenger = if (round > rounds) null else next()
  }

  fun drawnTo(names: Map<Int, String>) = score.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(3).mapNotNull { names[it.key] }
}

fun eliminationPool(store: Store, type: String): List<Pair<Item, List<Int>>> {
  val t = Tmdb(store.tmdb)
  val media = media(type)
  val base = if (type == "anime") "/discover/tv?with_genres=16&with_origin_country=JP&vote_count.gte=150" else "/discover/$media?vote_count.gte=${if (media == "movie") 1000 else 300}"
  val no = Thumbs.disliked(store)
  return (1..10).shuffled().take(3).flatMap { p -> runCatching { rawList(t, "$base&sort_by=popularity.desc&page=$p", media) }.getOrDefault(emptyList()) }
    .filter { it.first.backdrop != null && it.first.key !in no }.distinctBy { it.first.key }.shuffled().map { it.first to genreIds(it.second) }
}

@Composable
fun ThisOrThatPage(store: Store, type: String, nav: Nav) {
  var game by remember { mutableStateOf<Elimination?>(null) }
  var tick by remember { mutableIntStateOf(0) }
  var genres by remember { mutableStateOf(emptyMap<Int, String>()) }
  LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
      val pool = eliminationPool(store, type)
      genres = runCatching { Tmdb(store.tmdb).genres(media(type)).toMap() }.getOrDefault(emptyMap())
      game = if (pool.size >= 3) Elimination(pool) else null
    }
    if (game == null) tick = -1
  }
  val g = game ?: return Loading(if (tick == -1) "Couldn't load titles to choose between." else null)
  val left = remember { FocusRequester() }
  LaunchedEffect(tick) { delay(80); runCatching { left.requestFocus() } }
  key(tick) {
    if (g.done) {
      val w = g.champ.first
      val drawn = g.drawnTo(genres)
      Box(Modifier.fillMaxSize()) {
        AsyncImage(img(w.backdrop, "w1280"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Z.Bg, Z.Bg.copy(alpha = 0.55f), Color.Transparent))))
        Column(Modifier.align(Alignment.CenterStart).padding(start = Z.Pad * 2).fillMaxWidth(0.5f)) {
          Text("YOUR WINNER", style = MaterialTheme.typography.labelLarge, color = Z.Red)
          Text(w.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.displayMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
          MetaLine(listOf(w.year, "★ %.1f".format(w.rating)), Modifier.padding(top = 6.dp))
          if (drawn.isNotEmpty()) Text("You kept choosing ${drawn.joinToString(", ")}.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyLarge, color = Z.Text2)
          Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZButton("Play", { nav.play(w) }, Modifier.focusRequester(left), primary = true)
            ZButton("More Info", { nav.push(Screen.Title(w)) })
            ZButton("Play Again", { game = null; tick = 0; nav.replace(Screen.ThisOrThat(type)) })
          }
        }
      }
    } else {
      val c = g.challenger!!
      Column(Modifier.fillMaxSize().padding(horizontal = Z.Pad, vertical = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("THIS OR THAT", style = MaterialTheme.typography.labelLarge, color = Z.Red)
          Spacer(Modifier.weight(1f))
          Text("Round ${g.round} of 8", style = MaterialTheme.typography.labelLarge, color = Z.Text2)
        }
        Text("Which would you rather watch?", Modifier.padding(top = 6.dp, bottom = 18.dp), style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
          listOf(g.champ.first to true, c.first to false).forEachIndexed { i, (item, champ) ->
            ZCard({ g.pick(champ); tick++ }, Modifier.weight(1f).fillMaxHeight().then(if (i == 0) Modifier.focusRequester(left) else Modifier)) {
              Box(Modifier.fillMaxSize()) {
                AsyncImage(img(item.backdrop, "w780"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)))).padding(18.dp)) {
                  Text(item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  MetaLine(listOf(item.year, "★ %.1f".format(item.rating)))
                  Text(item.overview, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
              }
            }
          }
        }
      }
    }
  }
}

/** Movies / Series / Anime: two ways to decide. */
@Composable
fun HelpMePage(type: String, nav: Nav) {
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
  Column(Modifier.fillMaxSize().padding(horizontal = Z.Pad * 2), verticalArrangement = Arrangement.Center) {
    Text("Help me choose", style = MaterialTheme.typography.displaySmall)
    Text("Two ways to find tonight's ${if (type == "movie") "film" else if (type == "anime") "anime" else "series"}.", Modifier.padding(top = 6.dp, bottom = 26.dp), style = MaterialTheme.typography.bodyLarge, color = Z.Text2)
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      listOf(Triple("✦  Pick for Me", "Answer five quick questions about mood, era and more. Zylo picks, and you can re-roll.", { nav.replace(Screen.Quiz(type)) }),
        Triple("⚔  This or That", "Two at a time: keep the one you'd rather watch. It learns as you go until one is left.", { nav.replace(Screen.ThisOrThat(type)) }))
        .forEachIndexed { i, (name, line, go) ->
          ZSurface(go, Modifier.weight(1f).height(190.dp).then(if (i == 0) Modifier.focusRequester(first) else Modifier), container = Z.Card) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
              Text(name, style = MaterialTheme.typography.headlineSmall)
              Text(line, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
            }
          }
        }
    }
  }
}

// --- Time Machine ---------------------------------------------------------------------------------

/** A decade's look: accent colour and a line. */
private fun decadeLook(year: Int) = when (year / 10 * 10) {
  1950 -> 0xFFB08D57 to "Technicolor and drive-ins"
  1960 -> 0xFFE07A5F to "Swinging, spaced-out and new wave"
  1970 -> 0xFFD2691E to "Grain, flares and the New Hollywood"
  1980 -> 0xFFFF2E88 to "Neon, synths and VHS"
  1990 -> 0xFF2EC4B6 to "Dial-up, grunge and the multiplex"
  2000 -> 0xFF6C8EAD to "DVD box sets and the blockbuster boom"
  2010 -> 0xFF9B5DE5 to "Streaming arrives"
  else -> 0xFFBD1422 to "Right now"
}

class YearPack(val shelves: List<Shelf>, val people: List<JSONObject>, val music: String?, val culture: String?)

fun timeMachine(store: Store, y: Int): YearPack {
  val t = Tmdb(store.tmdb)
  fun l(path: String, type: String) = runCatching { t.list(path, type) }.getOrDefault(emptyList())
  val biggest = l("/discover/movie?primary_release_year=$y&sort_by=revenue.desc&vote_count.gte=50", "movie")
  val tv = l("/discover/tv?air_date.gte=$y-01-01&air_date.lte=$y-12-31&sort_by=popularity.desc&vote_count.gte=100", "tv")
  val anime = l("/discover/tv?with_genres=16&with_origin_country=JP&first_air_date_year=$y&sort_by=popularity.desc", "tv")
  val gems = l("/discover/movie?primary_release_year=$y&vote_average.gte=7.3&vote_count.gte=60&vote_count.lte=700&sort_by=vote_average.desc", "movie")
  // The year's faces: who turns up most in its ten biggest films.
  val people = biggest.take(10).flatMap { f -> runCatching { t.details("movie", f.id).optJSONObject("credits")?.optJSONArray("cast")?.objects().orEmpty().take(6) }.getOrDefault(emptyList()) }
    .groupBy { it.optInt("id") }.values.sortedByDescending { g -> g.size * 10 + g.first().optDouble("popularity") }.take(14).map { it.first() }
  fun wiki(page: String) = runCatching { getJson("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(page)}").str("extract") }.getOrNull()
  return YearPack(listOf(
    Shelf("Biggest films of $y", "The box office, top down.", biggest),
    Shelf("On TV in $y", "What was airing.", tv),
    Shelf("Anime of $y", "Series that started that year.", anime),
    Shelf("Hidden gems of $y", "Great, and somehow missed.", gems),
  ), people, wiki("${y}_in_music"), wiki("${y}_in_the_United_Kingdom") ?: wiki("$y"))
}

@Composable
fun TimeMachinePage(store: Store, nav: Nav) {
  val now = remember { Calendar.getInstance().get(Calendar.YEAR) }
  var year by rememberSaveableInt(1999)
  var pack by remember { mutableStateOf<YearPack?>(null) }
  LaunchedEffect(year) { pack = null; pack = withContext(Dispatchers.IO) { timeMachine(store, year) } }
  val (accent, vibe) = decadeLook(year)
  val tint = Color(accent)
  val p = pack
  Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.35f), Z.Bg, Z.Bg)))) {
    Shelves(store, p?.shelves, nav, label = { Text(it, style = MaterialTheme.typography.titleLarge, color = tint) }) {
      Column {
        Column(Modifier.padding(horizontal = Z.Pad)) {
          Text("TIME MACHINE", style = MaterialTheme.typography.labelLarge, color = tint)
          Text("$year", style = MaterialTheme.typography.displayLarge)
          Text(vibe, style = MaterialTheme.typography.titleMedium, color = Z.Text2)
        }
        // Decades, then the years of the chosen one.
        LazyRow(Modifier.padding(top = 14.dp), contentPadding = PaddingValues(horizontal = Z.Pad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items((1950..now step 10).toList()) { d -> Chip("${d}s", year / 10 * 10 == d) { year = minOf(d + 5, now) } }
        }
        LazyRow(Modifier.padding(top = 8.dp), contentPadding = PaddingValues(horizontal = Z.Pad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items((year / 10 * 10..minOf(year / 10 * 10 + 9, now)).toList()) { y -> Chip("$y", y == year) { year = y } }
        }
        if (p != null && p.people.isNotEmpty()) {
          Text("Faces of $year", Modifier.padding(start = Z.Pad, top = 22.dp), style = MaterialTheme.typography.titleLarge, color = tint)
          LazyRow(contentPadding = PaddingValues(horizontal = Z.Pad, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(p.people, key = { it.optInt("id") }) { person ->
              Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ZSurface({ nav.push(Screen.Person(person.optInt("id"), person.optString("name"))) }, Modifier.size(88.dp), container = Z.Card) {
                  img(person.str("profile_path"), "w185")?.let { AsyncImage(it, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop) }
                }
                Text(person.optString("name"), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }
        if (p != null && (p.music != null || p.culture != null)) Row(Modifier.padding(horizontal = Z.Pad, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
          listOfNotNull(p.music?.let { "🎵  Music in $year" to it }, p.culture?.let { "📰  $year" to it }).forEach { (head, text) ->
            Column(Modifier.weight(1f).background(Z.Card, Z.Medium).padding(16.dp)) {
              Text(head, style = MaterialTheme.typography.titleSmall, color = tint)
              Text(text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 6, overflow = TextOverflow.Ellipsis)
              Text("From Wikipedia", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = Z.Muted)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun rememberSaveableInt(start: Int) = androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(start) }

// --- Blind Date -----------------------------------------------------------------------------------

/** A film picked for you with the title, poster and cast hidden: only clues. */
class BlindDate(val item: Item, val genres: List<String>, val runtime: Int, val decade: String, val teaser: String, val clues: List<String>)

fun blindDate(store: Store): BlindDate? {
  val t = Tmdb(store.tmdb)
  // Your taste: genres of what you've liked, else of what you've watched recently, else anything well rated.
  val seeds = (listOfNotNull(Thumbs.lastLiked(store)) + store.watches().sortedByDescending { it.at }.map { it.item }.distinctBy { it.key }.take(5))
  val liked = seeds.flatMap { s -> runCatching { t.preview(s)?.optJSONArray("genres")?.objects()?.map { it.optInt("id") } }.getOrNull().orEmpty() }
  val movieGenres = t.genres("movie").map { it.first }.toSet()
  val g = liked.filter { it in movieGenres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }.randomOrNull()
  repeat(3) {
    val pick = t.list("/discover/movie?vote_average.gte=7&vote_count.gte=300" + (g?.let { "&with_genres=$it" } ?: "") + "&sort_by=popularity.desc&page=${page(10)}", "movie")
      .filter { !seenBefore(store, it) && it.key !in Thumbs.disliked(store) }.randomOrNull() ?: return@repeat
    val d = t.details("movie", pick.id)
    val names = (listOf(pick.title) + d.optJSONObject("credits")?.optJSONArray("cast")?.objects().orEmpty().take(8).flatMap { listOf(it.optString("name"), it.optString("character")) })
      .flatMap { it.split(' ', '/', '"') }.filter { it.length > 2 && it.first().isUpperCase() }.toSet()
    // Spoiler-free: the tagline if there is one, else the first sentence with names blanked out.
    val teaser = d.str("tagline") ?: d.optString("overview").substringBefore(". ").split(' ').joinToString(" ") { w -> if (w.trim(',', '.', '\'', 's') in names) "▇▇▇" else w } + "…"
    val year = pick.year.toIntOrNull() ?: 0
    val director = d.optJSONObject("credits")?.optJSONArray("crew")?.objects()?.firstOrNull { it.optString("job") == "Director" }
    val clues = listOfNotNull(
      d.optJSONArray("production_countries")?.objects()?.firstOrNull()?.str("name")?.let { "Made in $it" },
      d.str("original_language")?.takeIf { it != "en" }?.let { "Not in English: ${languageName(it)}" },
      Tmdb.certification(d)?.let { "Rated $it" },
      "★ %.1f from %,d ratings".format(d.optDouble("vote_average"), d.optInt("vote_count")),
      director?.let { dir -> runCatching { t.get("/person/${dir.optInt("id")}/movie_credits").optJSONArray("crew")?.objects()?.count { it.optString("job") == "Director" } }.getOrNull()?.let { "Its director has made $it films" } },
      d.optJSONObject("keywords")?.optJSONArray("keywords")?.objects()?.map { it.optString("name") }?.filter { k -> names.none { k.contains(it, true) } }?.shuffled()?.take(3)
        ?.takeIf { it.isNotEmpty() }?.let { "Themes: ${it.joinToString(", ")}" },
    )
    return BlindDate(pick, d.optJSONArray("genres")?.objects()?.map { it.optString("name") }.orEmpty(), d.optInt("runtime"),
      if (year > 0) "${year / 10 * 10}s" else "", teaser, clues)
  }
  return null
}

@Composable
fun BlindDatePage(store: Store, nav: Nav) {
  var date by remember { mutableStateOf<BlindDate?>(null) }
  var round by remember { mutableIntStateOf(0) }
  var revealed by remember { mutableStateOf(false) }
  var failed by remember { mutableStateOf(false) }
  LaunchedEffect(round) { date = null; revealed = false; failed = false; date = withContext(Dispatchers.IO) { runCatching { blindDate(store) }.getOrNull() }; failed = date == null }
  val first = remember { FocusRequester() }
  LaunchedEffect(date, revealed) { if (date != null) { delay(100); runCatching { first.requestFocus() } } }
  val d = date ?: return Loading(if (failed) "Couldn't set up a blind date right now." else null)
  AnimatedContent(revealed, label = "reveal") { shown ->
    if (!shown) Column(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF3A0A12), Z.Bg))).padding(horizontal = Z.Pad * 2, vertical = 40.dp), verticalArrangement = Arrangement.Center) {
      Text("BLIND DATE", style = MaterialTheme.typography.labelLarge, color = Z.Red)
      Text("A film picked for you. No title, no poster, no faces.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.headlineSmall)
      Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        (d.genres.take(3) + listOfNotNull(d.runtime.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m" }, d.decade.ifEmpty { null })).forEach {
          Text(it, Modifier.background(Z.Card, Z.Small).padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
        }
      }
      Text("“${d.teaser}”", Modifier.padding(top = 22.dp).fillMaxWidth(0.75f), style = MaterialTheme.typography.titleLarge)
      Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        d.clues.forEach { Text("•  $it", style = MaterialTheme.typography.bodyLarge, color = Z.Text2) }
      }
      Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ZButton("♥  I'm In: Reveal It", { revealed = true }, Modifier.focusRequester(first), primary = true)
        ZButton("Another Date", { round++ })
      }
      Text("Commit first. That's the deal.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium, color = Z.Muted)
    } else Box(Modifier.fillMaxSize()) {
      AsyncImage(img(d.item.backdrop, "w1280"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Z.Bg, Z.Bg.copy(alpha = 0.5f), Color.Transparent))))
      Row(Modifier.align(Alignment.CenterStart).padding(start = Z.Pad * 2), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(180.dp).aspectRatio(2f / 3f).clip(Z.Medium)) { Artwork(img(d.item.poster, "w342"), d.item.title) }
        Spacer(Modifier.width(28.dp))
        Column(Modifier.width(460.dp)) {
          Text("YOUR DATE IS", style = MaterialTheme.typography.labelLarge, color = Z.Red)
          Text(d.item.title, style = MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
          MetaLine(listOf(d.item.year), Modifier.padding(top = 4.dp))
          Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZButton("Play", { nav.play(d.item) }, Modifier.focusRequester(first), primary = true)
            ZButton("More Info", { nav.push(Screen.Title(d.item)) })
          }
        }
      }
    }
  }
}

// --- Discover's Explore row ------------------------------------------------------------------------

/** One way into discovery. [line] fits a tile in two lines; [pitch] is the fuller sentence when it's in the spotlight. */
class Mode(val name: String, val line: String, val pitch: String, val go: (Nav) -> Unit)

private class ModeGroup(val name: String, val modes: List<Mode>)

private fun modeGroups(store: Store): List<ModeGroup> {
  val kids = store.profile.kids != null
  return listOf(
    ModeGroup("Decide together", listOf(
      Mode("Movie Night Match", "Everyone swipes on their phone; one film wins.",
        "Everyone scans a code and swipes on their phone. The first film or series you all say yes to wins.") { it.push(Screen.MovieNight) },
      Mode("Help Me Choose", "A quick quiz, or This or That until one is left.",
        "Five questions and a reveal, or two titles at a time until only one is left.") { it.push(Screen.HelpMe("movie")) },
      Mode("Blind Date", "A film from clues alone. Commit, then see it.",
        "A film picked for you with only clues to go on. Commit to it, then it's revealed.") { it.push(Screen.BlindDate) },
      Mode("Double Feature", "Two films that belong together, and why.",
        "Two films that belong together: same director, original and remake, light then dark. Each pair comes with the reason.") { it.push(Screen.DoubleFeature) },
    )),
    ModeGroup("Go deeper", listOf(
      Mode("Rabbit Hole", "Follow films, people and themes as far as they go.",
        "Jump from a film to its director, cast, composer, genres and themes, and on from each of those. Back retraces the trail.") { it.push(Screen.RabbitStart) },
      Mode("Deep Cuts", "Things almost nobody has seen.",
        "Case files on the overlooked: acclaimed but unseen, tiny but brilliant, world cinema, forgotten classics, cancelled series.") { it.push(Screen.DeepCuts) },
      Mode("Franchise Journeys", "Every film and series in a universe, in order.",
        "Every film and series in a universe, in recommended, story or release order, with the spin-offs and what's coming.") { it.push(Screen.Franchises) },
      Mode("Moodboard", "Pick a feeling, not a genre.",
        "Pick a feeling, not a genre: Rainy Sunday, Late-Night Paranoia, Existential Sci-Fi and more.") { it.push(Screen.Moodboard) },
      Mode("Time Machine", "Go back to any year from 1950.",
        "Any year from 1950: its biggest films, TV, anime, faces, music and news, and the gems it hid.") { it.push(Screen.TimeMachine) },
    )),
    ModeGroup("Keep track", listOfNotNull(
      Mode("Watch Challenges", "Curated lists that tick themselves off.",
        "Curated lists to complete, ticked off automatically from everything you watch, here or on Trakt.") { it.push(Screen.Challenges) },
      Mode("Calendar", "What's out in the next four weeks.",
        "New episodes and films over the next four weeks, with reminders on the day.") { it.push(Screen.Calendar) },
      Mode("Your ${Calendar.getInstance().get(Calendar.YEAR)} Wrapped", "Your year, from Trakt.",
        "Your year from Trakt: hours watched, films and episodes, your biggest binge, top genres and busiest month.") { it.push(Screen.Wrapped) }
        .takeIf { Trakt.connected(store) && !kids },
    )),
  )
}

/**
 * The top of Discover: one mode in the spotlight (a different one each day) over real artwork, then every mode, grouped
 * by what you're in the mood to do. Rabbit Hole in the spotlight starts from the last thing you watched.
 */
@Composable
fun DiscoverFront(store: Store, nav: Nav, s: RowsState) {
  val groups = remember { modeGroups(store) }
  val all = groups.flatMap { it.modes }
  val day = remember { Calendar.getInstance().get(Calendar.DAY_OF_YEAR) }
  val featured = all[day % all.size]
  val last = remember { store.watches().maxByOrNull { it.at }?.item?.takeIf { it.backdrop != null } }
  val from = last?.takeIf { featured.name == "Rabbit Hole" }
  val art = from?.backdrop ?: s.rows.flatMap { it.second }.filter { it.backdrop != null }.let { l -> l.getOrNull(day % l.size.coerceAtLeast(1)) }?.backdrop
  Column {
    Box(Modifier.padding(horizontal = Z.Pad).fillMaxWidth().height(236.dp).clip(Z.Large).background(Z.Card)) {
      if (art != null) AsyncImage(img(art, "w1280"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      // Atmosphere, not a recommendation: the artwork stays well back behind the words.
      Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Z.Bg.copy(alpha = 0.97f), 0.45f to Z.Bg.copy(alpha = 0.85f), 1f to Z.Bg.copy(alpha = 0.45f))))
      Column(Modifier.align(Alignment.CenterStart).padding(horizontal = 32.dp).widthIn(max = 520.dp)) {
        Text(featured.name, style = MaterialTheme.typography.displaySmall)
        Text(featured.pitch, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge, color = Z.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
        ZButton(if (from != null) "Start from ${from.title}" else "Start", { if (from != null) nav.push(Screen.Rabbit(from)) else featured.go(nav) },
          Modifier.padding(top = 18.dp).focusMemory(s.focus, "spotlight", default = true), primary = true)
      }
    }
    groups.forEach { g ->
      Text(g.name, Modifier.padding(start = Z.Pad, top = 26.dp, bottom = 2.dp), style = MaterialTheme.typography.titleLarge)
      PivotRow {
        LazyRow(contentPadding = PaddingValues(start = Z.Pad, end = 400.dp, top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          items(g.modes, key = { it.name }) { m ->
            ZSurface({ m.go(nav) }, Modifier.focusMemory(s.focus, "mode/${m.name}").width(236.dp).height(108.dp), container = Z.Card) {
              Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text(m.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(m.line, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.8f), maxLines = 2, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }
      }
    }
    Text("By genre", Modifier.padding(start = Z.Pad, top = 26.dp), style = MaterialTheme.typography.headlineSmall)
  }
}
