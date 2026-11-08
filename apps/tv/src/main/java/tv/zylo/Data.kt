// HTTP, on-device storage and TMDB. No server: everything is called straight from the TV.
package tv.zylo

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class HttpError(val code: Int, message: String) : Exception(message)

fun http(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null, type: String? = null): String {
  val c = URL(url).openConnection() as HttpURLConnection
  try {
    try {
      c.requestMethod = method
    } catch (e: java.net.ProtocolException) { // PATCH (MyAnimeList) on stacks that only list the classic methods
      HttpURLConnection::class.java.getDeclaredField("method").apply { isAccessible = true }.set(c, method)
    }
    c.connectTimeout = 10_000
    c.readTimeout = 20_000
    c.setRequestProperty("User-Agent", "Zylo/0.10.0")
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    if (body != null) {
      c.doOutput = true
      if (type != null) c.setRequestProperty("Content-Type", type)
      c.outputStream.use { it.write(body) }
    }
    val code = c.responseCode
    // ponytail: reads the whole body; add a size cap if an addon ever sends something huge
    val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
    if (code == 401 || code == 403) throw HttpError(code, "${c.url.host} didn't accept the key ($code).")
    if (code >= 400) throw HttpError(code, "${c.url.host} answered $code.")
    return text
  } finally {
    c.disconnect()
  }
}

fun getJson(url: String, headers: Map<String, String> = emptyMap()) = JSONObject(http(url, headers = headers))
fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
fun form(vararg p: Pair<String, String>) = p.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }.toByteArray()
fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotEmpty() && it != "null" }
fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
fun strings(a: JSONArray?) = a?.let { (0 until it.length()).mapNotNull { i -> it.opt(i) as? String } } ?: emptyList()

// --- A film or series card -------------------------------------------------------

data class Item(val type: String, val id: Int, val title: String, val poster: String?, val backdrop: String?, val overview: String, val year: String, val rating: Double = 0.0) {
  val key get() = "$type$id"

  fun toJson(): JSONObject = JSONObject().put("type", type).put("id", id).put("title", title).put("poster", poster ?: "").put("backdrop", backdrop ?: "")
    .put("overview", overview).put("year", year).put("rating", rating)

  companion object {
    fun from(o: JSONObject) = Item(o.getString("type"), o.getInt("id"), o.optString("title"), o.str("poster"), o.str("backdrop"), o.optString("overview"), o.optString("year"), o.optDouble("rating", 0.0))
  }
}

fun img(path: String?, size: String) = path?.let { "https://image.tmdb.org/t/p/$size$it" }

/** One saved playback position. 90% counts as watched. */
/** One saved playback position. 90% counts as watched. `percent`: imported from Trakt, which only knows a percentage. */
class Watch(val key: String, val item: Item, val season: Int?, val episode: Int?, val pos: Long, val dur: Long, val at: Long, val percent: Boolean = false) {
  val fraction get() = if (dur > 0) pos.toFloat() / dur else 0f
  val finished get() = dur > 0 && pos >= dur * 0.9
  val started get() = !finished && if (percent) fraction > 0.01f else pos > 60_000
  val left get() = if (percent) "${(fraction * 100).toInt()}% watched" else "${((dur - pos) / 60_000).coerceAtLeast(1)} min left"
}

fun progressKey(item: Item, season: Int?, episode: Int?) = "${item.type}:${item.id}" + if (season != null) ":$season:$episode" else ""

// --- Storage ------------------------------------------------------------------------

class Store(ctx: Context) {
  private val p = ctx.getSharedPreferences("zylo", Context.MODE_PRIVATE)
  fun put(k: String, v: String) = p.edit().putString(k, v).apply()
  fun str(k: String) = p.getString(k, "")!!
  fun long(k: String) = p.getLong(k, 0)
  fun putLong(k: String, v: Long) = p.edit().putLong(k, v).apply()

  // ponytail: keys live in app-private storage, not Keystore-encrypted; add that if the Shield is shared or rooted
  var tmdb: String
    get() = p.getString("tmdb", "")!!
    set(v) = put("tmdb", v.trim())

  fun debrid(service: String) = p.getString("debrid_$service", "")!!
  fun setDebrid(service: String, key: String) = put("debrid_$service", key.trim())

  /** "ok", "fail:<why>", or "" (never tested). Only "ok" may show as Connected. */
  fun status(service: String) = p.getString("status_$service", "")!!
  fun setStatus(service: String, v: String) = put("status_$service", v)

  private val previewsState = mutableStateOf(p.getBoolean("previews", true))
  var previews: Boolean
    get() = previewsState.value
    set(v) { previewsState.value = v; p.edit().putBoolean("previews", v).apply() }

  private val trailerSoundState = mutableStateOf(p.getBoolean("trailerSound", false))
  var trailerSound: Boolean
    get() = trailerSoundState.value
    set(v) { trailerSoundState.value = v; p.edit().putBoolean("trailerSound", v).apply() }

  private val reduceMotionState = mutableStateOf(p.getBoolean("reduceMotion", false))
  var reduceMotion: Boolean
    get() = reduceMotionState.value
    set(v) { reduceMotionState.value = v; p.edit().putBoolean("reduceMotion", v).apply() }

  var addons: List<Addon>
    get() = JSONArray(p.getString("addons", "[]")).objects().map { Addon(it.getString("url"), it.getJSONObject("manifest")) }
    set(v) = put("addons", JSONArray(v.map { JSONObject().put("url", it.url).put("manifest", it.manifest) }).toString())

  var myList: List<Item>
    get() = JSONArray(p.getString("mylist", "[]")).objects().map { Item.from(it) }
    set(v) = put("mylist", JSONArray(v.map { it.toJson() }).toString())

  fun inMyList(item: Item) = myList.any { it.key == item.key }

  /** Returns true if it's now in the list. */
  fun toggleMyList(item: Item): Boolean {
    val list = myList
    val add = list.none { it.key == item.key }
    myList = if (add) listOf(item) + list else list.filter { it.key != item.key }
    return add
  }

  private fun allProgress() = JSONObject(p.getString("progress", "{}")!!)

  private var watchCache: List<Watch>? = null

  fun watches(): List<Watch> = watchCache ?: allProgress().let { all ->
    all.keys().asSequence().mapNotNull { k ->
      val o = all.optJSONObject(k) ?: return@mapNotNull null
      runCatching {
        Watch(k, Item.from(o.getJSONObject("item")), if (o.has("s")) o.getInt("s") else null, if (o.has("e")) o.getInt("e") else null, o.optLong("pos"), o.optLong("dur"), o.optLong("at"), o.optBoolean("pct"))
      }.getOrNull()
    }.toList()
  }.also { watchCache = it }

  fun watch(key: String) = watches().find { it.key == key }
  fun latest(item: Item) = watches().filter { it.item.key == item.key }.maxByOrNull { it.at }
  fun continueWatching() = watches().filter { it.started }.sortedByDescending { it.at }.distinctBy { it.item.key }.take(12)

  fun saveWatch(item: Item, season: Int?, episode: Int?, pos: Long, dur: Long, at: Long = System.currentTimeMillis(), percent: Boolean = false) {
    val o = JSONObject().put("item", item.toJson()).put("pos", pos).put("dur", dur).put("at", at)
    if (percent) o.put("pct", true)
    if (season != null) o.put("s", season).put("e", episode ?: 0)
    put("progress", allProgress().put(progressKey(item, season, episode), o).toString())
    watchCache = null
  }

  /** Watched here (90%+) or in Trakt's history. Keys as progressKey(). */
  fun watched(key: String) = watch(key)?.finished == true || key in traktWatched()

  private var traktCache: Set<String>? = null
  fun traktWatched(): Set<String> = traktCache ?: strings(JSONArray(p.getString("trakt_watched", "[]"))).toSet().also { traktCache = it }
  fun setTraktWatched(keys: Set<String>) {
    put("trakt_watched", JSONArray(keys.toList()).toString())
    traktCache = keys
  }

  fun forget(key: String) {
    put("progress", allProgress().apply { remove(key) }.toString())
    watchCache = null
  }
}

// --- TMDB -----------------------------------------------------------------------------

class Tmdb(private val key: String) {
  private val v3 = Regex("[0-9a-fA-F]{32}").matches(key) // a v3 API key; anything else is a v4 read token

  fun get(path: String): JSONObject {
    val url = "https://api.themoviedb.org/3$path${if ('?' in path) '&' else '?'}language=en-GB" + if (v3) "&api_key=$key" else ""
    return getJson(url, if (v3) emptyMap() else mapOf("Authorization" to "Bearer $key"))
  }

  fun list(path: String, type: String?) = get(path).optJSONArray("results")?.objects()?.mapNotNull { item(it, type) }?.distinctBy { it.key }.orEmpty()

  /** Everything a detail page or preview needs, in one request. Cached for the session. */
  fun details(type: String, id: Int): JSONObject = cache.getOrPut("$type:$id") {
    val extra = if (type == "movie") "release_dates" else "content_ratings"
    get("/$type/$id?append_to_response=external_ids,videos,credits,recommendations,images,$extra&include_video_language=en,null&include_image_language=en,null")
  }

  fun season(id: Int, n: Int): JSONObject = cache.getOrPut("season:$id:$n") { get("/tv/$id/season/$n") }

  fun genres(type: String) = cache.getOrPut("genres:$type") { get("/genre/$type/list") }.optJSONArray("genres")?.objects()?.map { it.optInt("id") to it.optString("name") }.orEmpty()

  /** A TMDB card from another site's id (imdb_id, tvdb_id). */
  fun find(id: String, source: String, type: String): Item? = cache.getOrPut("find:$source:$id") { get("/find/${enc(id)}?external_source=$source") }
    .optJSONArray(if (type == "movie") "movie_results" else "tv_results")?.objects()?.firstOrNull()?.let { item(it, type) }

  /** Details for a preview card. A failure is remembered, so a broken title isn't retried on every focus. */
  fun preview(item: Item): JSONObject? = if (item.key in failed) null else runCatching { details(item.type, item.id) }.onFailure { failed += item.key }.getOrNull()

  companion object {
    // ponytail: unbounded session cache (~50 KB a title); add an LRU if memory gets tight on cheaper TVs
    private val cache = ConcurrentHashMap<String, JSONObject>()
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun cached(item: Item): JSONObject? = cache["${item.type}:${item.id}"]

    fun item(o: JSONObject, type: String?): Item? {
      val t = type ?: o.optString("media_type")
      if (t != "movie" && t != "tv") return null
      val movie = t == "movie"
      return Item(
        t, o.optInt("id"), o.optString(if (movie) "title" else "name"), o.str("poster_path") ?: return null, o.str("backdrop_path"), o.optString("overview"),
        o.optString(if (movie) "release_date" else "first_air_date").take(4), o.optDouble("vote_average", 0.0),
      )
    }

    /** YouTube key of the best trailer: trailers before teasers, official first. */
    fun trailer(d: JSONObject?): String? = d?.optJSONObject("videos")?.optJSONArray("results")?.objects()
      ?.filter { it.optString("site") == "YouTube" && it.str("key") != null && it.optString("type") in setOf("Trailer", "Teaser") }
      ?.sortedWith(compareBy({ it.optString("type") != "Trailer" }, { !it.optBoolean("official") }))
      ?.firstOrNull()?.str("key")

    /** UK age rating, else US. */
    fun certification(d: JSONObject): String? {
      val movie = d.has("release_dates")
      val list = d.optJSONObject(if (movie) "release_dates" else "content_ratings")?.optJSONArray("results")?.objects().orEmpty()
      for (country in listOf("GB", "US")) {
        val r = list.find { it.optString("iso_3166_1") == country } ?: continue
        val cert = if (movie) r.optJSONArray("release_dates")?.objects()?.firstNotNullOfOrNull { it.str("certification") } else r.str("rating")
        if (cert != null) return cert
      }
      return null
    }

    fun meta(item: Item, d: JSONObject?): List<String> = listOfNotNull(
      item.year.ifEmpty { null },
      d?.let { certification(it) },
      d?.optInt("runtime")?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
      d?.optInt("number_of_seasons")?.takeIf { it > 0 }?.let { if (it == 1) "1 Season" else "$it Seasons" },
      d?.optJSONArray("genres")?.objects()?.take(2)?.joinToString(", ") { it.optString("name") }?.ifEmpty { null },
    )
  }
}

/** A row of titles: a TMDB list path, or a custom loader (Trakt, anime trackers). */
class RowSpec(val title: String, val path: String, val type: String?, val load: ((Tmdb) -> List<Item>)? = null)

fun homeRows(store: Store): List<RowSpec> {
  val last = store.watches().maxByOrNull { it.at }?.item
  val trakt = if (Trakt.connected(store)) listOf(
    RowSpec("Your Trakt Watchlist", "", null) { Trakt.items(it, Trakt.watchlist(store)) },
    RowSpec("Recommended For You", "", null) { Trakt.items(it, Trakt.recommendations(store)) },
  ) else emptyList()
  val popular = if (store.str("trakt_client").isNotEmpty()) listOf(RowSpec("Popular on Trakt", "", null) { Trakt.items(it, Trakt.trending(store)) }) else emptyList()
  return trakt + listOf(
    RowSpec("Trending Movies", "/trending/movie/week", "movie"),
    RowSpec("Popular Series", "/tv/popular", "tv"),
    RowSpec("Popular Anime", "/discover/tv?with_genres=16&with_origin_country=JP&sort_by=popularity.desc", "tv"),
  ) + listOfNotNull(last?.let { RowSpec("Because You Watched ${it.title}", "/${it.type}/${it.id}/recommendations", null) }) + listOf(
    RowSpec("In Cinemas Now", "/movie/now_playing?region=GB", "movie"),
    RowSpec("New Episodes This Week", "/tv/on_the_air", "tv"),
    RowSpec("Top Rated Films", "/movie/top_rated", "movie"),
  ) + popular
}

val DISCOVER_ROWS = listOf(
  RowSpec("Trending This Week", "/trending/all/week", null),
  RowSpec("Top Rated Series", "/tv/top_rated", "tv"),
  RowSpec("Action", "/discover/movie?with_genres=28", "movie"),
  RowSpec("Comedy", "/discover/movie?with_genres=35", "movie"),
  RowSpec("Sci-Fi", "/discover/movie?with_genres=878", "movie"),
  RowSpec("Thrillers", "/discover/movie?with_genres=53", "movie"),
  RowSpec("Horror", "/discover/movie?with_genres=27", "movie"),
  RowSpec("Crime Series", "/discover/tv?with_genres=80", "tv"),
  RowSpec("Anime Films", "/discover/movie?with_genres=16&with_origin_country=JP", "movie"),
  RowSpec("Family", "/discover/movie?with_genres=10751", "movie"),
  RowSpec("Documentaries", "/discover/movie?with_genres=99", "movie"),
)
