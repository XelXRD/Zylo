// Player extras: Skip Intro / Recap / Credits from free community databases, subtitle look, and subtitle timing.
package tv.zylo

import android.content.Context
import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import org.json.JSONObject

/** A part of a video you can skip, in milliseconds. end 0: to the end of the video. kind: intro, recap or credits. */
data class Segment(val kind: String, val start: Long, val end: Long)

object Skip {
  /**
   * IntroDB (api.introdb.app, by IMDb id) for films and series; AniSkip (api.aniskip.com, by MyAnimeList id via the
   * Kometa anime list) first for anime, whose openings it covers far better. Nothing found: no button.
   */
  fun find(ctx: Context, store: Store, item: Item, season: Int?, episode: Int?): List<Segment> {
    val d = Tmdb(store.tmdb).details(item.type, item.id)
    val imdb = d.optJSONObject("external_ids")?.str("imdb_id")
    val anime = isAnime(d)
    if (anime) runCatching { aniSkip(ctx, d, item, season, episode) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (imdb == null) return emptyList()
    val q = if (season != null && episode != null) "imdb_id=$imdb&season=$season&episode=$episode" else "imdb_id=$imdb&is_movie=true"
    return runCatching { introDb(http("https://api.introdb.app/segments?$q", timeout = 8_000)) }.getOrDefault(emptyList())
  }

  fun introDb(json: String): List<Segment> {
    val o = JSONObject(json)
    return listOf("recap" to "recap", "intro" to "intro", "outro" to "credits").mapNotNull { (field, kind) ->
      val s = o.optJSONObject(field) ?: return@mapNotNull null
      fun ms(name: String) = if (s.has("${name}_ms")) s.optLong("${name}_ms") else (s.optDouble("${name}_sec", -1.0) * 1000).toLong()
      val start = ms("start")
      val end = ms("end")
      if (start < 0 || (end in 1..start)) null else Segment(kind, start, end.coerceAtLeast(0))
    }
  }

  fun aniSkipParse(json: String): List<Segment> {
    val o = JSONObject(json)
    if (!o.optBoolean("found")) return emptyList()
    return o.optJSONArray("results")?.objects()?.mapNotNull { r ->
      val i = r.optJSONObject("interval") ?: return@mapNotNull null
      val kind = when (r.optString("skipType")) { "op", "mixed-op" -> "intro"; "ed", "mixed-ed" -> "credits"; "recap" -> "recap"; else -> return@mapNotNull null }
      Segment(kind, (i.optDouble("startTime") * 1000).toLong(), (i.optDouble("endTime") * 1000).toLong())
    }.orEmpty().sortedBy { it.start }
  }

  private fun aniSkip(ctx: Context, d: JSONObject, item: Item, season: Int?, episode: Int?): List<Segment> {
    val ids = Anime.ids(ctx)
    val hit = if (item.type == "movie" || season == null || episode == null) {
      d.optJSONObject("external_ids")?.str("imdb_id")?.let { ids.byImdb[it] }?.firstOrNull { it.mal.isNotEmpty() }?.let { it.mal[0] to 1 }
    } else {
      val rows = d.optJSONObject("external_ids")?.optInt("tvdb_id")?.let { ids.byTvdb[it] }.orEmpty()
      val absolute = d.optJSONArray("seasons")?.objects()?.filter { it.optInt("season_number") in 1 until season }?.sumOf { it.optInt("episode_count") }?.plus(episode)
      if (rows.isEmpty()) null else Anime.pickEntry(rows, "mal", season, episode, absolute)
    } ?: return emptyList()
    return aniSkipParse(http("https://api.aniskip.com/v2/skip-times/${hit.first}/${hit.second}?types%5B%5D=op&types%5B%5D=ed&types%5B%5D=recap&types%5B%5D=mixed-op&types%5B%5D=mixed-ed&episodeLength=0", timeout = 8_000))
  }

  /** The segment playing at [pos] (until a second before it ends), if any. */
  fun at(segments: List<Segment>, pos: Long, duration: Long): Segment? =
    segments.firstOrNull { val end = if (it.end > 0) it.end else duration; pos >= it.start && pos < end - 1_000 }

  fun label(kind: String) = when (kind) { "recap" -> "Skip Recap"; "credits" -> "Skip Credits"; else -> "Skip Intro" }
}

// --- Subtitles ---------------------------------------------------------------------------------------------

object SubStyle {
  val SIZES = listOf("Small" to 0.8f, "Medium" to 1f, "Large" to 1.25f, "Extra large" to 1.5f)
  val BACKGROUNDS = listOf("Shadow", "Outline", "Box", "None")
  val POSITIONS = listOf("Bottom" to 0.08f, "Raised" to 0.18f)

  fun size(store: Store) = SIZES.find { it.first == store.str("sub_size") } ?: SIZES[1]
  fun background(store: Store) = store.str("sub_bg").takeIf { it in BACKGROUNDS } ?: BACKGROUNDS[0]
  fun position(store: Store) = POSITIONS.find { it.first == store.str("sub_pos") } ?: POSITIONS[0]

  fun apply(store: Store, v: SubtitleView) {
    v.setApplyEmbeddedFontSizes(false)
    v.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * size(store).second)
    v.setBottomPaddingFraction(position(store).second)
    val (bg, edge) = when (background(store)) {
      "Box" -> 0xCC000000.toInt() to CaptionStyleCompat.EDGE_TYPE_NONE
      "Outline" -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_OUTLINE
      "None" -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_NONE
      else -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
    }
    v.setStyle(CaptionStyleCompat(Color.WHITE, bg, Color.TRANSPARENT, edge, Color.BLACK, null))
  }
}

private val STAMP = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})([,.])(\d{3})""")

/** SubRip or WebVTT with every cue moved by [ms] (positive: later). Only timing lines ("-->") change. */
fun shiftSubs(text: String, ms: Long): String = text.lines().joinToString("\n") { line ->
  if ("-->" !in line) line else line.replace(STAMP) { m ->
    val (h, mi, se, sep, mil) = m.destructured
    val t = ((((h.ifEmpty { "0" }.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + mil.toLong()) + ms).coerceAtLeast(0)
    "%02d:%02d:%02d%s%03d".format(t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, sep, t % 1000)
  }
}
