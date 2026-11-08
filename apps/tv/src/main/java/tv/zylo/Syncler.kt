// Syncler 2 "Express" packages: JSON recipes for searching torrent sites (a vendor lists packages; a package points at
// a provider file; each provider says how to search one site and read its results). Results are torrent hashes, which
// play through the debrid services like addon torrents do. Kosmos packages are JavaScript programs: not supported.
package tv.zylo

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import kotlin.coroutines.resume

/** What to search for. imdb: the film's or the series' IMDb id. */
data class Query(val title: String, val year: String, val imdb: String?, val season: Int?, val episode: Int?)

/** An installed provider file: its name, where it came from, and its providers (one per site). */
class SynclerPackage(val url: String, val name: String, val providers: JSONObject) {
  val sites get() = providers.keys().asSequence().filter { it != "_manifest" }.mapNotNull { providers.optJSONObject(it) }.filter { it.optBoolean("enabled", true) }.toList()
  fun toJson(): JSONObject = JSONObject().put("url", url).put("name", name).put("providers", providers)
}

object Syncler {
  /** Install from any Syncler link: a vendor (its default packages), a package manifest, or a provider file. Returns what was installed. */
  fun install(store: Store, url: String, fetch: (String) -> String = { http(it) }): List<SynclerPackage> {
    val o = runCatching { JSONObject(fetch(url)) }.getOrElse { throw Exception(if (it is HttpError) it.message else "That link isn't a Syncler vendor, package or provider file.") }
    return when {
      o.has("packages") -> strings(o.optJSONObject("defaults")?.optJSONArray("packages"))
        .ifEmpty { o.optJSONArray("packages")?.objects()?.mapNotNull { it.str("manifest") }?.take(1).orEmpty() }
        .flatMap { install(store, it, fetch) }
      o.has("_manifest") -> listOf(save(store, SynclerPackage(url, o.getJSONObject("_manifest").optString("name", "Syncler"), o)))
      o.has("url") && o.has("type") -> {
        if (o.optString("type") != "express") throw Exception("${o.optString("name", "That package")} is a Kosmos package (JavaScript). Zylo runs Express packages only.")
        install(store, o.getString("url"), fetch)
      }
      else -> throw Exception("That link isn't a Syncler vendor, package or provider file.")
    }
  }

  private fun save(store: Store, p: SynclerPackage): SynclerPackage {
    store.syncler = store.syncler.filter { it.url != p.url } + p
    return p
  }

  /** Every installed site searched at once; returns the torrents found and a one-line note on how many sites answered. */
  suspend fun sources(store: Store, q: Query): Pair<List<Source>, String?> = coroutineScope {
    val packs = store.syncler
    if (packs.isEmpty()) return@coroutineScope emptyList<Source>() to null
    val gate = Semaphore(10)
    val jobs = packs.flatMap { p -> p.sites.map { site -> p to site } }
    val done = java.util.concurrent.ConcurrentLinkedQueue<Pair<SynclerPackage, List<Source>?>>()
    // At most 15 s in all, so a few slow sites never hold up the sources page; whatever answered by then is used.
    // Detached: network calls don't stop for a coroutine timeout, so the page doesn't wait on them (each has its own time limit).
    val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val all = jobs.map { (p, site) ->
      bg.async { done += p to gate.withPermit { runCatching { search(site, q, { http(it, timeout = 5_000, total = 8_000) }, Dom::rows) }.getOrNull() } }
    }
    withTimeoutOrNull(15_000) { all.awaitAll() }
    val runs = done.toList() // sites still going at the deadline count as not answering
    val found = runs.flatMap { it.second.orEmpty() }.distinctBy { it.infoHash }
    val note = packs.map { p -> "${p.name}: ${runs.count { it.first === p && it.second != null }} of ${p.sites.size} sites answered" }
      .joinToString("  ·  ").ifEmpty { null }
    found to note.takeIf { runs.count { it.second != null } < jobs.size }
  }

  /** One site. [dom] reads an HTML page with the provider's selectors (a WebView on the TV). Null when the site didn't answer. */
  suspend fun search(site: JSONObject, q: Query, fetch: (String) -> String, dom: suspend (String, JSONObject) -> List<JSONObject>): List<Source>? {
    val kinds = if (q.season == null) listOf("movie") else listOf("episode", "season") // season packs: the debrid service picks the episode out
    val bases = (listOfNotNull(site.str("base_url")) + strings(site.optJSONArray("fallback_urls"))).take(3) // ponytail: 3 mirrors; enough while the deadline is 15 s
    var answered = false
    val out = mutableListOf<Source>()
    for (kind in kinds) {
      val spec = site.optJSONObject(kind) ?: continue
      val template = spec.str("query") ?: continue
      for (kw in keywords(spec).mapNotNull { fill(it, site, q) }) {
        val path = template.replace("{query}", encode(kw, site.str("separator"))).let { fill(it, site, q, raw = true) } ?: continue
        val got = bases.firstNotNullOfOrNull { base -> runCatching { base to fetch(base.trimEnd('/') + path) }.getOrNull() } ?: continue
        answered = true
        val rows = if (site.optString("response_type") == "json") jsonRows(got.second, site.optJSONObject("json_format") ?: JSONObject())
          else dom(got.second, site.optJSONObject("html_parser") ?: continue)
        val found = rows.take(40).mapIndexedNotNull { i, r -> toSource(site, r, got.first, if (i < 8) fetch else null) }
        out += found
        if (found.isNotEmpty()) break // first keyword set that finds something, as Syncler does
      }
    }
    return if (answered) out.distinctBy { it.infoHash } else null
  }

  private fun keywords(spec: JSONObject): List<String> = when (val k = spec.opt("keywords")) {
    is JSONArray -> strings(k)
    is String -> listOf(k)
    else -> listOf("{title}")
  }

  /** Fills {title}, {year}, {imdbId}, {season}… Null when the search needs something this title doesn't have. */
  fun fill(text: String, site: JSONObject, q: Query, raw: Boolean = false): String? {
    var title = q.title
    site.optJSONObject("title_replacement")?.let { r -> r.keys().forEach { k -> title = title.replace(k, r.optString(k)) } }
    val s = q.season?.toString()?.padStart(2, '0')
    val e = q.episode?.toString()?.padStart(2, '0')
    val values = mapOf(
      "title" to title, "year" to q.year, "imdbId" to q.imdb, "showImdbId" to q.imdb,
      "season" to q.season?.toString(), "episode" to q.episode?.toString(),
      "seasonCode" to s?.let { "S$it" }, "episodeCode" to if (s != null && e != null) "S${s}E$e" else null,
    )
    var out = text
    for (m in Regex("\\{(\\w+)\\}").findAll(text)) {
      val name = m.groupValues[1]
      if (raw && name == "query") continue
      out = out.replace(m.value, values[name] ?: return null)
    }
    return out.trim()
  }

  private fun encode(kw: String, separator: String?) = enc(kw).let { if (separator != null) it.replace("+", separator) else it.replace("+", "%20") }

  /** Rows from a JSON answer: the results path (a.b.c, or the whole answer when it's a list), then any sub-results. */
  fun jsonRows(body: String, f: JSONObject): List<JSONObject> {
    val root = JSONTokener(body).nextValue()
    var at: Any? = root
    f.str("results")?.split('.')?.forEach { k -> at = (at as? JSONObject)?.opt(k) }
    val list = (at as? JSONArray)?.objects().orEmpty()
    val sub = f.str("sub_results") ?: return list.map { pick(it, null, f) }
    return list.flatMap { parent -> parent.optJSONArray(sub)?.objects().orEmpty().map { pick(it, parent, f) } }
  }

  private fun pick(o: JSONObject, parent: JSONObject?, f: JSONObject) = JSONObject().apply {
    for (field in listOf("title", "hash", "size", "url", "quality")) {
      val k = f.str(field) ?: continue
      if (field == "url") { put("url", k.replace("{hash}", o.optString(f.optString("hash")))); continue }
      (o.opt(k)?.takeIf { it != JSONObject.NULL } ?: parent?.opt(k)?.takeIf { it != JSONObject.NULL })?.let { put(field, it.toString()) }
    }
  }

  private fun toSource(site: JSONObject, r: JSONObject, base: String, fetch: ((String) -> String)?): Source? {
    val title = r.str("title")?.let(::plain) ?: return null
    var hash = r.str("hash")?.let(::hex) ?: r.str("url")?.let(::hashIn)
    if (hash == null && site.optBoolean("source_is_in_sub_page") && fetch != null) {
      val link = r.str("url") ?: return null
      hash = runCatching { hashIn(fetch(URI(base.trimEnd('/') + "/").resolve(link).toString())) }.getOrNull()
    }
    val name = site.optString("name", "Syncler")
    val text = "$title ${r.optString("quality")}"
    return Source(name, name, title, qualityOf(text), hdrOf(text), sizeOf(r.optString("size")), null, infoHash = hash ?: return null)
  }

  private fun hashIn(text: String) = Regex("btih:([a-fA-F0-9]{40}|[A-Za-z2-7]{32})").find(text)?.groupValues?.get(1)?.let(::hex)

  /** A torrent hash as 40 lower-case hex digits (magnet links sometimes carry the 32-character base32 form). */
  fun hex(h: String): String? = when {
    Regex("^[a-fA-F0-9]{40}$").matches(h) -> h.lowercase()
    Regex("^[A-Za-z2-7]{32}$").matches(h) -> {
      var bits = 0L; var n = 0; val sb = StringBuilder()
      for (c in h.uppercase()) {
        bits = (bits shl 5) or "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c).toLong(); n += 5
        while (n >= 4) { n -= 4; sb.append("0123456789abcdef"[((bits shr n) and 15).toInt()]) }
      }
      sb.toString()
    }
    else -> null
  }

  private fun sizeOf(s: String): String? = s.trim().toLongOrNull()?.let { b -> if (b > 1_000_000) "%.1f GB".format(b / 1e9) else null }
    ?: Regex("(\\d+(?:[.,]\\d+)?)\\s?(GB|MB|GiB|MiB|TB)", RegexOption.IGNORE_CASE).find(s)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
}

private fun plain(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"").trim().ifEmpty { null }

/**
 * Reads web pages with a provider's selectors (`doc.querySelectorAll(…)`, `row.querySelector(…).textContent`), which are
 * JavaScript: run in a WebView that can't load anything, so a provider's code can only read the page it's given.
 */
@SuppressLint("SetJavaScriptEnabled")
object Dom {
  lateinit var app: Context
  private val lock = Mutex()
  private var web: WebView? = null
  private val ready = CompletableDeferred<Unit>()

  suspend fun rows(html: String, parser: JSONObject): List<JSONObject> = lock.withLock {
    withContext(Dispatchers.Main) {
      val w = web ?: WebView(app).also { w ->
        w.settings.javaScriptEnabled = true
        w.settings.blockNetworkLoads = true
        w.webViewClient = object : WebViewClient() { override fun onPageFinished(v: WebView?, url: String?) { ready.complete(Unit) } }
        w.loadDataWithBaseURL(null, "<html></html>", "text/html", "utf-8", null)
        web = w
      }
      ready.await()
      val fields = JSONObject().apply { parser.keys().forEach { k -> if (k != "row") put(k, parser.getString(k)) } }
      val js = """(function(){try{const doc=new DOMParser().parseFromString(${JSONObject.quote(html)},'text/html');
        const rows=Array.from(new Function('doc','return '+${JSONObject.quote(parser.optString("row"))})(doc)).slice(0,40);const F=$fields;
        return JSON.stringify(rows.map(function(row){const o={};for(const k in F){try{const v=new Function('doc','row','return '+F[k])(doc,row);if(v!=null)o[k]=String(v).trim()}catch(e){}}return o}))}catch(e){return '[]'}})()"""
      suspendCancellableCoroutine { c ->
        w.evaluateJavascript(js) { res -> c.resume(runCatching { JSONArray(JSONTokener(res).nextValue() as String).objects() }.getOrDefault(emptyList())) }
      }
    }
  }
}
