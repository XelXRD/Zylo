// Live TV reminders: OK on a programme that hasn't started sets one; when it starts (and Zylo is open) a card offers to
// switch to the channel. Kept per profile.
package tv.zylo

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(val channel: Channel, val title: String, val start: Long, val end: Long)

object Reminders {
  fun all(store: Store): List<Reminder> = runCatching {
    JSONArray(store.str("reminders")).objects().map { Reminder(Xtream.channelOf(it.getJSONObject("channel")), it.optString("title"), it.getLong("start"), it.optLong("end")) }
  }.getOrDefault(emptyList())

  private fun save(store: Store, list: List<Reminder>) = store.put("reminders", JSONArray(list.map {
    JSONObject().put("channel", Xtream.channelJson(it.channel)).put("title", it.title).put("start", it.start).put("end", it.end)
  }).toString())

  /** Sets a reminder, or clears it if there was one. Returns whether it's now set. */
  fun toggle(store: Store, c: Channel, p: Programme): Boolean {
    val list = all(store)
    val had = list.any { it.channel.id == c.id && it.start == p.start }
    save(store, if (had) list.filterNot { it.channel.id == c.id && it.start == p.start } else list + Reminder(c, p.title, p.start, p.end))
    return !had
  }

  /** The reminder due now (from a minute before the start until it ends), dropping any that are over. */
  fun due(store: Store, now: Long = System.currentTimeMillis()): Reminder? {
    val list = all(store)
    val live = list.filter { it.end > now || it.end == 0L && it.start + 3_600_000L > now }
    if (live.size != list.size) save(store, live)
    return live.firstOrNull { now >= it.start - 60_000L }
  }

  fun dismiss(store: Store, r: Reminder) = save(store, all(store).filterNot { it.channel.id == r.channel.id && it.start == r.start })
}

/** Shows a due reminder over whatever's on screen; Watch switches to the channel. */
@Composable
fun ReminderAlert(store: Store, ui: UiState, nav: Nav) {
  var due by remember { mutableStateOf<Reminder?>(null) }
  LaunchedEffect(Unit) { while (true) { if (due == null) due = Reminders.due(store); delay(20_000) } }
  val r = due ?: return
  val watch = remember { FocusRequester() }
  LaunchedEffect(r) { delay(80); runCatching { watch.requestFocus() } }
  fun close() { Reminders.dismiss(store, r); ui.live.reminders = Reminders.all(store); due = null }
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
    Column(Modifier.padding(top = 30.dp, end = Z.Pad).width(420.dp).background(Z.Surface, Z.Large).padding(22.dp)
      .focusGroup().focusProperties { onExit = { cancelFocusChange() } }) {
      Text(if (System.currentTimeMillis() < r.start) "STARTING NOW" else "ON NOW", style = MaterialTheme.typography.labelLarge, color = Z.Red)
      Text(r.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(r.channel.name, style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 1)
      Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ZButton("Watch", {
          close()
          val live = ui.live
          live.catchup = null
          live.playing = live.channels.indexOfFirst { it.id == r.channel.id }.takeIf { it >= 0 } ?: run { live.channels = listOf(r.channel) + live.channels; 0 }
          if (nav.top != Screen.LivePlay) nav.push(Screen.LivePlay) // already watching: the player follows live.playing
        }, Modifier.focusRequester(watch), primary = true)
        ZButton("Dismiss", { close() })
      }
    }
  }
}
