// Trakt, straight from the TV. Ported from apps/api/src/trakt.ts. Docs: https://trakt.docs.apiary.io
// Sign-in is the device-code flow (a code entered at trakt.tv/activate) and needs only the Client ID: new Trakt apps
// get no Client Secret. OAuth calls go to auth.trakt.tv, everything else to api.trakt.tv. Tokens are refreshed from
// expires_in (Trakt has changed the lifetime before); refresh tokens are single-use, so the new pair is always kept.
package tv.zylo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** A title Trakt pointed at, before TMDB fills in the artwork. */
data class Ref(val type: String, val tmdb: Int)

class TraktList(val name: String, val user: String, val id: String, val count: Int)

object Trakt {
  private const val API = "https://api.trakt.tv"
  private const val AUTH = "https://auth.trakt.tv"
  private const val REDIRECT = "urn:ietf:wg:oauth:2.0:oob" // never visited (device code); Trakt checks it on refresh
  val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  fun connected(store: Store) = store.str("trakt_client").isNotEmpty() && store.str("trakt_access").isNotEmpty()

  private fun headers(clientId: String) = mapOf("Content-Type" to "application/json", "trakt-api-version" to "2", "trakt-api-key" to clientId)

  private fun call(store: Store, path: String, method: String = "GET", body: JSONObject? = null, auth: Boolean = true): String = try {
    val h = headers(store.str("trakt_client")) + if (auth) mapOf("Authorization" to "Bearer ${token(store)}") else emptyMap()
    http("$API$path", method, h, body?.toString()?.toByteArray(), "application/json")
  } catch (e: HttpError) {
    throw when (e.code) {
      401 -> HttpError(401, "Trakt signed Zylo out. Connect it again in Settings > Integrations.")
      420 -> HttpError(420, "Your Trakt account has hit its free-account limit for lists or the watchlist.")
      423 -> HttpError(423, "Your Trakt account is locked. Contact Trakt support.")
      else -> e
    }
  }

  private fun arr(s: String) = if (s.isBlank()) JSONArray() else JSONArray(s)

  // --- Sign-in --------------------------------------------------------------------

  class DeviceCode(val device: String, val user: String, val url: String, val interval: Long, val expiresIn: Long)

  fun deviceCode(clientId: String): DeviceCode {
    val o = JSONObject(http("$AUTH/oauth/device/code", "POST", headers(clientId), JSONObject().put("client_id", clientId).toString().toByteArray(), "application/json"))
    return DeviceCode(o.getString("device_code"), o.getString("user_code"), o.optString("verification_url", "https://trakt.tv/activate"), o.optLong("interval", 5), o.optLong("expires_in", 600))
  }

  /** One poll: true when connected, false to keep waiting. Throws when it's over (expired, declined). */
  fun poll(store: Store, clientId: String, deviceCode: String): Boolean {
    val t = try {
      JSONObject(http("$AUTH/oauth/device/token", "POST", headers(clientId), JSONObject().put("code", deviceCode).put("client_id", clientId).toString().toByteArray(), "application/json"))
    } catch (e: HttpError) {
      when (e.code) {
        400, 429 -> return false // not approved yet / polling too fast
        410 -> throw HttpError(410, "The code expired. Try connecting again.")
        418 -> throw HttpError(418, "The request was declined on Trakt.")
        else -> throw HttpError(e.code, "Trakt answered ${e.code}. Check the Client ID.")
      }
    }
    store.put("trakt_client", clientId)
    saveTokens(store, t)
    store.put("trakt_user", runCatching { JSONObject(call(store, "/users/settings")).getJSONObject("user").optString("username") }.getOrDefault(""))
    store.putLong("trakt_synced", 0)
    return true
  }

  private fun saveTokens(store: Store, t: JSONObject) {
    store.put("trakt_access", t.getString("access_token"))
    store.put("trakt_refresh", t.getString("refresh_token"))
    store.putLong("trakt_expires", (t.optLong("created_at", System.currentTimeMillis() / 1000) + t.optLong("expires_in", 86_400)) * 1000)
  }

  @Synchronized
  private fun token(store: Store): String {
    if (store.long("trakt_expires") - System.currentTimeMillis() < 3_600_000) {
      val body = JSONObject().put("refresh_token", store.str("trakt_refresh")).put("client_id", store.str("trakt_client"))
        .put("redirect_uri", REDIRECT).put("grant_type", "refresh_token")
      val t = try {
        JSONObject(http("$AUTH/oauth/token", "POST", headers(store.str("trakt_client")), body.toString().toByteArray(), "application/json"))
      } catch (e: HttpError) {
        if (e.code == 400 || e.code == 401) {
          disconnect(store)
          throw HttpError(401, "Trakt needs you to connect again (Settings > Integrations).")
        }
        throw e
      }
      saveTokens(store, t)
    }
    return store.str("trakt_access")
  }

  fun disconnect(store: Store) {
    listOf("trakt_access", "trakt_refresh", "trakt_user").forEach { store.put(it, "") }
    store.setTraktWatched(emptySet())
  }

  // --- Reading ---------------------------------------------------------------------

  /** Films and series from any Trakt list shape ({movie}, {show}, or bare). Seasons, episodes and people are skipped. */
  private fun refs(a: JSONArray, bare: String? = null) = a.objects().mapNotNull { o ->
    val type = bare ?: o.optString("type")
    val (key, kind) = when (type) { "movie" -> "movie" to "movie"; "show" -> "show" to "tv"; else -> return@mapNotNull null }
    val ids = (if (bare != null) o else o.optJSONObject(key))?.optJSONObject("ids") ?: return@mapNotNull null
    ids.optInt("tmdb").takeIf { it > 0 }?.let { Ref(kind, it) }
  }.distinct()

  fun watchlist(store: Store) = refs(arr(call(store, "/sync/watchlist")))

  fun recommendations(store: Store) =
    (refs(arr(call(store, "/recommendations/movies?limit=20&ignore_collected=true")), "movie") + refs(arr(call(store, "/recommendations/shows?limit=20&ignore_collected=true")), "show")).shuffled()

  fun trending(store: Store) = refs(arr(call(store, "/movies/trending?limit=20", auth = false)).objects().map { it.put("type", "movie") }.let(::JSONArray))

  /** Your own lists and the lists you've liked (followed). */
  fun lists(store: Store): List<TraktList> {
    fun of(o: JSONObject?) = o?.let {
      TraktList(it.optString("name"), it.optJSONObject("user")?.optJSONObject("ids")?.optString("slug") ?: "me", it.optJSONObject("ids")?.optString("trakt").orEmpty(), it.optInt("item_count"))
    }
    val mine = arr(call(store, "/users/me/lists")).objects().mapNotNull { of(it) }
    val liked = runCatching { arr(call(store, "/users/likes/lists?limit=50")).objects().mapNotNull { of(it.optJSONObject("list")) } }.getOrDefault(emptyList())
    return (mine + liked).filter { it.id.isNotEmpty() }
  }

  fun listItems(store: Store, l: TraktList) = refs(arr(call(store, "/users/${l.user}/lists/${l.id}/items/movie,show")))

  /** Turn refs into cards via TMDB (cached), a few at a time. */
  fun items(t: Tmdb, refs: List<Ref>, max: Int = 60): List<Item> = runBlocking(Dispatchers.IO) {
    val gate = Semaphore(6)
    refs.take(max).map { r -> async { gate.withPermit { runCatching { Tmdb.item(t.details(r.type, r.tmdb), r.type) }.getOrNull() } } }.awaitAll().filterNotNull()
  }

  // --- Sync: Trakt → Zylo ------------------------------------------------------------

  /** Watched history and in-progress playback. Runs at most every 15 minutes unless forced. */
  fun sync(store: Store, force: Boolean = false) {
    if (!connected(store) || (!force && System.currentTimeMillis() - store.long("trakt_synced") < 15 * 60_000)) return
    val watched = mutableSetOf<String>()
    arr(call(store, "/sync/watched/movies")).objects().forEach { o -> o.optJSONObject("movie")?.optJSONObject("ids")?.optInt("tmdb")?.takeIf { it > 0 }?.let { watched += "movie:$it" } }
    arr(call(store, "/sync/watched/shows")).objects().forEach { o ->
      val id = o.optJSONObject("show")?.optJSONObject("ids")?.optInt("tmdb")?.takeIf { it > 0 } ?: return@forEach
      o.optJSONArray("seasons")?.objects()?.forEach { s -> s.optJSONArray("episodes")?.objects()?.forEach { e -> watched += "tv:$id:${s.optInt("number")}:${e.optInt("number")}" } }
    }
    store.setTraktWatched(watched)

    // Paused elsewhere: shows up in Continue Watching, unless Zylo has a newer position.
    val tmdb = Tmdb(store.tmdb)
    arr(call(store, "/sync/playback?limit=20")).objects().forEach { p ->
      runCatching {
        val movie = p.optString("type") == "movie"
        val id = (if (movie) p.optJSONObject("movie") else p.optJSONObject("show"))?.optJSONObject("ids")?.optInt("tmdb")?.takeIf { it > 0 } ?: return@forEach
        val ep = p.optJSONObject("episode")
        val type = if (movie) "movie" else "tv"
        val at = parseDate(p.optString("paused_at"))
        val key = if (movie) "movie:$id" else "tv:$id:${ep?.optInt("season")}:${ep?.optInt("number")}"
        if ((store.watch(key)?.at ?: 0) >= at) return@forEach
        val item = Tmdb.item(tmdb.details(type, id), type) ?: return@forEach
        store.saveWatch(item, ep?.optInt("season"), ep?.optInt("number"), (p.optDouble("progress") * 10).toLong(), 1000, at, percent = true)
      }
    }
    store.putLong("trakt_synced", System.currentTimeMillis())
  }

  private fun parseDate(s: String) = runCatching {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.take(19))!!.time
  }.getOrDefault(0L)

  // --- Zylo → Trakt (fire and forget: tracking must never get in the way of watching) ---------

  private fun body(item: Item, season: Int?, episode: Int?, extra: JSONObject.() -> Unit = {}): JSONObject {
    val ids = JSONObject().put("ids", JSONObject().put("tmdb", item.id))
    return if (item.type == "movie") JSONObject().put("movies", JSONArray().put(ids.apply(extra)))
    else if (season == null) JSONObject().put("shows", JSONArray().put(ids.apply(extra)))
    else JSONObject().put("shows", JSONArray().put(ids.put("seasons", JSONArray().put(JSONObject().put("number", season).put("episodes", JSONArray().put(JSONObject().put("number", episode).apply(extra)))))))
  }

  /** "Now watching". start/pause just inform; stop at 80%+ records a play on Trakt (409 = already recorded). */
  fun scrobble(store: Store, action: String, item: Item, season: Int?, episode: Int?, progress: Float) {
    if (!connected(store)) return
    val b = if (item.type == "movie") JSONObject().put("movie", JSONObject().put("ids", JSONObject().put("tmdb", item.id)))
    else JSONObject().put("show", JSONObject().put("ids", JSONObject().put("tmdb", item.id))).put("episode", JSONObject().put("season", season).put("number", episode))
    b.put("progress", progress.coerceIn(0f, 100f).toDouble())
    io.launch { runCatching { call(store, "/scrobble/$action", "POST", b) } }
    if (action == "stop" && progress >= 80f) store.setTraktWatched(store.traktWatched() + progressKey(item, season, episode))
  }

  fun setWatched(store: Store, item: Item, season: Int?, episode: Int?, watched: Boolean) {
    val key = progressKey(item, season, episode)
    store.setTraktWatched(if (watched) store.traktWatched() + key else store.traktWatched() - key)
    if (connected(store)) io.launch { runCatching { call(store, if (watched) "/sync/history" else "/sync/history/remove", "POST", body(item, season, episode)) } }
  }

  fun setWatchlisted(store: Store, item: Item, add: Boolean) {
    if (connected(store)) io.launch { runCatching { call(store, if (add) "/sync/watchlist" else "/sync/watchlist/remove", "POST", body(item, null, null)) } }
  }
}
