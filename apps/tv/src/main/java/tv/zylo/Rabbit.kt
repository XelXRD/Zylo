// The Rabbit Hole: an entertainment web. Every stop is a film or series, a person (actor, director, composer), a genre,
// a franchise or a theme, and each one branches to more. Back retraces the trail.
package tv.zylo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed class Node(val key: String, val label: String) {
  class Title(val item: Item) : Node("t/${item.key}", item.title)
  class Person(val id: Int, val name: String, val music: Boolean = false) : Node("p/$id/$music", name)
  class Genre(val id: Int, val name: String) : Node("g/$id", name)
  class Franchise(val id: Int, val name: String) : Node("c/$id", name)
  class Theme(val id: Int, val name: String) : Node("k/$id", name)
}

/** A way out of the current stop: "Director: Denis Villeneuve", "Theme: desert planet". */
class Branch(val kind: String, val node: Node, val image: String? = null)

/** One stop on the web. */
class Hub(val kind: String, val title: String, val about: String, val backdrop: String?, val image: String?, val branches: List<Branch>, val titles: List<Item>, val titlesLabel: String)

object Web {
  private fun genreNames(t: Tmdb) = (t.genres("movie") + t.genres("tv")).toMap()

  /** The genres that turn up most in a set of raw results (minus [except]). */
  private fun commonGenres(t: Tmdb, raw: List<JSONObject>, except: Int? = null, n: Int = 4): List<Branch> {
    val names = genreNames(t)
    return raw.flatMap { genreIds(it) }.filter { it != except }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(n)
      .mapNotNull { (id, _) -> names[id]?.let { Branch("Genre", Node.Genre(id, it)) } }
  }

  fun hub(t: Tmdb, node: Node): Hub = when (node) {
    is Node.Title -> {
      val i = node.item
      val d = t.details(i.type, i.id)
      val credits = d.optJSONObject("credits")
      val crew = credits?.optJSONArray("crew")?.objects().orEmpty()
      fun person(o: JSONObject, kind: String, music: Boolean = false) = Branch(kind, Node.Person(o.optInt("id"), o.optString("name"), music), img(o.str("profile_path"), "w185"))
      val makers = (crew.filter { it.optString("job") == "Director" } + d.optJSONArray("created_by")?.objects().orEmpty()).distinctBy { it.optInt("id") }.take(2)
        .map { person(it, if (i.type == "tv") "Created by" else "Directed by") }
      val stars = credits?.optJSONArray("cast")?.objects().orEmpty().take(5).map { person(it, "Starring") }
      val music = crew.filter { it.optString("job") in setOf("Original Music Composer", "Music") }.distinctBy { it.optInt("id") }.take(1).map { person(it, "Scored by", music = true) }
      val franchise = d.optJSONObject("belongs_to_collection")?.let { listOf(Branch("Franchise", Node.Franchise(it.optInt("id"), it.optString("name").removeSuffix(" Collection")), img(it.str("poster_path"), "w185"))) }.orEmpty()
      val genres = d.optJSONArray("genres")?.objects().orEmpty().map { Branch("Genre", Node.Genre(it.optInt("id"), it.optString("name"))) }
      val kw = d.optJSONObject("keywords")?.let { it.optJSONArray("keywords") ?: it.optJSONArray("results") }?.objects().orEmpty().take(6)
        .map { Branch("Theme", Node.Theme(it.optInt("id"), it.optString("name"))) }
      val recs = d.optJSONObject("recommendations")?.optJSONArray("results")?.objects().orEmpty().mapNotNull { Tmdb.item(it, null) }
      Hub(if (i.type == "tv") "Series" else "Film", i.title, d.optString("overview"), i.backdrop, img(i.poster, "w342"),
        makers + stars + music + franchise + genres + kw, t.safe(recs), "If you like this")
    }
    is Node.Person -> {
      val p = t.get("/person/${node.id}?append_to_response=combined_credits")
      val c = p.optJSONObject("combined_credits")
      val raw = (if (node.music) c?.optJSONArray("crew")?.objects().orEmpty().filter { it.optString("job") in setOf("Original Music Composer", "Music") }
      else c?.optJSONArray("cast")?.objects().orEmpty() + c?.optJSONArray("crew")?.objects().orEmpty().filter { it.optString("job") == "Director" })
        .filter { it.optInt("vote_count") > 40 && genreIds(it).none { g -> g in setOf(10763, 10767) } }.sortedByDescending { it.optDouble("popularity") }
      Hub(if (node.music) "Composer" else p.optString("known_for_department").ifEmpty { "Person" }, node.name, p.optString("biography"), null, img(p.str("profile_path"), "w342"),
        commonGenres(t, raw), t.safe(raw.mapNotNull { Tmdb.item(it, null) }.distinctBy { it.key }.take(30)), if (node.music) "Scored by ${node.name}" else "Known for")
    }
    is Node.Genre -> {
      val raw = t.get("/discover/movie?with_genres=${node.id}&vote_count.gte=2500&sort_by=vote_average.desc&page=${(1..3).random()}").optJSONArray("results")?.objects().orEmpty() +
        t.get("/discover/tv?with_genres=${node.id}&vote_count.gte=500&sort_by=vote_average.desc").optJSONArray("results")?.objects().orEmpty().filter { !Tmdb.animeSeries(it, "tv") }
      Hub("Genre", node.name, "The best of ${node.name.lowercase()}, and the genres it mixes with most.", raw.firstOrNull()?.str("backdrop_path"), null,
        commonGenres(t, raw, except = node.id), t.safe(raw.mapNotNull { Tmdb.item(it, if (it.has("title")) "movie" else "tv") }.shuffled()), "Essential ${node.name.lowercase()}")
    }
    is Node.Franchise -> {
      val col = t.get("/collection/${node.id}")
      val parts = col.optJSONArray("parts")?.objects().orEmpty().sortedBy { it.optString("release_date").ifEmpty { "9999" } }
      Hub("Franchise", node.name, col.optString("overview"), col.str("backdrop_path"), img(col.str("poster_path"), "w342"),
        commonGenres(t, parts), t.safe(parts.mapNotNull { Tmdb.item(it, "movie") }), "Every film, in release order")
    }
    is Node.Theme -> {
      val raw = t.get("/discover/movie?with_keywords=${node.id}&vote_count.gte=100&sort_by=popularity.desc").optJSONArray("results")?.objects().orEmpty().map { it.put("media_type", "movie") } +
        t.get("/discover/tv?with_keywords=${node.id}&vote_count.gte=30&sort_by=popularity.desc").optJSONArray("results")?.objects().orEmpty().filter { !Tmdb.animeSeries(it, "tv") }.map { it.put("media_type", "tv") }
      Hub("Theme", node.name.replaceFirstChar(Char::uppercase), "Everything that shares this theme.", raw.firstOrNull()?.str("backdrop_path"), null,
        commonGenres(t, raw), t.safe(raw.mapNotNull { Tmdb.item(it, null) }), "Also about ${node.name}")
    }
  }
}

/** Starts the Rabbit Hole somewhere you'll recognise: your latest thumbs up or watch, else something trending. */
@Composable
fun RabbitStartPage(store: Store, nav: Nav) {
  LaunchedEffect(Unit) {
    val start = Thumbs.lastLiked(store) ?: store.watches().maxByOrNull { it.at }?.item
      ?: withContext(Dispatchers.IO) { runCatching { Tmdb(store.tmdb).list("/trending/movie/week", "movie").random() }.getOrNull() }
    if (start != null) nav.replace(Screen.Rabbit(start)) else nav.back()
  }
  Loading(null)
}

@Composable
fun RabbitHolePage(store: Store, start: Item, nav: Nav) {
  val trail = remember { mutableStateListOf<Node>(Node.Title(start)) }
  val here = trail.last()
  var hub by remember { mutableStateOf<Hub?>(null) }
  var failed by remember { mutableStateOf(false) }
  val t = remember { Tmdb(store.tmdb) }
  LaunchedEffect(here.key) {
    hub = null; failed = false
    hub = withContext(Dispatchers.IO) { runCatching { Web.hub(t, here) }.getOrNull() }
    failed = hub == null
  }
  androidx.activity.compose.BackHandler(trail.size > 1) { trail.removeAt(trail.lastIndex) }
  val first = remember { FocusRequester() }
  LaunchedEffect(hub) { if (hub != null) { delay(80); runCatching { first.requestFocus() } } }
  fun go(n: Node) { if (trail.lastOrNull()?.key != n.key) trail += n }
  val h = hub
  Box(Modifier.fillMaxSize()) {
    h?.backdrop?.let { AsyncImage(img(it, "w1280"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    Box(Modifier.fillMaxSize().background(Z.Bg.copy(alpha = 0.84f)))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 30.dp, bottom = 50.dp)) {
      item("trail") {
        Column(Modifier.padding(horizontal = Z.Pad)) {
          Text("RABBIT HOLE  ·  ${trail.size} deep", style = MaterialTheme.typography.labelLarge, color = Z.Red)
          Text(trail.takeLast(7).joinToString("  ›  ") { it.label }.let { if (trail.size > 7) "…  ›  $it" else it }, Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
      }
      when {
        failed -> item("err") { Text("This branch is a dead end. Press Back.", Modifier.padding(Z.Pad), color = Z.Text2) }
        h == null -> item("load") { Text("Digging…", Modifier.padding(Z.Pad), color = Z.Text2) }
        else -> {
          item("head") {
            Row(Modifier.padding(start = Z.Pad, end = Z.Pad, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
              h.image?.let { im ->
                Box(Modifier.width(110.dp).then(if (here is Node.Person) Modifier.aspectRatio(1f).clip(CircleShape) else Modifier.aspectRatio(2f / 3f).clip(Z.Medium))) {
                  AsyncImage(im, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width(24.dp))
              }
              Column(Modifier.weight(1f)) {
                Text(h.kind.uppercase(), style = MaterialTheme.typography.labelMedium, color = Z.Text2)
                Text(h.title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (h.about.isNotBlank()) Text(h.about, Modifier.padding(top = 6.dp).fillMaxWidth(0.85f), style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  when (here) {
                    is Node.Title -> ZButton("Open ${h.kind}", { nav.push(Screen.Title(here.item)) }, Modifier.focusRequester(first), primary = true)
                    is Node.Person -> ZButton("Full Profile", { nav.push(Screen.Person(here.id, here.name)) }, Modifier.focusRequester(first))
                    else -> Unit
                  }
                }
              }
            }
          }
          if (h.branches.isNotEmpty()) item("branches") {
            Column(Modifier.padding(top = 22.dp)) {
              Text("Branches", Modifier.padding(start = Z.Pad), style = MaterialTheme.typography.titleLarge)
              LazyRow(contentPadding = PaddingValues(horizontal = Z.Pad, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(h.branches, key = { _, b -> b.kind + b.node.key }) { i, b ->
                  ZSurface({ go(b.node) }, if (i == 0 && here !is Node.Title && here !is Node.Person) Modifier.focusRequester(first) else Modifier, container = Z.Card) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                      if (b.node is Node.Person) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(Z.Surface)) { b.image?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
                        Spacer(Modifier.width(10.dp))
                      }
                      Column {
                        Text(b.kind, style = MaterialTheme.typography.labelSmall, color = Z.Text2)
                        Text(b.node.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                      }
                    }
                  }
                }
              }
            }
          }
          if (h.titles.isNotEmpty()) item("titles") {
            Column(Modifier.padding(top = 10.dp)) {
              Text(h.titlesLabel, Modifier.padding(start = Z.Pad), style = MaterialTheme.typography.titleLarge)
              PivotRow {
                LazyRow(contentPadding = PaddingValues(start = Z.Pad, end = 400.dp, top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                  items(h.titles.filter { n -> trail.none { it.key == "t/${n.key}" } }, key = { it.key }) { item ->
                    Column(Modifier.width(124.dp)) {
                      PosterCard(store, item) { go(Node.Title(item)) }
                      Text(item.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
}
