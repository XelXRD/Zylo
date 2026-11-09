// Live TV > Sports: today's and the next two days' fixtures, filtered by sport, with where to watch each one.
package tv.zylo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where-to-watch results kept per fixture until it's over (start + 4 hours; tournaments: end of the day), so a match
 * is searched once, not every time it's focused. Saved on the TV (global key "sports_found").
 */
object SeenOn {
  private var memo: org.json.JSONObject? = null
  private fun all(store: Store) = memo ?: runCatching { org.json.JSONObject(store.str("sports_found")) }.getOrElse { org.json.JSONObject() }.also { memo = it }
  private fun until(e: SportEvent) = if (e.allDay) e.start - e.start % 86_400_000L + 2 * 86_400_000L else e.start + 4 * 3_600_000L

  fun get(store: Store, e: SportEvent): List<WatchOption>? {
    val o = all(store).optJSONObject(e.id) ?: return null
    if (minOf(o.optLong("until"), until(e)) < System.currentTimeMillis()) return null // over (or moved)
    return o.optJSONArray("options")?.objects()?.map { w ->
      WatchOption(w.optString("label"), w.optJSONObject("p")?.let { Programme(it.optString("title"), "", it.optLong("start"), it.optLong("end")) },
        w.optJSONObject("channel")?.let(Xtream::channelOf), w.optString("from"), w.optBoolean("guessed"))
    }
  }

  fun put(store: Store, e: SportEvent, list: List<WatchOption>) {
    val now = System.currentTimeMillis()
    val o = all(store)
    o.keys().asSequence().toList().forEach { if (o.optJSONObject(it)?.optLong("until") ?: 0 < now) o.remove(it) } // lapsed
    o.put(e.id, org.json.JSONObject().put("until", until(e)).put("options", org.json.JSONArray(list.map { w ->
      org.json.JSONObject().put("label", w.label).put("from", w.from).put("guessed", w.guessed)
        .put("p", w.programme?.let { org.json.JSONObject().put("title", it.title).put("start", it.start).put("end", it.end) })
        .put("channel", w.channel?.let(Xtream::channelJson))
    })))
    store.put("sports_found", o.toString())
  }

  fun forget(store: Store, e: SportEvent) { all(store).remove(e.id); store.put("sports_found", all(store).toString()) }
}

class SportsState {
  var events by mutableStateOf<List<SportEvent>?>(null)
  var loadedAt = 0L
  var error by mutableStateOf<String?>(null)
  var sport by mutableStateOf("All")
  var focused by mutableStateOf<SportEvent?>(null)
  var all by mutableStateOf<List<Channel>>(emptyList()) // every channel the provider has
  var index by mutableStateOf<ChannelIndex?>(null) // built once from [all], off the main thread
  var options by mutableStateOf<List<WatchOption>?>(null)
  var linking by mutableStateOf<WatchOption?>(null)
  var links by mutableIntStateOf(0) // bumps when a channel is linked, to search again
  val focus = FocusMemory()
}

private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.UK).format(Date(t))
private fun dayLabel(t: Long, now: Long): String {
  val f = SimpleDateFormat("yyyyMMdd", Locale.UK)
  return when (f.format(Date(t))) {
    f.format(Date(now)) -> "Today"
    f.format(Date(now + 86_400_000L)) -> "Tomorrow"
    else -> SimpleDateFormat("EEEE d MMMM", Locale.UK).format(Date(t))
  }
}

@Composable
fun SportsPage(store: Store, x: Xtream, live: LiveState, nav: Nav) {
  val s = live.sched
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  BackHandler { live.sports = false }
  LaunchedEffect(Unit) {
    if (s.events == null || System.currentTimeMillis() - s.loadedAt > 10 * 60_000L) {
      runCatching { withContext(Dispatchers.IO) { Sports.schedule() } }.onSuccess { s.events = it; s.loadedAt = System.currentTimeMillis(); s.error = null }
        .onFailure { s.error = plainError(it, "ESPN") }
    }
  }
  LaunchedEffect(live.all) { // the channels saved on the TV (Live TV loads them once a day), indexed once
    s.all = live.all
    if (s.index?.all !== live.all) s.index = withContext(Dispatchers.Default) { ChannelIndex(live.all) }
  }
  // Where to watch the focused event: searched across every guide loaded, after a moment on it.
  LaunchedEffect(s.focused, s.index, s.links, Epg.changes) {
    val e = s.focused ?: return@LaunchedEffect
    // Found before (and the match isn't over): show it at once, no search. An empty result is looked for again.
    SeenOn.get(store, e)?.takeIf { it.isNotEmpty() }?.let { s.options = it; return@LaunchedEffect }
    val idx = s.index ?: return@LaunchedEffect
    s.options = null
    delay(3_000) // rest on a match for 3 s before searching the guides (OK searches at once)
    val rows = live.guide.toMap() // a copy: the guide keeps loading rows while this runs
    s.options = withContext(Dispatchers.Default) { runCatching { options(store, e, idx, Epg.current, rows) }.getOrDefault(emptyList()) }
      .also { SeenOn.put(store, e, it) }
  }
  val now = remember { System.currentTimeMillis() }
  val shown = s.events.orEmpty().filter { (s.sport == "All" || it.sport == s.sport) && (it.start > now - 4 * 3_600_000L || it.live) }
  RestoreFocus(s.focus, shown.isNotEmpty())

  Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(top = 84.dp)) {
      Row(Modifier.padding(horizontal = Z.Pad), verticalAlignment = Alignment.CenterVertically) {
        Text("Sports", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(16.dp))
        Text("OK on a match to watch it · fixtures from ESPN, channels from your TV guides", style = MaterialTheme.typography.bodySmall, color = Z.Muted)
        Spacer(Modifier.weight(1f))
        ZButton("TV Guide", { live.sports = false })
      }
      LazyRow(contentPadding = PaddingValues(horizontal = Z.Pad, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Sports.SPORTS) { sp -> Chip(sp, s.sport == sp) { s.sport = sp } }
      }
      when {
        s.events == null && s.error == null -> Loading("Loading fixtures…")
        shown.isEmpty() -> Text(s.error ?: "No ${if (s.sport == "All") "" else s.sport.lowercase() + " "}fixtures this week.", Modifier.padding(start = Z.Pad, top = 16.dp), color = Z.Text2)
        else -> Row(Modifier.fillMaxSize()) {
          // Fixtures, by day.
          LazyColumn(Modifier.weight(0.56f).fillMaxHeight(), contentPadding = PaddingValues(start = Z.Pad, end = 12.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.groupBy { dayLabel(it.start, now) }.forEach { (day, list) ->
              item(key = "d$day") { Text(day, Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall, color = Z.Text2) }
              items(list, key = { it.id }) { e -> EventRow(e, s, Modifier.focusMemory(s.focus, "e${e.id}", default = e == shown.first())) { scope.launch { watchNow(store, ctx, s, live, nav) } } }
            }
          }
          WhereToWatch(store, s, live, nav, Modifier.weight(0.44f).fillMaxHeight().padding(end = Z.Pad))
        }
      }
    }
    s.linking?.let { o -> ChannelPicker(store, o, s) }
  }
}

/** OK on a fixture: straight to the channel Zylo is sure shows it (else the best guess); otherwise say how to pick one. */
private suspend fun watchNow(store: Store, ctx: android.content.Context, s: SportsState, live: LiveState, nav: Nav) {
  val idx = s.index
  val e = s.focused
  if (s.options == null && e != null) s.options = SeenOn.get(store, e)
  if (s.options == null && idx != null && e != null) {
    val rows = live.guide.toMap()
    s.options = withContext(Dispatchers.Default) { runCatching { options(store, e, idx, Epg.current, rows) }.getOrDefault(emptyList()) }
      .also { SeenOn.put(store, e, it) }
  }
  val o = s.options
  val pick = o?.firstOrNull { it.channel != null && !it.guessed } ?: o?.firstOrNull { it.channel != null }
  when {
    pick != null -> play(pick.channel!!, live, nav)
    o == null -> toast(ctx, "Still looking for channels showing this…")
    else -> toast(ctx, "No channel found for this yet. Pick one under Where to watch.")
  }
}

private fun play(c: Channel, live: LiveState, nav: Nav) {
  live.playing = live.channels.indexOfFirst { it.id == c.id }.takeIf { it >= 0 } ?: run { live.channels = listOf(c) + live.channels; 0 }
  nav.push(Screen.LivePlay)
}

@Composable
private fun EventRow(e: SportEvent, s: SportsState, modifier: Modifier, onClick: () -> Unit) =
  ZSurface(onClick, modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) s.focused = e }, container = Z.Card) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.width(64.dp)) {
        Text(when { e.live -> "LIVE"; e.over -> "FT"; e.allDay && e.start < System.currentTimeMillis() -> "Today"; else -> hm(e.start) }, style = MaterialTheme.typography.titleSmall, color = if (e.live) Z.Red else Z.Text)
        if (e.live) Text(e.detail, style = MaterialTheme.typography.labelSmall, color = Z.Text2, maxLines = 1)
      }
      Column(Modifier.weight(1f)) {
        Text(e.league, style = MaterialTheme.typography.labelSmall, color = Z.Muted, maxLines = 1)
        if (e.teams.size == 2) e.teams.forEach { t ->
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp)) { t.logo?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } }
            Spacer(Modifier.width(8.dp))
            Text(t.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (e.live || e.over) Text(t.score ?: "", style = MaterialTheme.typography.titleSmall)
          }
        } else Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
  }

/**
 * Where to watch [e]: listings around kick-off that name it, in the daily guide and the guide rows already loaded,
 * each tied to one of your channels (a link you made, the guide's channel, or a clear name match); then ESPN's
 * broadcasters (best guesses). Only listings near the time are looked at, and channels come from [idx].
 */
fun options(store: Store, e: SportEvent, idx: ChannelIndex, guide: Guide, rows: Map<Int, List<Programme>>): List<WatchOption> {
  val links = Sports.links(store)
  fun linked(label: String) = links[channelKey(label)]?.let(idx::id)
  val found = ArrayList<WatchOption>()
  for ((gid, list) in guide.programmes) {
    val p = list.firstOrNull { Sports.matches(e, it) } ?: continue
    val g = guide.channels[gid]
    val label = g?.names?.firstOrNull() ?: Guide.idName(gid)
    found += WatchOption(label, p, linked(label) ?: idx.forGuide(g, gid) ?: idx.best(label), "TV guide")
  }
  for ((cid, list) in rows) {
    val p = list.firstOrNull { Sports.matches(e, it) } ?: continue
    idx.id(cid)?.let { c -> found += WatchOption(c.name, p, c, "TV guide") }
  }
  for (b in e.broadcasters) found += (linked(b)?.let { WatchOption(b, null, it, "US broadcaster") }
    ?: WatchOption(b, null, idx.best(b), "US broadcaster", guessed = true))
  return found.map { if (it.channel == null) it.copy(guessed = false) else it }
    .distinctBy { it.channel?.id?.toString() ?: "b:${it.label}" }
    .sortedBy { when { it.channel != null && !it.guessed -> 0; it.channel != null -> 1; else -> 2 } }
}

@Composable
private fun WhereToWatch(store: Store, s: SportsState, live: LiveState, nav: Nav, modifier: Modifier) {
  val ctx = LocalContext.current
  val e = s.focused ?: return Spacer(modifier)
  Column(modifier.padding(top = 4.dp).background(Z.Surface, Z.Large).padding(18.dp)) {
    Text(e.league.uppercase(), style = MaterialTheme.typography.labelMedium, color = Z.Red)
    Text(e.name, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text("${dayLabel(e.start, System.currentTimeMillis())} · ${hm(e.start)}" + if (e.live) " · Live ${e.detail}" else if (e.over) " · Full time" else "",
      Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
    Row(Modifier.padding(top = 16.dp, bottom = 6.dp)) {
      Text("WHERE TO WATCH", style = MaterialTheme.typography.labelMedium, color = Z.Muted)
      if (Epg.busy) Text("  ·  downloading TV guides, more may appear", style = MaterialTheme.typography.labelMedium, color = Z.Info)
    }
    val o = s.options
    when {
      o == null -> Text(when { s.index == null -> "Loading your channels…"; Epg.busy -> "Downloading TV guides…"; else -> "Finding channels showing this… (or press OK)" }, color = Z.Text2)
      o.isEmpty() -> Text(if (Epg.current.size == 0) "Turn on the better TV guide in Settings > Live TV so Zylo can find which channels show this."
        else "No channel in your TV guides lists this yet. Listings often appear a day or two before.", style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
      else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(o, key = { (it.channel?.id?.toString() ?: "") + it.label + it.from }) { w ->
          ZSurface({
            val c = w.channel
            if (c == null) s.linking = w else play(c, live, nav)
          }, Modifier.fillMaxWidth(), container = Z.Card, onLongClick = { s.linking = w }) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(w.channel?.name ?: w.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(w.programme?.let { "${hm(it.start)} ${it.title}" }, when {
                  w.channel == null -> w.from + " · pick which of your channels it is"
                  w.guessed -> "best match for ${w.label} · hold OK to change"
                  w.label != w.channel.name -> "listed as ${w.label}"
                  else -> w.from })
                  .joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = Z.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
              Text(if (w.channel != null) "Watch" else "Link", style = MaterialTheme.typography.labelLarge, color = if (w.channel != null) Z.Text else Z.Info)
            }
          }
        }
        item { Text("Hold OK on any of these to choose a different channel.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = Z.Muted) }
      }
    }
  }
}

/** Pick which of your channels is this broadcaster or guide channel; remembered for next time. */
@Composable
private fun ChannelPicker(store: Store, w: WatchOption, s: SportsState) {
  val ctx = LocalContext.current
  BackHandler { s.linking = null }
  var query by remember { mutableStateOf(w.label) }
  var ranked by remember { mutableStateOf<List<Channel>>(emptyList()) }
  LaunchedEffect(query, s.index) { ranked = withContext(Dispatchers.Default) { s.index?.rank(query)?.take(60)?.map { it.first }.orEmpty() } }
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
  Box(Modifier.fillMaxSize().background(Z.Bg.copy(alpha = 0.75f)), contentAlignment = Alignment.CenterEnd) {
    Column(Modifier.padding(top = 84.dp, end = Z.Pad, bottom = 24.dp).width(440.dp).fillMaxHeight().background(Z.Surface, Z.Large).padding(18.dp)
      .focusGroup().focusProperties { onExit = { cancelFocusChange() } }) { // pick a channel or press Back
      Text("Link ${w.label}", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("Which of your channels is it? Closest names first; Zylo remembers your choice for every event on it.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text2)
      Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZButton("Search by Name", { ask(ctx, "Channel name", query) { if (it.isNotBlank()) query = it } }, Modifier.focusRequester(first))
        if (w.channel != null) ZButton("Unlink", { Sports.link(store, w.label, null); s.focused?.let { SeenOn.forget(store, it) }; s.linking = null; s.links++ })
      }
      if (s.index == null) Text("Loading your channels…", color = Z.Text2)
      else if (ranked.isEmpty()) Text("No channel names look like \"$query\". Search by another name.", style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
      LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(ranked, key = { it.id }) { c ->
          ZSurface({ Sports.link(store, w.label, c); s.focused?.let { SeenOn.forget(store, it) }; s.linking = null; s.links++ }, Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Box(Modifier.size(34.dp, 24.dp)) { c.logo?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } }
              Spacer(Modifier.width(10.dp))
              Text(c.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }
  }
}

/** Channels most like a name first (tests and one-offs; screens use a [ChannelIndex]). */
fun rankChannels(name: String, all: List<Channel>, min: Double = 0.3) = ChannelIndex(all).rank(name, min)
fun bestChannel(name: String, all: List<Channel>) = ChannelIndex(all).best(name)
