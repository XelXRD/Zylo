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

class XtreamTest {
  private val x = Xtream("http://tv.example:8080", "alex", "p@ss", listOf("m3u8", "ts"))

  @Test fun readsTheM3uLinkProvidersGive() {
    assertEquals(Triple("http://tv.example:8080", "alex", "p@ss"), Xtream.parse("tv.example:8080/get.php?username=alex&password=p%40ss&type=m3u_plus&output=ts"))
    assertEquals(Triple("https://tv.example", null, null), Xtream.parse(" https://tv.example/ "))
  }

  @Test fun loginCategoriesChannelsGuide() {
    val asked = mutableListOf<String>()
    val web = { u: String -> asked += u; when {
      "get_live_categories" in u -> """[{"category_id":"3","category_name":"UK | Sport "}]"""
      "get_live_streams" in u -> """[{"num":101,"name":"Sky Sports","stream_id":55,"stream_icon":"http://l/1.png","category_id":"3"},{"num":0,"name":"bad","stream_id":0}]"""
      "get_simple_data_table" in u -> """{"epg_listings":[]}"""
      "get_short_epg" in u -> """{"epg_listings":[{"title":"TWF0Y2ggb2YgdGhlIERheQ==","description":"SGlnaGxpZ2h0cw==","start_timestamp":"1762610400","stop_timestamp":"1762614000"}]}"""
      else -> """{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["m3u8","ts"]}}"""
    } }
    x.login(web)
    assertEquals(listOf(Category("3", "UK | Sport")), x.categories(web))
    val ch = x.channels("3", web)
    assertEquals(listOf(Channel(55, 101, "Sky Sports", "http://l/1.png", "3")), ch)
    assertEquals(true, asked.last().endsWith("action=get_live_streams&category_id=3"))
    assertEquals(listOf(Programme("Match of the Day", "Highlights", 1762610400000, 1762614000000)), x.guide(55, web)) // full table empty → now and next
    assertEquals("http://tv.example:8080/live/alex/p%40ss/55.m3u8", x.streamUrl(ch[0]))
    assertEquals("http://tv.example:8080/live/alex/p%40ss/55.ts", Xtream(x.server, "alex", "p@ss", listOf("ts")).streamUrl(ch[0]))
  }

  @Test fun catchUp() {
    val c = Channel(55, 1, "BBC One", null, "1", archive = 7)
    val now = 1762617600000L // 16:00 UTC, 8 Nov 2026
    val p = Programme("Football Focus", "", now - 4 * 3_600_000, now - 3 * 3_600_000, archive = true, startText = "2026-11-08 12:00:00")
    assertEquals("http://tv.example:8080/timeshift/alex/p%40ss/60/2026-11-08:12-00/55.ts", x.catchupUrl(c, p))
    assertEquals(true, x.canCatchUp(c, p, now))
    assertEquals(false, x.canCatchUp(c.copy(archive = 0), p, now)) // channel without an archive
    assertEquals(false, x.canCatchUp(c, p.copy(end = now + 60_000), now)) // still on
    assertEquals(false, x.canCatchUp(c.copy(archive = 1), p.copy(start = now - 3 * 86_400_000, end = now - 3 * 86_400_000 + 3_600_000), now)) // older than the archive
  }

  @Test fun refusedLogins() {
    val bad = runCatching { x.login { """{"user_info":{"auth":0}}""" } }.exceptionOrNull()?.message
    assertEquals("The provider didn't accept that username and password.", bad)
    val expired = runCatching { x.login { """{"user_info":{"auth":1,"status":"Expired"}}""" } }.exceptionOrNull()?.message
    assertEquals("The provider says this account is Expired.", expired)
  }
}

class QuizTest {
  @Test fun answersBuildTheSearch() {
    val q = quiz("movie")
    val path = quizPath("movie", listOf(q[0].answers[0], q[1].answers[3], q[2].answers[0], q[3].answers[0], q[4].answers[0]))
    assertEquals(true, "with_genres=35" in path && "primary_release_date.gte=1990-01-01" in path && "with_runtime.lte=100" in path && "vote_count.gte=800" in path)
    val any = quizPath("tv", quiz("tv").map { it.answers.last() })
    assertEquals("/discover/tv?sort_by=popularity.desc&include_adult=false&page=1&vote_count.gte=50", any) // all "don't mind": just popular, with a few votes
    assertEquals(5, quiz("tv").size)
  }
}

class GuideTest {
  private val xml = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="Sky.Sports.Main.Event.HD.uk"><display-name lang="en">Sky Sports Main Event HD</display-name></channel>
  <channel id="BBC.One.Lon.HD.uk"><display-name lang="en">BBC One London HD</display-name></channel>
  <programme channel="Sky.Sports.Main.Event.HD.uk" start="20261108153000 +0000" stop="20261108180000 +0000">
    <title lang="en">Live: Arsenal v Chelsea</title><desc lang="en">Premier League coverage &amp; build-up.</desc>
  </programme>
  <programme channel="Sky.Sports.Main.Event.HD.uk" start="20261101153000 +0000" stop="20261101180000 +0000"><title>Old</title></programme>
  <programme channel="BBC.One.Lon.HD.uk" start="20261108180000 +0000" stop="20261108190000 +0000"><title>News</title></programme>
</tv>"""
  private val t = 1794153600000L // 16:00 UTC, 8 Nov 2026

  private fun guide(gz: Boolean): Guide {
    val bytes = if (gz) java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(xml.toByteArray()) } }.toByteArray() else xml.toByteArray()
    val chans = HashMap<String, GuideChannel>(); val progs = HashMap<String, MutableList<Programme>>()
    Epg.read(bytes.inputStream(), t - 86_400_000L, t + 2 * 86_400_000L, chans, progs)
    return Guide(chans, progs)
  }

  @Test fun readsXmltvPlainOrGzipped() {
    for (gz in listOf(false, true)) {
      val g = guide(gz)
      assertEquals(2, g.size)
      assertEquals(listOf("Live: Arsenal v Chelsea"), g.programmes["Sky.Sports.Main.Event.HD.uk"]!!.map { it.title }) // last week's dropped
      assertEquals("Premier League coverage & build-up.", g.programmes["Sky.Sports.Main.Event.HD.uk"]!![0].description)
    }
  }

  @Test fun keepsOnlyYourChannels() {
    val chans = HashMap<String, GuideChannel>(); val progs = HashMap<String, MutableList<Programme>>()
    val mine = listOf(Channel(1, 1, "UK | SKY SPORTS MAIN EVENT FHD", null, ""))
    val keys = mine.map { channelKey(it.name) }.toSet()
    Epg.read(xml.byteInputStream(), t - 86_400_000L, t + 2 * 86_400_000L, chans, progs) { g -> g.names.any { channelKey(it) in keys } }
    assertEquals(setOf("Sky.Sports.Main.Event.HD.uk"), progs.keys) // BBC One isn't one of yours: not kept
  }

  @Test fun matchesProviderChannels() {
    val g = guide(false)
    assertEquals("Sky.Sports.Main.Event.HD.uk", g.idFor(Channel(1, 1, "UK | SKY SPORTS MAIN EVENT FHD", null, "", epgId = null))) // by name
    assertEquals("Sky.Sports.Main.Event.HD.uk", g.idFor(Channel(2, 2, "Something else", null, "", epgId = "sky.sports.main.event.hd.uk"))) // by id
    assertEquals(null, g.idFor(Channel(3, 3, "Sky Sports Main Event +1", null, "")))
    assertEquals("skysportsmainevent", channelKey("[UK] Sky Sports Main Event HD"))
  }
}

class SportsTest {
  private val l = Sports.LEAGUES.first { it.name == "Premier League" }
  private val body = """{"events":[{"id":"1","date":"2026-11-08T16:30Z","name":"Chelsea at Arsenal","competitions":[{
    "competitors":[{"homeAway":"away","team":{"displayName":"Chelsea","shortDisplayName":"Chelsea","logo":"c.png"}},{"homeAway":"home","team":{"displayName":"Arsenal","shortDisplayName":"Arsenal","logo":"a.png"},"score":"1"}],
    "status":{"type":{"state":"pre","shortDetail":"16:30"}},"broadcasts":[{"names":["USA Net","Peacock"]}]}]}]}"""

  @Test fun readsEspnAndFindsChannels() {
    val e = Sports.parse(l, body).single()
    assertEquals("Arsenal v Chelsea", e.name) // home side first
    assertEquals(1794155400000L, e.start) // 16:30 UTC, 8 Nov 2026
    assertEquals(listOf("USA Net", "Peacock"), e.broadcasters)
    val show = Programme("Live: Arsenal v Chelsea", "", e.start - 3_600_000, e.start + 7_200_000)
    assertEquals(true, Sports.matches(e, show))
    assertEquals(false, Sports.matches(e, show.copy(title = "Arsenal: The Movie"))) // one side only
    assertEquals(false, Sports.matches(e, show.copy(start = e.start + 86_400_000, end = e.start + 90_000_000))) // the replay tomorrow
    val sky = Channel(10, 401, "UK | Sky Sports Main Event", null, "")
    val usa = Channel(11, 900, "US | USA Network HD", null, "")
    val got = Sports.whereToWatch(e, listOf(sky, usa), listOf(Triple("Sky Sports Main Event HD", sky, listOf(show)), Triple("Canal+ Sport", null, listOf(show))),
      mapOf(channelKey("USA Net") to 11))
    assertEquals(listOf("Sky Sports Main Event HD" to sky, "Canal+ Sport" to null, "USA Net" to usa, "Peacock" to null), got.map { it.label to it.channel })
  }

  @Test fun ukListingNames() {
    val e = Sports.parse(Sports.LEAGUES.first(), """{"events":[{"id":"2","date":"2026-11-08T16:30Z","competitions":[{"competitors":[
      {"homeAway":"home","team":{"displayName":"Manchester United","shortDisplayName":"Man United"}},{"homeAway":"away","team":{"displayName":"Tottenham Hotspur","shortDisplayName":"Spurs"}}]}]}]}""").single()
    fun on(title: String) = Sports.matches(e, Programme(title, "", e.start - 1_800_000, e.start + 7_200_000))
    assertEquals(true, on("Live: Man Utd v Spurs"))
    assertEquals(true, on("Premier League: Manchester United v Tottenham"))
    assertEquals(false, on("Live: Man City v Spurs")) // the other Manchester
  }

  @Test fun ranksChannelsForLinking() {
    val all = listOf(Channel(11, 0, "BBC One", null, ""), Channel(12, 0, "UK | Sky Sports Arena", null, ""), Channel(10, 0, "UK: SKY SPORTS MAIN EVENT ᶠᴴᴰ", null, ""),
      Channel(13, 0, "UK | TNT Sports 1 HD", null, ""), Channel(14, 0, "UK | TNT Sports 2 HD", null, ""), Channel(15, 0, "US: USA Network", null, ""))
    assertEquals(10, rankChannels("Sky Sports Main Event HD", all).first().first.id)
    assertEquals(10, bestChannel("Sky Sports Main Event HD", all)?.id) // ᶠᴴᴰ and "UK:" don't get in the way
    assertEquals(14, bestChannel("TNT Sports 2", all)?.id) // the number decides
    assertEquals(null, bestChannel("Sky Sports Football", all)) // not sure enough: ask
    assertEquals(15, rankChannels("USA Net", all).first().first.id)
    assertEquals(setOf("sky", "sports", "main", "event"), channelWords("UK | SKY SPORTS MAIN EVENT ᶠᴴᴰ"))
  }
}

class UpdateTest {
  @Test fun versions() {
    assertEquals(true, Updates.newer("0.19.0", "0.18.1"))
    assertEquals(true, Updates.newer("0.18.10", "0.18.2"))
    assertEquals(false, Updates.newer("0.18.1", "0.18.1"))
    assertEquals(false, Updates.newer("v0.17.0", "0.18.1"))
  }

  @Test fun readsGithubReleases() {
    val r = Updates.latest("alex/zylo-releases") { url ->
      assertEquals("https://api.github.com/repos/alex/zylo-releases/releases/latest", url)
      """{"tag_name":"v0.19.0","body":"Sports schedule","assets":[{"name":"notes.txt","browser_download_url":"x"},{"name":"zylo-tv-0.19.0.apk","browser_download_url":"https://github.com/a.apk"}]}"""
    }
    assertEquals(Release("0.19.0", "https://github.com/a.apk", "Sports schedule"), r)
    assertEquals(Release("0.20.0", "https://x/z.apk", ""), Updates.latest("https://x/update.json") { """{"version":"0.20.0","apk":"https://x/z.apk"}""" })
  }
}

class SportsSearchTest {
  @Test fun findsChannelsFast() {
    // 20,000 channels, a guide with the match on one of them: found, tied to your channel, quickly.
    val all = (1..20_000).map { Channel(it, it, "UK | Channel $it HD", null, "1") } + Channel(99_999, 401, "UK: SKY SPORTS MAIN EVENT ᶠᴴᴰ", null, "1")
    val idx = ChannelIndex(all)
    val e = Sports.parse(Sports.LEAGUES.first(), """{"events":[{"id":"1","date":"2026-11-08T16:30Z","competitions":[{"competitors":[
      {"homeAway":"home","team":{"displayName":"Arsenal"}},{"homeAway":"away","team":{"displayName":"Chelsea"}}]}],"status":{"type":{"state":"pre"}}}]}""").single()
    val progs = (1..3000).associate { "Ch$it.uk" to listOf(Programme("Show $it", "", e.start - 3_600_000, e.start + 3_600_000)) } +
      ("Sky.Sports.Main.Event.HD.uk" to listOf(Programme("Live: Arsenal v Chelsea", "", e.start - 1_800_000, e.start + 7_200_000)))
    val guide = Guide(mapOf("Sky.Sports.Main.Event.HD.uk" to GuideChannel("Sky.Sports.Main.Event.HD.uk", listOf("Sky Sports Main Event HD"))), progs)
    val files = HashMap<String, Mem>()
    val t0 = System.nanoTime()
    val got = options(Store { files.getOrPut(it) { Mem() } }, e, idx, guide, emptyMap())
    val ms = (System.nanoTime() - t0) / 1_000_000
    assertEquals(listOf(99_999), got.mapNotNull { it.channel?.id })
    assertEquals(false, got.single().guessed) // the guide proves it: a sure Watch
    assertEquals(true, ms < 1_000)
  }
}

class ScheduleTest {
  @Test fun asksOneDayAtATime() {
    val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
    val now = 1794153600000L // 8 Nov 2026
    val nfl = """{"events":[{"id":"9","date":"2026-11-08T18:00Z","competitions":[{"competitors":[{"homeAway":"home","team":{"displayName":"Atlanta Falcons"}},{"homeAway":"away","team":{"displayName":"Cincinnati Bengals"}}]}]}]}"""
    val got = Sports.schedule(1, { u -> asked += u; if ("-" in u.substringAfter("dates=").substringBefore('&')) throw HttpError(400, "range"); if ("football/nfl" in u && "20261108" in u) nfl else """{"events":[]}""" }, now)
    assertEquals(listOf("Atlanta Falcons v Cincinnati Bengals"), got.map { it.name })
    assertEquals(Sports.LEAGUES.count { !it.allDay } * 2 + Sports.LEAGUES.count { it.allDay }, asked.size) // today and tomorrow; tournaments today only
    assertEquals(true, asked.all { Regex("dates=\\d{8}&").containsMatchIn(it) })
    assertEquals("all down", runCatching { Sports.schedule(0, { throw java.io.IOException("all down") }, now) }.exceptionOrNull()?.message)
  }
}

class RemindersTest {
  @Test fun favouritesAndReminders() {
    val files = HashMap<String, Mem>()
    val st = Store { files.getOrPut(it) { Mem() } }
    val c = Channel(55, 101, "Sky Sports", "http://l/1.png", "3", archive = 7, epgId = "Sky.Sports.uk")
    Xtream.setFavourites(st, listOf(c))
    assertEquals(listOf(c), Xtream.favourites(st)) // kept whole: the Favourites list needs no download
    val p = Programme("Match of the Day", "", 1_000_000L, 4_600_000L)
    assertEquals(true, Reminders.toggle(st, c, p))
    assertEquals(null, Reminders.due(st, now = 0L)) // not yet
    assertEquals("Match of the Day", Reminders.due(st, now = 960_000L)?.title) // a minute before
    assertEquals(false, Reminders.toggle(st, c, p)) // OK again clears it
    Reminders.toggle(st, c, p)
    assertEquals(null, Reminders.due(st, now = 5_000_000L)) // over: dropped
    assertEquals(0, Reminders.all(st).size)
  }
}

class AnimeQuizAndSavedResultsTest {
  @Test fun animeQuiz() {
    val q = quiz("anime")
    assertEquals(5, q.size)
    assertEquals("Big battles", q[0].answers[0].label)
    val path = quizPath("anime", listOf(q[0].answers[0]))
    assertEquals(true, path.startsWith("/discover/tv?") && "with_genres=16,10759" in path && "with_original_language=ja" in path)
    assertEquals(true, "with_genres=16" in quizPath("anime", listOf(q[0].answers.last()))) // "Surprise me" is still anime
  }

  @Test fun keepsChannelsForAMatchUntilItsOver() {
    val files = HashMap<String, Mem>()
    val st = Store { files.getOrPut(it) { Mem() } }
    val now = System.currentTimeMillis()
    val e = SportEvent("77", "Football", "Premier League", "Arsenal v Chelsea", now + 3_600_000, "pre", "", emptyList(), emptyList(), emptyList())
    val c = Channel(10, 401, "Sky Sports Main Event", null, "1")
    SeenOn.put(st, e, listOf(WatchOption("Sky Sports Main Event HD", Programme("Live: Arsenal v Chelsea", "", e.start, e.start + 7_200_000), c, "TV guide")))
    assertEquals(c, SeenOn.get(st, e)?.single()?.channel)
    assertEquals(null, SeenOn.get(st, e.copy(id = "78")))
    assertEquals(null, SeenOn.get(st, e.copy(start = now - 5 * 3_600_000))) // kept until 4 h after the start
  }
}

class ExtrasTest {
  private fun store() = HashMap<String, Mem>().let { files -> Store { files.getOrPut(it) { Mem() } } }

  @Test fun skipSegments() {
    val intro = """{"imdb_id":"tt0903747","intro":{"start_sec":2.5,"end_sec":61.0,"start_ms":2500,"end_ms":61000},"recap":null,"outro":{"start_ms":2700000,"end_ms":0},"no_intro":false}"""
    val segs = Skip.introDb(intro)
    assertEquals(listOf(Segment("intro", 2500, 61000), Segment("credits", 2700000, 0)), segs)
    assertEquals("intro", Skip.at(segs, 10_000, 2_900_000)?.kind)
    assertNull(Skip.at(segs, 60_500, 2_900_000)) // the last second: let it finish
    assertEquals("credits", Skip.at(segs, 2_800_000, 2_900_000)?.kind) // end 0: to the end of the video
    val ani = """{"found":true,"results":[{"interval":{"startTime":1310.2,"endTime":1400.0},"skipType":"ed","skipId":"b","episodeLength":1420},{"interval":{"startTime":90.5,"endTime":180.5},"skipType":"op","skipId":"a","episodeLength":1420}],"message":"ok","statusCode":200}"""
    assertEquals(listOf(Segment("intro", 90500, 180500), Segment("credits", 1310200, 1400000)), Skip.aniSkipParse(ani))
    assertEquals(emptyList<Segment>(), Skip.aniSkipParse("""{"found":false,"results":[]}"""))
  }

  @Test fun subtitleTiming() {
    val srt = "1\n00:00:01,000 --> 00:00:02,500\nAt 12:30,000 we go\n\n2\n01:59:59,800 --> 02:00:00,900\nEnd"
    assertEquals("1\n00:00:01,500 --> 00:00:03,000\nAt 12:30,000 we go\n\n2\n02:00:00,300 --> 02:00:01,400\nEnd", shiftSubs(srt, 500))
    assertEquals("00:00:00,000 --> 00:00:01,000", shiftSubs("00:00:00,300 --> 00:00:01,500", -500)) // never below zero
    assertEquals("WEBVTT\n\n00:01:00.500 --> 00:01:02.000\nHi", shiftSubs("WEBVTT\n\n01:01.000 --> 01:02.500\nHi", -500)) // VTT, no hours
  }

  @Test fun pins() {
    val files = HashMap<String, Mem>()
    val st = Store { files.getOrPut(it) { Mem() } }
    val kid = st.addProfile("Kid")
    st.setKids(kid.id, 1)
    assertTrue(Pins.forSettings(st).isEmpty()) // grown-up profile in use: Settings open
    st.switchProfile(kid.id)
    val asKid = Store { files.getOrPut(it) { Mem() } }
    assertTrue(Pins.forSettings(asKid).isEmpty()) // no grown-up PIN yet: nothing to lock with
    Pins.set(st, "main", "1234")
    assertEquals(listOf("1234"), Pins.forSettings(asKid))
    assertTrue(Pins.valid("0007") && !Pins.valid("12a4") && !Pins.valid("123"))
    assertEquals("1234", Pins.of(st, "main"))
  }

  @Test fun autoSourceAndAudio() {
    val st = store()
    assertEquals(1, AutoSource.min(st)) // as before: plays as soon as there's a source
    st.put("autosource", "10"); assertEquals("When 10+ are found", AutoSource.label(AutoSource.min(st)))
    st.put("autosource", "0"); assertEquals("Off", AutoSource.label(AutoSource.min(st)))
    st.put("audiolang", "fr"); assertEquals("fr", st.audioLang)
    st.put("audiolang", "file"); assertNull(st.audioLang)
  }

  @Test fun animeDubsFirst() {
    fun src(file: String) = Source("A", "A", "", "1080p", "", null, file, url = "http://x/$file")
    val sub = src("[SubsPlease] Frieren - 01 (1080p).mkv")
    val dub = src("Frieren S01E01 1080p WEB Dual Audio.mkv")
    val eng = src("Frieren.S01E01.English.Dub.1080p.mkv")
    assertTrue(isDub(dub) && isDub(eng) && !isDub(sub))
    assertEquals(sub, rank(listOf(sub, dub, eng), dubs = true).last())
    assertEquals(sub, rank(listOf(sub, dub), dubs = true).last())
    assertEquals(sub, rank(listOf(sub, dub), dubs = false).first()) // off: order untouched
    assertTrue(isAnime(org.json.JSONObject("""{"original_language":"ja","genres":[{"id":16}]}""")))
    assertTrue(!isAnime(org.json.JSONObject("""{"original_language":"en","genres":[{"id":16}]}""")))
  }

  @Test fun startingSoonAndBusy() {
    val st = store()
    val c = Channel(1, 1, "BBC One", null, "1")
    val now = 10_000_000L
    Reminders.toggle(st, c, Programme("Later", "", now + 4 * 3_600_000L, now + 5 * 3_600_000L))
    Reminders.toggle(st, c, Programme("Soon", "", now + 600_000L, now + 3_600_000L))
    assertEquals(listOf("Soon"), LiveSearch.soon(st, now).map { it.title })
    runCatching { Busy.run("Test") { error("boom") } }
    assertTrue(Busy.now.isEmpty()) // gone even when the job fails
  }
}
