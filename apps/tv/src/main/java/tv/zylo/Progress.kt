// Where you're up to in each series, from everything Zylo knows: what you played here and your Trakt watch history.
// Feeds the series page (opens on your next episode), Play buttons, and the Up Next entries in Continue Watching.
package tv.zylo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where Play should start in a series. resume = an episode you stopped partway through. */
data class Target(val season: Int, val episode: Int, val resume: Boolean)

/** An episode to offer next in Continue Watching. isNew = it aired after you last watched the show. */
class NextUp(val item: Item, val season: Int, val episode: Int, val name: String, val isNew: Boolean, val at: Long)

/** Highest episode watched (specials excluded), from Zylo and Trakt. */
fun lastWatched(store: Store, item: Item): Pair<Int, Int>? {
  val prefix = "tv:${item.id}:"
  val here = store.watches().filter { it.item.key == item.key && it.finished && (it.season ?: 0) > 0 }.map { it.season!! to (it.episode ?: 0) }
  val trakt = store.traktWatched().filter { it.startsWith(prefix) }.mapNotNull { k ->
    k.removePrefix(prefix).split(':').let { p -> p.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 }?.let { s -> p.getOrNull(1)?.toIntOrNull()?.let { s to it } } }
  }
  return (here + trakt).maxWithOrNull(compareBy({ it.first }, { it.second }))
}

/**
 * Where to start: an episode you stopped partway through (newer than your last finished one), else the first aired
 * episode after the furthest you've watched that isn't already watched, else nothing (start at the beginning).
 */
fun target(store: Store, t: Tmdb, item: Item): Target? = target(store, item) { s, e -> nextEpisode(t, item, s, e) }

fun target(store: Store, item: Item, nextOf: (Int, Int) -> NextEp?): Target? {
  val last = lastWatched(store, item)
  store.watches().filter { it.item.key == item.key && it.started && it.season != null }.maxByOrNull { it.at }?.let { w ->
    val s = w.season!!
    if (last == null || compareValuesBy(s to (w.episode ?: 0), last, { it.first }, { it.second }) >= 0) return Target(s, w.episode ?: 1, true)
  }
  var at = last ?: return null
  repeat(5) { // skip ahead past anything already ticked off out of order
    val n = runCatching { nextOf(at.first, at.second) }.getOrNull() ?: return Target(at.first, at.second, false)
    if (!store.watched(progressKey(item, n.season, n.episode))) return Target(n.season, n.episode, false)
    at = n.season to n.episode
  }
  return Target(at.first, at.second, false)
}

/**
 * Next episodes for the series you're watching, most recently watched first: shows you watched in Zylo or on Trakt,
 * where the last episode is finished and a next one has aired. Shows with an episode in progress are left to the
 * in-progress entries.
 */
fun nextUps(store: Store, t: Tmdb, max: Int = 15): List<NextUp> = runBlocking(Dispatchers.IO) {
  val inProgress = store.continueWatching().map { it.item.key }.toSet()
  val local = store.watches().filter { it.item.type == "tv" }.groupBy { it.item.id }.map { (id, ws) -> Triple(id, ws.maxOf { it.at }, ws.first().item) }
  val trakt = Trakt.recentShows(store).map { Triple(it.first, it.second, null as Item?) }
  val shows = (local + trakt).sortedByDescending { it.second }.distinctBy { it.first }.filter { "tv${it.first}" !in inProgress }.take(max)
  val today = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date())
  val gate = Semaphore(5)
  shows.map { (id, at, known) ->
    async {
      gate.withPermit {
        runCatching {
          val item = known ?: Tmdb.item(t.details("tv", id), "tv") ?: return@runCatching null
          val last = lastWatched(store, item) ?: return@runCatching null
          var n = nextEpisode(t, item, last.first, last.second) ?: return@runCatching null
          repeat(4) { if (store.watched(progressKey(item, n.season, n.episode))) n = nextEpisode(t, item, n.season, n.episode) ?: return@runCatching null }
          val watchedOn = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(Date(at))
          NextUp(item, n.season, n.episode, n.name, n.airDate != null && n.airDate > watchedOn && n.airDate <= today, at)
        }.getOrNull()
      }
    }
  }.awaitAll().filterNotNull()
}
