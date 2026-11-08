// Live TV outside Live TV: the Home rows (On Now from your favourites, Starting Soon from reminders) and the live part
// of Search (channels, guide listings, fixtures). OK watches; a programme that hasn't started sets a reminder.
package tv.zylo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One thing on live TV: a channel (programme = what's on), a listing, or a fixture (event). */
class LiveHit(val key: String, val channel: Channel?, val title: String, val meta: String, val programme: Programme? = null, val event: SportEvent? = null)

private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.UK).format(Date(t))

object LiveSearch {
  @Volatile private var channels: List<Channel>? = null
  @Volatile private var index: ChannelIndex? = null
  @Volatile private var indexedGuide: Guide? = null

  /** Your channels (the Live TV page's, else the saved list) and the guide, loaded once. Call off the main thread. */
  private fun prepare(live: LiveState): Pair<List<Channel>, ChannelIndex> {
    val all = live.all.ifEmpty { channels ?: ChannelCache.load()?.third.orEmpty().also { channels = it } }
    Epg.load()
    val idx = index?.takeIf { it.all === all } ?: ChannelIndex(all).also { index = it }
    return all to idx
  }

  private fun nowOn(c: Channel, now: Long) = Epg.current.forChannel(c)?.firstOrNull { it.start <= now && it.end > now }

  /** Home: favourite channels with what's on now. */
  fun onNow(store: Store, now: Long = System.currentTimeMillis()): List<LiveHit> {
    Epg.load()
    return Xtream.favourites(store).map { c ->
      val p = nowOn(c, now)
      LiveHit("ch/${c.id}", c, p?.title ?: c.name, listOfNotNull(c.name.takeIf { p != null }, p?.let { "until ${hm(it.end)}" }).joinToString("  ·  ").ifEmpty { "Live" }, p)
    }
  }

  /** Home: reminders starting in the next three hours (or on now). */
  fun soon(store: Store, now: Long = System.currentTimeMillis()): List<LiveHit> =
    Reminders.all(store).filter { it.start < now + 3 * 3_600_000L && (it.end == 0L || it.end > now) }.sortedBy { it.start }.map { r ->
      LiveHit("rem/${r.channel.id}/${r.start}", r.channel, r.title, "${r.channel.name}  ·  " + if (r.start <= now) "On now" else hm(r.start),
        Programme(r.title, "", r.start, r.end))
    }

  /** Search: channels by name, listings in the next 24 hours by title, and loaded fixtures by name. */
  fun find(live: LiveState, query: String, now: Long = System.currentTimeMillis()): List<LiveHit> {
    val q = query.trim().lowercase()
    if (q.length < 2) return emptyList()
    val (all, idx) = prepare(live)
    val chans = all.asSequence().filter { q in it.name.lowercase() }.take(15).map { c ->
      val p = nowOn(c, now)
      LiveHit("ch/${c.id}", c, c.name, p?.let { "Now: ${it.title}" } ?: "Channel", p)
    }.toList()
    val guide = Epg.current
    val listings = guide.programmes.asSequence().flatMap { (id, list) ->
      list.asSequence().filter { it.end > now && it.start < now + 86_400_000L && q in it.title.lowercase() }.map { id to it }
    }.take(200).mapNotNull { (id, p) -> idx.forGuide(guide.channels[id], id)?.let { c -> c to p } }
      .sortedBy { it.second.start }.distinctBy { it.first.id to it.second.start }.take(20).map { (c, p) ->
        LiveHit("p/${c.id}/${p.start}", c, p.title, "${c.name}  ·  " + if (p.start <= now) "On now" else hm(p.start), p)
      }.toList()
    val fixtures = live.sched.events.orEmpty().filter { q in it.name.lowercase() || q in it.league.lowercase() }.take(10).map { e ->
      LiveHit("e/${e.id}", null, e.name, "${e.league}  ·  ${if (e.allDay) "All day" else hm(e.start)}", event = e)
    }
    return chans + listings + fixtures
  }

  /** Builds the channel index once so the first search is quick. */
  fun warm(live: LiveState) { runCatching { prepare(live) } }
}

fun watchChannel(c: Channel, live: LiveState, nav: Nav) {
  live.catchup = null
  live.playing = live.channels.indexOfFirst { it.id == c.id }.takeIf { it >= 0 } ?: run { live.channels = listOf(c) + live.channels; 0 }
  nav.push(Screen.LivePlay)
}

/** OK on a live result: watch it; a later programme sets (or clears) a reminder; a fixture opens Sports. */
fun openHit(ctx: android.content.Context, store: Store, h: LiveHit, nav: Nav) {
  val live = nav.ui.live
  val c = h.channel
  val p = h.programme
  when {
    h.event != null -> { nav.back(); live.sports = true; nav.ui.section = Section.Live }
    c == null -> Unit
    p != null && p.start > System.currentTimeMillis() -> {
      val on = Reminders.toggle(store, c, p)
      live.reminders = Reminders.all(store)
      toast(ctx, if (on) "Reminder set for ${p.title} at ${hm(p.start)}" else "Reminder removed")
    }
    else -> watchChannel(c, live, nav)
  }
}

@Composable
fun LiveHitRow(store: Store, title: String, hits: List<LiveHit>, focus: FocusMemory, nav: Nav, modifier: Modifier = Modifier) = Column(modifier) {
  val ctx = LocalContext.current
  Text(title, Modifier.padding(start = Z.Pad, top = 10.dp), style = MaterialTheme.typography.titleLarge)
  PivotRow {
    LazyRow(contentPadding = PaddingValues(start = Z.Pad, end = 200.dp, top = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      items(hits, key = { it.key }) { h ->
        ZSurface({ openHit(ctx, store, h, nav) }, Modifier.focusMemory(focus, "live/${h.key}").width(300.dp), container = Z.Card) {
          Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
              val logo = h.channel?.logo
              if (logo != null) AsyncImage(logo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
              else Text(if (h.event != null) "⚽" else "TV", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text(h.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
              MetaLine(listOf(h.meta))
              h.programme?.takeIf { it.end > it.start }?.let { p ->
                val now = System.currentTimeMillis()
                if (p.start <= now) ProgressBar(((now - p.start).toFloat() / (p.end - p.start)).coerceIn(0f, 1f), Modifier.padding(top = 6.dp))
              }
            }
          }
        }
      }
    }
  }
}

/** Home: On Live TV Now (favourites) and Starting Soon (reminders). Nothing shows without Live TV set up. */
@Composable
fun LiveHome(store: Store, focus: FocusMemory, nav: Nav) {
  if (store.profile.kids != null || Xtream.from(store) == null) return
  var now by remember { mutableStateOf(emptyList<LiveHit>()) }
  var soon by remember { mutableStateOf(emptyList<LiveHit>()) }
  LaunchedEffect(Epg.changes) {
    withContext(Dispatchers.IO) { runCatching { now = LiveSearch.onNow(store); soon = LiveSearch.soon(store) } }
  }
  if (soon.isNotEmpty()) LiveHitRow(store, "Starting Soon", soon, focus, nav)
  if (now.isNotEmpty()) LiveHitRow(store, "On Live TV Now", now, focus, nav)
}
