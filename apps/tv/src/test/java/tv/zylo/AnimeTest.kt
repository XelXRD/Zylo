package tv.zylo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnimeTest {
  // Attack on Titan-style split cour: TVDB season 3 is two AniList entries (episodes 1-12, then 13-22 with offset 12).
  private val rows = listOf(
    Anime.Row(267440, 3, 0, emptyList(), listOf(99147), listOf(35760)),
    Anime.Row(267440, 3, 12, emptyList(), listOf(104578), listOf(38524)),
    Anime.Row(267440, -1, 59, emptyList(), listOf(110277), emptyList()), // absolute-numbered entry
  )

  @Test fun picksSplitCour() {
    assertEquals(99147 to 5, Anime.pickEntry(rows, "anilist", 3, 5, null))
    assertEquals(104578 to 1, Anime.pickEntry(rows, "anilist", 3, 13, null))
    assertEquals(38524 to 10, Anime.pickEntry(rows, "mal", 3, 22, null))
  }

  @Test fun fallsBackToAbsoluteOrder() {
    assertEquals(110277 to 1, Anime.pickEntry(rows, "anilist", 4, 1, 60))
    assertNull(Anime.pickEntry(rows, "mal", 4, 1, 60)) // no MAL id on that entry
  }
}
