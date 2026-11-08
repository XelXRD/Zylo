// A better TV guide: XMLTV files (the provider's own full guide, plus free per-country guides from epgshare01.online)
// read as they download, trimmed to yesterday…+2 days, cached on disk, and matched to the provider's channels by
// EPG id or by name. Used to fill gaps in the provider's guide and to find which channels show a sporting event.
package tv.zylo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

/** A guide channel: its XMLTV id and display names. */
data class GuideChannel(val id: String, val names: List<String>)

class Guide(val channels: Map<String, GuideChannel>, val programmes: Map<String, List<Programme>>) {
  private val byId = channels.keys.associateBy { it.lowercase() }
  private val byName = HashMap<String, String>().apply {
    channels.values.forEach { c -> (c.names + idName(c.id)).forEach { n -> putIfAbsent(channelKey(n), c.id) } }
  }

  /** The guide channel for one of the provider's channels: its EPG id, else its name. */
  fun idFor(c: Channel): String? = c.epgId?.lowercase()?.let(byId::get)?.takeIf { programmes.containsKey(it) }
    ?: byName[channelKey(c.name)]?.takeIf { programmes.containsKey(it) }

  fun forChannel(c: Channel): List<Programme>? = idFor(c)?.let(programmes::get)
  val size get() = programmes.size
  val count get() = programmes.values.sumOf { it.size }

  companion object {
    val EMPTY = Guide(emptyMap(), emptyMap())
    /** "Sky.Sports.Main.Event.HD.uk" → "Sky Sports Main Event HD". */
    fun idName(id: String) = id.replace(Regex("\\.[a-z]{2}$"), "").replace('.', ' ')
  }
}

/**
 * A channel name reduced to what identifies it: "UK | Sky Sports Main Event FHD" and "Sky Sports Main Event HD" both
 * become "skysportsmainevent". Country tags and quality labels go; "+1" stays (it's a different channel).
 */
fun channelKey(name: String): String {
  // ᴴᴰ, ＦＨＤ, accents and the like → plain letters first; emoji and symbols go.
  var n = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD).replace(Regex("[^\\p{ASCII}]"), "").lowercase()
    .replace(Regex("^\\s*[\\[(|]?\\s*[a-z]{2,3}\\s*[\\])|:]\\s*"), "") // "UK |", "[UK]", "UK:"
    .replace(Regex("\\b(uhd|fhd|hd|sd|4k|8k|hevc|h265|raw|backup|vip|50fps|60fps|1080p|720p)\\b"), " ")
    .replace("+1", " plus1 ").replace("&", " and ")
  n = n.replace(Regex("[^a-z0-9]"), "")
  return n
}

private val NOISE = setOf("uk", "us", "gb", "ie", "hd", "fhd", "uhd", "sd", "4k", "tv", "the", "channel", "live", "vip", "raw", "hevc", "backup")

/** A channel name's meaningful words: "UK | SKY SPORTS MAIN EVENT ᶠᴴᴰ" → sky, sports, main, event. */
fun channelWords(name: String) = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKD).replace(Regex("[^\\p{ASCII}]"), "").lowercase()
  .replace("+1", " plus1 ").replace("&", " and ").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in NOISE }.toSet()

/**
 * How alike two channel names are, 0…1: shared words (all of the shorter name's words found scores high) blended with
 * shared letter pairs (catches "SkySp Main Evt"-style spellings). Numbers must agree: "Sky Sports 1" isn't "Sky Sports 2".
 */
fun channelLikeness(a: String, b: String): Double {
  val (wa, wb) = channelWords(a) to channelWords(b)
  if (wa.isEmpty() || wb.isEmpty()) return 0.0
  val numbers = { w: Set<String> -> w.filter { it.all(Char::isDigit) || it == "plus1" }.toSet() }
  if (numbers(wa) != numbers(wb)) return 0.0
  // A word matches the same word or its longer form ("net" → "network", "utd" won't, "sport" → "sports").
  val (short, long) = if (wa.size <= wb.size) wa to wb else wb to wa
  val words = short.count { w -> w in long || (w.length >= 3 && long.any { it.startsWith(w) || (it.length >= 3 && w.startsWith(it)) }) }.toDouble() / short.size
  val pa = channelKey(a).windowed(2).toSet(); val pb = channelKey(b).windowed(2).toSet()
  val pairs = if (pa.isEmpty() || pb.isEmpty()) 0.0 else 2.0 * (pa intersect pb).size / (pa.size + pb.size)
  return 0.6 * words + 0.4 * pairs
}

object Epg {
  private val stamp = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
  private val stampLocal = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)

  /** epgshare01 per-country files: the codes people most often want (all are listed at epgshare01.online/epgshare01). */
  val SHARE = listOf(
    "UK1" to "United Kingdom", "IE1" to "Ireland", "US2" to "USA", "US_SPORTS1" to "USA sports", "CA2" to "Canada", "AU1" to "Australia",
    "NZ1" to "New Zealand", "DE1" to "Germany", "FR1" to "France", "ES1" to "Spain", "IT1" to "Italy", "NL1" to "Netherlands",
    "BE2" to "Belgium", "PT1" to "Portugal", "SE1" to "Sweden", "NO1" to "Norway", "DK1" to "Denmark", "FI1" to "Finland",
    "PL1" to "Poland", "TR3" to "Turkey", "AE1" to "UAE", "IN1" to "India", "ZA1" to "South Africa", "MX1" to "Mexico",
    "BR1" to "Brazil", "AR1" to "Argentina", "BEIN1" to "beIN Sports", "DIRECTVSPORTS1" to "DirecTV Sports", "SPORTKLUB1" to "Sport Klub",
  )
  fun shareUrl(code: String) = "https://epgshare01.online/epgshare01/epg_ripper_$code.xml.gz"

  /**
   * Reads one XMLTV file (plain or gzipped) into [chans]/[progs], keeping programmes from [from] to [to].
   * Streams: a 200 MB guide never sits in memory, only what's kept.
   */
  fun read(input: InputStream, from: Long, to: Long, chans: MutableMap<String, GuideChannel>, progs: MutableMap<String, MutableList<Programme>>,
           keep: ((GuideChannel) -> Boolean)? = null) {
    val kept = HashSet<String>() // with [keep]: only these channels' programmes are kept (guides list channels first)
    val pin = PushbackInputStream(input, 2)
    val head = ByteArray(2).also { val n = pin.read(it); if (n > 0) pin.unread(it, 0, n) }
    val stream = if (head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()) GZIPInputStream(pin, 1 shl 16) else pin
    val p = XmlPullParserFactory.newInstance().newPullParser()
    p.setInput(stream, null)
    var chan: String? = null
    val names = mutableListOf<String>()
    var prog: Array<Any?>? = null // channel, start, stop, title, desc
    var field: String? = null
    while (true) {
      when (p.next()) {
        XmlPullParser.END_DOCUMENT -> return
        XmlPullParser.START_TAG -> when (p.name) {
          "channel" -> { chan = p.getAttributeValue(null, "id"); names.clear() }
          "display-name" -> field = "name"
          "programme" -> {
            val start = time(p.getAttributeValue(null, "start"))
            val stop = time(p.getAttributeValue(null, "stop"))
            prog = if (start != null && stop != null && stop > from && start < to) arrayOf(p.getAttributeValue(null, "channel"), start, stop, null, null) else null
          }
          "title" -> if (prog != null && prog[3] == null) field = "title"
          "desc" -> if (prog != null && prog[4] == null) field = "desc"
        }
        XmlPullParser.TEXT -> when (field) {
          "name" -> names += p.text.trim()
          "title" -> prog?.set(3, p.text.trim())
          "desc" -> prog?.set(4, p.text.trim().take(200))
        }
        XmlPullParser.END_TAG -> {
          field = null
          when (p.name) {
            "channel" -> chan?.let { id ->
              val g = GuideChannel(id, (chans[id]?.names.orEmpty() + names).distinct())
              if (keep == null || keep(g)) { chans[id] = g; kept += id }
            }.also { chan = null }
            "programme" -> prog?.let { g ->
              val id = g[0] as? String ?: return@let
              if (keep != null && id !in kept) return@let
              progs.getOrPut(id) { mutableListOf() } += Programme(g[3] as? String ?: "", g[4] as? String ?: "", g[1] as Long, g[2] as Long)
            }.also { prog = null }
          }
        }
      }
    }
  }

  private fun time(s: String?): Long? = s?.trim()?.let { t ->
    runCatching { synchronized(stamp) { if (' ' in t) stamp.parse(t) else stampLocal.parse(t.take(14)) }?.time }.getOrNull()
  }

  // --- The cached guide ---

  @Volatile var current: Guide = Guide.EMPTY
    private set
  /** A download is running (shown on the Sports page). */
  var busy by androidx.compose.runtime.mutableStateOf(false)
  /** Bumps whenever the guide changes, so screens using it look again. */
  var changes by androidx.compose.runtime.mutableIntStateOf(0)
  lateinit var dir: File
  private val cache get() = File(dir, "guide.tsv.gz")

  /** Loads the last downloaded guide from disk (quick; called on the way into Live TV). */
  fun load() {
    if (current !== Guide.EMPTY || !::dir.isInitialized || !cache.exists()) return
    runCatching {
      val chans = HashMap<String, GuideChannel>()
      val progs = HashMap<String, MutableList<Programme>>()
      GZIPInputStream(cache.inputStream()).bufferedReader().forEachLine { line ->
        val f = line.split('\t')
        when (f[0]) {
          "C" -> chans[f[1]] = GuideChannel(f[1], f.drop(2))
          "P" -> progs.getOrPut(f[1]) { mutableListOf() } += Programme(f[4], f.getOrElse(5) { "" }, f[2].toLong(), f[3].toLong())
        }
      }
      current = Guide(chans, progs.mapValues { (_, v) -> v.sortedBy { it.start } })
      changes++
    }
  }

  fun age(store: Store) = System.currentTimeMillis() - (store.str("epg_at").toLongOrNull() ?: 0)

  /**
   * Downloads every guide source and replaces the cached guide. [sources]: XMLTV links. Returns a summary line;
   * a source that fails is skipped (and named), the others still count.
   */
  fun refresh(store: Store, sources: List<Pair<String, String>>, channels: List<Channel>? = null, open: (String) -> InputStream = ::openStream): String {
    val now = System.currentTimeMillis()
    // Six hours back to two days ahead, and (given your channels) only guide channels you have: a provider's full guide
    // covers thousands of channels for a week, more than a TV's memory holds.
    val from = now - 6 * 3_600_000L
    val to = now + 2 * 86_400_000L
    val ids = channels?.mapNotNull { it.epgId?.lowercase() }?.toHashSet()
    val keys = channels?.map { channelKey(it.name) }?.toHashSet()
    val keep: ((GuideChannel) -> Boolean)? = if (channels == null) null else { g ->
      g.id.lowercase() in ids!! || (g.names + Guide.idName(g.id)).any { channelKey(it) in keys!! }
    }
    val chans = HashMap<String, GuideChannel>()
    val progs = HashMap<String, MutableList<Programme>>()
    val failed = mutableListOf<String>()
    for ((label, url) in sources) runCatching { open(url).use { read(it, from, to, chans, progs, keep) } }.onFailure { failed += label }
    val guide = Guide(chans, progs.mapValues { (_, v) -> v.distinctBy { it.start }.sortedBy { it.start } })
    if (guide.size > 0 || failed.isEmpty()) {
      current = guide
      changes++
      if (::dir.isInitialized) runCatching {
        java.util.zip.GZIPOutputStream(cache.outputStream()).bufferedWriter().use { w ->
          chans.values.forEach { c -> w.write("C\t${c.id}\t${c.names.joinToString("\t") { it.esc() }}\n") }
          guide.programmes.forEach { (id, list) -> list.forEach { p -> w.write("P\t$id\t${p.start}\t${p.end}\t${p.title.esc()}\t${p.description.esc()}\n") } }
        }
      }
      store.put("epg_at", now.toString())
    }
    val summary = "${guide.size} channels, ${guide.count} programmes" + if (failed.isNotEmpty()) ". Couldn't get: ${failed.joinToString()}" else ""
    store.put("epg_status", summary)
    return summary
  }

  /** The sources chosen in Settings > Live TV. */
  fun sources(store: Store): List<Pair<String, String>> {
    val x = Xtream.from(store)
    val provider = if (x != null && store.str("epg_provider") != "off") listOf("your provider's guide" to x.xmltvUrl()) else emptyList()
    return provider + store.str("epg_share").split(',').filter { it.isNotBlank() }.map { "epgshare01 $it" to shareUrl(it) }
  }

  private fun String.esc() = replace('\t', ' ').replace('\n', ' ')
}
