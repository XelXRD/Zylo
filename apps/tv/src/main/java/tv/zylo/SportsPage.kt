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
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SportsState {
  var events by mutableStateOf<List<SportEvent>?>(null)
  var loadedAt = 0L
  var error by mutableStateOf<String?>(null)
  var sport by mutableStateOf("All")
  var focused by mutableStateOf<SportEvent?>(null)
  var all by mutableStateOf<List<Channel>>(emptyList()) // every channel the provider has
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
  BackHandler { live.sports = false }
  LaunchedEffect(Unit) {
    if (s.events == null || System.currentTimeMillis() - s.loadedAt > 10 * 60_000L) {
      runCatching { withContext(Dispatchers.IO) { Sports.schedule() } }.onSuccess { s.events = it; s.loadedAt = System.currentTimeMillis(); s.error = null }
        .onFailure { s.error = it.message }
    }
  }
  LaunchedEffect(live.all) { s.all = live.all } // the channels saved on the TV (Live TV loads them once a day)
  // Where to watch the focused event: searched across every guide loaded, after a moment on it.
  LaunchedEffect(s.focused, s.all, s.links, Epg.changes) {
    val e = s.focused ?: return@LaunchedEffect
    s.options = null
    delay(3_000) // rest on a match for 3 s before searching the guides (OK searches at once)
    s.options = withContext(Dispatchers.Default) { options(store, e, s.all, live) }
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
              items(list, key = { it.id }) { e -> EventRow(e, s, Modifier.focusMemory(s.focus, "e${e.id}", default = e == shown.first())) { watchNow(store, ctx, s, live, nav) } }
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
private fun watchNow(store: Store, ctx: android.content.Context, s: SportsState, live: LiveState, nav: Nav) {
  val o = s.options ?: s.focused?.let { e -> options(store, e, s.all, live) }?.also { s.options = it }
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

/** Guide listings across every loaded guide (provider + epgshare), labelled with your channel when there is one. */
private fun options(store: Store, e: SportEvent, all: List<Channel>, live: LiveState): List<WatchOption> {
  val g = Epg.current
  val mine = HashMap<String, Channel>()
  all.forEach { c -> g.idFor(c)?.let { id -> mine.putIfAbsent(id, c) } }
  val guides = g.programmes.map { (id, list) -> Triple(g.channels[id]?.names?.firstOrNull() ?: Guide.idName(id), mine[id], list) } +
    live.guide.mapNotNull { (cid, list) -> all.find { it.id == cid }?.let { c -> Triple(c.name, c, list) } }
  // Not linked and not in your guide: the closest of your channels by name, when it's a clear match (changeable).
  // A guide listing naming the match is proof: if one of your channels is clearly that channel, it's a sure Watch.
  // A US broadcaster's name alone is only a best guess.
  return Sports.whereToWatch(e, all, guides, Sports.links(store)).map { w ->
    if (w.channel != null) w else bestChannel(w.label, all)?.let { w.copy(channel = it, guessed = w.programme == null) } ?: w
  }.distinctBy { it.channel?.id?.toString() ?: "b:${it.label}" }
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
      o == null -> Text(if (Epg.busy) "Downloading TV guides…" else "Rest here a moment to find channels showing this, or press OK.", color = Z.Text2)
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
  val ranked = remember(query, s.all) { rankChannels(query, s.all).take(60).map { it.first } }
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
  Box(Modifier.fillMaxSize().background(Z.Bg.copy(alpha = 0.75f)), contentAlignment = Alignment.CenterEnd) {
    Column(Modifier.padding(top = 84.dp, end = Z.Pad, bottom = 24.dp).width(440.dp).fillMaxHeight().background(Z.Surface, Z.Large).padding(18.dp)
      .focusGroup().focusProperties { onExit = { cancelFocusChange() } }) { // pick a channel or press Back
      Text("Link ${w.label}", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("Which of your channels is it? Closest names first; Zylo remembers your choice for every event on it.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text2)
      Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZButton("Search by Name", { ask(ctx, "Channel name", query) { if (it.isNotBlank()) query = it } }, Modifier.focusRequester(first))
        if (w.channel != null) ZButton("Unlink", { Sports.link(store, w.label, null); s.linking = null; s.links++ })
      }
      if (s.all.isEmpty()) Text("Loading your channels…", color = Z.Text2)
      else if (ranked.isEmpty()) Text("No channel names look like \"$query\". Search by another name.", style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
      LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(ranked, key = { it.id }) { c ->
          ZSurface({ Sports.link(store, w.label, c); s.linking = null; s.links++ }, Modifier.fillMaxWidth()) {
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

/** Channels most like a name first: "Sky Sports Main Event HD" puts "UK | SKY SPORTS MAIN EVENT ᶠᴴᴰ" at the top. */
fun rankChannels(name: String, all: List<Channel>, min: Double = 0.3): List<Pair<Channel, Double>> =
  all.map { it to channelLikeness(name, it.name) }.filter { it.second >= min }.sortedByDescending { it.second }

/** Sure enough to use without asking (it can still be changed): the top match, clearly ahead of the next. */
fun bestChannel(name: String, all: List<Channel>): Channel? {
  val r = rankChannels(name, all, 0.75).take(2)
  val top = r.firstOrNull() ?: return null
  return top.first.takeIf { r.size < 2 || top.second - r[1].second >= 0.05 || channelKey(top.first.name) == channelKey(name) }
}
