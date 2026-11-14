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
  // Scrobbles go one at a time, in order: sent side by side, a "start" could land after the "stop", leaving Trakt
  // thinking you're still watching until it marked the whole thing watched.
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val inOrder = Dispatchers.IO.limitedParallelism(1)

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

  /** OAuth calls. api.trakt.tv answers them (checked 8 Nov 2026); auth.trakt.tv is Trakt's newer host, used if api ever stops. */
  private fun oauth(path: String, clientId: String, body: JSONObject): String = try {
    http("$API$path", "POST", headers(clientId), body.toString().toByteArray(), "application/json")
  } catch (e: HttpError) {
    if (e.code != 404 && e.code != 405) throw e
    http("$AUTH$path", "POST", headers(clientId), body.toString().toByteArray(), "application/json")
  }

  /**
   * Only catches obvious paste mistakes (spaces, a stray word). Lengths vary: older Trakt apps have 64-character IDs,
   * newer ones 43. Trakt itself says whether an ID is real when sign-in starts.
   */
  fun validClientId(id: String) = Regex("[A-Za-z0-9_.~-]{20,200}").matches(id.trim())

  private fun arr(s: String) = if (s.isBlank()) JSONArray() else JSONArray(s)

  /** Trakt pages its sync and list endpoints (100 a page unless asked), so walk every page — as Nuvio does. */
  private fun paged(store: Store, path: String, limit: Int = 250): List<JSONObject> {
    val all = mutableListOf<JSONObject>()
    val sep = if ('?' in path) '&' else '?'
    for (page in 1..1000) {
      val got = arr(call(store, "$path${sep}page=$page&limit=$limit")).objects()
      all += got
      if (got.size < limit) break
    }
    return all
  }

  // --- Sign-in --------------------------------------------------------------------

  class DeviceCode(val device: String, val user: String, val url: String, val interval: Long, val expiresIn: Long)

  fun deviceCode(clientId: String): DeviceCode {
    val o = try {
      JSONObject(oauth("/oauth/device/code", clientId, JSONObject().put("client_id", clientId)))
    } catch (e: HttpError) {
      throw HttpError(e.code, if (e.code == 401 || e.code == 403) "Trakt doesn't recognise that Client ID. Copy it again from trakt.tv > Settings > Your API Apps." else "Trakt answered ${e.code}.")
    } catch (e: java.io.IOException) {
      throw java.io.IOException("Couldn't reach Trakt (${e.message}). Check the TV's internet connection.")
    }
    return DeviceCode(o.getString("device_code"), o.getString("user_code"), o.optString("verification_url", "https://trakt.tv/activate"), o.optLong("interval", 5), o.optLong("expires_in", 600))
  }

  /** One poll: true when connected, false to keep waiting. Throws when it's over (expired, declined). */
  fun poll(store: Store, clientId: String, deviceCode: String): Boolean {
    val t = try {
      JSONObject(oauth("/oauth/device/token", clientId, JSONObject().put("code", deviceCode).put("client_id", clientId)))
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
        JSONObject(oauth("/oauth/token", store.str("trakt_client"), body))
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

  fun watchlist(store: Store) = refs(JSONArray(paged(store, "/sync/watchlist")))

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

  /** Thumbs up = 9/10, down = 3/10, null = rating removed. Through the Outbox, so it lands even offline. */
  fun rate(store: Store, item: Item, rating: Int?) {
    if (!connected(store)) return
    Outbox.add(store, item.title + (rating?.let { " rated $it/10" } ?: " rating removed"),
      JSONObject().put("service", "trakt").put("path", if (rating != null) "/sync/ratings" else "/sync/ratings/remove")
        .put("body", body(item, null, null) { if (rating != null) put("rating", rating) }))
  }

  /** Your shows' episodes and the films on your lists coming out over [days] from [start] (yyyy-MM-dd). */
  fun calendar(store: Store, start: String, days: Int): Pair<List<JSONObject>, List<JSONObject>> =
    arr(call(store, "/calendars/my/shows/$start/$days")).objects() to runCatching { arr(call(store, "/calendars/my/movies/$start/$days")).objects() }.getOrDefault(emptyList())

  /** Everything you watched between two ISO times, with runtimes and genres (extended=full). */
  fun history(store: Store, from: String, to: String) = paged(store, "/sync/history?start_at=$from&end_at=$to&extended=full")

  /** Lists you own (the ones you can add to). */
  fun ownLists(store: Store) = arr(call(store, "/users/me/lists")).objects().mapNotNull { o ->
    o.optJSONObject("ids")?.optString("trakt")?.takeIf { it.isNotEmpty() }?.let { TraktList(o.optString("name"), "me", it, o.optInt("item_count")) }
  }

  fun setListed(store: Store, item: Item, l: TraktList, add: Boolean) =
    Outbox.add(store, item.title + if (add) " added to ${l.name}" else " removed from ${l.name}",
      JSONObject().put("service", "trakt").put("path", "/users/me/lists/${l.id}/items" + if (add) "" else "/remove").put("body", body(item, null, null)))

  fun listItems(store: Store, l: TraktList) = refs(JSONArray(paged(store, "/users/${l.user}/lists/${l.id}/items/movie,show")))

  /** Trakt's most-liked public lists short enough to finish (6–20 titles). Needs only the Client ID, no sign-in. */
  fun popularLists(store: Store) = arr(call(store, "/lists/popular?limit=100", auth = false)).objects().mapNotNull { it.optJSONObject("list") }
    .filter { it.optInt("item_count") in 6..20 }
    .mapNotNull { o -> o.optJSONObject("ids")?.optInt("trakt")?.takeIf { it > 0 }?.let { TraktList(o.optString("name"), o.optJSONObject("user")?.optString("username").orEmpty(), it.toString(), o.optInt("item_count")) } }

  fun publicListItems(store: Store, id: String) = refs(arr(call(store, "/lists/$id/items/movie,show?limit=100", auth = false)))

  /** Turn refs into cards via TMDB (cached), a few at a time. */
  fun items(t: Tmdb, refs: List<Ref>, max: Int = 60): List<Item> = runBlocking(Dispatchers.IO) {
    val gate = Semaphore(6)
    refs.take(max).map { r -> async { gate.withPermit { runCatching { t.details(r.type, r.tmdb).takeIf { Ratings.allows(Tmdb.certification(it)) }?.let { Tmdb.item(it, r.type) } }.getOrNull() } } }.awaitAll().filterNotNull()
  }

  // --- Sync: Trakt → Zylo ------------------------------------------------------------

  /** Watched history and in-progress playback. Runs at most every 15 minutes unless forced. */
  fun sync(store: Store, force: Boolean = false) {
    if (!connected(store) || (!force && System.currentTimeMillis() - store.long("trakt_synced") < 15 * 60_000)) return
    Busy.run("Syncing Trakt") {
      val watched = mutableSetOf<String>()
      val movies = paged(store, "/sync/watched/movies")
      movies.forEach { o -> o.optJSONObject("movie")?.optJSONObject("ids")?.optInt("tmdb")?.takeIf { it > 0 }?.let { watched += "movie:$it" } }
      val shows = paged(store, "/sync/watched/shows?extended=progress")
      val recent = JSONArray() // shows by when you last watched them, for Up Next
      shows.forEach { o ->
        val id = o.optJSONObject("show")?.optJSONObject("ids")?.optInt("tmdb")?.takeIf { it > 0 } ?: return@forEach
        o.optJSONArray("seasons")?.objects()?.forEach { s -> s.optJSONArray("episodes")?.objects()?.forEach { e -> watched += "tv:$id:${s.optInt("number")}:${e.optInt("number")}" } }
        recent.put(JSONObject().put("id", id).put("at", parseDate(o.optString("last_watched_at"))))
      }
      store.setTraktWatched(watched)
      store.put("trakt_shows", JSONArray(recent.objects().sortedByDescending { it.optLong("at") }.take(60)).toString())
      val episodes = watched.count { it.startsWith("tv:") }

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
      store.setStatus("trakt", "ok")
      Outbox.log(store, "trakt", "Synced from Trakt", "${movies.size} films and $episodes episodes watched (${shows.size} series).", true)
    }
  }

  /** Series you've watched on Trakt, most recent first: (TMDB id, last watched time). */
  fun recentShows(store: Store) = runCatching { JSONArray(store.str("trakt_shows").ifEmpty { "[]" }).objects().map { it.optInt("id") to it.optLong("at") } }.getOrDefault(emptyList())

  private fun parseDate(s: String) = runCatching {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.take(19))!!.time
  }.getOrDefault(0L)

  // --- Zylo → Trakt. Lasting changes go through the Outbox (retried, logged); "now watching" is sent directly. ------

  fun post(store: Store, path: String, body: JSONObject): String = call(store, path, "POST", body)

  private fun label(item: Item, season: Int?, episode: Int?) = item.title + if (season != null) " S${season}E$episode" else ""

  private fun body(item: Item, season: Int?, episode: Int?, extra: JSONObject.() -> Unit = {}): JSONObject {
    val ids = JSONObject().put("ids", JSONObject().put("tmdb", item.id))
    return if (item.type == "movie") JSONObject().put("movies", JSONArray().put(ids.apply(extra)))
    else if (season == null) JSONObject().put("shows", JSONArray().put(ids.apply(extra)))
    else JSONObject().put("shows", JSONArray().put(ids.put("seasons", JSONArray().put(JSONObject().put("number", season).put("episodes", JSONArray().put(JSONObject().put("number", episode).apply(extra)))))))
  }

  /**
   * "Now watching". start/pause just inform Trakt. stop at 80%+ records the play (409 means it's already recorded,
   * which counts as sent); if stop can't be delivered, the play goes into the Outbox as a history entry with the time
   * you finished, so it still lands later.
   */
  fun scrobble(store: Store, action: String, item: Item, season: Int?, episode: Int?, progress: Float) {
    if (!connected(store)) return
    val b = if (item.type == "movie") JSONObject().put("movie", JSONObject().put("ids", JSONObject().put("tmdb", item.id)))
    else JSONObject().put("show", JSONObject().put("ids", JSONObject().put("tmdb", item.id))).put("episode", JSONObject().put("season", season).put("number", episode))
    b.put("progress", progress.coerceIn(0f, 100f).toDouble())
    val finished = action == "stop" && progress >= 80f
    if (finished) store.setTraktWatched(store.traktWatched() + progressKey(item, season, episode))
    val at = isoNow()
    io.launch(inOrder) {
      try {
        call(store, "/scrobble/$action", "POST", b)
        if (action == "stop") Outbox.log(store, "trakt", label(item, season, episode), if (finished) "Watched, sent to Trakt." else "Progress (${progress.toInt()}%) sent to Trakt.", true)
      } catch (e: HttpError) {
        if (e.code == 409) Outbox.log(store, "trakt", label(item, season, episode), "Already recorded on Trakt.", true)
        else if (finished) Outbox.add(store, label(item, season, episode), JSONObject().put("service", "trakt").put("path", "/sync/history").put("body", body(item, season, episode) { put("watched_at", at) }))
        else if (action == "stop") stopLater(store, item, season, episode, b)
        else if (action != "pause") Outbox.log(store, "trakt", label(item, season, episode), "Now-watching update failed: ${e.message}", false)
      } catch (e: Exception) {
        if (finished) Outbox.add(store, label(item, season, episode), JSONObject().put("service", "trakt").put("path", "/sync/history").put("body", body(item, season, episode) { put("watched_at", at) }))
        else if (action == "stop") stopLater(store, item, season, episode, b)
      }
    }
  }

  /** A "stop" that didn't get through is retried, so Trakt doesn't go on thinking you're watching (and finish it for you). */
  private fun stopLater(store: Store, item: Item, season: Int?, episode: Int?, b: JSONObject) =
    Outbox.add(store, label(item, season, episode) + " stopped at ${b.optDouble("progress").toInt()}%", JSONObject().put("service", "trakt").put("path", "/scrobble/stop").put("body", b))

  fun setWatched(store: Store, item: Item, season: Int?, episode: Int?, watched: Boolean) {
    val key = progressKey(item, season, episode)
    store.setTraktWatched(if (watched) store.traktWatched() + key else store.traktWatched() - key)
    if (connected(store)) Outbox.add(store, label(item, season, episode) + if (watched) " marked watched" else " marked unwatched",
      JSONObject().put("service", "trakt").put("path", if (watched) "/sync/history" else "/sync/history/remove").put("body", body(item, season, episode)))
  }

  fun setWatchlisted(store: Store, item: Item, add: Boolean) {
    if (connected(store)) Outbox.add(store, item.title + if (add) " added to watchlist" else " removed from watchlist",
      JSONObject().put("service", "trakt").put("path", if (add) "/sync/watchlist" else "/sync/watchlist/remove").put("body", body(item, null, null)))
  }

  private fun isoNow() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'.000Z'", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date())
}
