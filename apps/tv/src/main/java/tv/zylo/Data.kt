// HTTP, on-device storage and TMDB. No server: everything is called straight from the TV.
package tv.zylo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpError(val code: Int, message: String) : Exception(message)

fun http(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null, type: String? = null): String {
  val c = URL(url).openConnection() as HttpURLConnection
  try {
    c.requestMethod = method
    c.connectTimeout = 10_000
    c.readTimeout = 20_000
    c.setRequestProperty("User-Agent", "Zylo/0.8.0")
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

data class Item(val type: String, val id: Int, val title: String, val poster: String?, val backdrop: String?, val overview: String, val year: String) {
  fun toJson(): JSONObject = JSONObject().put("type", type).put("id", id).put("title", title).put("poster", poster ?: "").put("backdrop", backdrop ?: "")
    .put("overview", overview).put("year", year)

  companion object {
    fun from(o: JSONObject) = Item(o.getString("type"), o.getInt("id"), o.optString("title"), o.str("poster"), o.str("backdrop"), o.optString("overview"), o.optString("year"))
  }
}

fun img(path: String?, size: String) = path?.let { "https://image.tmdb.org/t/p/$size$it" }

// --- Storage ------------------------------------------------------------------------

class Store(ctx: Context) {
  private val p = ctx.getSharedPreferences("zylo", Context.MODE_PRIVATE)
  private fun put(k: String, v: String) = p.edit().putString(k, v).apply()

  // ponytail: keys live in app-private storage, not Keystore-encrypted; add that if the Shield is shared or rooted
  var tmdb: String
    get() = p.getString("tmdb", "")!!
    set(v) = put("tmdb", v.trim())

  fun debrid(service: String) = p.getString("debrid_$service", "")!!
  fun setDebrid(service: String, key: String) = put("debrid_$service", key.trim())

  var addons: List<Addon>
    get() = JSONArray(p.getString("addons", "[]")).objects().map { Addon(it.getString("url"), it.getJSONObject("manifest")) }
    set(v) = put("addons", JSONArray(v.map { JSONObject().put("url", it.url).put("manifest", it.manifest) }).toString())

  private fun allProgress() = JSONObject(p.getString("progress", "{}")!!)
  fun progress(key: String): JSONObject? = allProgress().optJSONObject(key)
  fun saveProgress(key: String, o: JSONObject) = put("progress", allProgress().put(key, o).toString())

  /** Started, not finished (90% counts as finished), newest first. */
  fun continueWatching(): List<Item> {
    val all = allProgress()
    return all.keys().asSequence().map { all.getJSONObject(it) }
      .filter { it.optLong("pos") > 60_000 && it.optLong("pos") < it.optLong("dur") * 0.9 }
      .sortedByDescending { it.optLong("at") }
      .map { Item.from(it.getJSONObject("item")) }
      .distinctBy { it.type to it.id }.take(12).toList()
  }
}

fun progressKey(item: Item, season: Int?, episode: Int?) = "${item.type}:${item.id}" + if (season != null) ":$season:$episode" else ""

// --- TMDB -----------------------------------------------------------------------------

class Tmdb(private val key: String) {
  private val v3 = Regex("[0-9a-fA-F]{32}").matches(key) // a v3 API key; anything else is a v4 read token

  fun get(path: String): JSONObject {
    val url = "https://api.themoviedb.org/3$path${if ('?' in path) '&' else '?'}language=en-GB" + if (v3) "&api_key=$key" else ""
    return getJson(url, if (v3) emptyMap() else mapOf("Authorization" to "Bearer $key"))
  }

  fun list(path: String, type: String?) = get(path).optJSONArray("results")?.objects()?.mapNotNull { item(it, type) }?.distinctBy { it.type to it.id }.orEmpty()
  fun details(type: String, id: Int) = get("/$type/$id?append_to_response=external_ids")
  fun season(id: Int, n: Int) = get("/tv/$id/season/$n")

  /** YouTube key of the best trailer: trailers before teasers, official first. */
  fun trailer(type: String, id: Int): String? = get("/$type/$id/videos?include_video_language=en,null").optJSONArray("results")?.objects()
    ?.filter { it.optString("site") == "YouTube" && it.str("key") != null && it.optString("type") in setOf("Trailer", "Teaser") }
    ?.sortedWith(compareBy({ it.optString("type") != "Trailer" }, { !it.optBoolean("official") }))
    ?.firstOrNull()?.str("key")

  companion object {
    fun item(o: JSONObject, type: String?): Item? {
      val t = type ?: o.optString("media_type")
      if (t != "movie" && t != "tv") return null
      val movie = t == "movie"
      return Item(t, o.optInt("id"), o.optString(if (movie) "title" else "name"), o.str("poster_path") ?: return null, o.str("backdrop_path"), o.optString("overview"),
        o.optString(if (movie) "release_date" else "first_air_date").take(4))
    }
  }
}

val ROWS: List<Triple<String, String, String?>> = listOf(
  Triple("In cinemas now", "/movie/now_playing?region=GB", "movie"),
  Triple("Trending this week", "/trending/all/week", null),
  Triple("Popular films", "/movie/popular?region=GB", "movie"),
  Triple("Popular TV", "/tv/popular", "tv"),
  Triple("New episodes this week", "/tv/on_the_air", "tv"),
  Triple("Top rated films", "/movie/top_rated", "movie"),
  Triple("Top rated TV", "/tv/top_rated", "tv"),
  Triple("Action", "/discover/movie?with_genres=28", "movie"),
  Triple("Comedy", "/discover/movie?with_genres=35", "movie"),
  Triple("Sci-fi", "/discover/movie?with_genres=878", "movie"),
  Triple("Thrillers", "/discover/movie?with_genres=53", "movie"),
  Triple("Horror", "/discover/movie?with_genres=27", "movie"),
  Triple("Crime TV", "/discover/tv?with_genres=80", "tv"),
  Triple("Animation", "/discover/movie?with_genres=16", "movie"),
  Triple("Family", "/discover/movie?with_genres=10751", "movie"),
  Triple("Documentaries", "/discover/movie?with_genres=99", "movie"),
)
