// Sending what you watch back to Trakt, AniList and MyAnimeList, reliably and visibly.
// Every update goes into an outbox (kept on the TV, per profile) before it's sent, so a dropped connection, a Shield
// asleep mid-request or an expired token doesn't lose it: it's retried on the next start, sync or update. Everything
// sent, and every failure with its reason, goes into an activity log shown in Settings > Integrations.
package tv.zylo

import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class SyncEntry(val at: Long, val service: String, val label: String, val result: String, val ok: Boolean)

object Outbox {
  private const val MAX_LOG = 40
  private const val MAX_TRIES = 30

  /**
   * Queue an update and try to send it now.
   * job: {"service":"trakt","path":"/sync/history","body":{…}} or {"service":"anilist"|"mal","id":123,"episode":4}.
   */
  fun add(store: Store, label: String, job: JSONObject) {
    synchronized(this) { save(store, jobs(store) + job.put("label", label).put("at", System.currentTimeMillis()).put("tries", 0)) }
    flushAsync(store)
  }

  fun flushAsync(store: Store) = Trakt.io.launch { flush(store) }

  /** Send everything waiting. Kept: failures worth retrying. Dropped: sent, or refused for good (logged either way). */
  @Synchronized
  fun flush(store: Store) {
    val keep = jobs(store).mapNotNull { job ->
      val service = job.optString("service")
      val label = job.optString("label")
      try {
        val result = send(store, job)
        log(store, service, label, result, true)
        null
      } catch (e: Exception) {
        val tries = job.optInt("tries") + 1
        val retry = retryable(e) && tries < MAX_TRIES
        log(store, service, label, (e.message ?: e.javaClass.simpleName) + if (retry) " Will retry." else "", false)
        if (retry) job.put("tries", tries) else null
      }
    }
    save(store, keep)
  }

  private fun send(store: Store, job: JSONObject): String = when (job.optString("service")) {
    "trakt" -> {
      if (!Trakt.connected(store)) throw HttpError(401, "Trakt isn't connected on this profile.")
      Trakt.post(store, job.getString("path"), job.getJSONObject("body"))
      "Sent to Trakt."
    }
    else -> Anime.push(store, job.optString("service"), job.getInt("id"), job.getInt("episode"))
  }

  /** No connection, timeouts, rate limits and server trouble are worth another go; a refused request isn't. */
  private fun retryable(e: Exception) = when (e) {
    is HttpError -> e.code == 401 || e.code == 408 || e.code == 429 || e.code >= 500
    is IOException -> true
    else -> false
  }

  fun pending(store: Store) = jobs(store).size

  private fun jobs(store: Store) = runCatching { JSONArray(store.str("outbox").ifEmpty { "[]" }).objects() }.getOrDefault(emptyList())
  private fun save(store: Store, jobs: List<JSONObject>) = store.put("outbox", JSONArray(jobs).toString())

  // --- Activity log ---

  @Synchronized
  fun log(store: Store, service: String, label: String, result: String, ok: Boolean) {
    val entry = JSONObject().put("at", System.currentTimeMillis()).put("service", service).put("label", label).put("result", result).put("ok", ok)
    store.put("synclog", JSONArray(listOf(entry) + logJson(store).take(MAX_LOG - 1)).toString())
  }

  fun entries(store: Store) = logJson(store).map { SyncEntry(it.optLong("at"), it.optString("service"), it.optString("label"), it.optString("result"), it.optBoolean("ok")) }

  private fun logJson(store: Store) = runCatching { JSONArray(store.str("synclog").ifEmpty { "[]" }).objects() }.getOrDefault(emptyList())
}
