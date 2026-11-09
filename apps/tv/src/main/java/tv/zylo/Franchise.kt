// Franchise Journeys (every film and series of a universe, in release, story or recommended order) and Watch
// Challenges (curated lists you tick off; progress comes from what you've watched, Trakt included).
package tv.zylo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** Titles compared loosely: case, spaces and punctuation ignored ("Alien³" and "Thunderbolts*" keep their marks). */
fun normTitle(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == '³' || it == '*' }

class Stop(val item: Item, val original: String, val date: String) {
  fun matches(name: String) = normTitle(name).let { it == normTitle(item.title) || it == normTitle(original) }
}

/**
 * A universe. Sources: TMDB collections (by name), keywords (by exact name), and single searches ("movie:Prey|2022").
 * story / recommended: titles in order; anything not listed follows in release order.
 */
class Franchise(
  val name: String, val emoji: String, val line: String,
  val collections: List<String> = emptyList(), val keywords: List<String> = emptyList(), val searches: List<String> = emptyList(),
  val story: List<String>? = null, val recommended: List<String>? = null, val advice: String = "",
)

val FRANCHISES = listOf(
  Franchise("Marvel Cinematic Universe", "🛡️", "Avengers, assembled in order", keywords = listOf("marvel cinematic universe (mcu)", "marvel cinematic universe"),
    story = listOf("Captain America: The First Avenger", "Captain Marvel", "Iron Man", "Iron Man 2", "The Incredible Hulk", "Thor", "The Avengers", "Thor: The Dark World", "Iron Man 3",
      "Captain America: The Winter Soldier", "Guardians of the Galaxy", "Guardians of the Galaxy Vol. 2", "Avengers: Age of Ultron", "Ant-Man", "Captain America: Civil War", "Black Widow",
      "Black Panther", "Spider-Man: Homecoming", "Doctor Strange", "Thor: Ragnarok", "Ant-Man and the Wasp", "Avengers: Infinity War", "Avengers: Endgame", "Loki", "WandaVision",
      "The Falcon and the Winter Soldier", "Shang-Chi and the Legend of the Ten Rings", "Eternals", "Spider-Man: Far From Home", "Spider-Man: No Way Home", "Hawkeye", "Moon Knight",
      "Doctor Strange in the Multiverse of Madness", "Ms. Marvel", "Thor: Love and Thunder", "She-Hulk: Attorney at Law", "Black Panther: Wakanda Forever", "Secret Invasion",
      "Guardians of the Galaxy Vol. 3", "The Marvels", "Echo", "Agatha All Along", "Deadpool & Wolverine", "Captain America: Brave New World", "Thunderbolts*", "Daredevil: Born Again", "Ironheart"),
    advice = "Most fans say release order: it's how the story was built, and the surprises land as intended."),
  Franchise("Star Wars", "⭐", "A long time ago, in order", collections = listOf("Star Wars"),
    searches = listOf("movie:Rogue One: A Star Wars Story", "movie:Solo: A Star Wars Story", "tv:Star Wars: The Clone Wars|2008", "tv:Star Wars Rebels", "tv:The Mandalorian",
      "tv:The Book of Boba Fett", "tv:Obi-Wan Kenobi", "tv:Andor", "tv:Ahsoka", "tv:Star Wars: The Bad Batch", "tv:Star Wars: Skeleton Crew"),
    story = listOf("Star Wars: Episode I - The Phantom Menace", "Star Wars: Episode II - Attack of the Clones", "Star Wars: The Clone Wars", "Star Wars: Episode III - Revenge of the Sith",
      "Star Wars: The Bad Batch", "Solo: A Star Wars Story", "Obi-Wan Kenobi", "Andor", "Star Wars Rebels", "Rogue One: A Star Wars Story", "Star Wars", "The Empire Strikes Back",
      "Return of the Jedi", "The Mandalorian", "The Book of Boba Fett", "Ahsoka", "Star Wars: Skeleton Crew", "Star Wars: The Force Awakens", "Star Wars: The Last Jedi", "Star Wars: The Rise of Skywalker"),
    recommended = listOf("Star Wars", "The Empire Strikes Back", "Return of the Jedi", "Star Wars: Episode I - The Phantom Menace", "Star Wars: Episode II - Attack of the Clones",
      "Star Wars: The Clone Wars", "Star Wars: Episode III - Revenge of the Sith", "Andor", "Rogue One: A Star Wars Story", "The Mandalorian", "Star Wars: The Force Awakens",
      "Star Wars: The Last Jedi", "Star Wars: The Rise of Skywalker"),
    advice = "Originals first so the big reveal lands, then the prequels and The Clone Wars, then Andor into Rogue One, then the rest."),
  Franchise("Alien", "👽", "In space no one can hear you scream", collections = listOf("Alien"),
    searches = listOf("movie:Prometheus|2012", "movie:Alien: Covenant", "movie:Alien: Romulus", "tv:Alien: Earth"),
    story = listOf("Prometheus", "Alien: Covenant", "Alien: Earth", "Alien", "Alien: Romulus", "Aliens", "Alien³", "Alien Resurrection"),
    advice = "Release order: Alien and Aliens first. Prometheus works best once you know where it's heading."),
  Franchise("Predator", "🎯", "The hunt, across the centuries", collections = listOf("Predator"),
    searches = listOf("movie:Prey|2022", "movie:Predator: Killer of Killers", "movie:Predator: Badlands", "movie:AVP: Alien vs. Predator", "movie:Aliens vs Predator: Requiem"),
    story = listOf("Predator: Killer of Killers", "Prey", "Predator", "Predator 2", "AVP: Alien vs. Predator", "Aliens vs Predator: Requiem", "Predators", "The Predator", "Predator: Badlands"),
    recommended = listOf("Predator", "Predator 2", "Prey", "Predators", "Predator: Killer of Killers", "Predator: Badlands", "The Predator"),
    advice = "Start with the 1987 original, then jump to Prey: the two best. The rest are for completists."),
  Franchise("DC", "🦇", "Superman, Batman and the rest", keywords = listOf("dc extended universe (dceu)", "dc universe (dcu)", "dc extended universe"),
    collections = listOf("The Dark Knight"), advice = "The DC films come in separate universes: the Dark Knight trilogy stands alone; the DCEU runs from Man of Steel."),
  Franchise("Middle-earth", "💍", "One journey to rule them all", collections = listOf("The Lord of the Rings", "The Hobbit"),
    searches = listOf("tv:The Lord of the Rings: The Rings of Power", "movie:The Lord of the Rings: The War of the Rohirrim"),
    story = listOf("The Lord of the Rings: The Rings of Power", "The Hobbit: An Unexpected Journey", "The Hobbit: The Desolation of Smaug", "The Hobbit: The Battle of the Five Armies",
      "The Lord of the Rings: The War of the Rohirrim", "The Lord of the Rings: The Fellowship of the Ring", "The Lord of the Rings: The Two Towers", "The Lord of the Rings: The Return of the King"),
    advice = "The Lord of the Rings first. Always."),
  Franchise("Wizarding World", "🪄", "Hogwarts and beyond", collections = listOf("Harry Potter", "Fantastic Beasts"),
    story = listOf("Fantastic Beasts and Where to Find Them", "Fantastic Beasts: The Crimes of Grindelwald", "Fantastic Beasts: The Secrets of Dumbledore",
      "Harry Potter and the Philosopher's Stone", "Harry Potter and the Chamber of Secrets", "Harry Potter and the Prisoner of Azkaban", "Harry Potter and the Goblet of Fire",
      "Harry Potter and the Order of the Phoenix", "Harry Potter and the Half-Blood Prince", "Harry Potter and the Deathly Hallows: Part 1", "Harry Potter and the Deathly Hallows: Part 2"),
    advice = "The eight Harry Potter films first; Fantastic Beasts after, as a prequel."),
  Franchise("Jurassic", "🦖", "Life finds a way", collections = listOf("Jurassic Park", "Jurassic World")),
  Franchise("James Bond", "🍸", "Every 007 mission", collections = listOf("James Bond")),
  Franchise("Mission: Impossible", "💣", "Should you choose to accept it", collections = listOf("Mission: Impossible")),
  Franchise("Fast & Furious", "🏎️", "Family, and cars", collections = listOf("Fast & Furious")),
  Franchise("Dragon Ball", "🐉", "Saiyans, in order", collections = listOf("Dragon Ball", "Dragon Ball Z"),
    searches = listOf("tv:Dragon Ball|1986", "tv:Dragon Ball Z", "tv:Dragon Ball Super", "tv:Dragon Ball GT", "tv:Dragon Ball Daima"),
    story = listOf("Dragon Ball", "Dragon Ball Z", "Dragon Ball Daima", "Dragon Ball Super", "Dragon Ball GT"),
    recommended = listOf("Dragon Ball", "Dragon Ball Z", "Dragon Ball Super", "Dragon Ball Daima"),
    advice = "Dragon Ball, Z, then Super. GT is optional (and no longer part of the main story)."),
  Franchise("Attack on Titan", "🧱", "Beyond the walls", searches = listOf("tv:Attack on Titan", "movie:Attack on Titan: The Last Attack")),
  Franchise("Gundam", "🤖", "Mobile suits through the ages", searches = listOf("tv:Mobile Suit Gundam|1979", "tv:Mobile Suit Zeta Gundam", "tv:Mobile Suit Gundam Unicorn",
    "tv:Mobile Suit Gundam: Iron-Blooded Orphans", "tv:Mobile Suit Gundam: The Witch from Mercury", "tv:Mobile Suit Gundam SEED")),
)

object Journeys {
  private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Stop>>()

  private fun stop(o: JSONObject, type: String) = Tmdb.item(o, type)?.let {
    Stop(it, o.optString(if (type == "movie") "original_title" else "original_name"), o.optString(if (type == "movie") "release_date" else "first_air_date"))
  }

  /** Every film and series of the franchise, in release order (unreleased last, as "coming"). */
  fun load(t: Tmdb, f: Franchise): List<Stop> = cache.getOrPut(f.name) {
    val got = mutableListOf<Stop>()
    f.collections.forEach { name ->
      runCatching {
        val hit = t.get("/search/collection?query=${enc(name)}").optJSONArray("results")?.objects()?.firstOrNull { normTitle(it.optString("name")).startsWith(normTitle(name)) } ?: return@runCatching
        t.get("/collection/${hit.optInt("id")}").optJSONArray("parts")?.objects()?.forEach { o -> stop(o, "movie")?.let { got += it } }
      }
    }
    f.keywords.mapNotNull { Keywords.id(t, it) }.forEach { kw ->
      listOf("movie", "tv").forEach { type ->
        (1..3).forEach { p -> runCatching { t.get("/discover/$type?with_keywords=$kw&page=$p&sort_by=popularity.desc").optJSONArray("results")?.objects()?.forEach { o -> stop(o, type)?.let { got += it } } } }
      }
    }
    f.searches.forEach { s ->
      val type = s.substringBefore(':')
      val q = s.substringAfter(':').substringBefore('|')
      val year = s.substringAfter('|', "").ifEmpty { null }
      runCatching {
        val hits = t.get("/search/$type?query=${enc(q)}").optJSONArray("results")?.objects().orEmpty().mapNotNull { stop(it, type) }
        (hits.firstOrNull { it.matches(q) && (year == null || it.date.startsWith(year)) } ?: hits.firstOrNull { year == null || it.date.startsWith(year) })?.let { got += it }
      }
    }
    val safe = t.safe(got.map { it.item }).map { it.key }.toSet()
    got.filter { it.item.key in safe }.distinctBy { it.item.key }.sortedBy { it.date.ifEmpty { "9999" } }
  }

  /** [order] titles first, as listed; anything else after, in release order. */
  fun ordered(stops: List<Stop>, order: List<String>?): List<Stop> {
    if (order == null) return stops
    val placed = order.flatMap { name -> stops.filter { it.matches(name) } }.distinctBy { it.item.key }
    return placed + stops.filter { s -> placed.none { it.item.key == s.item.key } }
  }
}

@Composable
fun FranchisesPage(store: Store, nav: Nav) {
  val ctx = LocalContext.current
  var chosen by remember { mutableStateOf<Franchise?>(null) }
  val f = chosen
  androidx.activity.compose.BackHandler(f != null) { chosen = null }
  if (f == null) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(horizontal = Z.Pad, vertical = 36.dp)) {
      Text("Franchise Journeys", style = MaterialTheme.typography.displaySmall)
      Text("Every film and series in a universe, in release order, story order or the order we'd recommend.", Modifier.padding(top = 6.dp, bottom = 20.dp),
        style = MaterialTheme.typography.bodyLarge, color = Z.Text2)
      LazyVerticalGrid(GridCells.Fixed(5), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(FRANCHISES + Franchise("Find another…", "🔎", "Search any franchise")) { i, fr ->
          ZSurface({
            if (fr.name.startsWith("Find another")) ask(ctx, "Which franchise?", "") { q -> if (q.isNotBlank()) chosen = Franchise(q.trim(), "🔎", "", collections = listOf(q.trim()), searches = listOf("tv:${q.trim()}")) }
            else chosen = fr
          }, Modifier.then(if (i == 0) Modifier.focusRequester(first) else Modifier).height(120.dp), container = Z.Card) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.Bottom) {
              Text(fr.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Text(fr.line, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }
  } else JourneyPage(store, f, nav)
}

@Composable
private fun JourneyPage(store: Store, f: Franchise, nav: Nav) {
  var stops by remember(f) { mutableStateOf<List<Stop>?>(null) }
  LaunchedEffect(f) { stops = withContext(Dispatchers.IO) { runCatching { Journeys.load(Tmdb(store.tmdb), f) }.getOrDefault(emptyList()) } }
  val orders = listOfNotNull("Recommended", "Story order".takeIf { f.story != null }, "Release order")
  var order by remember(f) { mutableStateOf(orders.first()) }
  val list = stops?.let { s -> Journeys.ordered(s, when (order) { "Story order" -> f.story; "Recommended" -> f.recommended; else -> null }) }
  val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(java.util.Date()) }
  val focus = remember(f) { FocusMemory() }
  RestoreFocus(focus, list?.isNotEmpty() == true)
  Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
    Column(Modifier.padding(horizontal = Z.Pad)) {
      Text(f.name, style = MaterialTheme.typography.displaySmall)
      val seen = list?.count { seenBefore(store, it.item) } ?: 0
      MetaLine(listOfNotNull(list?.let { "${it.size} films and series" }, list?.let { "You've seen $seen" },
        list?.count { it.date.isEmpty() || it.date > today }?.takeIf { it > 0 }?.let { "$it coming" }), Modifier.padding(top = 4.dp))
      Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { orders.forEach { o -> Chip(o, o == order) { order = o } } }
      val note = when (order) {
        "Recommended" -> f.advice.ifEmpty { "Release order: the way it came out." } + if (f.recommended == null) "" else ""
        "Story order" -> "In story order: when each one happens in the universe."
        else -> "In the order they came out."
      }
      Text(note, Modifier.padding(top = 10.dp).fillMaxWidth(0.8f), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
    }
    when {
      list == null -> Loading(null)
      list.isEmpty() -> Text("Couldn't find anything for ${f.name}.", Modifier.padding(Z.Pad), color = Z.Text2)
      else -> LazyColumn(contentPadding = PaddingValues(start = Z.Pad, end = Z.Pad, top = 14.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(list, key = { _, s -> s.item.key }) { i, s ->
          val coming = s.date.isEmpty() || s.date > today
          val seen = seenBefore(store, s.item)
          ZSurface({ nav.push(Screen.Title(s.item)) }, Modifier.focusMemory(focus, s.item.key, i == 0).fillMaxWidth(), container = Z.Card) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
              Text("${i + 1}", Modifier.width(40.dp), style = MaterialTheme.typography.titleLarge, color = if (seen) Z.Muted else Z.Text)
              Box(Modifier.width(46.dp).aspectRatio(2f / 3f).clip(Z.Small)) { Artwork(img(s.item.poster, "w92"), s.item.title) }
              Spacer(Modifier.width(14.dp))
              Column(Modifier.weight(1f)) {
                Text(s.item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MetaLine(listOfNotNull(if (s.item.type == "tv") "Series" else "Film", s.date.take(4).ifEmpty { null }))
              }
              when {
                coming -> Text(if (s.date.isEmpty()) "Coming" else "Coming ${s.date}", Modifier.background(Z.Red, Z.Small).padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
                seen -> Text("✓ Seen", style = MaterialTheme.typography.labelLarge, color = Z.Text2)
              }
            }
          }
        }
      }
    }
  }
}

// --- Watch Challenges -----------------------------------------------------------------------------

class Challenge(val id: String, val emoji: String, val name: String, val line: String, val load: (Tmdb) -> List<Item>)

private fun personId(t: Tmdb, name: String) = t.get("/search/person?query=${enc(name)}").optJSONArray("results")?.objects()?.firstOrNull()?.optInt("id")
private fun directed(t: Tmdb, name: String) = personId(t, name)?.let { id ->
  t.get("/person/$id/movie_credits").optJSONArray("crew")?.objects().orEmpty().filter { it.optString("job") == "Director" && it.optString("release_date").isNotEmpty() && it.optInt("vote_count") > 200 }
    .sortedBy { it.optString("release_date") }.mapNotNull { Tmdb.item(it, "movie") }.distinctBy { it.key }
}.orEmpty()
private fun studio(t: Tmdb, name: String, n: Int) = t.get("/search/company?query=${enc(name)}").optJSONArray("results")?.objects()?.firstOrNull { it.optString("name").equals(name, true) }?.optInt("id")?.let { id ->
  t.list("/discover/movie?with_companies=$id&vote_count.gte=300&sort_by=vote_average.desc", "movie").take(n).sortedBy { it.year }
}.orEmpty()

val CHALLENGES = listOf(
  Challenge("scifi10", "🚀", "The 10 Greatest Sci-Fi Films", "The highest rated, by a lot of people") { it.list("/discover/movie?with_genres=878&vote_count.gte=8000&sort_by=vote_average.desc", "movie").take(10) },
  Challenge("horror7", "🔪", "7 Days of Horror", "One a night. Lights off.") { it.list("/discover/movie?with_genres=27&vote_count.gte=3000&sort_by=vote_average.desc", "movie").take(7) },
  Challenge("nolan", "🌀", "The Christopher Nolan Collection", "Every film, in order") { directed(it, "Christopher Nolan") },
  Challenge("ghibli", "🍃", "Studio Ghibli Journey", "The studio's best, oldest first") { studio(it, "Studio Ghibli", 15) },
  Challenge("90min", "⏱️", "The 90-Minute Challenge", "Ten great films, none over 90 minutes") { it.list("/discover/movie?with_runtime.gte=70&with_runtime.lte=90&vote_count.gte=2000&sort_by=vote_average.desc", "movie").take(10) },
  Challenge("kubrick", "🎥", "The Kubrick Collection", "From Paths of Glory to Eyes Wide Shut") { directed(it, "Stanley Kubrick") },
  Challenge("tarantino", "🩸", "Tarantino in Order", "All of them, as they came out") { directed(it, "Quentin Tarantino") },
  Challenge("pixar", "💡", "The Pixar Marathon", "The best of Pixar") { studio(it, "Pixar", 12) },
  Challenge("world10", "🌍", "Around the World in 10 Films", "The best film in ten languages") { t ->
    listOf("ko", "fr", "it", "ja", "es", "de", "sv", "fa", "hi", "pt").mapNotNull { l -> runCatching { t.list("/discover/movie?with_original_language=$l&vote_count.gte=1000&sort_by=vote_average.desc", "movie").firstOrNull() }.getOrNull() }
  },
  Challenge("80s", "📼", "Best of the 80s", "Ten essentials from the decade") { it.list("/discover/movie?primary_release_date.gte=1980-01-01&primary_release_date.lte=1989-12-31&vote_count.gte=3000&sort_by=vote_average.desc", "movie").take(10) },
)

object Challenges {
  /** A challenge's list is fixed the first time it's opened, so the goalposts don't move. */
  fun items(store: Store, c: Challenge): List<Item> {
    store.str("challenge:${c.id}").takeIf { it.isNotEmpty() }?.let { s -> return JSONArray(s).objects().map { Item.from(it) } }
    val got = c.load(Tmdb(store.tmdb))
    if (got.isNotEmpty()) store.put("challenge:${c.id}", JSONArray(got.map { it.toJson() }).toString())
    return got
  }
  fun cached(store: Store, c: Challenge) = store.str("challenge:${c.id}").takeIf { it.isNotEmpty() }?.let { s -> JSONArray(s).objects().map { Item.from(it) } }
}

@Composable
fun ChallengesPage(store: Store, nav: Nav) {
  var open by remember { mutableStateOf<Challenge?>(null) }
  val c = open
  androidx.activity.compose.BackHandler(c != null) { open = null }
  if (c == null) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(horizontal = Z.Pad, vertical = 36.dp)) {
      Text("Watch Challenges", style = MaterialTheme.typography.displaySmall)
      Text("Curated lists to complete. Anything you watch (here or on Trakt) ticks itself off.", Modifier.padding(top = 6.dp, bottom = 20.dp),
        style = MaterialTheme.typography.bodyLarge, color = Z.Text2)
      LazyVerticalGrid(GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(CHALLENGES) { i, ch ->
          val list = remember { Challenges.cached(store, ch) }
          val done = list?.count { seenBefore(store, it) } ?: 0
          ZSurface({ open = ch }, Modifier.then(if (i == 0) Modifier.focusRequester(first) else Modifier).height(150.dp), container = Z.Card) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
              Row { Spacer(Modifier.weight(1f)); if (list != null && list.isNotEmpty() && done == list.size) StatusChip("Complete", Z.Success) }
              Column {
                Text(ch.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (list == null) Text(ch.line, style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else {
                  ProgressBar(done.toFloat() / list.size.coerceAtLeast(1), Modifier.padding(top = 6.dp))
                  Text("$done of ${list.size}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, color = Z.Text2)
                }
              }
            }
          }
        }
      }
    }
  } else {
    var list by remember(c) { mutableStateOf<List<Item>?>(null) }
    LaunchedEffect(c) { list = withContext(Dispatchers.IO) { runCatching { Challenges.items(store, c) }.getOrDefault(emptyList()) } }
    val focus = remember(c) { FocusMemory() }
    RestoreFocus(focus, list?.isNotEmpty() == true)
    val l = list
    val done = l?.count { seenBefore(store, it) } ?: 0
    Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
      Column(Modifier.padding(horizontal = Z.Pad)) {
        Text(c.name, style = MaterialTheme.typography.displaySmall)
        if (l != null && l.isNotEmpty()) {
          Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressBar(done.toFloat() / l.size, Modifier.width(320.dp))
            Text(if (done == l.size) "  🏆 Complete!" else "  $done of ${l.size} watched", style = MaterialTheme.typography.titleSmall, color = if (done == l.size) Z.Red else Z.Text2)
          }
        }
      }
      when {
        l == null -> Loading(null)
        l.isEmpty() -> Text("Couldn't build this challenge right now.", Modifier.padding(Z.Pad), color = Z.Text2)
        else -> LazyVerticalGrid(GridCells.Fixed(6), contentPadding = PaddingValues(start = Z.Pad, end = Z.Pad, top = 18.dp, bottom = 48.dp),
          horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
          itemsIndexed(l, key = { _, it -> it.key }) { i, item ->
            Column {
              Box {
                PosterCard(store, item, Modifier.focusMemory(focus, item.key, i == 0)) { nav.push(Screen.Title(item)) }
                if (seenBefore(store, item)) Text("✓", Modifier.align(Alignment.TopEnd).padding(6.dp).background(Z.Red, Z.Small).padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelLarge)
              }
              Text("${i + 1}. ${item.title}", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }
  }
}
