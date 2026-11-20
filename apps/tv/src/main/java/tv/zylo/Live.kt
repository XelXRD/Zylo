// Live TV from an Xtream Codes account: categories, a channel guide (EPG) and full-screen playback with channel zapping.
// Xtream Codes is a plain HTTP API on the provider's server (player_api.php); everything is called from the TV.
package tv.zylo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [archive]: days of catch-up the provider keeps for this channel (0 = none). [id] is unique across your providers: the
 * first one's stream ids as they are (so channels saved before more providers existed still match), the others' folded
 * in below zero (see [Channel.slot]).
 */
data class Channel(val id: Int, val num: Int, val name: String, val logo: String?, val category: String, val archive: Int = 0, val epgId: String? = null)
/** [ids]: a category merged from several (same name across providers, or HD and HEVC packs of one) holds all of theirs. */
data class Category(val id: String, val name: String, val ids: List<String> = listOf(id))
/** A programme in the guide. Times in epoch milliseconds; [startText] is the provider's own clock ("2026-11-08 20:00:00"), which catch-up links use. */
data class Programme(val title: String, val description: String, val start: Long, val end: Long, val archive: Boolean = false, val startText: String = "")

// ponytail: 100M stream ids per provider and 21 providers fit in an Int; move Channel.id to Long if either runs out.
private const val SLOT = 100_000_000

/** Which of your providers the channel comes from (its account's slot). */
val Channel.slot: Int get() = if (id >= 0) 0 else -id / SLOT
/** The provider's own id for the channel. */
val Channel.stream: Int get() = if (id >= 0) id else -id % SLOT

/**
 * An Xtream Codes account. [formats]: the stream formats the provider allows ("m3u8", "ts"). [slot]: its number among
 * your providers, kept for good so its channels keep their ids. [info]: the provider's account details from sign-in.
 */
class Xtream(val server: String, val user: String, val pass: String, val formats: List<String> = emptyList(), val slot: Int = 0, name: String = "", val info: String = "") {
  /** What it's called in Zylo: its name, or the server's host. */
  val name: String = name.ifBlank { server.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("www.") }

  private fun api(action: String = "", extra: String = "") =
    "$server/player_api.php?username=${enc(user)}&password=${enc(pass)}" + (if (action.isNotEmpty()) "&action=$action" else "") + extra

  /** Ids made unique across providers: this account's channels and categories. */
  private fun channelId(stream: Int) = if (slot == 0) stream else -(slot * SLOT + stream)
  private fun categoryId(id: String) = if (slot == 0) id else "$slot~$id"

  /** Checks the account. Returns the provider's account details; throws a readable error when it isn't usable. */
  fun login(fetch: (String) -> String = { http(it) }): JSONObject {
    val o = runCatching { JSONObject(fetch(api())) }.getOrElse { throw Exception(if (it is HttpError) it.message else "That server didn't answer like an Xtream Codes server. Check the address.") }
    val info = o.optJSONObject("user_info") ?: throw Exception("That server didn't answer like an Xtream Codes server. Check the address.")
    if (info.optInt("auth", 0) != 1) throw Exception("The provider didn't accept that username and password.")
    val status = info.optString("status")
    if (status.isNotEmpty() && !status.equals("Active", true)) throw Exception("The provider says this account is $status.")
    return o
  }

  fun categories(fetch: (String) -> String = { http(it) }) =
    JSONArray(fetch(api("get_live_categories"))).objects().map { Category(categoryId(it.optString("category_id")), it.optString("category_name").trim()) }

  fun channels(category: String?, fetch: (String) -> String = { http(it, timeout = 15_000) }) =
    JSONArray(fetch(api("get_live_streams", category?.let { "&category_id=${enc(it)}" } ?: ""))).objects().mapNotNull {
      val id = it.optInt("stream_id").takeIf { n -> n in 1 until SLOT } ?: return@mapNotNull null
      Channel(channelId(id), it.optInt("num"), it.optString("name").trim(), it.str("stream_icon")?.takeIf { u -> u.startsWith("http") },
        (listOf(it.optString("category_id")) + strings(it.optJSONArray("category_ids")) + (it.optJSONArray("category_ids")?.let { a -> (0 until a.length()).mapNotNull { i -> a.opt(i) as? Int }.map(Int::toString) } ?: emptyList()))
          .filter { c -> c.isNotEmpty() && c != "null" }.distinct().joinToString(",") { c -> categoryId(c) },
        if (it.optString("tv_archive") == "1") it.optString("tv_archive_duration").toIntOrNull() ?: 1 else 0, it.str("epg_channel_id"))
    }

  /**
   * One channel's guide (base64-encoded text): the full listing, past days included (for catch-up) and ahead (for paging
   * the guide); falls back to now-and-next when the provider doesn't serve the full table. [id]: the provider's own id.
   */
  fun guide(id: Int, fetch: (String) -> String = { http(it, timeout = 10_000) }): List<Programme> {
    fun parse(body: String) = JSONObject(body).optJSONArray("epg_listings")?.objects()?.mapNotNull {
      val start = it.optString("start_timestamp").toLongOrNull() ?: return@mapNotNull null
      val end = (it.optString("stop_timestamp").ifEmpty { it.optString("end_timestamp") }).toLongOrNull() ?: return@mapNotNull null
      Programme(b64(it.optString("title")), b64(it.optString("description")), start * 1000, end * 1000, it.optString("has_archive") == "1", it.optString("start"))
    }?.distinctBy { it.start }?.sortedBy { it.start }.orEmpty()
    return runCatching { parse(fetch(api("get_simple_data_table", "&stream_id=$id"))) }.getOrDefault(emptyList())
      .ifEmpty { parse(fetch(api("get_short_epg", "&stream_id=$id&limit=24"))) }
  }

  /** A past programme from the provider's archive. */
  fun catchupUrl(c: Channel, p: Programme): String? {
    val m = Regex("^(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}):(\\d{2})").find(p.startText) ?: return null
    val (day, h, min) = m.destructured
    return "$server/timeshift/${enc(user)}/${enc(pass)}/${(p.end - p.start) / 60_000}/$day:$h-$min/${c.stream}.ts"
  }

  /** Whether a programme can be watched again: it's over, and the provider keeps it. */
  fun canCatchUp(c: Channel, p: Programme, now: Long = System.currentTimeMillis()) =
    c.archive > 0 && p.end <= now && p.start >= now - c.archive * 86_400_000L && p.startText.isNotEmpty() && (p.archive || c.archive > 0)

  /** The provider's whole guide in one XMLTV file. */
  fun xmltvUrl() = "$server/xmltv.php?username=${enc(user)}&password=${enc(pass)}"

  /** HLS when the provider allows it (steadier on TV), else MPEG-TS. */
  fun streamUrl(c: Channel) = "$server/live/${enc(user)}/${enc(pass)}/${c.stream}." + if (formats.isEmpty() || "m3u8" in formats) "m3u8" else "ts"

  /** When the account runs out (epoch ms), if the provider says. */
  val expires: Long? get() = runCatching { JSONObject(info).optJSONObject("user_info")?.optString("exp_date")?.toLongOrNull()?.times(1000) }.getOrNull()

  private fun json() = JSONObject().put("server", server).put("user", user).put("pass", pass).put("formats", JSONArray(formats)).put("slot", slot).put("name", name).put("info", info)

  companion object {
    /** Your providers, in the order added. */
    fun accounts(store: Store): List<Xtream> {
      val list = runCatching {
        JSONArray(store.str("xc_accounts")).objects().map { o ->
          Xtream(o.getString("server"), o.optString("user"), o.optString("pass"), strings(o.optJSONArray("formats")), o.optInt("slot"), o.optString("name"), o.optString("info"))
        }
      }.getOrDefault(emptyList())
      if (list.isNotEmpty() || store.str("xc_server").isEmpty()) return list
      // Before more than one provider, the one account lived in keys of its own.
      return listOf(Xtream(store.str("xc_server"), store.str("xc_user"), store.str("xc_pass"), store.str("xc_formats").split(',').filter { it.isNotBlank() }, 0, "", store.str("xc_info")))
    }

    private fun save(store: Store, list: List<Xtream>, refetch: Boolean = true) {
      store.put("xc_accounts", JSONArray(list.map { it.json() }).toString())
      listOf("xc_server", "xc_user", "xc_pass", "xc_formats", "xc_info").forEach { store.put(it, "") }
      if (refetch) changed(store)
    }

    /** Your first provider, or null with none: "is Live TV set up?" */
    fun from(store: Store) = accounts(store).firstOrNull()

    /** Bumps when providers change, so a Live TV page already open fetches its channels again. */
    fun changed(store: Store) { ChannelCache.clear(); store.put("xc_rev", System.nanoTime().toString()) }

    /**
     * The server from whatever the provider gave you: "host:port", a full URL, or the M3U playlist link
     * (get.php?username=…&password=…), which carries the username and password too.
     */
    fun parse(input: String): Triple<String, String?, String?> {
      val t = input.trim().let { if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it)) it else "http://$it" }
      val q = t.substringAfter('?', "").split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
      val base = Regex("^(https?://[^/?#]+)", RegexOption.IGNORE_CASE).find(t)!!.groupValues[1]
      return Triple(base, q["username"], q["password"])
    }

    /** Adds a provider (or signs in again to one you have, keeping its place and its channels' ids). */
    fun connect(store: Store, server: String, user: String, pass: String) {
      val o = Xtream(server, user.trim(), pass.trim()).login()
      val formats = strings(o.optJSONObject("user_info")?.optJSONArray("allowed_output_formats"))
      val list = accounts(store)
      val same = list.find { it.server.equals(server, true) && it.user == user.trim() }
      val slot = same?.slot ?: ((list.maxOfOrNull { it.slot } ?: -1) + 1)
      if (slot * SLOT.toLong() >= Int.MAX_VALUE) throw Exception("That's as many providers as Zylo can hold. Remove one first.")
      val account = Xtream(server, user.trim(), pass.trim(), formats, slot, same?.name ?: "", o.toString())
      save(store, if (same != null) list.map { if (it.slot == slot) account else it } else list + account)
    }

    /** What you call a provider (shown beside its channels when you have more than one). Blank: its server's name. */
    fun rename(store: Store, slot: Int, name: String) =
      save(store, accounts(store).map { if (it.slot == slot) Xtream(it.server, it.user, it.pass, it.formats, it.slot, name.trim(), it.info) else it }, refetch = false)

    /** Removes a provider, and its channels from Favourites and Recent. The last one going clears Live TV. */
    fun disconnect(store: Store, slot: Int) {
      val left = accounts(store).filter { it.slot != slot }
      if (left.isEmpty()) { forget(store); changed(store); return }
      save(store, left)
      setFavourites(store, favourites(store).filter { it.slot != slot })
      store.put("xc_recent", JSONArray(recent(store).filter { it.slot != slot }.map(::channelJson)).toString())
    }
    private fun forget(store: Store) = listOf("xc_accounts", "xc_server", "xc_user", "xc_pass", "xc_formats", "xc_info", "xc_cat", "xc_favs", "xc_recent", "xc_hidden").forEach { store.put(it, "") }

    /** Favourite channels, kept whole so the Favourites list needs no download (and never shows anything else). */
    fun favourites(store: Store): List<Channel> = channels(store, "xc_favs")
    fun setFavourites(store: Store, list: List<Channel>) = store.put("xc_favs", JSONArray(list.map(::channelJson)).toString())

    /** The last channels watched live, newest first. */
    fun recent(store: Store): List<Channel> = channels(store, "xc_recent")
    fun addRecent(store: Store, c: Channel): List<Channel> = (listOf(c) + recent(store).filter { it.id != c.id }).take(12)
      .also { store.put("xc_recent", JSONArray(it.map(::channelJson)).toString()) }

    /** Hidden categories: "cat:<id>" for one, "pre:<tag>" for every category with that country tag. */
    fun hidden(store: Store): Set<String> = store.str("xc_hidden").split('\n').filter { it.isNotBlank() }.toSet()
    fun setHidden(store: Store, set: Set<String>) = store.put("xc_hidden", set.joinToString("\n"))

    private fun channels(store: Store, key: String) = runCatching { JSONArray(store.str(key)).objects().map(::channelOf) }.getOrDefault(emptyList())

    fun channelJson(c: Channel): JSONObject = JSONObject().put("id", c.id).put("num", c.num).put("name", c.name).put("logo", c.logo ?: "")
      .put("category", c.category).put("archive", c.archive).put("epg", c.epgId ?: "")
    fun channelOf(o: JSONObject) = Channel(o.getInt("id"), o.optInt("num"), o.optString("name"), o.str("logo"), o.optString("category"), o.optInt("archive"), o.str("epg"))

    private fun b64(s: String): String = runCatching {
      val bytes = try { java.util.Base64.getDecoder().decode(s.trim()) } catch (e: NoClassDefFoundError) { android.util.Base64.decode(s, 0) }
      String(bytes, Charsets.UTF_8).takeIf { d -> d.isNotBlank() && d.none { it == '�' } }
    }.getOrNull() ?: s // some panels send plain text
  }
}

/** All your providers at once: each channel is played, replayed and looked up on the one it came from. */
class Providers(val list: List<Xtream>) {
  fun of(c: Channel) = list.find { it.slot == c.slot } ?: list.first()
  fun streamUrl(c: Channel) = of(c).streamUrl(c)
  fun catchupUrl(c: Channel, p: Programme) = of(c).catchupUrl(c, p)
  fun canCatchUp(c: Channel, p: Programme, now: Long = System.currentTimeMillis()) = of(c).canCatchUp(c, p, now)
  /** The provider's name when you have more than one (else nothing: there's nothing to tell apart). */
  fun from(c: Channel): String? = if (list.size > 1) of(c).name else null

  companion object {
    fun from(store: Store) = Xtream.accounts(store).takeIf { it.isNotEmpty() }?.let(::Providers)
  }
}

/**
 * A provider's list, tidied: the country tag on a name ("UK| SPORTS"), the quality words ("ᴴᴰ/ᴿᴬᵂ", "FHD", "HEVC"), and
 * one channel's HD / SD / 4K copies, which providers (and different providers) list as separate channels.
 */
object LiveTidy {
  /** Settings > Live TV > Combine versions: names lose their quality words and copies become one entry. */
  @Volatile var merging = true

  private val TAG = Regex("^\\s*[|\\[(]?\\s*([A-Z]{2,4})\\s*[|\\]):]\\s*")
  private val DASH = Regex("^([A-Z]{2,3})\\s+-\\s+")
  private val UHD8 = setOf("8k", "4320", "4320p")
  private val UHD = setOf("uhd", "4k", "2160", "2160p", "3840p")
  private val QHD = setOf("1440", "1440p", "2k", "qhd")
  private val FHD = setOf("fhd", "fhd+", "1080", "1080p", "1080i")
  private val HD = setOf("hd", "hd+", "720", "720p")
  private val SD = setOf("sd", "576", "576p", "480", "480p", "lq")
  private val HEVC = setOf("hevc", "h265", "x265")
  private val FPS = setOf("50fps", "60fps")
  private val QUALITY = UHD8 + UHD + QHD + FHD + HD + SD + HEVC + FPS + setOf("h264", "x264", "25fps", "30fps", "fps", "raw", "backup", "vip", "hq")
  private val keys = java.util.concurrent.ConcurrentHashMap<String, String>() // big providers: tens of thousands of names, asked for often

  /** "UK| SPORTS" → ("UK", "SPORTS"); a name without a country tag → (null, name). */
  fun tag(name: String): Pair<String?, String> {
    val m = TAG.find(name) ?: DASH.find(name) ?: return null to name
    val rest = name.substring(m.range.last + 1).trim()
    return if (rest.isEmpty()) null to name else m.groupValues[1] to rest
  }

  /** Superscript letters (ᴴᴰ, ʰᵉᵛᶜ) to plain ones, lower case, h.265 as one word. */
  private fun plain(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).lowercase().replace("h.265", "h265").replace("h.264", "h264")
  // Letters of any script count: matching on Latin letters alone made every Arabic or Cyrillic name look the same.
  private val NOT_WORD = Regex("[^\\p{L}\\p{N}+]+")
  private fun words(name: String) = plain(tag(name).second).split(NOT_WORD).filter { it.isNotEmpty() }
  private fun quality(token: String) = plain(token).split('/').map { it.replace(NOT_WORD, "") }.let { p -> p.any { it.isNotEmpty() } && p.all { it.isEmpty() || it in QUALITY } }

  /** Just the quality words of a name, plain and once each: "UK: SKY CINEMA ACTION ᴴᴰ ʰᵉᵛᶜ" → "HD HEVC"; none → "Standard". */
  fun qualityLabel(name: String): String = java.text.Normalizer.normalize(tag(name).second, java.text.Normalizer.Form.NFKC).split(Regex("\\s+"))
    .filter { it.isNotEmpty() && quality(it) }.flatMap { it.split('/') }.map { it.replace(NOT_WORD, "").uppercase() }.filter { it.isNotEmpty() }.distinct().joinToString(" ").ifEmpty { "Standard" }

  /** The name without its tag and quality words, as written: "UK: SKY CINEMA ACTION ᴴᴰ/ᴿᴬᵂ" → "SKY CINEMA ACTION". */
  fun title(name: String): String {
    val rest = tag(name).second
    return java.text.Normalizer.normalize(rest, java.text.Normalizer.Form.NFKC).split(Regex("\\s+")).filterNot { it.isEmpty() || quality(it) }.joinToString(" ").ifEmpty { rest }
  }

  /**
   * The same for every version of one channel: its country and name without the quality words. A channel with no
   * country (no tag, or a tier like VIP) gets "*": which country it joins is decided by [group], from the whole list.
   */
  fun key(name: String): String = keys.getOrPut(name) {
    tag(name).first.let { t -> if (t == null || t in TIERS) "*" else country(t) } + "/" + words(name).filter { it !in QUALITY }.joinToString(" ").ifEmpty { name.lowercase() }
  }

  // Where each "*" name belongs, worked out once per channel list (see [prepare]).
  private var preparedFor: List<Channel>? = null
  private var wild: Map<String, String> = emptyMap()
  private var named: Map<String, List<String>> = emptyMap() // a name to the countries that have a channel called it

  /**
   * Looks over the whole list once: a channel with no country joins the country that has a channel of the same name.
   * "VIP: SKY SPORTS MAIN EVENT" joins "UK: Sky Sports Main Event" because only the UK has one, whatever the TV's
   * region. When several countries have the name, it joins yours ([home]) if that's one of them, else stays apart.
   */
  @Synchronized fun prepare(all: List<Channel>) {
    if (preparedFor === all) return
    val map = HashMap<String, String>()
    val named = HashMap<String, List<String>>()
    all.asSequence().map { key(it.name) }.distinct().groupBy { it.substringAfter('/') }.forEach { (name, ks) ->
      // Only real countries decide: service or region tags (NOW, JOYN, ASIA) keep their own channels but don't claim others.
      val countries = ks.map { it.substringBefore('/') }.filter { it.length == 2 }.distinct()
      map["*/$name"] = (if (countries.size == 1) countries[0] else home) + "/" + name
      if (countries.isNotEmpty()) named[name] = countries
    }
    wild = map
    this.named = named
    preparedFor = all
  }

  /** The group a version belongs to, with "*" settled (call [prepare] with the list first). */
  fun group(name: String): String = key(name).let { k -> if (k.startsWith("*/")) wild[k] ?: (home + k.substring(1)) else k }
  /** The country a channel is from once settled ("UK"), or a region / service tag ("ASIA", "NOW"). */
  fun countryOf(name: String): String = group(name).substringBefore('/')

  /** Every country a channel might be from: its own tag's, or with none, each country that has a channel of that name. */
  fun countriesOf(name: String): List<String> = key(name).let { k -> if (k.startsWith("*/")) named[k.substringAfter('/')].orEmpty() else listOf(k.substringBefore('/')) }

  private val COUNTRIES = java.util.Locale.getISOCountries().toSet() // before [home], which needs it
  /** Three-letter country codes some providers use (BRA, ARG, SRB) to the two-letter ones others use (BR, AR, RS). */
  // Not ARG: in IPTV lists AR means Arabic, so Argentina keeps its three letters.
  private val ISO3 = COUNTRIES.associateBy { runCatching { java.util.Locale("", it).isO3Country }.getOrDefault("") }.filterValues { it != "AR" } + mapOf("CRO" to "HR", "SLO" to "SI", "GER" to "DE", "POR" to "PT", "SUI" to "CH", "NED" to "NL")
  /** Tags that say what kind of stream it is, not where it's from: these channels are taken to be your own country's. */
  private val TIERS = setOf("VIP", "PLUS", "PRO", "PREM", "PPV", "TOP", "LIVE", "NEW", "HD", "FHD", "UHD", "SD", "RAW", "HEVC", "BACKUP", "EXTRA")
  /** Your TV's country: where a channel with no country tag (or a tag like VIP) is taken to be from. */
  @Volatile var home: String = java.util.Locale.getDefault().country.let { if (it == "GB" || it !in COUNTRIES) "UK" else it }
  /**
   * The country a tag names, as one spelling (UK, GB and EN are all UK; BRA is BR). No tag or a tier like VIP: [home].
   * Any other tag (ASIA, JOYN, CAR) stays itself, so it only matches its own kind: a region or service isn't guessed at.
   */
  private fun country(tag: String?): String = when {
    tag == null || tag in TIERS -> home
    tag in setOf("UK", "GB", "EN", "ENG") -> "UK"
    tag.length == 2 && tag in COUNTRIES -> tag
    tag.length == 3 && tag in ISO3 -> ISO3.getValue(tag).let { if (it == "GB") "UK" else it }
    else -> tag
  }

  fun isHevc(name: String) = words(name).any { it in HEVC }

  /** Whether this TV shows 4K. On a 1080p screen a 4K stream costs bandwidth and decoding for no visible gain. */
  @Volatile var screen4k = true

  /**
   * Which version plays first, before any has been measured: the bitrate its name promises, in kb/s, as an H.264 stream
   * would need for the same picture. From UK broadcast and IPTV figures:
   * - 4K / UHD about 16 Mb/s (Sky's UHD runs near 18); on a 1080p TV, counted as 8.
   * - RAW about 10 Mb/s: the broadcaster's own feed passed through untouched (Sky's 1080i HD runs 10 to 13); with a
   *   resolution, 1.5 times that resolution's figure, whichever is more.
   * - Full HD about 7, HD (usually 720p re-encoded) 4.5, unmarked 4, SD 2.
   * - 50 / 60 fps carry about 30% more. HEVC gives the same picture for less but is harder to decode: 10% under its twin.
   * An "SD" or "LQ" in the name wins over any bigger word (some lists file SD copies under an "8K:" heading).
   */
  fun rank(name: String): Int {
    val w = words(name).toSet()
    val sd = w.any { it in SD }
    var kbps = when {
      sd -> 2_000
      w.any { it in UHD8 || it in UHD } -> if (screen4k) 16_000 else 8_000
      w.any { it in QHD } -> 11_000
      w.any { it in FHD } -> 7_000
      w.any { it in HD } -> 4_500
      else -> 4_000
    }.toDouble()
    if ("raw" in w) kbps = if (sd) kbps * 1.5 else maxOf(kbps * 1.5, 10_000.0)
    if (w.any { it in FPS }) kbps *= 1.3
    if (w.any { it in HEVC }) kbps *= 0.9
    return kbps.toInt()
  }

  /** One entry per channel, its best version standing in for the rest, in the list's own order. */
  fun combine(list: List<Channel>, all: List<Channel> = list): List<Channel> { prepare(all); return list.groupBy { group(it.name) }.values.map { g -> g.maxBy { rank(it.name) } } }

  /** The channel's other versions (from any of your providers), best first. */
  fun versions(all: List<Channel>, c: Channel): List<Channel> {
    prepare(all)
    val k = group(c.name)
    return all.filter { it.id != c.id && group(it.name) == k }.sortedByDescending { rank(it.name) }
  }

  /**
   * Categories with the same name once tidied become one ("UK | SKY CINEMA ᴴᴰ/ᴿᴬᵂ" and "UK: Sky Cinema HEVC", from one
   * provider or several), first place kept. A category on its own keeps its id, so saved choices still find it.
   */
  fun mergeCategories(list: List<Category>): List<Category> = list.groupBy { country(tag(it.name).first) + "/" + plain(title(it.name)) }.values.map { g ->
    val (tag, _) = tag(g[0].name)
    val name = (if (tag != null) "$tag | " else "") + title(g[0].name)
    if (g.size == 1) g[0].copy(name = name) else Category("m:" + key(name), name, g.flatMap { it.ids })
  }

  fun hidden(c: Category, set: Set<String>) = "cat:${c.id}" in set || tag(c.name).first?.let { "pre:$it" in set } == true

  /** A heading the provider put in the channel list ("#### SKY CINEMA HD/4K ####"), not a channel. */
  fun header(name: String) = Regex("^\\s*[#=*~]{3,}").containsMatchIn(name)
}

/** A channel's name as shown: without the provider's country tag, and without quality words while versions are combined. */
val Channel.label: String get() = if (LiveTidy.merging) LiveTidy.title(name) else LiveTidy.tag(name).second

/**
 * Every channel and category from all your providers, saved on the TV and fetched again once a day (one request each for
 * all channels; categories then just filter it). Loading per category on every visit timed out on big providers.
 */
object ChannelCache {
  lateinit var dir: java.io.File
  private val file get() = java.io.File(dir, "channels.json")
  /** Providers that didn't answer on the last fetch (the others' channels still came in). */
  @Volatile var failed: List<String> = emptyList()

  fun load(): Triple<Long, List<Category>, List<Channel>>? = runCatching {
    val o = JSONObject(file.readText())
    Triple(o.getLong("at"), o.getJSONArray("categories").objects().map { Category(it.getString("id"), it.getString("name")) },
      o.getJSONArray("channels").objects().map(Xtream::channelOf))
  }.getOrNull()

  fun fetch(p: Providers): Pair<List<Category>, List<Channel>> = Busy.run("Updating channels") {
    val got = p.list.map { x ->
      x to runCatching { x.categories() to x.channels(null) { http(it, timeout = 20_000, total = 120_000) }.distinctBy { it.id } } // some providers list a channel twice
    }
    failed = got.filter { it.second.isFailure }.map { it.first.name }
    got.firstOrNull { it.second.isSuccess } ?: throw got.first().second.exceptionOrNull()!!
    val cats = got.flatMap { it.second.getOrNull()?.first.orEmpty() }
    val chans = got.flatMap { it.second.getOrNull()?.second.orEmpty() }
    runCatching {
      file.writeText(JSONObject().put("at", System.currentTimeMillis())
        .put("categories", JSONArray(cats.map { JSONObject().put("id", it.id).put("name", it.name) }))
        .put("channels", JSONArray(chans.map(Xtream::channelJson))).toString())
    }
    cats to chans
  }

  fun clear() { if (::dir.isInitialized) file.delete() }
}

/** Live TV page state; kept across visits so the category, list and guide are where you left them. */
class LiveState {
  var categories by mutableStateOf<List<Category>>(emptyList())
  var category by mutableStateOf<Category?>(null)
  var channels by mutableStateOf<List<Channel>>(emptyList()) // what the guide shows (the chosen category)
  var all by mutableStateOf<List<Channel>>(emptyList()) // every channel (saved on the TV, fetched daily)
  var error by mutableStateOf<String?>(null)
  var loading by mutableStateOf(false)
  val guide: SnapshotStateMap<Int, List<Programme>> = mutableStateMapOf()
  var focused by mutableStateOf<Pair<Channel, Programme?>?>(null)
  var playing by mutableIntStateOf(0) // index into channels for the player
  var catchup by mutableStateOf<Pair<Channel, Programme>?>(null) // set: the player shows this past programme instead
  var shift by mutableIntStateOf(0) // hours the guide is moved from now (negative: earlier, for catch-up)
  var shiftFocus by mutableIntStateOf(0) // bumps after a shift so focus lands on the right programme
  var favs by mutableStateOf<List<Channel>?>(null) // favourite channels, in the order added
  var reminders by mutableStateOf<List<Reminder>>(emptyList())
  var sports by mutableStateOf(false) // the sports schedule instead of the guide
  var recent by mutableStateOf<List<Channel>>(emptyList()) // last channels watched, newest first
  var hidden by mutableStateOf<Set<String>>(emptySet()) // hidden categories (see Xtream.hidden)
  var combine by mutableStateOf(true) // one entry per channel instead of its HD / SD / 4K copies; same-name categories as one
  var rev = "" // which set of providers the channels came from (Xtream.changed)
  /** The categories as shown: merged by name while [combine] is on. */
  val cats by derivedStateOf { if (combine) LiveTidy.mergeCategories(categories) else categories }
  var resumed = false // the last channel has been offered this session
  var moving by mutableStateOf<Int?>(null) // a favourite being moved up or down
  var channelMenu by mutableStateOf<Channel?>(null) // hold OK on a favourite: move or remove
  var details by mutableStateOf<Pair<Channel, Programme>?>(null) // hold OK on a programme
  var returnTo: FocusRequester? = null // what a closing panel gives focus back to
  var refocus by mutableIntStateOf(0) // bumps when a panel closes
  var focusFirst = false // a category was just picked: focus its first channel
  var startWith: Channel? = null // a version you chose to watch: the player starts on it
  val sched = SportsState()
  val focus = FocusMemory()
  internal val gate = Semaphore(4) // guide requests at once

  /** What a category shows. Hidden categories drop out of All Channels too; Favourites and Recent are yours as they are. */
  fun listFor(c: Category): List<Channel> = when (c) {
    FAVS -> favs.orEmpty()
    RECENT -> recent
    else -> {
      val off = cats.filter { LiveTidy.hidden(it, hidden) }.flatMap { it.ids }.toSet()
      val l = if (c == ALL) all.filter { ch -> !LiveTidy.header(ch.name) && ch.category.split(',').let { ids -> ids.all { it.isEmpty() } || ids.any { it !in off } } }
        else all.filter { ch -> !LiveTidy.header(ch.name) && ch.category.split(',').any { it in c.ids } }
      if (combine) LiveTidy.combine(l, all) else l
    }
  }.distinctBy { it.id } // the list's keys must be unique
}

/** Adds the channel to Favourites, or takes it off. Returns whether it's a favourite now. */
private fun toggleFavourite(store: Store, s: LiveState, c: Channel): Boolean {
  val fav = s.favs.orEmpty().any { it.id == c.id }
  val list = if (fav) s.favs.orEmpty().filter { it.id != c.id } else s.favs.orEmpty() + c
  s.favs = list
  Xtream.setFavourites(store, list)
  return !fav
}

/** Watch a channel: live, or a past programme from the archive. [start]: a version you chose, played before the rest. */
private fun watch(s: LiveState, x: Providers, nav: Nav, c: Channel, p: Programme?, start: Channel? = null) {
  s.playing = s.channels.indexOfFirst { it.id == c.id }.takeIf { it >= 0 } ?: run { s.channels = listOf(c) + s.channels; 0 }
  s.catchup = p?.takeIf { x.canCatchUp(c, it) }?.let { c to it }
  s.startWith = start
  nav.push(Screen.LivePlay)
}

/**
 * What Zylo has learnt about each version by playing it, since providers don't say: picture height, codec and bitrate
 * (measured from what actually arrives). And the version you pinned to play first, per channel.
 */
object LiveStats {
  class Measured(val height: Int, val codec: String?, val kbps: Int)
  private var store: Store? = null
  private val measured = LinkedHashMap<Int, Measured>()
  private val pins = HashMap<String, Int>()
  /** Bumps when a pin changes, so lists showing it redraw. */
  var rev by mutableIntStateOf(0)

  @Synchronized fun load(store: Store) {
    if (this.store != null) return
    this.store = store
    runCatching { JSONObject(store.str("live_measured")).let { o -> o.keys().forEach { k -> val a = o.getString(k).split('|'); measured[k.toInt()] = Measured(a[0].toInt(), a[1].ifEmpty { null }, a[2].toInt()) } } }
    runCatching { JSONObject(store.str("live_pins")).let { o -> o.keys().forEach { k -> pins[k] = o.getInt(k) } } }
  }

  @Synchronized private fun save() {
    val s = store ?: return
    // ponytail: the 3,000 most recent measurements are kept; plenty for the channels one household actually watches.
    s.put("live_measured", JSONObject().apply { measured.entries.toList().takeLast(3000).forEach { (k, m) -> put(k.toString(), "${m.height}|${m.codec ?: ""}|${m.kbps}") } }.toString())
    s.put("live_pins", JSONObject().apply { pins.forEach { (k, v) -> put(k, v) } }.toString())
  }

  fun kbps(id: Int) = measured[id]?.kbps ?: 0

  /**
   * A version's place in the play order: the bitrate measured when it last played (HEVC counted as the H.264 an equal
   * picture would need, less the same 10% for being harder to decode), else the estimate from its name.
   */
  fun score(c: Channel): Int = measured[c.id]?.let { m -> if (m.codec == "H.265") (m.kbps * 1.6 * 0.9).toInt() else m.kbps } ?: LiveTidy.rank(c.name)

  /** What the version's name suggests it sends, as a reading: "Estimated ~10 Mb/s" (HEVC at its own, lower rate). */
  fun estimate(name: String): String {
    val kbps = LiveTidy.rank(name).let { if (LiveTidy.isHevc(name)) it / 0.9 * 0.6 else it.toDouble() }
    return String.format(Locale.UK, "Estimated ~%.0f Mb/s", kbps / 1000)
  }

  /** Whether the TV shows 4K (else 4K versions are counted as 8 Mb/s: see [LiveTidy.rank]). */
  fun checkScreen(ctx: android.content.Context) {
    LiveTidy.screen4k = runCatching {
      @Suppress("DEPRECATION") (ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        .supportedModes.maxOf { maxOf(it.physicalWidth, it.physicalHeight) } >= 3840
    }.getOrDefault(true)
  }
  @Synchronized fun put(id: Int, m: Measured) { measured.remove(id); measured[id] = m; save() }

  // Versions that didn't start lately (this session): tried last for half an hour, so zapping back doesn't wait on them.
  private val failedAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()
  fun failed(id: Int) { failedAt[id] = System.currentTimeMillis() }
  fun worked(id: Int) { failedAt.remove(id) }
  fun failedLately(id: Int) = failedAt[id]?.let { System.currentTimeMillis() - it < 30 * 60_000L } == true

  /**
   * "1080p · H.265 · 6.2 Mb/s", or null before it has been played. Almost nothing arriving means the provider was
   * sending a holding slate (off air), not the channel.
   */
  fun describe(id: Int): String? {
    if (failedLately(id)) return "Didn't start just now"
    val m = measured[id] ?: return null
    if (m.kbps in 0..49) return "No picture when last played"
    return listOfNotNull(m.height.takeIf { it > 0 }?.let { "${it}p" }, m.codec, String.format(Locale.UK, "%.1f Mb/s", m.kbps / 1000f)).joinToString(" · ")
  }

  fun pinned(c: Channel): Int? = pins[LiveTidy.group(c.name)]
  /** Pins [v] to play first for its channel; null goes back to automatic. */
  @Synchronized fun pin(c: Channel, v: Channel?) { val k = LiveTidy.group(c.name); if (v == null) pins.remove(k) else pins[k] = v.id; save(); rev++ }

  fun codec(mime: String?) = when {
    mime == null -> null
    "hevc" in mime || "hev1" in mime || "hvc1" in mime -> "H.265"
    "avc" in mime -> "H.264"
    "av01" in mime -> "AV1"
    "mpeg2" in mime -> "MPEG-2"
    else -> null
  }
}

/**
 * Every version of a channel in the order Play tries them: the one you pinned, then the best quality first (by what its
 * name promises, then by the bitrate measured when it last played).
 */
fun versionChain(all: List<Channel>, c: Channel): List<Channel> {
  LiveTidy.prepare(all)
  val pin = LiveStats.pinned(c)
  return (listOf(c) + LiveTidy.versions(all, c)).distinctBy { it.id }
    .sortedWith(compareByDescending<Channel> { it.id == pin }.thenBy { LiveStats.failedLately(it.id) }.thenByDescending { LiveStats.score(it) })
}

/**
 * One version in a list: its provider (when you have several), quality label, and what was measured when it played.
 * OK plays it; holding OK pins it to play first for the channel (again: back to automatic).
 */
@Composable
private fun VersionRow(x: Providers, c: Channel, v: Channel, playing: Boolean, number: Boolean = false, pick: () -> Unit) {
  val ctx = LocalContext.current
  LiveStats.rev // redraw when a pin changes
  val pinned = LiveStats.pinned(c) == v.id
  ZSurface(pick, Modifier.fillMaxWidth().padding(vertical = 2.dp), selected = playing, onLongClick = {
    LiveStats.pin(c, if (pinned) null else v)
    toast(ctx, if (pinned) "${c.label}: back to the best version first" else "${c.label}: ${LiveTidy.qualityLabel(v.name)}" + (x.from(v)?.let { " from $it" } ?: "") + " plays first from now on")
  }) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(listOfNotNull(x.from(v), LiveTidy.qualityLabel(v.name), "No. ${v.num}".takeIf { number && v.num > 0 }, "Always first".takeIf { pinned }, "Playing".takeIf { playing }).joinToString("  ·  "),
        Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(LiveStats.describe(v.id) ?: LiveStats.estimate(v.name), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
  }
}

/** Versions whose provider and quality label are the same as another's. */
private fun sameLabels(x: Providers, versions: List<Channel>): Set<Int> =
  versions.groupBy { x.from(it) + "/" + LiveTidy.qualityLabel(it.name) }.values.filter { it.size > 1 }.flatten().map { it.id }.toSet()

/** The versions as a short scrolling list, with what OK and holding OK do. */
@Composable
private fun VersionList(x: Providers, c: Channel, versions: List<Channel>, pick: (Channel) -> Unit) {
  Text("${versions.size} versions, in the order Play tries them" + if (versions.size > 5) "  ·  ▲ ▼ to see them all" else "", Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
  val twins = sameLabels(x, versions) // two versions with the same label get their channel number too
  LazyColumn(Modifier.heightIn(max = 190.dp)) { items(versions, key = { it.id }) { v -> VersionRow(x, c, v, false, v.id in twins) { pick(v) } } }
  Text("OK plays that one  ·  hold OK to always play it first  ·  estimates come from each label, until a version has played for about 40 seconds and been measured",
    Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = Z.Muted)
}

private fun remind(ctx: android.content.Context, store: Store, s: LiveState, c: Channel, p: Programme) {
  val on = Reminders.toggle(store, c, p)
  s.reminders = Reminders.all(store)
  toast(ctx, if (on) "Reminder set: ${p.title}, ${hm(p.start)} on ${c.label}" else "Reminder removed")
}

/** The provider's guide for a channel, or the better guide's when the provider has nothing from now on. */
fun bestGuide(x: Providers, c: Channel): List<Programme> {
  val now = System.currentTimeMillis()
  // The daily guide is already on the TV: use it, and only ask the provider for channels with catch-up (it marks what
  // can be replayed) or ones the daily guide doesn't cover.
  if (c.archive == 0) Epg.current.forChannel(c)?.takeIf { l -> l.any { it.end > now } }?.let { return it }
  val own = runCatching { x.of(c).guide(c.stream) }.getOrDefault(emptyList())
  if (own.any { it.end > now }) return own
  return Epg.current.forChannel(c)?.let { extra -> (own.filter { it.archive } + extra.filter { e -> own.none { it.start == e.start } }).sortedBy { it.start } } ?: own
}

private val ALL = Category("", "All Channels")
private val FAVS = Category("fav", "Favourites")
private val RECENT = Category("recent", "Recent")
private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.UK).format(Date(t))
private fun day(t: Long) = SimpleDateFormat("yyyyMMdd", Locale.UK).format(Date(t))

@Composable
fun LivePage(store: Store, s: LiveState, nav: Nav, restore: Boolean) {
  val x = remember { Providers.from(store) }
  if (x == null) {
    Column(Modifier.fillMaxSize().padding(start = Z.Pad, top = 110.dp)) {
      Text("Live TV", style = MaterialTheme.typography.displaySmall)
      Text("Add your Xtream Codes details in Settings > Live TV to see your channels and guide here.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyLarge, color = Z.Text2)
      ZButton("Open Settings", { nav.push(Screen.Settings) }, Modifier.padding(top = 20.dp), primary = true)
    }
    return
  }
  if (s.favs == null) { s.favs = Xtream.favourites(store); s.reminders = Reminders.all(store); s.recent = Xtream.recent(store); s.hidden = Xtream.hidden(store) }
  // 1. Channels: from the TV at once, from the provider once a day. 2. Then the better guide, once a day, kept to
  // your channels. Fixtures (Sports) load separately, only when opened.
  val ctx = LocalContext.current
  LaunchedEffect(Unit) {
    s.combine = store.str("live_combine") != "off" // Settings may have changed these since the last visit
    LiveTidy.merging = s.combine
    LiveStats.load(store); LiveStats.checkScreen(ctx)
    s.hidden = Xtream.hidden(store)
    // A provider added or removed since: everything comes again, from all of them.
    store.str("xc_rev").let { rev -> if (rev != s.rev) { s.rev = rev; s.all = emptyList(); s.categories = emptyList(); s.channels = emptyList(); s.guide.clear(); s.category = null; s.focused = null } }
    if (s.all.isEmpty()) {
      s.loading = true
      val saved = withContext(Dispatchers.IO) { ChannelCache.load() }
      if (saved != null) { s.categories = saved.second; s.all = saved.third }
      if (saved == null || System.currentTimeMillis() - saved.first > 86_400_000L || saved.third.isEmpty()) {
        runCatching { withContext(Dispatchers.IO) { ChannelCache.fetch(x) } }
          .onSuccess { (cats, chans) -> s.categories = cats; s.all = chans; s.error = null }
          .onFailure { if (s.all.isEmpty()) s.error = "Couldn't load your channels. " + plainError(it, "your Live TV provider") }
        ChannelCache.failed.takeIf { it.isNotEmpty() && s.all.isNotEmpty() }?.let { toast(ctx, "Couldn't reach ${it.joinToString(" or ")} just now. Its channels are missing until it answers (Settings > Live TV > Refresh Channels).") }
      }
      s.loading = false
    }
    val saved = store.str("xc_cat")
    if (s.category == null) s.category = (listOf(FAVS, RECENT) + s.cats).find { it.id == saved || saved in it.ids } ?: ALL
    // Settings > Live TV > Start on your last channel: once a session, straight back to what you were watching.
    if (!s.resumed) {
      s.resumed = true
      val last = s.recent.firstOrNull()?.let { r -> s.all.find { it.id == r.id } ?: r }
      if (last != null && store.str("live_resume") == "on") {
        s.channels = s.listFor(s.category ?: ALL)
        watch(s, x, nav, last, null)
      }
    }
    withContext(Dispatchers.IO) {
      Epg.load()
      val sources = Epg.sources(store)
      if (sources.isNotEmpty() && s.all.isNotEmpty() && Epg.age(store) > 86_400_000L && !Epg.busy) {
        Epg.busy = true
        runCatching { Epg.refresh(store, sources, s.all) }
        Epg.busy = false
      }
    }
  }
  if (s.sports) { SportsPage(store, x, s, nav); return }
  // 4. Preview: one muted player for the guide, showing the channel you rest on (Settings > Live TV can turn it off).
  val preview = remember {
    if (store.str("live_preview") == "off") null
    else ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(DefaultHttpDataSource.Factory().setUserAgent(UA).setAllowCrossProtocolRedirects(true))).build()
      .apply { volume = 0f; playWhenReady = true }
  }
  DisposableEffect(Unit) { onDispose { preview?.release() } }
  var previewing by remember { mutableStateOf<Int?>(null) }
  LaunchedEffect(s.focused?.first?.id) {
    val c = s.focused?.first ?: return@LaunchedEffect
    if (previewing == c.id || preview == null) return@LaunchedEffect
    previewing = null
    preview.stop()
    delay(1_500)
    preview.setMediaItem(MediaItem.fromUri(x.streamUrl(c)))
    preview.prepare()
    previewing = c.id
  }
  // 2. Channel numbers typed on the remote in the guide: jump to that channel after a moment.
  var typed by remember { mutableStateOf("") }
  LaunchedEffect(typed) {
    if (typed.isEmpty()) return@LaunchedEffect
    delay(1_500)
    val n = typed.toIntOrNull()
    typed = ""
    val c = s.channels.firstOrNull { it.num == n } ?: s.all.firstOrNull { it.num == n }
    if (c == null) { toast(ctx, "No channel $n"); return@LaunchedEffect }
    watch(s, x, nav, c, null)
  }
  // A category is a filter on the saved channels: quick, and nothing left over from the last one.
  LaunchedEffect(s.category, s.all, s.favs, s.recent, s.hidden, s.combine) {
    val c = s.category ?: return@LaunchedEffect
    if (s.moving != null) return@LaunchedEffect // moving a favourite keeps its own order
    s.channels = withContext(Dispatchers.Default) { s.listFor(c) }
  }
  RestoreFocus(s.focus, s.channels.isNotEmpty() && restore)
  var picking by remember { mutableStateOf(false) }
  val catButton = remember { FocusRequester() }
  // A panel closed: focus goes back to what opened it.
  LaunchedEffect(s.refocus) { if (s.refocus > 0) { delay(60); runCatching { s.returnTo?.requestFocus() } } }
  BackHandler(s.moving != null) { s.moving = null }
  // Paged away from now: Back comes back to it before it leaves the page.
  BackHandler(s.shift != 0 && !picking && s.moving == null) { backToNow(s) }

  Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown || picking) return@onPreviewKeyEvent false
    digit(e.key)?.let { typed = (typed + it).takeLast(4); true } ?: false
  }) {
    if (typed.isNotEmpty()) Text(typed, Modifier.align(Alignment.TopEnd).padding(top = 90.dp, end = Z.Pad).background(Z.Elevated, Z.Medium).padding(horizontal = 22.dp, vertical = 10.dp),
      style = MaterialTheme.typography.displaySmall)
    Column(Modifier.fillMaxSize().padding(top = 84.dp)) {
      Details(s, x, preview, previewing)
      // Category dropdown and the time ruler.
      val now = remember { System.currentTimeMillis() }
      val windowStart = now - now % (30 * 60_000) + s.shift * 3_600_000L // the current half hour, moved by any paging
      Row(Modifier.padding(start = Z.Pad, end = Z.Pad, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(CHANNEL_W), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          ZSurface({ picking = true }, Modifier.focusMemory(s.focus, "category").focusRequester(catButton), container = Z.Card) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(s.category?.name?.let { LiveTidy.tag(it).second } ?: "Categories", Modifier.widthIn(max = 118.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
              Spacer(Modifier.width(4.dp))
              Glyph(Icons.Filled.KeyboardArrowDown, 18.dp)
            }
          }
          ZSurface({ s.sports = true }, container = Z.Card) {
            Text("Sports", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
          }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
          val perMin = maxWidth / WINDOW_MIN.toFloat()
          repeat(WINDOW_MIN / 30 - if (s.shift != 0) 1 else 0) { i -> // the last label makes room for Back to Now
            val at = windowStart + i * 30 * 60_000L
            Text((if (i == 0 && day(at) != day(now)) SimpleDateFormat("EEE ", Locale.UK).format(Date(at)) else "") + hm(at), Modifier.offset(x = perMin * (i * 30f)), style = MaterialTheme.typography.labelMedium, color = Z.Muted)
          }
          if (s.shift != 0) ZSurface({ backToNow(s) }, Modifier.align(Alignment.CenterEnd), container = Z.Card) {
            Text("Back to Now", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
          }
        }
      }
      when {
        s.channels.isEmpty() && s.loading -> Loading("Loading your channels (once a day)…")
        s.channels.isEmpty() -> Text(s.error ?: when (s.category) {
          FAVS -> "No favourites yet. In any category, hold OK on a channel's name to add it."
          RECENT -> "Channels you watch will appear here."
          else -> "No channels in ${s.category?.name ?: "this category"}."
        }, Modifier.padding(start = Z.Pad, top = 24.dp), color = if (s.error != null) Z.Error else Z.Text2)
        else -> Guide(store, x, s, nav, windowStart, now)
      }
    }
    if (picking) CategoryPicker(store, s, { picking = false; s.returnTo = catButton; s.refocus++ }) { c ->
      picking = false
      store.put("xc_cat", c.id)
      s.focused = null
      s.focus.key = null // the new list's first channel
      s.focusFirst = true
      s.category = c
    }
    // Hold OK on a channel: watch it (or a version you pick), favourite it, and in Favourites move it.
    s.channelMenu?.let { c ->
      val close: () -> Unit = { s.channelMenu = null; s.refocus++ }
      val fav = s.favs.orEmpty().any { it.id == c.id }
      val inFavs = s.category == FAVS
      val versions = remember(c.id, s.all, LiveStats.rev) { versionChain(s.all, c) }
      ActionPanel(c.label, listOfNotNull(x.from(c), "In your Favourites".takeIf { fav }).joinToString("  ·  "), close, buildList {
        add("Watch" to { s.channelMenu = null; watch(s, x, nav, c, null) })
        if (inFavs) add("Move" to { s.channelMenu = null; s.moving = c.id; s.shiftFocus++ })
        add((if (fav) "Remove from Favourites" else "Add to Favourites") to {
          if (inFavs) {
            // Focus moves to its neighbour, since the row it was on goes.
            val l = s.favs.orEmpty()
            val i = l.indexOfFirst { it.id == c.id }
            s.focus.key = (l.getOrNull(i + 1) ?: l.getOrNull(i - 1))?.let { "ch/${it.id}" }
            toggleFavourite(store, s, c)
            s.channels = s.favs.orEmpty()
            if (s.channels.isEmpty()) s.focused = null
            s.channelMenu = null
            s.shiftFocus++
          } else {
            toast(ctx, if (toggleFavourite(store, s, c)) "Added ${c.label} to Favourites" else "Removed ${c.label} from Favourites")
            close()
          }
        })
      }) {
        VersionList(x, c, versions) { v -> s.channelMenu = null; watch(s, x, nav, c, null, v) }
      }
    }
    s.details?.let { (c, p) -> ProgrammePanel(store, x, s, nav, c, p) }
  }
}

/** Back to the guide's present, with focus on the same channel. */
private fun backToNow(s: LiveState) {
  val c = s.focused?.first
  if (c != null && s.focus.key?.startsWith("ch/") != true) {
    val now = System.currentTimeMillis()
    s.focus.key = s.guide[c.id]?.find { now in it.start until it.end }?.let { "row/${c.id}/${it.start}" } ?: "ch/${c.id}"
  }
  s.shift = 0
  s.shiftFocus++
}

/**
 * A small panel over the page: a heading, a line, and its actions (the first is primary and takes focus). Back closes.
 * Focus can't leave it.
 */
@Composable
private fun ActionPanel(title: String, text: String, close: () -> Unit, actions: List<Pair<String, () -> Unit>>, body: @Composable ColumnScope.() -> Unit = {}) {
  BackHandler(onBack = close)
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
  Box(Modifier.fillMaxSize().background(Z.Bg.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 640.dp).background(Z.Surface, Z.Large).padding(28.dp).panel(close)) {
      Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
      if (text.isNotEmpty()) Text(text, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
      body()
      Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.forEachIndexed { i, (label, act) -> ZButton(label, act, if (i == 0) Modifier.focusRequester(first) else Modifier, primary = i == 0) }
      }
    }
  }
}

/** Hold OK on a programme: all of it, and what you can do with it. */
@Composable
private fun ProgrammePanel(store: Store, x: Providers, s: LiveState, nav: Nav, c: Channel, p: Programme) {
  val ctx = LocalContext.current
  val now = System.currentTimeMillis()
  val on = now in p.start until p.end
  val back = x.canCatchUp(c, p, now)
  val reminded = s.reminders.any { it.channel.id == c.id && it.start == p.start }
  val fav = s.favs.orEmpty().any { it.id == c.id }
  val close: () -> Unit = { s.details = null; s.refocus++ }
  val actions = buildList<Pair<String, () -> Unit>> {
    when {
      on -> add("Watch" to { s.details = null; watch(s, x, nav, c, null) })
      back -> add("Watch from the Start" to { s.details = null; watch(s, x, nav, c, p) })
      p.start > now -> add((if (reminded) "Remove Reminder" else "Remind Me") to { remind(ctx, store, s, c, p); close() })
      else -> add("Watch ${c.label}" to { s.details = null; watch(s, x, nav, c, null) })
    }
    add((if (fav) "Remove from Favourites" else "Add to Favourites") to {
      toast(ctx, if (toggleFavourite(store, s, c)) "Added ${c.label} to Favourites" else "Removed ${c.label} from Favourites")
      close()
    })
  }
  ActionPanel(p.title, "", close, actions) {
    MetaLine(listOfNotNull(c.label, SimpleDateFormat("EEE d MMM", Locale.UK).format(Date(p.start)).takeIf { day(p.start) != day(now) }, "${hm(p.start)} – ${hm(p.end)}",
      when { on -> "On now"; back -> "In the archive"; p.end <= now -> "Ended"; reminded -> "Reminder set"; else -> "${(p.end - p.start) / 60_000} min" }),
      Modifier.padding(top = 6.dp))
    if (on) ProgressBar((now - p.start).toFloat() / (p.end - p.start), Modifier.padding(top = 10.dp).width(240.dp))
    // Every version this entry plays from, in play order: pick one to watch it (live), or see what was merged.
    val versions = remember(c.id, s.all, LiveTidy.merging, LiveStats.rev) { versionChain(s.all, c) }
    Text(p.description.ifBlank { "No description in the guide." }, Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodyLarge,
      color = Z.Text2, maxLines = if (versions.size > 1) 3 else 7, overflow = TextOverflow.Ellipsis)
    if (versions.size > 1) VersionList(x, c, versions) { v -> s.details = null; watch(s, x, nav, c, null, v) }
  }
}

private val CHANNEL_W = 250.dp
private const val WINDOW_MIN = 180 // the guide shows three hours

/** What's focused: the programme's title, time, a progress bar if it's on now, and its description. */
@Composable
private fun Details(s: LiveState, x: Providers, preview: ExoPlayer?, previewing: Int?) {
  val (c, p) = s.focused ?: return Spacer(Modifier.height(150.dp))
  Row(Modifier.height(150.dp).padding(horizontal = Z.Pad), verticalAlignment = Alignment.CenterVertically) {
    // 4. The focused channel playing, muted, once you've rested on it; its logo until then.
    Box(Modifier.size(if (preview != null) 216.dp else 96.dp, 122.dp).clip(Z.Medium).background(Z.Card), contentAlignment = Alignment.Center) {
      val logo = Logos.of(c)
      if (logo != null) AsyncImage(logo, null, Modifier.size(96.dp).padding(12.dp), contentScale = ContentScale.Fit)
      else Text(c.label.take(2).uppercase(), style = MaterialTheme.typography.titleLarge, color = Z.Text2)
      if (preview != null && previewing == c.id) AndroidView({ PlayerView(it).apply { useController = false; player = preview } }, Modifier.fillMaxSize())
    }
    Spacer(Modifier.width(24.dp))
    Column(Modifier.weight(1f)) {
      Row {
        Text(listOfNotNull(c.label, x.from(c)).joinToString("  ·  "), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Z.Text2, maxLines = 1)
        val moving = s.moving?.let { id -> s.favs.orEmpty().find { it.id == id } }
        Text(if (moving != null) "Moving ${moving.label}:  ▲ ▼ to move  ·  OK when it's in place"
          else "Hold OK on a channel or show: versions, favourite, details  ·  ◀ ▶ past the edge: earlier / later",
          style = MaterialTheme.typography.labelSmall, color = if (moving != null) Z.Text else Z.Muted)
      }
      Text(p?.title ?: "No guide information", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (p != null) {
        val now = System.currentTimeMillis()
        val on = now in p.start until p.end
        val back = x.canCatchUp(c, p, now)
        MetaLine(listOfNotNull(SimpleDateFormat("EEE", Locale.UK).format(Date(p.start)).takeIf { day(p.start) != day(now) }, "${hm(p.start)} – ${hm(p.end)}",
          when { on -> "On now"; back -> "↺ Watch from the start"; p.end <= now -> "Ended"; else -> "${(p.end - p.start) / 60_000} min" }),
          Modifier.padding(top = 4.dp), color = if (on || back) Z.Text else Z.Text2)
        if (on) ProgressBar((now - p.start).toFloat() / (p.end - p.start), Modifier.padding(top = 6.dp).width(240.dp))
        if (p.description.isNotBlank()) Text(p.description, Modifier.padding(top = 6.dp).fillMaxWidth(0.8f), style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

/**
 * The channel guide: one row per channel, its programmes on a three-hour timeline, with a line at "now".
 * Left/Right past the edge pages the guide an hour earlier or later (back a day for catch-up, ahead a day).
 * OK plays the channel, or a past programme from the archive; holding OK adds or removes a favourite.
 */
@Composable
private fun Guide(store: Store, x: Providers, s: LiveState, nav: Nav, windowStart: Long, now: Long) {
  val ctx = LocalContext.current
  val windowEnd = windowStart + WINDOW_MIN * 60_000L
  val list = rememberLazyListState()
  val focusManager = LocalFocusManager.current
  LaunchedEffect(s.shiftFocus) { if (s.shiftFocus > 0) { delay(60); runCatching { s.focus.requester.requestFocus() } } }
  // A new category: focus on its first channel once the list is in.
  LaunchedEffect(s.channels) { if (s.focusFirst && s.channels.isNotEmpty()) { s.focusFirst = false; list.scrollToItem(0); delay(80); runCatching { s.focus.requester.requestFocus() } } }
  /** Moves the guide an hour and puts focus on the same channel's programme at the edge it came in from. */
  fun page(by: Int) {
    val next = (s.shift + by).coerceIn(-24, 24)
    if (next == s.shift) return
    val c = s.focused?.first ?: return
    val from = windowStart + (next - s.shift) * 3_600_000L
    val shows = s.guide[c.id].orEmpty().filter { it.end > from && it.start < from + WINDOW_MIN * 60_000L }
    val target = if (by > 0) shows.firstOrNull() else shows.lastOrNull()
    if (s.focus.key?.startsWith("ch/") != true) s.focus.key = target?.let { "row/${c.id}/${it.start}" } ?: "row/${c.id}/none"
    s.shift = next
    s.shiftFocus++
  }
  /** Moving a favourite: swaps it with its neighbour, and focus goes with it. */
  fun move(by: Int) {
    val id = s.moving ?: return
    val l = s.favs.orEmpty().toMutableList()
    val i = l.indexOfFirst { it.id == id }
    if (i < 0 || i + by !in l.indices) return
    java.util.Collections.swap(l, i, i + by)
    s.favs = l
    s.channels = l
    Xtream.setFavourites(store, l)
    s.focus.key = "ch/$id"
    s.shiftFocus++
  }
  BoxWithConstraints(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
    if (s.moving != null) {
      val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
      if (e.type == KeyEventType.KeyDown) when (e.key) {
        Key.DirectionUp -> move(-1)
        Key.DirectionDown -> move(1)
        else -> if (ok && e.nativeKeyEvent.repeatCount == 0) s.moving = null
      }
      return@onPreviewKeyEvent ok || e.key in setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)
    }
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (e.key) {
      Key.DirectionRight -> { if (!focusManager.moveFocus(FocusDirection.Right)) page(1); true }
      Key.DirectionLeft -> { if (!focusManager.moveFocus(FocusDirection.Left)) page(-1); true }
      else -> false
    }
  }) {
    val perMin = (maxWidth - CHANNEL_W - Z.Pad * 2) / WINDOW_MIN.toFloat()
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 40.dp)) {
      itemsIndexed(s.channels, key = { _, c -> c.id }) { i, c ->
        // Each row loads its own guide when it scrolls into view, a few at a time.
        LaunchedEffect(c.id) {
          if (c.id !in s.guide) s.guide[c.id] = s.gate.withPermit { withContext(Dispatchers.IO) { bestGuide(x, c) } }
        }
        val shows = s.guide[c.id].orEmpty().filter { it.end > windowStart && it.start < windowEnd }
        val fav = s.favs.orEmpty().any { it.id == c.id }
        fun open(p: Programme?) {
          // Not on yet: OK sets (or clears) a reminder instead of playing.
          if (p != null && p.start > System.currentTimeMillis()) remind(ctx, store, s, c, p) else watch(s, x, nav, c, p)
        }
        fun favourite() {
          s.channelMenu = c // watch a version, favourite, move
        }
        Row(Modifier.padding(start = Z.Pad, end = Z.Pad).height(64.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
          // Channel: number, logo, name (★ for a favourite). Up/Down moves down the channels here; Right goes into the
          // programmes. OK plays the channel; holding OK adds it to or removes it from Favourites.
          val self = remember { FocusRequester() }
          ZSurface({ open(null) }, Modifier.width(CHANNEL_W).fillMaxHeight().padding(end = 6.dp)
            .focusMemory(s.focus, "ch/${c.id}", default = s.focus.key == null && i == 0).focusRequester(self)
            .onFocusChanged { if (it.isFocused) s.focused = c to s.guide[c.id].orEmpty().find { p -> now in p.start until p.end } },
            selected = s.moving == c.id, container = Z.Surface, onLongClick = { s.returnTo = self; favourite() }) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(if (c.num > 0) "${c.num}" else "", Modifier.width(38.dp), style = MaterialTheme.typography.labelMedium, color = Z.Muted)
              Box(Modifier.size(40.dp, 30.dp), contentAlignment = Alignment.Center) {
                Logos.of(c)?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
              }
              Spacer(Modifier.width(10.dp))
              if (fav) Box(Modifier.padding(end = 6.dp)) { Glyph(androidx.compose.material.icons.Icons.Filled.Star, 14.dp, Z.Text, "Favourite") }
              Text(c.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
          Box(Modifier.weight(1f).fillMaxHeight()) {
            if (shows.isEmpty()) {
              Cell(if (s.guide.containsKey(c.id)) "No guide information" else "", Modifier.fillMaxSize(), live = true, past = false, s, c, null, "row/${c.id}/none", { open(null) }, ::favourite)
            } else shows.forEach { p ->
              val from = maxOf(p.start, windowStart)
              val to = minOf(p.end, windowEnd)
              val back = x.canCatchUp(c, p, now)
              val reminded = s.reminders.any { it.channel.id == c.id && it.start == p.start }
              Cell((if (back) "↺  " else if (reminded) "🔔  " else "") + p.title, Modifier.offset(x = perMin * ((from - windowStart) / 60_000f)).width((perMin * ((to - from) / 60_000f) - 4.dp).coerceAtLeast(6.dp)).fillMaxHeight(),
                now in p.start until p.end, p.end <= now && !back, s, c, p, "row/${c.id}/${p.start}", { open(p) }) { s.details = c to p }
            }
          }
        }
      }
    }
    // "Now" line across the timeline, when now is in view.
    if (now in windowStart until windowEnd) {
      val nowX = CHANNEL_W + Z.Pad + perMin * ((now - windowStart) / 60_000f)
      Box(Modifier.offset(x = nowX).width(2.dp).fillMaxHeight().background(Brush.verticalGradient(listOf(Z.Red, Z.Red.copy(alpha = 0.15f)))))
    }
  }
}

@Composable
private fun Cell(text: String, modifier: Modifier, live: Boolean, past: Boolean, s: LiveState, c: Channel, p: Programme?, key: String, play: () -> Unit, hold: () -> Unit) {
  val self = remember { FocusRequester() } // a panel opened from here gives focus back here
  ZSurface(play, modifier.focusMemory(s.focus, key).focusRequester(self)
    .onFocusChanged { if (it.isFocused) s.focused = c to p }, container = if (live) Z.Elevated else if (past) Z.Surface else Z.Card, onLongClick = { s.returnTo = self; hold() }) {
    Text(text, Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge,
      color = when { live -> Z.Text; past -> Z.Muted; else -> Z.Text2 }, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/**
 * The category dropdown: a panel over the guide; Back closes it. Names lose the provider's country tag (shown small on
 * the right, with how many channels each holds). Holding OK hides a category, or every one with its tag; hidden ones
 * wait at the bottom under Hidden.
 */
@Composable
private fun CategoryPicker(store: Store, s: LiveState, close: () -> Unit, pick: (Category) -> Unit) {
  val current = s.category ?: ALL
  var menu by remember { mutableStateOf<Category?>(null) } // held OK: hide it, or show it again
  BackHandler(menu == null, onBack = close)
  val first = remember { FocusRequester() }
  val counts = remember(s.all) { HashMap<String, Int>().also { m -> s.all.forEach { ch -> ch.category.split(',').forEach { id -> m[id] = (m[id] ?: 0) + 1 } } } }
  val (off, on) = s.cats.partition { LiveTidy.hidden(it, s.hidden) }
  val rows: List<Category?> = listOf(FAVS, RECENT, ALL) + on + (if (off.isEmpty()) emptyList() else listOf(null) + off) // null: the Hidden heading
  val at = rows.indexOfFirst { it?.id == current.id }.coerceAtLeast(0)
  // Open scrolled to the current category: a row further down wasn't composed yet, the focus request failed
  // silently, and focus stayed on the guide behind the menu. Same after hiding one (its row may have moved).
  val list = rememberLazyListState(initialFirstVisibleItemIndex = at)
  LaunchedEffect(menu) {
    if (menu != null) return@LaunchedEffect
    if (list.layoutInfo.visibleItemsInfo.none { it.index == at }) list.scrollToItem(at)
    delay(60)
    runCatching { first.requestFocus() }
  }
  fun count(c: Category) = when (c) { FAVS -> s.favs.orEmpty().size; RECENT -> s.recent.size; ALL -> null; else -> c.ids.sumOf { counts[it] ?: 0 } }
  Box(Modifier.fillMaxSize().background(Z.Bg.copy(alpha = 0.7f))) {
    // Focus can't leave the menu while it's open (except into the hide panel): pick a category or press Back.
    Column(Modifier.padding(start = Z.Pad, top = 84.dp).width(380.dp).fillMaxHeight().background(Z.Surface, Z.Large).padding(vertical = 14.dp)
      .panel(close) { menu == null }) {
      Text("Categories", Modifier.padding(start = 20.dp, bottom = 2.dp), style = MaterialTheme.typography.titleLarge)
      Text("Hold OK on one to hide it", Modifier.padding(start = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = Z.Muted)
      LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 10.dp)) {
        itemsIndexed(rows) { i, c ->
          if (c == null) {
            Text("Hidden", Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = Z.Muted)
            return@itemsIndexed
          }
          val selected = c.id == current.id
          val (tag, name) = LiveTidy.tag(c.name)
          ZSurface({ pick(c) }, (if (i == at) Modifier.focusRequester(first) else Modifier).fillMaxWidth().padding(vertical = 2.dp).focusProperties { canFocus = menu == null },
            selected = selected, onLongClick = if (c == FAVS || c == RECENT || c == ALL) null else ({ menu = c })) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
              if (tag != null) Text(tag, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelSmall)
              count(c)?.let { Text("$it", Modifier.padding(start = 10.dp).widthIn(min = 30.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End) }
            }
          }
        }
      }
    }
  }
  menu?.let { c ->
    val (tag, name) = LiveTidy.tag(c.name)
    val isHidden = LiveTidy.hidden(c, s.hidden)
    val sameTag = tag?.let { t -> s.cats.count { LiveTidy.tag(it.name).first == t } } ?: 0
    fun save(set: Set<String>) { s.hidden = set; Xtream.setHidden(store, set); menu = null }
    val actions = buildList<Pair<String, () -> Unit>> {
      if (!isHidden) {
        add("Hide" to { save(s.hidden + "cat:${c.id}") })
        if (tag != null && sameTag > 1) add("Hide All $sameTag $tag Categories" to { save(s.hidden + "pre:$tag") })
      } else {
        if ("cat:${c.id}" in s.hidden) add("Show Again" to { save(s.hidden - "cat:${c.id}") })
        if ("pre:$tag" in s.hidden) add("Show All $tag Categories Again" to { save(s.hidden - "pre:$tag") })
      }
      add("Cancel" to { menu = null })
    }
    ActionPanel(if (tag != null) "$name · $tag" else name,
      if (isHidden) "Showing it again puts it back in this list and in All Channels."
      else "Hidden categories leave this list and All Channels, and wait at the bottom under Hidden. Favourites keep their channels.",
      { menu = null }, actions)
  }
}

/**
 * Full-screen live channel. Up/Down (or Channel +/-) changes channel; the banner shows what's on. OK shows the banner,
 * then the channel list; holding OK (or Menu) opens audio, subtitles, pause and favourite.
 */
@Composable
fun LivePlayer(store: Store, s: LiveState, nav: Nav) {
  val ctx = LocalContext.current
  val x = remember { Providers.from(store) } ?: return
  var index by remember { mutableIntStateOf(s.playing.coerceIn(0, (s.channels.size - 1).coerceAtLeast(0))) }
  val channel = s.channels.getOrNull(index) ?: return
  var error by remember { mutableStateOf<String?>(null) }
  var banner by remember { mutableIntStateOf(0) } // bumps to show the banner again
  // A past programme from the archive, until you change channel (which goes back to live).
  var past by remember { mutableStateOf(s.catchup?.takeIf { it.first.id == channel.id }) }
  // Back in the guide, focus is on the channel you were last watching (it may have changed since you came in).
  val watching by rememberUpdatedState(channel)
  DisposableEffect(Unit) { onDispose { s.catchup = null; if (s.channels.any { it.id == watching.id }) { s.focus.key = "ch/${watching.id}"; s.focused = null } } }
  var showBanner by remember { mutableStateOf(true) }

  // --- A stream that drops is tried again (three times, a little longer apart each time), then the channel's other
  // versions (HD, SD, 4K), before you see an error. Live streams stall far more often than they truly fail. ---
  var version by remember { mutableStateOf<Channel?>(null) } // another version standing in for this channel
  var attempt by remember { mutableIntStateOf(0) } // bumps to connect again
  var retries by remember { mutableIntStateOf(0) }
  var started by remember { mutableStateOf(false) } // the version playing now has shown a picture
  var reconnecting by remember { mutableStateOf(false) }
  val tried = remember { mutableSetOf<Int>() }
  var failure by remember { mutableStateOf<Pair<Int, String>?>(null) } // (how many, the player's code): bumps on each drop
  fun fail(code: String) { failure = ((failure?.first ?: 0) + 1) to code }
  fun fresh() { version = null; attempt = 0; retries = 0; started = false; tried.clear(); reconnecting = false; failure = null; error = null }
  LaunchedEffect(s.playing) { if (s.playing != index && s.playing in s.channels.indices) { past = null; fresh(); index = s.playing } } // switched from outside (a reminder)

  var tracks by remember { mutableStateOf(Tracks.EMPTY) }
  var paused by remember { mutableStateOf(false) }
  var playing by remember { mutableStateOf(false) }
  LiveStats.load(store)
  LaunchedEffect(Unit) { LiveStats.checkScreen(ctx) }
  // Every byte of video that arrives, counted: a live stream arrives at its own bitrate, so that's how it's measured.
  val received = remember { java.util.concurrent.atomic.AtomicLong() }
  val counter = remember {
    object : androidx.media3.datasource.TransferListener {
      override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
      override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
      override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytes: Int) { received.addAndGet(bytes.toLong()) }
      override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
    }
  }
  // The audio as it's decoded, for subtitles made on the TV (Settings > Live TV): while they show, sound and picture
  // are held back a few seconds so each line is written before it's said. Idle otherwise.
  val tap = remember { LiveCaptions.Tap() }
  val player = remember {
    ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(DefaultHttpDataSource.Factory().setUserAgent(UA).setAllowCrossProtocolRedirects(true).setTransferListener(counter)))
      // Up to 90 s held ahead, so a short pause on live carries on where it stopped.
      .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 90_000, 2_500, 5_000).build())
      .setRenderersFactory(object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
          androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).setAudioProcessorChain(LiveCaptions.Chain(tap)).build()
      })
      .build().apply { playWhenReady = true }
  }
  // Subtitles from the audio: on for this player when you chose them (and kept on, channel to channel, until you don't).
  var captioner by remember { mutableStateOf<LiveCaptions.Captioner?>(null) }
  fun captions(on: Boolean) {
    captioner?.stop(); tap.listener = null; captioner = null
    if (on && LiveCaptions.on) captioner = LiveCaptions.Captioner(tap).start().also { tap.listener = it }
    store.put("live_captions_show", if (on) "on" else "")
  }
  LaunchedEffect(Unit) { if (LiveCaptions.on && store.str("live_captions_show") == "on") captions(true) }
  DisposableEffect(Unit) { onDispose { captioner?.stop(); tap.listener = null } }
  DisposableEffect(Unit) {
    val l = object : Player.Listener {
      override fun onPlayerError(e: PlaybackException) {
        // Fallen behind what the provider still holds (after a pause): back to live, no fuss.
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { player.seekToDefaultPosition(); player.prepare(); return }
        fail(e.errorCodeName)
      }
      override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED && past == null) fail("STREAM_ENDED") } // live never ends: it dropped
      override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying; if (isPlaying) { started = true; retries = 0; reconnecting = false; LiveStats.worked((version ?: channel).id) } }
      override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { paused = !playWhenReady }
      override fun onTracksChanged(t: Tracks) { tracks = t }
    }
    player.addListener(l)
    onDispose { player.removeListener(l); player.release() }
  }
  LaunchedEffect(failure) {
    val (_, code) = failure ?: return@LaunchedEffect
    if (past != null) { error = "This programme isn't in the provider's archive ($code). Try another, or try again later."; return@LaunchedEffect }
    // A version that played and dropped is tried again; one that never started gives way to the next one down at once
    // (in play order from where it was, then any left above it, for a version you picked yourself).
    val chain = versionChain(s.all, channel)
    val at = chain.indexOfFirst { it.id == (version ?: channel).id }
    val other = (chain.drop(at + 1) + chain.take(at.coerceAtLeast(0))).firstOrNull { it.id !in tried }
    if ((started || other == null) && retries < 3) { retries++; reconnecting = true; delay(1_500L * retries); attempt++; return@LaunchedEffect }
    if (other != null) { if (!started) LiveStats.failed((version ?: channel).id); retries = 0; started = false; version = other; return@LaunchedEffect }
    reconnecting = false
    error = "This channel didn't start ($code). Try another, or try again later."
  }
  LaunchedEffect(channel.id, past, version, attempt) {
    error = null
    // A new channel starts on the version you picked, else the first in play order (your pinned one, else the best).
    if (version == null && attempt == 0 && past == null) {
      val first = s.startWith?.takeIf { LiveTidy.group(it.name) == LiveTidy.group(channel.name) } ?: versionChain(s.all, channel).first()
      s.startWith = null
      if (first.id != channel.id) { version = first; return@LaunchedEffect }
    }
    val stream = version ?: channel
    tried += stream.id
    player.setMediaItem(MediaItem.fromUri(past?.let { (c, p) -> x.catchupUrl(c, p) } ?: x.streamUrl(stream)))
    player.prepare()
    player.playWhenReady = true
    s.playing = index
    // Not playing when it should be, with no error (the provider stopped sending, or the player sat waiting), counts as
    // a drop too: after 15 s, or 10 s for a version that hasn't shown a picture yet, so a dead one hands over sooner.
    // Any state counts, not just loading: a player left idle or ready-but-still was what kept "Trying…" up for good.
    var stuck = 0
    while (true) {
      delay(1_000)
      val waiting = player.playWhenReady && !player.isPlaying && error == null &&
        player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
      stuck = if (waiting) stuck + 1 else 0
      if (stuck >= (if (started) 15 else 10)) { stuck = 0; fail("STALLED") }
    }
  }
  // The guide on its own, so a slow provider never holds up the watch above.
  LaunchedEffect(channel.id) { if (channel.id !in s.guide) s.guide[channel.id] = withContext(Dispatchers.IO) { bestGuide(x, channel) } }
  LaunchedEffect(channel.id, past) { if (past == null) s.recent = Xtream.addRecent(store, channel) } // reconnects don't count
  // Measuring: 20 s in (past the first rush that fills the buffer), what arrives over the next 20 s is the stream's
  // bitrate. Saved with the picture height and codec, for the version lists and to order versions with the same label.
  LaunchedEffect(started, version?.id ?: channel.id, past) {
    if (!started || past != null) return@LaunchedEffect
    val stream = version ?: channel
    delay(20_000)
    if (!player.isPlaying) return@LaunchedEffect
    val (b1, t1) = received.get() to System.currentTimeMillis()
    delay(20_000)
    if (!player.isPlaying) return@LaunchedEffect
    val kbps = ((received.get() - b1) * 8 / (System.currentTimeMillis() - t1).coerceAtLeast(1)).toInt()
    val f = player.videoFormat
    LiveStats.put(stream.id, LiveStats.Measured(f?.height?.takeIf { it > 0 } ?: 0, LiveStats.codec(f?.sampleMimeType ?: f?.codecs), kbps))
  }
  LaunchedEffect(index, banner) { showBanner = true; delay(5_000); showBanner = false }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
  // 1. Last channel: Left on live flips back to the channel you were on before.
  var last by remember { mutableStateOf<Channel?>(null) }
  /** Switch to a channel. The same one keeps playing, but its place is found again (the list may have just changed). */
  fun go(c: Channel) {
    if (c.id != channel.id) { last = channel; past = null; fresh() }
    index = s.channels.indexOfFirst { it.id == c.id }.takeIf { it >= 0 } ?: run { s.channels = listOf(c) + s.channels; 0 }
    s.playing = index
  }
  fun zap(step: Int) { if (s.channels.isNotEmpty()) go(s.channels[(index + step + s.channels.size) % s.channels.size]) }
  // 2. Channel numbers typed on the remote: jump after a moment.
  var typed by remember { mutableStateOf("") }
  LaunchedEffect(typed) {
    if (typed.isEmpty()) return@LaunchedEffect
    delay(1_500)
    val n = typed.toIntOrNull()
    typed = ""
    val c = s.channels.firstOrNull { it.num == n } ?: s.all.firstOrNull { it.num == n }
    if (c != null) go(c) else toast(ctx, "No channel $n")
  }
  // 3. Mini guide: OK on live opens a channel list over the picture (now and next); OK there switches.
  var mini by remember { mutableStateOf(false) }
  var options by remember { mutableStateOf(false) }
  BackHandler(mini) { mini = false }
  LaunchedEffect(mini, options) { if (!mini && !options) { delay(60); runCatching { focus.requestFocus() } } }
  fun togglePause() { player.playWhenReady = !player.playWhenReady; banner++ }
  fun ok() {
    when {
      past != null || paused -> togglePause() // catch-up pauses; paused live carries on
      showBanner -> mini = true // OK again with the banner up: the channel list
    }
    banner++
  }
  // OK is read as press-and-release, so holding it can open the options; its repeats and release stay here.
  var okDown by remember { mutableStateOf(false) }
  var okHeld by remember { mutableStateOf(false) }
  Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).onPreviewKeyEvent { e ->
    val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
    if (isOk && okDown) {
      if (e.type == KeyEventType.KeyUp) { if (!okHeld) ok(); okDown = false }
      else if (!okHeld && e.nativeKeyEvent.repeatCount > 0) { okHeld = true; options = true }
      return@onPreviewKeyEvent true
    }
    if (e.type != KeyEventType.KeyDown || mini || options) return@onPreviewKeyEvent false
    if (isOk) { okDown = true; okHeld = false; return@onPreviewKeyEvent true }
    digit(e.key)?.let { typed = (typed + it).takeLast(4); return@onPreviewKeyEvent true }
    when (e.key) {
      Key.DirectionUp, Key.ChannelUp -> { zap(-1); true }
      Key.DirectionDown, Key.ChannelDown -> { zap(1); true }
      // Catch-up can be paused and skipped through; on live, Left is the last channel.
      Key.DirectionLeft -> { if (past != null) player.seekTo((player.currentPosition - 30_000).coerceAtLeast(0)) else last?.let(::go); banner++; true }
      Key.DirectionRight -> { if (past != null) player.seekTo(player.currentPosition + 30_000); banner++; true }
      Key.MediaPlayPause -> { togglePause(); true }
      Key.MediaPause -> { player.playWhenReady = false; banner++; true }
      Key.MediaPlay -> { player.playWhenReady = true; banner++; true }
      Key.Menu -> { options = true; true }
      Key.Info, Key.Guide -> { if (past == null) mini = true; banner++; true }
      else -> false
    }
  }.focusable()) { // focusable: without it the key handler above never had focus, so OK and Up/Down did nothing
    AndroidView({ PlayerView(it).apply {
      useController = false; this.player = player; keepScreenOn = true
      isFocusable = false; descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
    } }, Modifier.fillMaxSize())
    error?.let { Text(it, Modifier.align(Alignment.Center).padding(40.dp), style = MaterialTheme.typography.titleMedium, color = Z.Text) }
    if (error == null && (reconnecting || (version != null && !playing))) {
      Text(version?.let { "Trying ${it.name}…" } ?: "Reconnecting…", Modifier.align(Alignment.TopStart).padding(36.dp).background(Z.Bg.copy(alpha = 0.85f), Z.Medium)
        .padding(horizontal = 18.dp, vertical = 10.dp), style = MaterialTheme.typography.titleMedium)
    }
    if (showBanner || (paused && past == null)) {
      val now = System.currentTimeMillis()
      val shows = s.guide[channel.id].orEmpty()
      val on = past?.second ?: shows.find { now in it.start until it.end }
      val next = shows.firstOrNull { it.start >= (on?.end ?: now) }
      Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Z.Bg.copy(alpha = 0.95f))))
        .padding(start = Z.Pad, end = Z.Pad, top = 60.dp, bottom = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(84.dp).background(Z.Card, Z.Medium), contentAlignment = Alignment.Center) {
          val logo = Logos.of(channel)
          if (logo != null) AsyncImage(logo, null, Modifier.fillMaxSize().padding(10.dp), contentScale = ContentScale.Fit)
          else Text(channel.label.take(2).uppercase(), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
          MetaLine(listOfNotNull(channel.num.takeIf { it > 0 }?.toString(), channel.label, x.from(version ?: channel), version?.let { "Playing ${it.name}" },
            when { past != null -> "Catch-up · ${hm(past!!.second.start)}"; paused -> "Paused"; else -> "Live" }))
          Text(on?.title ?: "No guide information", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (past != null) ProgressBar(if (player.duration > 0) player.currentPosition.toFloat() / player.duration else 0f, Modifier.padding(top = 8.dp).width(320.dp))
          else if (on != null) ProgressBar((now - on.start).toFloat() / (on.end - on.start), Modifier.padding(top = 8.dp).width(320.dp))
          if (past == null) next?.let { Text("Next  ${hm(it.start)}  ${it.title}", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 1) }
        }
        Text(when {
          past != null -> "◀ ▶  30 s  ·  OK  Pause  ·  ▲ ▼  Back to live"
          paused -> "OK  Carry on  ·  ▲ ▼  Channel"
          else -> "▲ ▼  Channel  ·  ◀  Last channel  ·  OK  Channel list  ·  Hold OK  Audio & subtitles"
        }, style = MaterialTheme.typography.labelMedium, color = Z.Muted)
      }
    }
    if (typed.isNotEmpty()) Text(typed, Modifier.align(Alignment.TopEnd).padding(36.dp).background(Z.Bg.copy(alpha = 0.85f), Z.Medium).padding(horizontal = 22.dp, vertical = 10.dp),
      style = MaterialTheme.typography.displaySmall)
    // Subtitles made from the audio: like broadcast ones, white on a dark band, lifted above the banner when it shows.
    captioner?.let { cc ->
      val text = if (cc.starting) "Starting subtitles…" else cc.lines
      if (text.isNotBlank()) Text(text, Modifier.align(Alignment.BottomCenter).padding(bottom = if (showBanner || (paused && past == null)) 190.dp else 48.dp)
        .widthIn(max = 900.dp).background(Color.Black.copy(alpha = 0.72f), Z.Small).padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    if (mini) MiniGuide(s, x, channel, { mini = false }) { c, cat, list ->
      mini = false
      // Picked from another category: it's the list now, and the guide comes back on this channel.
      if (cat != null) { s.category = cat; store.put("xc_cat", cat.id); s.channels = list; s.focused = null; s.focus.key = "ch/${c.id}" }
      go(c)
    }
    if (options) {
      val versions = remember(channel.id, s.all, LiveStats.rev) { if (past == null) versionChain(s.all, channel) else emptyList() }
      LiveOptions(player, tracks, paused, past != null, s.favs.orEmpty().any { it.id == channel.id }, ::togglePause,
        { toast(ctx, if (toggleFavourite(store, s, channel)) "Added ${channel.label} to Favourites" else "Removed ${channel.label} from Favourites") },
        versions = { if (versions.size > 1) { item { OptionHeading("Version") }; items(versions, key = { it.id }) { v -> VersionRow(x, channel, v, v.id == (version ?: channel).id, v.id in sameLabels(x, versions)) {
          // Switch now; if it won't start, the next one down takes over as usual.
          options = false
          if (v.id != (version ?: channel).id) { tried.clear(); retries = 0; started = false; failure = null; error = null; version = v }
        } } } },
        made = if (LiveCaptions.on && past == null) captioner != null else null, setMade = { captions(it) }) { options = false }
    }
  }
}

/** A digit key (remote number keys or a keyboard's), or null. */
fun digit(k: Key): Int? = when (k) {
  Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2; Key.Three, Key.NumPad3 -> 3; Key.Four, Key.NumPad4 -> 4
  Key.Five, Key.NumPad5 -> 5; Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7; Key.Eight, Key.NumPad8 -> 8; Key.Nine, Key.NumPad9 -> 9
  else -> null
}

/**
 * The channel list over the picture: what's on now and next on each; OK switches, Back closes. Left opens the
 * categories beside it, so another category can be browsed without leaving the picture.
 */
@Composable
private fun MiniGuide(s: LiveState, x: Providers, current: Channel, close: () -> Unit, pick: (Channel, Category?, List<Channel>) -> Unit) {
  var browse by remember { mutableStateOf<Category?>(null) } // another category, until you pick from it
  var cats by remember { mutableStateOf(false) }
  val channels by produceState(s.channels, browse) { value = browse?.let { b -> withContext(Dispatchers.Default) { s.listFor(b) } } ?: s.channels }
  val target = channels.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
  val list = rememberLazyListState(initialFirstVisibleItemIndex = (target - 2).coerceAtLeast(0))
  val here = remember { FocusRequester() }
  val catHere = remember { FocusRequester() }
  LaunchedEffect(channels, cats) {
    if (cats) { delay(60); runCatching { catHere.requestFocus() }; return@LaunchedEffect }
    if (list.layoutInfo.visibleItemsInfo.none { it.index == target }) list.scrollToItem((target - 2).coerceAtLeast(0))
    delay(80)
    runCatching { here.requestFocus() }
  }
  val shownCat = browse ?: s.category ?: ALL
  val now = System.currentTimeMillis()
  Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Z.Bg.copy(alpha = 0.92f), 0.6f to Z.Bg.copy(alpha = 0.6f), 1f to Color.Transparent))) {
    Row(Modifier.padding(start = Z.Pad, top = 36.dp, bottom = 24.dp).fillMaxHeight().panel({ if (cats) cats = false else close() })) {
      if (cats) {
        val all = listOf(FAVS, RECENT, ALL) + s.cats.filterNot { LiveTidy.hidden(it, s.hidden) }
        val at = all.indexOfFirst { it.id == shownCat.id }.coerceAtLeast(0)
        Column(Modifier.width(280.dp).padding(end = 14.dp)) {
          Text("Categories", style = MaterialTheme.typography.titleLarge)
          LazyColumn(Modifier.padding(top = 10.dp).onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { cats = false; true } else false
          }, state = rememberLazyListState(initialFirstVisibleItemIndex = (at - 2).coerceAtLeast(0)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(all) { i, c ->
              ZSurface({ browse = c.takeIf { it.id != s.category?.id }; cats = false }, (if (i == at) Modifier.focusRequester(catHere) else Modifier).fillMaxWidth(),
                selected = c.id == shownCat.id, container = Z.Card.copy(alpha = 0.7f)) {
                Text(LiveTidy.tag(c.name).second, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }
      }
      Column(Modifier.width(520.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
          Text(LiveTidy.tag(shownCat.name).second, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (!cats) Text("◀  Categories", Modifier.padding(start = 14.dp, bottom = 2.dp), style = MaterialTheme.typography.labelSmall, color = Z.Muted)
        }
        if (channels.isEmpty()) Text("No channels here.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
        LazyColumn(Modifier.padding(top = 10.dp).onPreviewKeyEvent { e ->
          if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { cats = true; true } else false
        }, state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
          itemsIndexed(channels, key = { _, c -> c.id }) { i, c ->
            LaunchedEffect(c.id) { if (c.id !in s.guide) s.guide[c.id] = s.gate.withPermit { withContext(Dispatchers.IO) { bestGuide(x, c) } } }
            val shows = s.guide[c.id].orEmpty()
            val on = shows.find { now in it.start until it.end }
            val next = shows.firstOrNull { it.start >= (on?.end ?: now) }
            ZSurface({ pick(c, browse, channels) }, (if (i == target) Modifier.focusRequester(here) else Modifier).fillMaxWidth(),
              selected = c.id == current.id, container = Z.Card.copy(alpha = 0.7f)) {
              Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (c.num > 0) "${c.num}" else "", Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium, color = Z.Muted)
                Column(Modifier.weight(1f)) {
                  Text(c.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  Text(listOfNotNull(on?.let { "Now ${it.title}" }, next?.let { "${hm(it.start)} ${it.title}" }).joinToString("  ·  ").ifEmpty { " " },
                    style = MaterialTheme.typography.labelSmall, color = Z.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
              }
            }
          }
        }
      }
    }
  }
}

/** Hold OK (or Menu) while watching: pause, favourite, and the channel's audio and subtitle tracks. Back closes. */
@Composable
private fun LiveOptions(player: ExoPlayer, tracks: Tracks, paused: Boolean, catchUp: Boolean, fav: Boolean, pause: () -> Unit, favourite: () -> Unit,
  versions: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
  made: Boolean? = null, setMade: (Boolean) -> Unit = {}, close: () -> Unit) { // made: subtitles from the audio (null: not set up)
  BackHandler(onBack = close)
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
  val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
  val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
  fun choose(g: Tracks.Group, i: Int) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(g.type, false)
      .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
  }
  fun subsOff() { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
  Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.4f to Color.Transparent, 1f to Z.Bg.copy(alpha = 0.9f))), contentAlignment = Alignment.CenterEnd) {
    Column(Modifier.padding(end = Z.Pad).width(440.dp).heightIn(max = 600.dp).background(Z.Surface, Z.Large).padding(24.dp)
      .panel(close)) {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ZButton(if (paused) "Carry On" else "Pause", { pause(); close() }, Modifier.focusRequester(first), primary = true)
        ZButton(if (fav) "Remove from Favourites" else "Add to Favourites", favourite)
      }
      if (paused && !catchUp) Text("Live picks up where it stopped, for about a minute and a half.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text2)
      LazyColumn {
        versions() // the channel's versions, to switch to one mid-programme
        item { OptionHeading("Audio") }
        if (audio.sumOf { it.length } <= 1) item { Text("This channel sends one audio track.", style = MaterialTheme.typography.bodySmall, color = Z.Text2) }
        else audio.forEach { g -> items(g.length) { i -> OptionRow(trackName(g.getTrackFormat(i), i + 1), g.isTrackSelected(i)) { choose(g, i) } } }
        item { OptionHeading("Subtitles") }
        if (text.isEmpty() && made == null) item { Text("This channel sends no subtitles. Settings > Live TV can make them from the audio.", style = MaterialTheme.typography.bodySmall, color = Z.Text2) }
        else {
          item { OptionRow("Off", text.none { it.isSelected } && made != true) { subsOff(); setMade(false) } }
          // Made on the TV from what's being said: for channels whose own subtitles don't come through.
          if (made != null) item { OptionRow("Made from the audio (English)", made) { subsOff(); setMade(true); close() } }
          text.forEach { g -> items(g.length) { i -> OptionRow(trackName(g.getTrackFormat(i), i + 1), g.isTrackSelected(i)) { setMade(false); choose(g, i) } } }
        }
      }
    }
  }
}

@Composable
private fun OptionHeading(t: String) = Text(t, Modifier.padding(top = 20.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium)

@Composable
private fun OptionRow(label: String, on: Boolean, pick: () -> Unit) = ZSurface(pick, Modifier.fillMaxWidth().padding(vertical = 2.dp), selected = on) {
  Text(label, Modifier.padding(horizontal = 12.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A track as a person would name it: its label or language, and its channels for audio. */
private fun trackName(f: Format, n: Int): String = listOfNotNull(
  f.label?.takeIf { it.isNotBlank() } ?: f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifEmpty { it } },
  when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> null },
).joinToString("  ·  ").ifEmpty { if (f.sampleMimeType in setOf(androidx.media3.common.MimeTypes.APPLICATION_CEA608, androidx.media3.common.MimeTypes.APPLICATION_CEA708)) "Closed captions" else "Track $n" }
