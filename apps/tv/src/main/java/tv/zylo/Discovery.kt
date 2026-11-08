@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
// Ways to find something to watch, and to look back on it: thumbs up/down, cast pages, the Rabbit Hole, the calendar
// of new episodes and films, and Zylo Wrapped (your year from Trakt).
package tv.zylo

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// --- Thumbs up / down ---------------------------------------------------------------------------------

/**
 * Your thumbs, per profile (newest first). Up feeds "Because You Liked…" on Home; down hides the title from rows.
 * With Trakt connected each one is sent as a rating (9/10 or 3/10).
 */
object Thumbs {
  private fun all(store: Store): List<Pair<Item, Int>> =
    runCatching { JSONArray(store.str("thumbs").ifEmpty { "[]" }).objects().map { Item.from(it.getJSONObject("item")) to it.optInt("v") } }.getOrDefault(emptyList())

  fun of(store: Store, item: Item) = all(store).find { it.first.key == item.key }?.second ?: 0

  /** v: 1 up, -1 down, 0 cleared. */
  fun set(store: Store, item: Item, v: Int) {
    val list = (if (v != 0) listOf(item to v) else emptyList()) + all(store).filter { it.first.key != item.key }
    store.put("thumbs", JSONArray(list.take(400).map { JSONObject().put("item", it.first.toJson()).put("v", it.second) }).toString())
    Trakt.rate(store, item, when (v) { 1 -> 9; -1 -> 3; else -> null })
  }

  fun lastLiked(store: Store) = all(store).firstOrNull { it.second == 1 }?.first
  fun disliked(store: Store) = all(store).filter { it.second == -1 }.map { it.first.key }.toSet()
}

@Composable
fun ThumbButtons(store: Store, item: Item, modifier: Modifier = Modifier, first: Modifier = Modifier, done: () -> Unit = {}) {
  var v by remember(item) { mutableIntStateOf(Thumbs.of(store, item)) }
  fun pick(x: Int) { v = if (v == x) 0 else x; Thumbs.set(store, item, v); done() }
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    ZButton(if (v == 1) "👍  Liked" else "👍", { pick(1) }, first, primary = v == 1)
    ZButton(if (v == -1) "👎  Not for me" else "👎", { pick(-1) }, primary = v == -1)
  }
}

/** After a film ends: one question, then it goes. Focus stays inside it. */
@Composable
fun RatePrompt(store: Store, item: Item, close: () -> Unit) {
  val r = remember { FocusRequester() }
  LaunchedEffect(item) { delay(120); runCatching { r.requestFocus() } }
  androidx.activity.compose.BackHandler(onBack = close)
  Box(Modifier.fillMaxSize().background(Z.Bg.copy(alpha = 0.6f)), contentAlignment = Alignment.BottomCenter) {
    Row(Modifier.padding(bottom = 48.dp).width(760.dp).background(Z.Surface, Z.Large).padding(20.dp)
      .focusProperties { @Suppress("DEPRECATION") exit = { FocusRequester.Cancel } }.focusGroup(), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.width(72.dp).aspectRatio(2f / 3f).clip(Z.Small)) { Artwork(img(item.poster, "w185"), item.title) }
      Spacer(Modifier.width(18.dp))
      Column(Modifier.weight(1f)) {
        Text("How was ${item.title}?", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Your thumbs shape what Zylo suggests" + if (Trakt.connected(store)) ", and go to Trakt as a rating." else ".",
          Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          ThumbButtons(store, item, first = Modifier.focusRequester(r), done = close)
          ZButton("Skip", close)
        }
      }
    }
  }
}

// --- Cast & crew ----------------------------------------------------------------------------------------

/** The Cast & Crew tab: faces with their character; OK opens the person. */
@Composable
fun CastRow(d: JSONObject, focus: FocusMemory, nav: Nav) {
  val credits = d.optJSONObject("credits")
  val people = credits?.optJSONArray("cast")?.objects().orEmpty().take(20).map { it to it.optString("character") } +
    credits?.optJSONArray("crew")?.objects().orEmpty().filter { it.optString("job") in setOf("Director", "Creator", "Screenplay", "Writer") }.distinctBy { it.optInt("id") }.take(4).map { it to it.optString("job") } +
    d.optJSONArray("created_by")?.objects().orEmpty().map { it to "Creator" }
  LazyRow(Modifier.padding(top = 8.dp).bleed(Z.Pad), contentPadding = PaddingValues(horizontal = Z.Pad, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    items(people.distinctBy { it.first.optInt("id") to it.second }, key = { "${it.first.optInt("id")}/${it.second}" }) { (p, role) ->
      Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ZSurface({ nav.push(Screen.Person(p.optInt("id"), p.optString("name"))) }, Modifier.focusMemory(focus, "person/${p.optInt("id")}/$role").size(104.dp), container = Z.Card) {
          val photo = img(p.str("profile_path"), "w185")
          if (photo != null) AsyncImage(photo, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
          else Text(p.optString("name").split(" ").mapNotNull { it.firstOrNull() }.take(2).joinToString(""), Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleLarge)
        }
        Text(p.optString("name"), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(role, style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

/** Everything a person has been in (or made), most popular first, with what you've seen ticked. */
@Composable
fun PersonPage(store: Store, p: Screen.Person, nav: Nav) {
  val focus = remember { FocusMemory() }
  var info by remember { mutableStateOf<JSONObject?>(null) }
  var work by remember { mutableStateOf<List<Item>?>(null) }
  LaunchedEffect(p.id) {
    withContext(Dispatchers.IO) {
      val t = Tmdb(store.tmdb)
      runCatching {
        val o = t.get("/person/${p.id}?append_to_response=combined_credits")
        info = o
        val c = o.optJSONObject("combined_credits")
        val all = (c?.optJSONArray("cast")?.objects().orEmpty() + c?.optJSONArray("crew")?.objects().orEmpty().filter { it.optString("job") in setOf("Director", "Creator") })
          .filter { it.optInt("vote_count") > 20 && !(it.optString("media_type") == "tv" && it.optJSONArray("genre_ids")?.let { g -> (0 until g.length()).any { i -> g.optInt(i) in setOf(10763, 10767) } } == true) } // no news or talk-show guest spots
          .sortedByDescending { it.optDouble("popularity") }
        work = t.safe(all.mapNotNull { Tmdb.item(it, null) }.distinctBy { it.key })
      }.onFailure { work = emptyList() }
    }
  }
  RestoreFocus(focus, work?.isNotEmpty() == true)
  val list = work
  val seen = remember(list) { list.orEmpty().count { seenBefore(store, it) } }
  Column(Modifier.fillMaxSize().padding(top = 36.dp)) {
    Row(Modifier.padding(horizontal = Z.Pad), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(96.dp).clip(CircleShape).background(Z.Card)) {
        img(info?.str("profile_path"), "w185")?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
      }
      Spacer(Modifier.width(24.dp))
      Column(Modifier.weight(1f)) {
        Text(p.name, style = MaterialTheme.typography.displaySmall)
        MetaLine(listOfNotNull(info?.str("known_for_department"), info?.str("birthday")?.take(4)?.let { "Born $it" }, info?.str("place_of_birth"),
          list?.let { "You've seen $seen of ${it.size}" }), Modifier.padding(top = 6.dp))
        info?.str("biography")?.let { Text(it, Modifier.padding(top = 8.dp).fillMaxWidth(0.85f), style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 3, overflow = TextOverflow.Ellipsis) }
      }
    }
    Box(Modifier.padding(top = 20.dp)) {
      when {
        list == null -> Loading(null)
        list.isEmpty() -> Text("Nothing to show for ${p.name}.", Modifier.padding(Z.Pad), color = Z.Text2)
        else -> LazyVerticalGrid(GridCells.Fixed(6), contentPadding = PaddingValues(start = Z.Pad, end = Z.Pad, top = 8.dp, bottom = 48.dp),
          horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
          items(list, key = { it.key }) { item ->
            Box {
              PosterCard(store, item, Modifier.focusMemory(focus, item.key, item == list.first())) { nav.push(Screen.Title(item)) }
              if (seenBefore(store, item)) Text("✓ Seen", Modifier.align(Alignment.TopEnd).padding(6.dp).background(Z.Bg.copy(alpha = 0.85f), Z.Small).padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
    }
  }
}

// --- Calendar -------------------------------------------------------------------------------------------

/** One upcoming thing: an episode of a show you watch, or a film on your lists. */
class Upcoming(val day: String, val time: Long, val item: Item, val label: String, val season: Int? = null, val episode: Int? = null) {
  val key get() = "${item.key}:${season ?: ""}:${episode ?: ""}"
}

private fun ymd(t: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(java.util.Date(t))
private fun parseIso(s: String) = runCatching {
  SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.take(19))!!.time
}.getOrNull() ?: runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.UK).parse(s.take(10))!!.time }.getOrDefault(0L)

object EpisodeCalendar {
  /**
   * The next four weeks. With Trakt: its calendar of your shows and films. Without: the next episode of every series
   * you're watching, from TMDB. Card artwork comes from TMDB either way.
   */
  fun load(store: Store, now: Long = System.currentTimeMillis()): List<Upcoming> {
    val t = Tmdb(store.tmdb)
    val today = ymd(now)
    val got = if (Trakt.connected(store)) {
      val (shows, films) = Trakt.calendar(store, today, 28)
      val eps = shows.mapNotNull { o ->
        val id = o.optJSONObject("show")?.optJSONObject("ids")?.optInt("tmdb")?.takeIf { it > 0 } ?: return@mapNotNull null
        val e = o.optJSONObject("episode") ?: return@mapNotNull null
        val at = parseIso(o.optString("first_aired"))
        val item = runCatching { Tmdb.item(t.preview(Item("tv", id, "", null, null, "", ""))!!, "tv") }.getOrNull() ?: return@mapNotNull null
        Upcoming(ymd(at), at, item, "S${e.optInt("season")} E${e.optInt("number")}" + (e.str("title")?.let { "  ·  $it" } ?: ""), e.optInt("season"), e.optInt("number"))
      }
      val movies = films.mapNotNull { o ->
        val id = o.optJSONObject("movie")?.optJSONObject("ids")?.optInt("tmdb")?.takeIf { it > 0 } ?: return@mapNotNull null
        val at = parseIso(o.optString("released"))
        val item = runCatching { Tmdb.item(t.preview(Item("movie", id, "", null, null, "", ""))!!, "movie") }.getOrNull() ?: return@mapNotNull null
        Upcoming(ymd(at), at, item, "Film release")
      }
      eps + movies
    } else {
      val ids = (store.continueWatching().map { it.item } + store.myList).filter { it.type == "tv" }.distinctBy { it.id }
      ids.mapNotNull { item ->
        val n = runCatching { t.preview(item)?.optJSONObject("next_episode_to_air") }.getOrNull() ?: return@mapNotNull null
        val at = parseIso(n.optString("air_date"))
        Upcoming(ymd(at), at, item, "S${n.optInt("season_number")} E${n.optInt("episode_number")}" + (n.str("name")?.let { "  ·  $it" } ?: ""), n.optInt("season_number"), n.optInt("episode_number"))
      }.filter { it.day <= ymd(now + 28 * 86_400_000L) }
    }
    return got.filter { it.day >= today }.sortedBy { it.time }
  }

  // Reminders: "Remind me" on an episode or film; on the day it's out, Zylo tells you when it opens.
  private fun saved(store: Store) = runCatching { JSONArray(store.str("cal_reminders").ifEmpty { "[]" }).objects() }.getOrDefault(emptyList())
  fun reminded(store: Store, u: Upcoming) = saved(store).any { it.optString("key") == u.key }
  fun toggle(store: Store, u: Upcoming): Boolean {
    val on = !reminded(store, u)
    val rest = saved(store).filter { it.optString("key") != u.key }
    val list = if (on) rest + JSONObject().put("key", u.key).put("day", u.day).put("label", u.label).put("item", u.item.toJson())
      .put("season", u.season ?: JSONObject.NULL).put("episode", u.episode ?: JSONObject.NULL) else rest
    store.put("cal_reminders", JSONArray(list).toString())
    return on
  }
  /** Reminders whose day has come. */
  fun due(store: Store, now: Long = System.currentTimeMillis()) = saved(store).filter { it.optString("day") <= ymd(now) }
  fun dismiss(store: Store, key: String) = store.put("cal_reminders", JSONArray(saved(store).filter { it.optString("key") != key }).toString())
}

@Composable
fun CalendarPage(store: Store, nav: Nav) {
  val ctx = LocalContext.current
  val focus = remember { FocusMemory() }
  var list by remember { mutableStateOf<List<Upcoming>?>(null) }
  var tick by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) { list = withContext(Dispatchers.IO) { runCatching { EpisodeCalendar.load(store) }.getOrDefault(emptyList()) } }
  RestoreFocus(focus, list?.isNotEmpty() == true)
  val days = list.orEmpty().groupBy { it.day }
  val fmt = remember { SimpleDateFormat("EEEE d MMMM", Locale.UK) }
  val today = ymd(System.currentTimeMillis())
  Column(Modifier.fillMaxSize().padding(top = 36.dp)) {
    Text("Calendar", Modifier.padding(horizontal = Z.Pad), style = MaterialTheme.typography.displaySmall)
    Text((if (Trakt.connected(store)) "New episodes of your shows and films on your Trakt lists" else "New episodes of the series you're watching") +
      " over the next four weeks. OK opens it; hold OK for a reminder on the day.", Modifier.padding(horizontal = Z.Pad, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
    when {
      list == null -> Loading(null)
      days.isEmpty() -> Text("Nothing coming up in the next four weeks.", Modifier.padding(Z.Pad), color = Z.Text2)
      else -> LazyColumn(contentPadding = PaddingValues(start = Z.Pad, end = Z.Pad, top = 12.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEach { (day, ups) ->
          item(day) {
            val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.UK).parse(day)!! }.getOrNull()
            Text(if (day == today) "Today" else date?.let { fmt.format(it) } ?: day, Modifier.padding(top = 14.dp, bottom = 2.dp), style = MaterialTheme.typography.titleMedium, color = Z.Text2)
          }
          items(ups, key = { it.key }) { u ->
            val on = remember(tick) { EpisodeCalendar.reminded(store, u) }
            ZSurface({ nav.push(Screen.Title(u.item)) }, Modifier.focusMemory(focus, u.key).fillMaxWidth(), container = Z.Card,
              onLongClick = { val now = EpisodeCalendar.toggle(store, u); tick++; toast(ctx, if (now) "We'll remind you on the day" else "Reminder removed") }) {
              Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(Z.Small)) { Artwork(img(u.item.backdrop, "w300"), u.item.title) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                  Text(u.item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  Text(u.label, style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (u.time % 86_400_000L != 0L) Text(SimpleDateFormat("HH:mm", Locale.UK).format(java.util.Date(u.time)), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge, color = Z.Text2)
                if (on) Text("🔔", style = MaterialTheme.typography.titleMedium)
              }
            }
          }
        }
      }
    }
  }
}

/** On the day: "it's out", with Play. Shown over Home. */
@Composable
fun EpisodeAlert(store: Store, nav: Nav) {
  var due by remember { mutableStateOf(EpisodeCalendar.due(store).firstOrNull()) }
  val d = due ?: return
  val item = Item.from(d.getJSONObject("item"))
  val season = d.opt("season").let { it as? Int }
  val episode = d.opt("episode").let { it as? Int }
  fun next() { EpisodeCalendar.dismiss(store, d.optString("key")); due = EpisodeCalendar.due(store).firstOrNull() }
  val r = remember { FocusRequester() }
  LaunchedEffect(d) { delay(400); runCatching { r.requestFocus() } }
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
    Row(Modifier.padding(Z.Pad).width(560.dp).background(Z.Surface, Z.Large).padding(16.dp)
      .focusProperties { @Suppress("DEPRECATION") exit = { FocusRequester.Cancel } }.focusGroup(), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.width(130.dp).aspectRatio(16f / 9f).clip(Z.Small)) { Artwork(img(item.backdrop, "w300"), item.title) }
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f)) {
        Text("Out today", style = MaterialTheme.typography.labelMedium, color = Z.Red)
        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(d.optString("label"), style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          ZButton("Play", { next(); nav.push(Screen.Sources(item, season, episode, autoplay = true)) }, Modifier.focusRequester(r), primary = true)
          ZButton("Later", { next() })
        }
      }
    }
  }
}

// --- Zylo Wrapped ---------------------------------------------------------------------------------------

/** A year of watching, worked out from Trakt history. */
class Wrapped(
  val year: Int, val hours: Int, val films: Int, val episodes: Int, val topGenres: List<String>, val topShow: Pair<String, Int>?,
  val binge: Pair<String, Int>?, val rewatch: Pair<String, Int>?, val busiestMonth: String?, val lateNight: Int,
)

object WrappedStats {
  fun of(history: List<JSONObject>, year: Int): Wrapped {
    val tz = TimeZone.getDefault()
    var minutes = 0
    val genres = HashMap<String, Int>()
    val shows = HashMap<String, Int>()
    val films = HashMap<String, Int>()
    val days = HashMap<String, Int>()
    val months = HashMap<Int, Int>()
    var late = 0
    history.forEach { h ->
      val at = parseIso(h.optString("watched_at"))
      val cal = Calendar.getInstance(tz).apply { timeInMillis = at }
      months.merge(cal.get(Calendar.MONTH), 1, Int::plus)
      if (cal.get(Calendar.HOUR_OF_DAY) in 0..3) late++
      val movie = h.optString("type") == "movie"
      val m = if (movie) h.optJSONObject("movie") else h.optJSONObject("episode")
      minutes += m?.optInt("runtime")?.takeIf { it > 0 } ?: if (movie) 110 else 45
      val owner = if (movie) h.optJSONObject("movie") else h.optJSONObject("show")
      owner?.optJSONArray("genres")?.let { g -> (0 until g.length()).forEach { genres.merge(g.optString(it), 1, Int::plus) } }
      val title = owner?.optString("title").orEmpty()
      if (movie) films.merge(title, 1, Int::plus)
      else { shows.merge(title, 1, Int::plus); days.merge(ymd(at) + "|" + title, 1, Int::plus) }
    }
    val monthName = months.maxByOrNull { it.value }?.key?.let { java.text.DateFormatSymbols(Locale.UK).months[it] }
    return Wrapped(
      year, minutes / 60, history.count { it.optString("type") == "movie" }, history.count { it.optString("type") == "episode" },
      genres.entries.sortedByDescending { it.value }.take(3).map { it.key.replace('-', ' ').replaceFirstChar(Char::uppercase) },
      shows.maxByOrNull { it.value }?.toPair(),
      days.maxByOrNull { it.value }?.let { (k, v) -> k.substringAfter('|') to v }?.takeIf { it.second >= 3 },
      films.maxByOrNull { it.value }?.toPair()?.takeIf { it.second >= 2 },
      monthName, late,
    )
  }
}

@Composable
fun WrappedPage(store: Store, nav: Nav) {
  val year = remember { Calendar.getInstance().get(Calendar.YEAR) }
  var w by remember { mutableStateOf<Wrapped?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) {
    if (!Trakt.connected(store)) { error = "Zylo Wrapped is worked out from your Trakt history. Connect Trakt in Settings > Integrations."; return@LaunchedEffect }
    withContext(Dispatchers.IO) {
      runCatching { WrappedStats.of(Busy.run("Adding up your year") { Trakt.history(store, "$year-01-01T00:00:00.000Z", "$year-12-31T23:59:59.000Z") }, year) }
        .onSuccess { w = it }.onFailure { error = "Couldn't get your Trakt history: ${it.message}" }
    }
  }
  val wr = w ?: return Loading(error)
  // One big number per slide; Left/Right (or OK) to move through them.
  val slides = listOfNotNull(
    Triple("YOUR $year IN ZYLO", "${wr.hours} hours", "of films and TV. That's ${"%.1f".format(wr.hours / 24f)} days."),
    Triple("FILMS", "${wr.films}", "films watched" + (wr.rewatch?.let { ", and you watched ${it.first} ${it.second} times" } ?: "")),
    Triple("EPISODES", "${wr.episodes}", "episodes" + (wr.topShow?.let { ", ${it.second} of them ${it.first}" } ?: "")),
    wr.binge?.let { Triple("BIGGEST BINGE", "${it.second} episodes", "of ${it.first} in one day") },
    wr.topGenres.takeIf { it.isNotEmpty() }?.let { Triple("YOUR GENRES", it.first(), it.drop(1).joinToString("  ·  ").ifEmpty { "your favourite" }) },
    wr.busiestMonth?.let { Triple("BUSIEST MONTH", it, "when you watched the most") },
    Triple("NIGHT OWL", "${wr.lateNight}", if (wr.lateNight == 0) "things watched after midnight. Sensible." else "things watched between midnight and 4 am"),
  )
  var i by remember { mutableIntStateOf(0) }
  val r = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(80); runCatching { r.requestFocus() } }
  Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Z.Bg, Z.Red.copy(alpha = 0.35f), Z.Bg)))
    .focusRequester(r).onPreviewKeyEvent { e ->
      if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
      when (e.key) {
        Key.DirectionRight, Key.DirectionCenter, Key.Enter -> { if (i < slides.lastIndex) i++ else nav.back(); true }
        Key.DirectionLeft -> { if (i > 0) i--; true }
        else -> false
      }
    }.focusable(), contentAlignment = Alignment.CenterStart) {
    val (label, big, small) = slides[i.coerceIn(0, slides.lastIndex)]
    androidx.compose.animation.AnimatedContent(i, label = "slide") { _ ->
      Column(Modifier.padding(horizontal = Z.Pad * 2)) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Z.Red)
        Text(big, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.displayLarge)
        Text(small, Modifier.padding(top = 10.dp).fillMaxWidth(0.7f), style = MaterialTheme.typography.headlineSmall, color = Z.Text2)
      }
    }
    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      slides.indices.forEach { n -> Box(Modifier.width(if (n == i) 22.dp else 8.dp).height(4.dp).background(if (n == i) Z.Text else Z.Text.copy(alpha = 0.3f), Z.Small)) }
    }
    Text("◀ ▶", Modifier.align(Alignment.BottomEnd).padding(36.dp), style = MaterialTheme.typography.labelLarge, color = Z.Muted)
  }
}

