// Channel logos from tv-logo/tv-logos (github.com/tv-logo/tv-logos): clean, transparent PNGs, one per channel, a folder
// per country ("countries/united-kingdom/sky-sports-main-event-uk.png"). Settings > Live TV turns them on; a channel
// they don't have keeps your provider's logo.
package tv.zylo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object Logos {
  private const val API = "https://api.github.com/repos/tv-logo/tv-logos"
  private const val RAW = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/"
  private const val WEEK = 7 * 86_400_000L

  lateinit var dir: File
  var on by mutableStateOf(false)
  /** Bumps when a country's list arrives, so logos already on screen swap over. */
  private var rev by mutableIntStateOf(0)

  /** Zylo's country codes (see [LiveTidy]) to the repo's folders; anything else tries "international" only. */
  private val FOLDERS = mapOf(
    "UK" to "united-kingdom", "US" to "united-states", "IE" to "ireland", "CA" to "canada", "AU" to "australia", "NZ" to "new-zealand",
    "DE" to "germany", "AT" to "austria", "CH" to "switzerland", "FR" to "france", "BE" to "belgium", "NL" to "netherlands", "LU" to "luxembourg",
    "IT" to "italy", "ES" to "spain", "PT" to "portugal", "BR" to "brazil", "ARG" to "argentina", "MX" to "mexico", "CL" to "chile", "CR" to "costa-rica",
    "PL" to "poland", "CZ" to "czech-republic", "SK" to "slovakia", "HU" to "hungary", "RO" to "romania", "BG" to "bulgaria", "GR" to "greece",
    "TR" to "turkey", "RU" to "russia", "UA" to "ukraine", "RS" to "serbia", "HR" to "croatia", "SI" to "slovenia", "AL" to "albania", "AZ" to "azerbaijan",
    "LT" to "lithuania", "MT" to "malta", "IL" to "israel", "LB" to "lebanon", "AE" to "united-arab-emirates", "IN" to "india", "ID" to "indonesia",
    "MY" to "malaysia", "SG" to "singapore", "PH" to "philippines", "HK" to "hong-kong", "ZA" to "south-africa",
    "SE" to "nordic", "NO" to "nordic", "DK" to "nordic", "FI" to "nordic", "AR" to "world-middle-east",
  )

  // Per folder: a channel's squashed name ("skysportsmainevent") to its file name.
  private val index = ConcurrentHashMap<String, Map<String, String>>()
  private val asked = ConcurrentHashMap.newKeySet<String>()

  fun load(store: Store) { on = store.str("live_logos") == "on" }
  fun set(store: Store, value: Boolean) { on = value; store.put("live_logos", if (value) "on" else "") }

  /** The logo to show: tv-logos' one for the channel when on and it has one, else the provider's. */
  fun of(c: Channel): String? {
    if (!on) return c.logo
    rev // read, so a list arriving redraws whoever asked
    val name = squash(LiveTidy.title(c.name))
    // Its country's folder; for a channel with no country tag, each country that has one of that name (yours first).
    val countries = LiveTidy.countriesOf(c.name).sortedByDescending { it == LiveTidy.home }.ifEmpty { listOf(LiveTidy.home) }
    for (folder in (countries.mapNotNull { FOLDERS[it] } + "international").distinct()) {
      val files = index[folder] ?: run { fetch(folder); null } ?: continue
      files[name]?.let { return RAW + folder + "/" + it }
    }
    // No country to go on ("VIP: SKY SPORTS BOX OFFICE" and nothing tagged with a country by that name): any list
    // already on the TV, which costs nothing to look through.
    if (LiveTidy.countriesOf(c.name).isEmpty()) index.entries.firstOrNull { (f, files) -> f != "international" && name in files }?.let { (f, files) -> return RAW + f + "/" + files.getValue(name) }
    return c.logo
  }

  /** "Sky Sports +" → "skysportsplus"; "ITV 1" and "itv-1" both → "itv1". */
  fun squash(s: String): String = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).lowercase()
    .replace("+", "plus").replace("&", "and").replace(Regex("[^a-z0-9]"), "")

  /**
   * A folder's file names to squashed channel names: "sky-sports-main-event-uk.png" → "skysportsmainevent". The wide
   * ("-hz"), icon and alternative versions are skipped: the plain one is the channel's own logo.
   */
  fun indexOf(files: List<String>): Map<String, String> = HashMap<String, String>().apply {
    files.filter { it.endsWith(".png") && !Regex("-(hz|icon|alt)(-|\\.)").containsMatchIn(it) }.forEach { f ->
      val base = f.removeSuffix(".png").let { b -> b.substringBeforeLast('-').takeIf { b.substringAfterLast('-').length in 2..3 && '-' in b } ?: b }
      putIfAbsent(squash(base), f)
    }
  }

  /** A country's list, from the TV (a week at most) or GitHub: two small requests, in the background, once. */
  private fun fetch(folder: String) {
    if (!asked.add(folder)) return
    Thread {
      val got = runCatching {
        val saved = File(dir, "logos-$folder.txt")
        val files = if (saved.exists() && System.currentTimeMillis() - saved.lastModified() < WEEK) saved.readLines()
        else {
          val sha = folderShas()[folder] ?: return@runCatching emptyMap()
          JSONObject(http("$API/git/trees/$sha", timeout = 15_000)).getJSONArray("tree").objects()
            .filter { it.optString("type") == "blob" }.map { it.getString("path") }
            .also { saved.writeText(it.joinToString("\n")) }
        }
        indexOf(files)
      }.getOrDefault(emptyMap()) // no list this session (offline, or GitHub's hourly limit): the provider's logos stay
      index[folder] = got
      android.os.Handler(android.os.Looper.getMainLooper()).post { rev++ }
    }.start()
  }

  /** Each country folder's id in the repo (needed to list it), kept a week. */
  @Synchronized private fun folderShas(): Map<String, String> {
    val saved = File(dir, "logos-folders.txt")
    val lines = if (saved.exists() && System.currentTimeMillis() - saved.lastModified() < WEEK) saved.readLines()
    else JSONArray(http("$API/contents/countries", timeout = 15_000)).objects().filter { it.optString("type") == "dir" }
      .map { it.getString("name") + " " + it.getString("sha") }.also { saved.writeText(it.joinToString("\n")) }
    return lines.mapNotNull { l -> l.split(' ').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
  }
}
