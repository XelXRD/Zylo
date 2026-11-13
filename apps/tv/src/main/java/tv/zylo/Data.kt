// HTTP, on-device storage and TMDB. No server: everything is called straight from the TV.
package tv.zylo

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

val UA = "Zylo/${BuildConfig.VERSION_NAME} (Android TV)"

class HttpError(val code: Int, message: String) : Exception(message)

// OkHttp, as Stremio and Nuvio use: Cloudflare-fronted addon hosts (ElfHosted, Torrentio…) answer 403 to Android's
// built-in HttpURLConnection even when a browser on the same connection gets through. It also does PATCH natively.
private val client = OkHttpClient.Builder().followRedirects(true).build()

/** A download as it arrives (for big files read while downloading, like TV guides and app updates). */
fun openStream(url: String): java.io.InputStream {
  val r = client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build().newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute()
  if (!r.isSuccessful) { r.close(); throw HttpError(r.code, "${r.request.url.host} answered ${r.code}.") }
  return r.body!!.byteStream()
}

fun http(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null, type: String? = null, timeout: Int = 10_000, total: Long = 0): String {
  val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json, text/plain, */*")
  headers.forEach { (k, v) -> req.header(k, v) }
  val rb = body?.toRequestBody(type?.toMediaTypeOrNull()) ?: if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(null)
  val call = client.newBuilder().connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(total, TimeUnit.MILLISECONDS).build()
    .newCall(req.method(method, rb).build())
  call.execute().use { r ->
    val code = r.code
    val host = r.request.url.host
    // ponytail: reads the whole body; add a size cap if an addon ever sends something huge
    val text = r.body?.string() ?: ""
    if (code == 401) throw HttpError(code, "$host didn't accept the key (401).")
    // Say who refused: a Cloudflare block page and the addon itself need different fixes.
    val why = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim()?.take(80)
      ?: text.trim().take(80).takeIf { it.isNotEmpty() && !it.startsWith("<") }
    if (code == 403) throw HttpError(code, "$host refused the request (403" + (r.header("server")?.let { ", $it" } ?: "") + ")." + (why?.let { " It said: \"$it\"" } ?: ""))
    if (code >= 400) throw HttpError(code, "$host answered $code.")
    return text
  }
}

fun getJson(url: String, headers: Map<String, String> = emptyMap()) = JSONObject(http(url, headers = headers))

/**
 * TMDB's answers, kept on disk. A page opens from the last answer at once; one older than [FRESH] is fetched again
 * behind the scenes, ready for next time. Only successful answers are kept, so a bad key or an outage is never cached.
 */
object TmdbDisk {
  var dir: java.io.File? = null
  private const val FRESH = 3 * 3_600_000L
  private const val KEEP = 14 * 86_400_000L
  private val refreshing = ConcurrentHashMap.newKeySet<String>()
  private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

  private fun file(url: String) = dir?.let { d ->
    java.io.File(d, java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })
  }

  private fun save(f: java.io.File, text: String) {
    val tmp = java.io.File(f.path + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
  }

  fun get(url: String, fetch: () -> String): String {
    val f = file(url) ?: return fetch()
    val kept = if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    if (kept != null) {
      if (System.currentTimeMillis() - f.lastModified() > FRESH && refreshing.add(url)) io.launch {
        try { runCatching { save(f, fetch()) } } finally { refreshing.remove(url) }
      }
      return kept
    }
    return fetch().also { text -> runCatching { save(f, text) } }
  }

  /** Answers nobody has asked for in [KEEP]. ponytail: age-based only; add a size cap if the folder ever grows large. */
  fun prune() = dir?.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > KEEP }?.forEach { it.delete() }
}
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

/** [kids]: a kids profile's highest allowed rating, as an index into Ratings.LEVELS; null for a normal profile. */
data class Profile(val id: String, val name: String, val color: Int, val kids: Int? = null)

/** Age ratings, lowest first (UK, with US film and TV equivalents). Kids profiles hide anything above their level and anything unrated. */
object Ratings {
  val LEVELS = listOf("U", "PG", "12", "15")
  private val LEVEL = mapOf(
    "U" to 0, "G" to 0, "TV-Y" to 0, "TV-G" to 0,
    "PG" to 1, "TV-Y7" to 1, "TV-Y7-FV" to 1, "TV-PG" to 1,
    "12" to 2, "12A" to 2, "PG-13" to 2, "TV-14" to 2,
    "15" to 3, "R" to 3,
    "18" to 4, "R18" to 4, "NC-17" to 4, "TV-MA" to 4,
  )
  fun level(cert: String?) = cert?.trim()?.uppercase()?.let(LEVEL::get)

  // ponytail: process-wide; set from the active profile at launch (switching profile recreates the activity)
  @Volatile var max: Int? = null
  fun allows(cert: String?) = max.let { m -> m == null || (level(cert) ?: Int.MAX_VALUE) <= m }
}

/**
 * Settings and data, on the TV. Two layers:
 * - global ("zylo"): TMDB/fanart/debrid keys, addons, media servers, app-wide preferences, the profile list;
 * - per profile: watch progress, My List, Trakt / AniList / MyAnimeList sign-ins, remembered sources, subtitle language.
 * The first profile ("main") uses the global file too, so data from before profiles existed simply stays where it was.
 */
class Store(private val open: (String) -> SharedPreferences) {
  constructor(ctx: Context) : this({ ctx.getSharedPreferences(it, Context.MODE_PRIVATE) })

  private val g = open("zylo")
  val profileId: String = g.getString("profile", "main")!!.let { id -> if (id == "main" || profilesIn(g).any { it.id == id }) id else "main" }
  private val p = if (profileId == "main") g else open("zylo_p_$profileId")

  /** Which layer a key lives in. App credentials (…_client) stay global so each profile only signs in. */
  private fun layer(k: String) = if (!k.endsWith("_client") && PROFILE_KEYS.any { k.startsWith(it) }) p else g

  fun put(k: String, v: String) = layer(k).edit().putString(k, v).apply()
  fun str(k: String) = layer(k).getString(k, "")!!
  fun long(k: String) = layer(k).getLong(k, 0)
  fun putLong(k: String, v: Long) = layer(k).edit().putLong(k, v).apply()

  /** Installed Syncler Express packages (shared by every profile). */
  var syncler: List<SynclerPackage>
    get() = runCatching { JSONArray(g.getString("syncler", "[]")).objects().map { SynclerPackage(it.getString("url"), it.optString("name"), it.getJSONObject("providers")) } }.getOrDefault(emptyList())
    set(v) = put("syncler", JSONArray(v.map { it.toJson() }).toString())

  // --- Profiles ---
  val profiles: List<Profile> get() = profilesIn(g)
  val profile: Profile get() = profiles.find { it.id == profileId } ?: profiles.first()

  fun addProfile(name: String): Profile {
    val list = profiles
    val made = Profile("p${System.currentTimeMillis()}", name.trim().ifEmpty { "Profile ${list.size + 1}" }, AVATARS[list.size % AVATARS.size])
    saveProfiles(list + made)
    return made
  }

  fun setKids(id: String, level: Int?) = saveProfiles(profiles.map { if (it.id == id) it.copy(kids = level) else it })

  fun renameProfile(id: String, name: String) = saveProfiles(profiles.map { if (it.id == id && name.isNotBlank()) it.copy(name = name.trim()) else it })

  /** Removes the profile and everything it saved. The main profile and the one in use can't be removed. */
  fun deleteProfile(id: String) {
    if (id == "main" || id == profileId) return
    open("zylo_p_$id").edit().clear().apply()
    saveProfiles(profiles.filter { it.id != id })
  }

  /** Takes effect when the screen is rebuilt (MainActivity recreates itself). */
  fun switchProfile(id: String) = g.edit().putString("profile", id).commit()

  private fun saveProfiles(list: List<Profile>) =
    g.edit().putString("profiles", JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("color", it.color).apply { it.kids?.let { k -> put("kids", k) } } }).toString()).apply()

  companion object {
    private val PROFILE_KEYS = listOf("progress", "mylist", "trakt_", "status_trakt", "anilist_", "status_anilist", "mal_", "status_mal", "src:", "sublang", "audiolang", "anime_dub", "thumbs", "outbox", "synclog", "xc_favs", "reminders", "lit:")
    val AVATARS = listOf(0xFF9A4155, 0xFF2F6F73, 0xFFB7791F, 0xFF3D5A80, 0xFF6B8F71, 0xFF8C5A3C).map { it.toInt() }

    private fun profilesIn(g: SharedPreferences): List<Profile> =
      JSONArray(g.getString("profiles", "[]")).objects().map { Profile(it.getString("id"), it.optString("name"), it.optInt("color", AVATARS[0]), if (it.has("kids")) it.optInt("kids") else null) }
        .ifEmpty { listOf(Profile("main", "Me", AVATARS[0])) }
  }

  // ponytail: keys live in app-private storage, not Keystore-encrypted; add that if the Shield is shared or rooted
  var tmdb: String
    get() = g.getString("tmdb", "")!!
    set(v) = put("tmdb", v.trim())

  fun debrid(service: String) = g.getString("debrid_$service", "")!!
  fun setDebrid(service: String, key: String) = put("debrid_$service", key.trim())

  /** "ok", "fail:<why>", or "" (never tested). Only "ok" may show as Connected. */
  fun status(service: String) = str("status_$service")
  fun setStatus(service: String, v: String) = put("status_$service", v)

  private val previewsState = mutableStateOf(g.getBoolean("previews", true))
  var previews: Boolean
    get() = previewsState.value
    set(v) { previewsState.value = v; g.edit().putBoolean("previews", v).apply() }

  private val trailerSoundState = mutableStateOf(g.getBoolean("trailerSound", false))
  var trailerSound: Boolean
    get() = trailerSoundState.value
    set(v) { trailerSoundState.value = v; g.edit().putBoolean("trailerSound", v).apply() }

  private val reduceMotionState = mutableStateOf(g.getBoolean("reduceMotion", false))
  var reduceMotion: Boolean
    get() = reduceMotionState.value
    set(v) { reduceMotionState.value = v; g.edit().putBoolean("reduceMotion", v).apply() }

  /** Highest quality Play picks on its own ("4K", "1080p", "720p"). Higher ones stay listed in Choose Source. */
  var maxQuality: String
    get() = g.getString("maxQuality", "4K")!!
    set(v) = g.edit().putString("maxQuality", v).apply()

  /** Largest file Play picks on its own, in GB; 0 = no limit. Bigger ones stay listed in Choose Source. */
  var maxSizeGb: Int
    get() = g.getInt("maxSizeGb", 0)
    set(v) = g.edit().putInt("maxSizeGb", v).apply()

  /** How Choose Source orders sources, and so which one Play tries first (see SourceSort). */
  var sourceSort: String
    get() = g.getString("sourceSort", SourceSort.BEST)!!
    set(v) = g.edit().putString("sourceSort", v).apply()

  /** Preferred subtitle language (ISO 639-1, e.g. "en"); "" = subtitles off unless the file forces them. */
  /** Audio language: set in Settings, else the TV's language; null = the file's own default ("file"). */
  val audioLang: String? get() = when (val v = str("audiolang")) { "file" -> null; "" -> java.util.Locale.getDefault().language.ifEmpty { "en" }; else -> v }

  /** Anime: dubbed releases first and audio in your language; off = the original Japanese with subtitles. */
  var animeDubs: Boolean
    get() = str("anime_dub") == "1"
    set(v) = put("anime_dub", if (v) "1" else "")

  var subLang: String
    get() = str("sublang")
    set(v) = put("sublang", v)

  /** The source that last played well for this film or series (for a series: what the next episode should look like). */
  fun sourcePref(item: Item): JSONObject? = str("src:${item.key}").takeIf { it.isNotEmpty() }?.let { runCatching { JSONObject(it) }.getOrNull() }
  fun rememberSource(item: Item, pref: String) = put("src:${item.key}", pref)

  var addons: List<Addon>
    get() = JSONArray(g.getString("addons", "[]")).objects().map { Addon(it.getString("url"), it.getJSONObject("manifest")) }
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
    val headers = if (v3) emptyMap() else mapOf("Authorization" to "Bearer $key")
    return JSONObject(TmdbDisk.get(url + if (v3) "" else "#" + key.hashCode()) { http(url, headers = headers) })
  }

  fun list(path: String, type: String?) = safe(get(path).optJSONArray("results")?.objects()
    ?.filter { keepsAnime(path) || !animeSeries(it, type) }?.mapNotNull { item(it, type) }?.distinctBy { it.key }.orEmpty())

  /** On a kids profile, only titles rated at or below its level. Every list goes through here, so rows, search and browse all agree. */
  fun safe(items: List<Item>): List<Item> = if (Ratings.max == null) items else runBlocking(Dispatchers.IO) {
    val gate = Semaphore(8)
    items.map { i -> async { gate.withPermit { i.takeIf { Ratings.allows(rating(i)) } } } }.awaitAll().filterNotNull()
  }

  /** A title's age rating (UK, else US), cached. Empty when it has none. */
  fun rating(item: Item): String = ratings.getOrPut(item.key) {
    val part = if (item.type == "movie") "release_dates" else "content_ratings"
    runCatching { certification(JSONObject().put(part, get("/${item.type}/${item.id}/$part"))) }.getOrNull() ?: ""
  }

  /** Everything a detail page or preview needs, in one request. Cached for the session. */
  fun details(type: String, id: Int): JSONObject = cache.getOrPut("$type:$id") {
    val extra = if (type == "movie") "release_dates" else "content_ratings"
    get("/$type/$id?append_to_response=external_ids,videos,credits,recommendations,images,keywords,$extra&include_video_language=en,null&include_image_language=en,null")
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
    private val ratings = ConcurrentHashMap<String, String>()
    private val failed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun cached(item: Item): JSONObject? = cache["${item.type}:${item.id}"]

    /** Anime series (Japanese animation) belong in the Anime section only (Alex, 8 Nov): kept out of other TV lists. */
    fun animeSeries(o: JSONObject, type: String?) = (type ?: o.optString("media_type")) == "tv" && o.optString("original_language") == "ja" &&
      o.optJSONArray("genre_ids")?.let { g -> (0 until g.length()).any { g.optInt(it) == 16 } } == true

    /** Lists that may show anime series: the Anime section and anime quiz (Japanese-only queries), search, "more like this". */
    fun keepsAnime(path: String) = "with_origin_country=JP" in path || "with_original_language=ja" in path || path.startsWith("/search/") ||
      "/recommendations" in path || "/similar" in path

    fun item(o: JSONObject, type: String?): Item? {
      val t = type ?: o.optString("media_type")
      if (t != "movie" && t != "tv") return null
      val movie = t == "movie"
      return Item(
        t, o.optInt("id"), o.optString(if (movie) "title" else "name"), o.str("poster_path") ?: return null, o.str("backdrop_path"), o.optString("overview"),
        o.optString(if (movie) "release_date" else "first_air_date").take(4), o.optDouble("vote_average", 0.0),
      )
    }

    /** Videos TMDB files as trailers that aren't one: countdowns, reactions, recaps, spots and the like. */
    private val NOT_A_TRAILER = Regex("countdown|reaction|recap|featurette|behind the scenes|clip|interview|review|breakdown|explained|tv spot|promo|sneak peek|first look|announcement", RegexOption.IGNORE_CASE)

    /** YouTube key of the best trailer: a real trailer before a teaser, "Official Trailer" first, then official uploads. */
    fun trailer(d: JSONObject?): String? = d?.optJSONObject("videos")?.optJSONArray("results")?.objects()
      ?.filter { it.optString("site") == "YouTube" && it.str("key") != null && it.optString("type") in setOf("Trailer", "Teaser") && !NOT_A_TRAILER.containsMatchIn(it.optString("name")) }
      ?.sortedWith(compareBy({ it.optString("type") != "Trailer" }, { !it.optString("name").contains("official trailer", true) }, { !it.optBoolean("official") }))
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

/** A kids profile's Home: family and children's titles up to its rating (lists are filtered again in Tmdb.safe). */
fun kidsRows(level: Int): List<RowSpec> {
  val cert = "certification_country=GB&certification.lte=${Ratings.LEVELS[level]}&sort_by=popularity.desc"
  return listOf(
    RowSpec("Family Films", "/discover/movie?with_genres=10751&$cert", "movie"),
    RowSpec("Kids' Series", "/discover/tv?with_genres=10762&sort_by=popularity.desc", "tv"),
    RowSpec("Animated Films", "/discover/movie?with_genres=16&$cert", "movie"),
    RowSpec("Family Series", "/discover/tv?with_genres=10751&sort_by=popularity.desc", "tv"),
    RowSpec("Animated Series", "/discover/tv?with_genres=16&with_original_language=en&sort_by=popularity.desc", "tv"),
    RowSpec("Family Favourites", "/discover/movie?with_genres=10751&vote_count.gte=500&certification_country=GB&certification.lte=${Ratings.LEVELS[level]}&sort_by=vote_average.desc", "movie"),
  )
}

fun homeRows(store: Store): List<RowSpec> {
  store.profile.kids?.let { return kidsRows(it) }
  val last = store.watches().maxByOrNull { it.at }?.item
  val trakt = if (Trakt.connected(store)) listOf(
    RowSpec("Your Trakt Watchlist", "", null) { Trakt.items(it, Trakt.watchlist(store)) },
    RowSpec("Recommended For You", "", null) { Trakt.items(it, Trakt.recommendations(store)) },
  ) else emptyList()
  val popular = if (store.str("trakt_client").isNotEmpty()) listOf(RowSpec("Popular on Trakt", "", null) { Trakt.items(it, Trakt.trending(store)) }) else emptyList()
  return trakt + listOf(
    RowSpec("Trending Movies", "/trending/movie/week", "movie"),
    RowSpec("Popular Series", "/tv/popular", "tv"),
  ) + listOfNotNull(
    last?.let { RowSpec("Because You Watched ${it.title}", "/${it.type}/${it.id}/recommendations", null) },
    Thumbs.lastLiked(store)?.takeIf { it.key != last?.key }?.let { RowSpec("Because You Liked ${it.title}", "/${it.type}/${it.id}/recommendations", null) },
  ) + moodRows(store) + listOf(
    RowSpec("In Cinemas Now", "/movie/now_playing?region=GB", "movie"),
    RowSpec("New Episodes This Week", "/tv/on_the_air", "tv"),
    RowSpec("Top Rated Films", "/movie/top_rated", "movie"),
  ) + popular
}

/** Rows by feel rather than genre. "Something Different" is a new genre each launch. */
fun moodRows(store: Store): List<RowSpec> {
  val different = listOf(37 to "Westerns", 10752 to "War Films", 36 to "History", 10402 to "Musicals & Music", 9648 to "Mysteries", 14 to "Fantasy", 99 to "Documentaries", 16 to "Animated Films", 10749 to "Romance").random()
  val comfort = RowSpec("Comfort Rewatch", "", null) { t ->
    // Things you've finished before: films, and series you last watched over three months ago.
    val films = (store.traktWatched().filter { it.startsWith("movie:") } + store.watches().filter { it.finished && it.season == null }.map { "movie:${it.item.id}" })
      .mapNotNull { it.removePrefix("movie:").toIntOrNull() }.distinct().map { Ref("movie", it) }
    val shows = Trakt.recentShows(store).filter { System.currentTimeMillis() - it.second > 90L * 86_400_000 }.map { Ref("tv", it.first) }
    Trakt.items(t, (films + shows).shuffled().take(24), 20)
  }
  return listOf(
    comfort,
    RowSpec("Under 90 Minutes", "/discover/movie?with_runtime.gte=60&with_runtime.lte=90&vote_count.gte=500&sort_by=popularity.desc", "movie"),
    RowSpec("Hidden Gems", "/discover/movie?vote_average.gte=7.4&vote_count.gte=150&vote_count.lte=1500&sort_by=vote_average.desc", "movie"),
    RowSpec("Something Different: ${different.second}", "/discover/movie?with_genres=${different.first}&vote_average.gte=7&vote_count.gte=300&sort_by=popularity.desc", "movie"),
  )
}

// Genre rows under Discover's modes. Trending and top-rated live on Home, so they aren't repeated here.
val DISCOVER_ROWS = listOf(
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

// --- Up Next ---------------------------------------------------------------------------------------

class NextEp(val season: Int, val episode: Int, val name: String, val still: String?, val airDate: String? = null)

/**
 * The episode after S[season]E[episode]: the next one in the season, else the first of the next season (specials,
 * season 0, are skipped). Nothing if it hasn't aired yet. today: "yyyy-MM-dd".
 */
fun nextEpisode(episodesOf: (Int) -> List<JSONObject>, seasons: List<Int>, season: Int, episode: Int, today: String): NextEp? {
  fun aired(e: JSONObject) = e.str("air_date")?.let { it <= today } ?: false
  fun of(s: Int, e: JSONObject) = if (aired(e)) NextEp(s, e.optInt("episode_number"), e.optString("name"), e.str("still_path"), e.str("air_date")) else null
  episodesOf(season).find { it.optInt("episode_number") == episode + 1 }?.let { return of(season, it) }
  val following = seasons.filter { it > season && it > 0 }.minOrNull() ?: return null
  return episodesOf(following).minByOrNull { it.optInt("episode_number") }?.let { of(following, it) }
}

fun nextEpisode(t: Tmdb, item: Item, season: Int, episode: Int): NextEp? = nextEpisode(
  { s -> t.season(item.id, s).optJSONArray("episodes")?.objects().orEmpty() },
  t.details("tv", item.id).optJSONArray("seasons")?.objects()?.map { it.optInt("season_number") }.orEmpty(),
  season, episode, java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date()),
)
