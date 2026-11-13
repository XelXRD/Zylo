// Stremio addons → playable sources, with debrid. Ported from apps/api/src/stremio.ts, debrid.ts and playback.ts.
// Spec: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
package tv.zylo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONException
import java.net.URL
import org.json.JSONObject
import kotlin.math.roundToInt

private val I = RegexOption.IGNORE_CASE

class Addon(val url: String, val manifest: JSONObject) {
  val name: String get() = manifest.optString("name", "Addon")

  /** The manifest link minus its file name (configured addons keep their config path). */
  val base: String get() = url.substringBefore('?').substringBefore('#').substringBeforeLast('/')

  /** Does this addon answer `resource` requests for this type and id? */
  fun supports(resource: String, type: String, id: String): Boolean {
    val res = manifest.optJSONArray("resources") ?: return false
    for (i in 0 until res.length()) {
      val o = res.opt(i) as? JSONObject
      if ((o?.optString("name") ?: res.opt(i) as? String) != resource) continue
      val types = strings(o?.optJSONArray("types") ?: manifest.optJSONArray("types"))
      val prefixes = strings(o?.optJSONArray("idPrefixes") ?: manifest.optJSONArray("idPrefixes"))
      return type in types && (prefixes.isEmpty() || prefixes.any { id.startsWith(it) })
    }
    return false
  }

  companion object {
    fun load(input: String): Addon {
      var url = input.replace(Regex("\\s"), "").replace(Regex("^stremio://", I), "https://") // pasted links can pick up line breaks
      if (!Regex("^https?://", I).containsMatchIn(url)) throw Exception("That isn't a web address.")
      // People often paste the addon's home or /configure page rather than its manifest link.
      if (!url.substringBefore('?').endsWith("manifest.json")) url = url.substringBefore('?').removeSuffix("/").removeSuffix("/configure") + "/manifest.json"
      val m = try { getJson(url) } catch (e: JSONException) { throw Exception("That address didn't return an addon manifest.") } catch (e: HttpError) {
        throw Exception(if (e.code == 403) "${e.message} Tried: $url" else e.message)
      }
      if (m.str("id") == null || m.str("name") == null || m.str("version") == null || (m.optJSONArray("resources")?.length() ?: 0) == 0 || m.optJSONArray("types") == null)
        throw Exception("That link works, but it isn't a Stremio addon manifest.")
      return Addon(url, m)
    }
  }
}

data class Source(
  val addon: String,
  val label: String,
  val detail: String,
  val quality: String?,
  val hdr: String,
  val size: String?,
  val filename: String?,
  val url: String? = null,
  val headers: Map<String, String> = emptyMap(),
  val infoHash: String? = null,
  val fileIdx: Int? = null,
  val debrid: String? = null, // service that will stream this torrent
  val cached: Boolean = false, // that service said it's ready (Real-Debrid can't say)
  val library: String? = null, // "plex" / "jellyfin": a file on your own media server
) {
  /** What to remember about a source that played: enough to find it again, or one like it for the next episode. */
  fun signature(): String = JSONObject().put("addon", addon).put("quality", quality ?: "").put("debrid", debrid ?: "").put("library", library ?: "")
    .put("hash", infoHash ?: "").put("filename", filename ?: "").toString()

  /** 0 = the very source remembered, 1 = the same kind (same addon/server, quality and service), 2 = anything else. */
  fun likeness(p: JSONObject?): Int {
    if (p == null) return 2
    if ((infoHash != null && infoHash == p.str("hash")) || (filename != null && filename == p.str("filename"))) return 0
    val sameKind = if (library != null) library == p.str("library") else addon == p.optString("addon") && (debrid ?: "") == p.optString("debrid")
    return if (sameKind && (quality ?: "") == p.optString("quality")) 1 else 2
  }
}

private val QUALITY = listOf(Regex("\\b(2160p|4k|uhd)\\b", I) to "4K", Regex("\\b1080p\\b", I) to "1080p", Regex("\\b720p\\b", I) to "720p", Regex("\\b(480p|576p|sd)\\b", I) to "480p")
private val QUALITIES = QUALITY.map { it.second }

/** "HDR", "DV" or both, from a release name or stream description. */
fun hdrOf(text: String) = listOfNotNull("HDR".takeIf { Regex("\\bHDR", I).containsMatchIn(text) }, "DV".takeIf { Regex("\\bDV\\b|dolby.?vision", I).containsMatchIn(text) }).joinToString(" ")

fun source(raw: JSONObject, addon: String): Source? {
  val hints = raw.optJSONObject("behaviorHints") ?: JSONObject()
  val label = raw.str("name") ?: addon
  val detail = raw.str("description") ?: raw.str("title") ?: ""
  val filename = hints.str("filename")
  val text = listOfNotNull(label, detail, filename).joinToString(" ")
  val base = Source(
    addon, label, detail,
    QUALITY.firstOrNull { it.first.containsMatchIn(text) }?.second,
    hdrOf(text),
    Regex("(\\d+(?:[.,]\\d+)?)\\s?(GB|MB|GiB|MiB)\\b", I).find(text)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" },
    filename,
  )
  val url = raw.str("url")
  if (url != null && Regex("^https?://", I).containsMatchIn(url)) {
    val h = hints.optJSONObject("proxyHeaders")?.optJSONObject("request")
    return base.copy(url = url, headers = h?.keys()?.asSequence()?.associateWith { h.optString(it) }.orEmpty())
  }
  val hash = raw.str("infoHash")
  if (hash != null && Regex("^[a-f0-9]{40}$", I).matches(hash)) return base.copy(infoHash = hash.lowercase(), fileIdx = if (raw.has("fileIdx")) raw.optInt("fileIdx") else null)
  return null // YouTube, websites, usenet, archives: nothing this player opens
}

fun qualityOf(text: String) = QUALITY.firstOrNull { it.first.containsMatchIn(text) }?.second

val QUALITY_STEPS = QUALITIES.take(3) // the choices for Settings > Playback > Maximum quality

private fun qualityIndex(q: String?) = QUALITIES.indexOf(q).let { if (it < 0) 9 else it }

/** The source's file size in GB, from the "1.4 GB" / "700 MB" the addon showed; null when it didn't say. */
fun gbOf(s: Source): Double? = s.size?.let { Regex("""([\d.,]+)\s?(GB|MB|GiB|MiB)""", I).find(it) }?.let { m ->
  val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
  if (m.groupValues[2].startsWith("M", ignoreCase = true)) n / 1024 else n
}

/** Settings > Playback > Maximum file size: the choices, in GB (0 = no limit). */
val SIZE_STEPS = listOf(0, 2, 5, 10, 20, 40)
fun sizeLabel(gb: Int) = if (gb == 0) "No limit" else "$gb GB"
fun overSize(s: Source, maxGb: Int) = maxGb > 0 && (gbOf(s) ?: 0.0) > maxGb

/** Settings > Playback > Sort sources. */
object SourceSort {
  const val BEST = "best"
  val CHOICES = listOf(BEST to "Best match", "quality" to "Highest quality", "largest" to "Largest first", "smallest" to "Smallest first")
  fun label(v: String) = CHOICES.firstOrNull { it.first == v }?.second ?: CHOICES[0].second
}

/**
 * Order for Choose Source, and so for Play. Always first: under your quality and size limits (anything over stays
 * listed, last, and is never auto-played), then dubs if you prefer them, then your own media servers, then what's
 * ready now (direct links, cached torrents). Within that, [sort]: Best match puts the source you last played (or one
 * like it) first, then the best quality; the others order by quality or by file size, unknown sizes last.
 */
fun rank(all: List<Source>, cap: String = "4K", pref: JSONObject? = null, dubs: Boolean = false, sort: String = SourceSort.BEST, maxGb: Int = 0) =
  all.filter { it.url != null || it.debrid != null }.sortedWith(
    compareBy<Source>(
      { overCap(it, cap) || overSize(it, maxGb) },
      { dubs && !isDub(it) }, // anime with "prefer dubs" on: English-dubbed / dual-audio releases first
    ).then(
      if (sort == SourceSort.BEST) compareBy({ it.likeness(pref) }, { it.library == null }, { it.debrid != null && !it.cached }, { qualityIndex(it.quality) })
      else compareBy<Source>({ it.library == null }, { it.debrid != null && !it.cached }).then(when (sort) {
        "largest" -> compareBy({ -(gbOf(it) ?: -1.0) }, { qualityIndex(it.quality) })
        "smallest" -> compareBy({ gbOf(it) ?: Double.MAX_VALUE }, { qualityIndex(it.quality) })
        else -> compareBy({ qualityIndex(it.quality) }, { -(gbOf(it) ?: 0.0) })
      }),
    ),
  )

private val DUB = Regex("""\b(dub|dubbed|dual[ ._-]?audio|multi[ ._-]?audio|eng(lish)?[ ._-]?dub)\b""", RegexOption.IGNORE_CASE)
fun isDub(s: Source) = DUB.containsMatchIn(listOfNotNull(s.label, s.detail, s.filename).joinToString(" "))

/** Japanese animation (TMDB's Animation genre, Japanese original): what Zylo treats as anime. */
fun isAnime(d: JSONObject?) = d != null && d.optString("original_language") == "ja" && d.optJSONArray("genres")?.objects()?.any { it.optInt("id") == 16 } == true

fun overCap(s: Source, cap: String) = qualityIndex(s.quality) < QUALITIES.indexOf(cap).coerceAtLeast(0)

/** Over either limit in Settings > Playback: listed in Choose Source, never picked by Play on its own. */
fun overLimit(s: Source, store: Store) = overCap(s, store.maxQuality) || overSize(s, store.maxSizeGb)

class Found(val sources: List<Source>, val errors: List<String>, val waiting: Int, val otherTitles: Int = 0)

private val TITLE_END = Regex("\\b(s\\d{1,2}\\s?e\\d{1,4}|s\\d{1,2}|\\d{1,2}x\\d{2,3}|(19|20)\\d{2}|2160p|1080p|720p|576p|480p|4k|uhd|complete|season|series|episode|ep\\d+)\\b")
private val SMALL_WORDS = setOf("the", "a", "an", "of", "and", "in", "on", "to", "for", "la", "le", "les", "el", "der", "die", "das")
private fun words(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** The title a release name starts with ("Twin.Peaks.S01E01.2160p…" → "twin peaks"), or null when it doesn't say. */
fun releaseTitle(name: String): String? {
  val w = words(name)
  val end = TITLE_END.find(w)?.range?.first ?: return null
  return w.substring(0, end).trim().takeIf { it.isNotEmpty() }
}

/**
 * Whether a file is plainly for some other title. Addons match on episode numbers too, so "S01E01" of a different show
 * can turn up. Only the file's own leading title counts, against every name TMDB knows the title by; anything unclear
 * (no title in the name, very short titles) is kept.
 */
fun otherTitle(src: Source, names: List<String>): Boolean {
  if (src.library != null) return false
  val file = releaseTitle(src.filename ?: return false) ?: return false
  val flat = file.replace(" ", "")
  return names.none { n ->
    val w = words(n)
    val key = w.split(' ').filter { it.length >= 3 && it !in SMALL_WORDS || it.all(Char::isDigit) }
    key.isEmpty() || w.replace(" ", "").let { it.isNotEmpty() && (flat.contains(it) || it.contains(flat)) } || key.any { k -> file.split(' ').contains(k) }
  }
}

/**
 * Every place this video can play from: your media servers (Plex, Jellyfin) and every addon that serves streams.
 * videoId: tt123 for films, tt123:1:2 for episodes.
 */
suspend fun findSources(store: Store, item: Item, season: Int?, episode: Int?, videoId: String): Found = coroutineScope {
  val stype = if (item.type == "tv") "series" else "movie"
  val addons = store.addons.filter { it.supports("stream", stype, videoId) }.map { a ->
    async(Dispatchers.IO) {
      runCatching { getJson("${a.base}/stream/$stype/${enc(videoId)}.json").optJSONArray("streams")?.objects()?.mapNotNull { source(it, a.name) }.orEmpty() }
        .fold({ it to null }, { emptyList<Source>() to "${a.name}: " + plainError(it, a.name) })
    }
  }
  val servers = Library.connected(store).map { server ->
    async(Dispatchers.IO) { runCatching { server.find(item, season, episode) }.fold({ it to null }, { emptyList<Source>() to "${server.name}: " + plainError(it, server.name) }) }
  }
  // Syncler Express packages: torrent-site searches, played through the debrid services.
  val syncler = async(Dispatchers.IO) {
    Syncler.sources(store, Query(item.title, item.year, videoId.substringBefore(':').takeIf { it.startsWith("tt") }, season, episode))
  }
  val results = (servers + addons + syncler).awaitAll()
  val everything = withDebrid(store, results.flatMap { it.first })
  val details = Tmdb(store.tmdb).preview(item)
  // Every name the title goes by: its own, the original, and TMDB's alternatives (anime fansubs use romaji, for instance).
  val names = (listOf(item.title, details?.optString("original_title").orEmpty(), details?.optString("original_name").orEmpty()) +
    (details?.optJSONObject("alternative_titles")?.let { it.optJSONArray("titles") ?: it.optJSONArray("results") }?.objects()?.map { it.optString("title") } ?: emptyList()))
    .filter { it.isNotBlank() }.distinct()
  val other = everything.filter { otherTitle(it, names) }
  // If every file looks wrong, the matcher is what's wrong: show them all.
  val all = if (other.size < everything.size) everything - other.toSet() else everything
  val dubs = store.animeDubs && isAnime(details)
  Found(rank(all, store.maxQuality, store.sourcePref(item), dubs, store.sourceSort, store.maxSizeGb), results.mapNotNull { it.second }, all.count { it.infoHash != null && it.debrid == null }, everything.size - all.size)
}

// --- Subtitles (the addon "subtitles" resource) -------------------------------------------------

data class Sub(val url: String, val lang: String, val label: String)

/** Subtitles from every addon that offers them, your language first. Embedded subtitles in the file show up too. */
suspend fun findSubtitles(store: Store, type: String, videoId: String): List<Sub> = coroutineScope {
  val stype = if (type == "tv") "series" else "movie"
  val got = store.addons.filter { it.supports("subtitles", stype, videoId) }.map { a ->
    async(Dispatchers.IO) {
      runCatching {
        getJson("${a.base}/subtitles/$stype/${enc(videoId)}.json").optJSONArray("subtitles")?.objects()?.mapNotNull { o ->
          val url = o.str("url")?.takeIf { Regex("^https?://", I).containsMatchIn(it) } ?: return@mapNotNull null
          val lang = o.optString("lang").ifEmpty { "und" }
          Sub(url, lang, "${languageName(lang)} · ${a.name}")
        }.orEmpty()
      }.getOrDefault(emptyList())
    }
  }.awaitAll().flatten().distinctBy { it.url }
  val mine = store.subLang
  got.sortedBy { if (mine.isNotEmpty() && sameLanguage(it.lang, mine)) 0 else 1 }.take(40) // ponytail: 40 tracks; enough to choose from, light on memory
}

fun languageName(code: String): String = runCatching { java.util.Locale(code).displayLanguage }.getOrNull()?.takeIf { it.isNotBlank() && it != code } ?: code

/** "eng" and "en" are the same language. */
fun sameLanguage(a: String, b: String) = a.equals(b, true) || runCatching {
  java.util.Locale(a).isO3Language.equals(java.util.Locale(b).isO3Language, true)
}.getOrDefault(false)

// --- Debrid ------------------------------------------------------------------------------

val SERVICES = linkedMapOf("torbox" to "TorBox", "realdebrid" to "Real-Debrid", "premiumize" to "Premiumize")

class NotReady(message: String) : Exception(message)
data class Hint(val filename: String?, val fileIdx: Int?, val season: Int?, val episode: Int?)
class DebridFile(val name: String, val size: Long, val idx: Int? = null, val id: String = "", val link: String = "")

interface Debrid {
  /** Which hashes stream instantly; null = this service can't tell without adding them. */
  fun cached(hashes: List<String>): Set<String>?
  /** A direct link, right before playing (links expire). */
  fun resolve(hash: String, hint: Hint): String
}

fun debrid(service: String, key: String): Debrid = when (service) {
  "torbox" -> TorBox(key)
  "realdebrid" -> RealDebrid(key)
  else -> Premiumize(key)
}

/** Torrent sources a connected service can stream become playable, marked with that service. */
fun withDebrid(store: Store, all: List<Source>): List<Source> {
  val hashes = all.mapNotNull { it.infoHash }.distinct().take(100) // ponytail: first 100 torrents per title
  val services = SERVICES.keys.filter { store.debrid(it).isNotEmpty() }
  if (hashes.isEmpty() || services.isEmpty()) return all
  // A failed check (service down, key revoked) just means "don't use this service right now".
  val checks = services.map { s -> s to runCatching { debrid(s, store.debrid(s)).cached(hashes) } }
  return all.map { o ->
    val h = o.infoHash ?: return@map o
    val hit = checks.find { it.second.getOrNull()?.contains(h) == true } ?: checks.find { it.second.isSuccess && it.second.getOrNull() == null }
    if (hit == null) o else o.copy(debrid = hit.first, cached = hit.second.getOrNull() != null)
  }
}

private val VIDEO = Regex("\\.(mkv|mp4|m4v|avi|mov|webm|wmv|flv|ts|m2ts|mpg|mpeg)$", I)
private fun magnet(hash: String) = "magnet:?xt=urn:btih:$hash"
private fun baseName(path: String) = path.substringAfterLast('/')

/** The file the addon meant: its file name, then its index, then the episode, then the biggest video. */
fun pickFile(files: List<DebridFile>, hint: Hint): DebridFile {
  val pool = files.filter { VIDEO.containsMatchIn(it.name) }.ifEmpty { files }
  if (pool.isEmpty()) throw NotReady("That torrent has no files.")
  val name = hint.filename?.lowercase()
  val ep = if (hint.season != null && hint.episode != null) Regex("(s0*${hint.season}[ ._-]*e0*${hint.episode}|\\b0*${hint.season}x0*${hint.episode})(?!\\d)", I) else null
  return name?.let { n -> pool.find { baseName(it.name).lowercase() == n } }
    ?: hint.fileIdx?.let { i -> pool.find { it.idx == i } }
    ?: ep?.let { r -> pool.filter { r.containsMatchIn(baseName(it.name)) }.maxByOrNull { it.size } }
    ?: pool.maxBy { it.size }
}

class TorBox(private val key: String) : Debrid {
  private fun call(path: String, method: String = "GET", body: ByteArray? = null, type: String? = null): Any? =
    JSONObject(http("https://api.torbox.app/v1/api$path", method, mapOf("Authorization" to "Bearer $key"), body, type)).opt("data")

  override fun cached(hashes: List<String>) =
    (call("/torrents/checkcached?format=list&list_files=false", "POST", JSONObject().put("hashes", JSONArray(hashes)).toString().toByteArray(), "application/json") as? JSONArray)
      ?.objects()?.map { it.optString("hash").lowercase() }?.toSet() ?: emptySet()

  override fun resolve(hash: String, hint: Hint): String {
    // Reuse it if it's already in the account (adding is rate-limited to 50 an hour).
    var t = (call("/torrents/mylist?bypass_cache=true") as? JSONArray)?.objects()?.find { it.optString("hash").lowercase() == hash }
    if (t == null) {
      val b = "zylo${System.nanoTime()}"
      val body = "--$b\r\nContent-Disposition: form-data; name=\"magnet\"\r\n\r\n${magnet(hash)}\r\n--$b--\r\n".toByteArray()
      val id = (call("/torrents/createtorrent", "POST", body, "multipart/form-data; boundary=$b") as JSONObject).getInt("torrent_id")
      t = call("/torrents/mylist?bypass_cache=true&id=$id") as JSONObject
    }
    val files = t.optJSONArray("files")?.objects().orEmpty()
    if (!t.optBoolean("download_present") || files.isEmpty())
      throw NotReady("TorBox doesn't have this yet. It's downloading it (${(t.optDouble("progress", 0.0) * 100).roundToInt()}%); try again when it's done.")
    val f = pickFile(files.map { DebridFile(it.optString("name"), it.optLong("size"), id = it.optString("id")) }, hint)
    return call("/torrents/requestdl?" + String(form("token" to key, "torrent_id" to t.optString("id"), "file_id" to f.id))) as String
  }
}

class RealDebrid(private val key: String) : Debrid {
  private val auth = mapOf("Authorization" to "Bearer $key")
  private val dead = setOf("magnet_error", "error", "virus", "dead")
  private fun get(path: String) = http("https://api.real-debrid.com/rest/1.0$path", headers = auth)
  private fun post(path: String, vararg f: Pair<String, String>) = http("https://api.real-debrid.com/rest/1.0$path", "POST", auth, form(*f), "application/x-www-form-urlencoded")
  private fun info(id: String) = JSONObject(get("/torrents/info/$id"))

  // Real-Debrid switched off its cache check in 2024: the only way to know is to add the torrent.
  override fun cached(hashes: List<String>): Set<String>? = null

  override fun resolve(hash: String, hint: Hint): String {
    // ponytail: looks at the newest 100 torrents only; older duplicates just get added again.
    val mine = JSONArray(get("/torrents?limit=100").ifBlank { "[]" }).objects()
    val id = mine.find { it.optString("hash").lowercase() == hash && it.optString("status") !in dead }?.optString("id")
      ?: JSONObject(post("/torrents/addMagnet", "magnet" to magnet(hash))).getString("id")
    var info = info(id)
    for (i in 0 until 8) {
      if (info.optString("status") != "magnet_conversion") break
      Thread.sleep(1000)
      info = info(id)
    }
    if (info.optString("status") == "waiting_files_selection") {
      // Every video file, so a season pack is ready for every episode.
      val files = info.optJSONArray("files")?.objects().orEmpty()
      post("/torrents/selectFiles/$id", "files" to files.filter { VIDEO.containsMatchIn(it.optString("path")) }.ifEmpty { files }.joinToString(",") { it.optString("id") })
      info = info(id)
    }
    val status = info.optString("status")
    if (status in dead) throw NotReady("Real-Debrid couldn't get this torrent (it may be dead or blocked).")
    if (status != "downloaded") throw NotReady("Real-Debrid doesn't have this yet. It's downloading it (${info.optInt("progress")}%); try again when it's done.")
    val links = info.optJSONArray("links")
    // links[] lines up with the selected files, in order.
    val selected = info.optJSONArray("files")?.objects().orEmpty().filter { it.optInt("selected") == 1 }
      .mapIndexed { i, f -> DebridFile(f.optString("path"), f.optLong("bytes"), f.optInt("id") - 1, link = links?.optString(i).orEmpty()) }
    return JSONObject(post("/unrestrict/link", "link" to pickFile(selected, hint).link)).getString("download")
  }
}

class Premiumize(private val key: String) : Debrid {
  // Premiumize answers 200 with status "error" for most failures.
  private fun call(path: String, vararg f: Pair<String, String>): JSONObject {
    val url = "https://www.premiumize.me/api$path${if ('?' in path) '&' else '?'}apikey=${enc(key)}"
    val r = JSONObject(if (f.isEmpty()) http(url) else http(url, "POST", body = form(*f), type = "application/x-www-form-urlencoded"))
    if (r.optString("status") != "success") throw HttpError(400, r.optString("message", "Premiumize said no."))
    return r
  }

  override fun cached(hashes: List<String>): Set<String> {
    val r = call("/cache/check?" + hashes.joinToString("&") { "items[]=$it" }).optJSONArray("response")
    return hashes.filterIndexed { i, _ -> r?.optBoolean(i) == true }.toSet()
  }

  override fun resolve(hash: String, hint: Hint): String {
    val content = try { call("/transfer/directdl", "src" to magnet(hash)).optJSONArray("content")?.objects().orEmpty() } catch (e: HttpError) { emptyList() }
    if (content.isEmpty()) throw NotReady("Premiumize doesn't have this cached.")
    return pickFile(content.map { DebridFile(it.optString("path"), it.optLong("size"), link = it.optString("link")) }, hint).link
  }
}

/** A real request with the key, so Settings only says Connected when it actually works. */
fun testService(service: String, key: String) {
  when (service) {
    "tmdb" -> Tmdb(key).get("/configuration")
    "torbox" -> http("https://api.torbox.app/v1/api/user/me", headers = mapOf("Authorization" to "Bearer $key"))
    "fanart" -> http("https://webservice.fanart.tv/v3/movies/603?api_key=${enc(key)}")
    "realdebrid" -> http("https://api.real-debrid.com/rest/1.0/user", headers = mapOf("Authorization" to "Bearer $key"))
    else -> JSONObject(http("https://www.premiumize.me/api/account/info?apikey=${enc(key)}")).let {
      if (it.optString("status") != "success") throw HttpError(401, it.optString("message", "Premiumize didn't accept the key."))
    }
  }
}
