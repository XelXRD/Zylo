package tv.zylo

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {
  private fun src(addon: String, quality: String?, hash: String? = null, url: String? = "https://x/$addon$quality", library: String? = null, debrid: String? = null, cached: Boolean = false) =
    Source(addon, addon, "", quality, "", null, "$addon.$quality.mkv", url = url, infoHash = hash, library = library, debrid = debrid, cached = cached)

  @Test fun rankingHonoursCapMemoryAndLibrary() {
    val uhd = src("Torrentio", "4K")
    val fhd = src("Torrentio", "1080p")
    val plex = src("Home", "1080p", library = "plex")
    val hd = src("Other", "720p")
    // Library first, then best quality; 4K is pushed past everything when the cap is 1080p.
    assertEquals(listOf(plex, fhd, hd, uhd), rank(listOf(uhd, fhd, plex, hd), cap = "1080p"))
    assertEquals(listOf(plex, uhd, fhd, hd), rank(listOf(uhd, fhd, plex, hd), cap = "4K"))
    // The source that last played comes first, and one like it (same addon and quality) for the next episode.
    val pref = JSONObject(hd.signature())
    assertEquals(hd, rank(listOf(uhd, fhd, plex, hd), cap = "4K", pref = pref).first())
    val nextEp = src("Other", "720p").copy(filename = "Other.S01E02.720p.mkv")
    assertEquals(1, nextEp.likeness(pref))
    assertTrue(overCap(uhd, "1080p"))
  }

  private fun ep(n: Int, aired: String?, name: String = "E$n") = JSONObject().put("episode_number", n).put("name", name).apply { aired?.let { put("air_date", it) } }

  @Test fun nextEpisodeRollsOverAndStopsAtUnaired() {
    val seasons = mapOf(1 to listOf(ep(1, "2024-01-01"), ep(2, "2024-01-08")), 2 to listOf(ep(1, "2025-02-01", "Return")), 3 to listOf(ep(1, "2099-01-01")))
    val of = { s: Int -> seasons[s].orEmpty() }
    assertEquals(2, nextEpisode(of, listOf(0, 1, 2, 3), 1, 1, "2026-11-08")!!.episode)
    nextEpisode(of, listOf(0, 1, 2, 3), 1, 2, "2026-11-08")!!.let { assertEquals(2, it.season); assertEquals("Return", it.name) }
    assertNull(nextEpisode(of, listOf(0, 1, 2, 3), 2, 1, "2026-11-08")) // season 3 hasn't aired
  }

  @Test fun profilesKeepTheirOwnData() {
    val files = HashMap<String, Mem>()
    fun store() = Store { files.getOrPut(it) { Mem() } }
    val main = store()
    main.tmdb = "key"
    main.toggleMyList(Item("movie", 1, "A", "/a", null, "", "2024"))
    main.put("trakt_access", "alex-token")
    main.put("trakt_client", "app-id")
    val kid = main.addProfile("Lily")
    main.switchProfile(kid.id)
    val other = store()
    assertEquals(kid.id, other.profileId)
    assertEquals("key", other.tmdb) // shared
    assertEquals("app-id", other.str("trakt_client")) // app credential shared
    assertEquals("", other.str("trakt_access")) // sign-in is per profile
    assertTrue(other.myList.isEmpty()) // My List is per profile
    other.switchProfile("main")
    assertEquals(1, store().myList.size) // the original profile's data is untouched
  }
}

class Mem : SharedPreferences {
  val m = HashMap<String, Any?>()
  override fun getAll() = m
  override fun getString(k: String, d: String?) = (m[k] as String?) ?: d
  override fun getStringSet(k: String, d: MutableSet<String>?) = d
  override fun getInt(k: String, d: Int) = (m[k] as Int?) ?: d
  override fun getLong(k: String, d: Long) = (m[k] as Long?) ?: d
  override fun getFloat(k: String, d: Float) = (m[k] as Float?) ?: d
  override fun getBoolean(k: String, d: Boolean) = (m[k] as Boolean?) ?: d
  override fun contains(k: String) = k in m
  override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
  override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
  override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
    override fun putString(k: String, v: String?) = apply { m[k] = v }
    override fun putStringSet(k: String, v: MutableSet<String>?) = apply { m[k] = v }
    override fun putInt(k: String, v: Int) = apply { m[k] = v }
    override fun putLong(k: String, v: Long) = apply { m[k] = v }
    override fun putFloat(k: String, v: Float) = apply { m[k] = v }
    override fun putBoolean(k: String, v: Boolean) = apply { m[k] = v }
    override fun remove(k: String) = apply { m.remove(k) }
    override fun clear() = apply { m.clear() }
    override fun commit() = true
    override fun apply() {}
  }
}

class ProgressTest {
  private val show = Item("tv", 1399, "Show", "/p", null, "", "2011")
  private fun store() = HashMap<String, Mem>().let { files -> Store { files.getOrPut(it) { Mem() } } }
  private val seasons = mapOf(3 to 10, 4 to 8) // episodes per season
  private val next = { s: Int, e: Int -> if (e < (seasons[s] ?: 0)) NextEp(s, e + 1, "E${e + 1}", null) else seasons[s + 1]?.let { NextEp(s + 1, 1, "E1", null) } }

  @Test fun picksUpFromTraktHistory() {
    val st = store()
    st.setTraktWatched((1..10).map { "tv:1399:3:$it" }.toSet() + setOf("tv:1399:4:1", "tv:1399:4:2"))
    assertEquals(Target(4, 3, false), target(st, show, next)) // watched to S4E2 elsewhere → S4E3, not S1E1
    st.setTraktWatched(st.traktWatched() + "tv:1399:4:3" + "tv:1399:4:4") // E3 and E4 ticked off → E5
    assertEquals(Target(4, 5, false), target(st, show, next))
  }

  @Test fun resumesAnEpisodeInProgress() {
    val st = store()
    st.setTraktWatched(setOf("tv:1399:4:2"))
    st.saveWatch(show, 4, 3, 600_000, 3_000_000)
    assertEquals(Target(4, 3, true), target(st, show, next))
    assertEquals(null, target(store(), show, next)) // never watched: start at the beginning
  }
}

class ScrollTest {
  @Test fun onlyScrollsWhatsNeeded() {
    assertEquals(0f, keepDistance(300f, 50f, 1000f, 100f, 30f)) // hero buttons already on screen: page stays put
    assertEquals(-60f, keepDistance(40f, 50f, 1000f, 100f, 30f)) // above the top bar: down just enough to clear it
    assertEquals(80f, keepDistance(950f, 100f, 1000f, 100f, 30f)) // off the bottom: up just enough
  }
}

class KidsTest {
  @Test fun ratingGate() {
    try {
      Ratings.max = 1 // PG and under
      assertEquals(true, Ratings.allows("U") && Ratings.allows("PG") && Ratings.allows("TV-Y7") && Ratings.allows("G"))
      assertEquals(false, Ratings.allows("12A") || Ratings.allows("TV-14") || Ratings.allows("18"))
      assertEquals(false, Ratings.allows(null)) // unrated is hidden on a kids profile
      Ratings.max = null
      assertEquals(true, Ratings.allows("18") && Ratings.allows(null)) // normal profiles see everything
    } finally { Ratings.max = null }
  }

  @Test fun kidsSettingSaves() {
    val files = HashMap<String, Mem>()
    val st = Store { files.getOrPut(it) { Mem() } }
    val kid = st.addProfile("Kids")
    st.setKids(kid.id, 0)
    assertEquals(0, Store { files.getOrPut(it) { Mem() } }.profiles.first { it.id == kid.id }.kids)
    st.setKids(kid.id, null)
    assertEquals(null, st.profiles.first { it.id == kid.id }.kids)
  }
}

class PhoneDropTest {
  @Test fun phoneSendsLinkToTv() {
    var got = ""
    val d = PhoneDrop { got = it; "Added Comet." }
    try {
      val port = d.url.substringAfterLast(':').substringBefore('/')
      val k = d.url.substringAfter("?k=")
      fun post(key: String) = (java.net.URL("http://127.0.0.1:$port/?k=$key").openConnection() as java.net.HttpURLConnection).run {
        requestMethod = "POST"; doOutput = true
        outputStream.write(java.net.URLEncoder.encode("https://x.example/abc==/manifest.json", "UTF-8").toByteArray())
        responseCode to (if (responseCode < 400) inputStream else errorStream).bufferedReader().readText()
      }
      assertEquals(403, post("wrong").first) // a different device on the Wi-Fi can't add addons
      assertEquals("" , got)
      assertEquals(200 to "Added Comet.", post(k))
      assertEquals("https://x.example/abc==/manifest.json", got)
    } finally { d.stop() }
  }
}

class SynclerTest {
  private fun res(n: String) = javaClass.getResource("/syncler/$n")!!.readText()
  private val web = mapOf(
    "https://raw.githubusercontent.com/h53h54h55/Syncler2/main/LobstorV2.json" to res("vendor.json"),
    "https://raw.githubusercontent.com/h53h54h55/Syncler2/main/Manifest-Full.json" to res("package.json"),
    "https://raw.githubusercontent.com/h53h54h55/Syncler2/main/Full-Lobstor-Dinner.json" to res("providers.json"),
  )
  private val noDom: suspend (String, org.json.JSONObject) -> List<org.json.JSONObject> = { _, _ -> emptyList() }

  @Test fun installsLobstorFromItsVendorLink() {
    val files = HashMap<String, Mem>()
    val st = Store { files.getOrPut(it) { Mem() } }
    val got = Syncler.install(st, "https://raw.githubusercontent.com/h53h54h55/Syncler2/main/LobstorV2.json") { web.getValue(it) }
    assertEquals(1, got.size)
    assertEquals(st.syncler.single().sites.size, got.single().sites.size)
    assertEquals(true, got.single().sites.any { it.optString("name") == "TORRENTIO" })
  }

  @Test fun searchesJsonSites() = kotlinx.coroutines.runBlocking {
    val sites = org.json.JSONObject(res("providers.json"))
    val asked = mutableListOf<String>()
    val torrentio = Syncler.search(sites.getJSONObject("torrentio"), Query("Dune", "2021", "tt1160419", null, null), { asked += it
      """{"streams":[{"title":"Dune.2021.2160p.HDR.x265\n👤 50 💾 18.2 GB","infoHash":"ABCDEF0123456789ABCDEF0123456789ABCDEF01"}]}""" }, noDom)!!
    assertEquals("https://torrentio.strem.fun/stream/movie/tt1160419.json", asked.single())
    assertEquals("abcdef0123456789abcdef0123456789abcdef01", torrentio.single().infoHash)
    assertEquals("4K", torrentio.single().quality)
    // YTS: results in data.movies, one row per torrent, the title from the film.
    val yts = Syncler.search(sites.getJSONObject("yts"), Query("Dune", "2021", "tt1160419", null, null), {
      """{"data":{"movies":[{"title_long":"Dune (2021)","torrents":[{"hash":"1111111111111111111111111111111111111111","quality":"1080p","size":"2.1 GB"},{"hash":"2222222222222222222222222222222222222222","quality":"2160p","size":"5 GB"}]}]}}""" }, noDom)!!
    assertEquals(listOf("1080p", "4K"), yts.map { it.quality })
    // An episode fills S01E02 into text searches; a site that doesn't answer is null, not empty.
    assertEquals("Show S01E02", Syncler.fill("{title} {episodeCode}", org.json.JSONObject(), Query("Show", "2020", null, 1, 2)))
    assertEquals(null, Syncler.search(sites.getJSONObject("torrentio"), Query("Dune", "2021", "tt1160419", null, null), { throw java.io.IOException() }, noDom))
  }

  @Test fun base32Hashes() = assertEquals("0123456789abcdef0123456789abcdef01234567", Syncler.hex("AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH"))
}
