package tv.zylo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {
  private val pack = listOf(
    DebridFile("Show/Show.S01E01.1080p.mkv", 900, 0),
    DebridFile("Show/Show.S01E02.1080p.mkv", 950, 1),
    DebridFile("Show/Show.S01E12.1080p.mkv", 990, 2),
    DebridFile("Show/sample.txt", 9999, 3),
  )

  @Test fun picksFile() {
    assertEquals("Show/Show.S01E02.1080p.mkv", pickFile(pack, Hint("show.s01e02.1080p.mkv", null, null, null)).name) // by file name
    assertEquals("Show/Show.S01E01.1080p.mkv", pickFile(pack, Hint(null, 0, null, null)).name) // by index
    assertEquals("Show/Show.S01E01.1080p.mkv", pickFile(pack, Hint(null, null, 1, 1)).name) // E1 isn't E12
    assertEquals("Show/Show.S01E12.1080p.mkv", pickFile(pack, Hint(null, null, null, null)).name) // biggest video, not the txt
  }

  @Test fun parsesAndRanksStreams() {
    val direct = source(JSONObject("""{"name":"HTTP 720p","url":"https://x.example/a.mp4","behaviorHints":{"proxyHeaders":{"request":{"Referer":"https://x.example"}}}}"""), "A")!!
    assertEquals("720p", direct.quality)
    assertEquals(mapOf("Referer" to "https://x.example"), direct.headers)
    val torrent = source(JSONObject("""{"name":"4K HDR","title":"Film 2160p DV 12.5 GB","infoHash":"ABCDEFABCDEFABCDEFABCDEFABCDEFABCDEFABCD","fileIdx":2}"""), "B")!!
    assertEquals("abcdefabcdefabcdefabcdefabcdefabcdefabcd", torrent.infoHash)
    assertEquals("HDR DV", torrent.hdr)
    assertEquals("12.5 GB", torrent.size)
    assertNull(source(JSONObject("""{"ytId":"dQw4w9WgXcQ"}"""), "C"))

    val ranked = rank(listOf(direct, torrent, torrent.copy(debrid = "torbox", cached = true), torrent.copy(debrid = "realdebrid")))
    assertEquals(listOf("torbox", null, "realdebrid"), ranked.map { it.debrid }) // cached 4K, direct 720p, then not-known-ready
  }

  @Test fun addonSupport() {
    val a = Addon("https://addon.example/cfg/manifest.json", JSONObject("""{"types":["movie","series"],"resources":["catalog",{"name":"stream","types":["movie"],"idPrefixes":["tt"]}]}"""))
    assertEquals("https://addon.example/cfg", a.base)
    assertTrue(a.supports("stream", "movie", "tt0111161"))
    assertTrue(!a.supports("stream", "series", "tt0111161:1:1"))
    assertTrue(!a.supports("stream", "movie", "tmdb:278"))
  }
}
