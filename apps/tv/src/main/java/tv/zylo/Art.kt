// Artwork beyond TMDB's posters: title logos, clean backgrounds and landscape thumbs from fanart.tv (your own API key),
// with TMDB's logos as the fallback so titles still get a logo without fanart.tv. API: https://fanart.tv/api-docs/
package tv.zylo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Everything a card or page needs to look its best. Nulls mean "use the plain TMDB artwork". */
class Look(val details: JSONObject?, val logo: String?, val background: String?, val thumb: String?)

object Art {
  private val fanart = ConcurrentHashMap<String, JSONObject>()
  private val looks = ConcurrentHashMap<String, Look>()
  private val ENGLISH = setOf("en", "", "00", "null")

  private fun fanart(store: Store, item: Item, d: JSONObject?): JSONObject? {
    val key = store.str("fanart_key").ifEmpty { return null }
    val id = if (item.type == "movie") "${item.id}" else d?.optJSONObject("external_ids")?.optInt("tvdb_id")?.takeIf { it > 0 }?.toString() ?: return null
    val path = "${if (item.type == "movie") "movies" else "tv"}/$id"
    fanart[path]?.let { return it }
    return runCatching { getJson("https://webservice.fanart.tv/v3/$path?api_key=${enc(key)}") }.getOrElse { JSONObject() }.also { fanart[path] = it }
  }

  /** Most-liked English (or textless) image of the first kind that has one. */
  private fun best(f: JSONObject?, vararg kinds: String, preview: Boolean = false): String? = kinds.firstNotNullOfOrNull { k ->
    f?.optJSONArray(k)?.objects()?.filter { it.optString("lang") in ENGLISH }?.maxByOrNull { it.optString("likes").toIntOrNull() ?: 0 }?.str("url")
  }?.let { if (preview) it.replace("/fanart/", "/preview/") else it }

  private fun tmdbLogo(d: JSONObject?) = d?.optJSONObject("images")?.optJSONArray("logos")?.objects()
    ?.filter { it.optString("file_path").endsWith(".png") && it.optString("iso_639_1") in ENGLISH } // Coil can't draw TMDB's SVG logos
    ?.maxByOrNull { it.optDouble("vote_average") }?.str("file_path")?.let { img(it, "w500") }

  /** Blocking; call off the main thread. Cached for the session. */
  fun look(store: Store, item: Item): Look = looks[item.key] ?: run {
    val d = Tmdb(store.tmdb).preview(item)
    val f = fanart(store, item, d)
    Look(d, best(f, "hdmovielogo", "hdtvlogo", "movielogo", "clearlogo") ?: tmdbLogo(d), best(f, "moviebackground", "showbackground"), best(f, "moviethumb", "tvthumb", preview = true))
  }.also { if (it.details != null) looks[item.key] = it }

  fun cached(item: Item) = looks[item.key]
}

@Composable
fun rememberLook(store: Store, item: Item, enabled: Boolean = true): Look? {
  var look by remember(item.key) { mutableStateOf(Art.cached(item)) }
  LaunchedEffect(item.key, enabled) { if (enabled && look == null) look = withContext(Dispatchers.IO) { Art.look(store, item) } }
  return look
}

/** The title's logo artwork, or the title in text when there's no logo (or it fails to load). */
@Composable
fun TitleLogo(look: Look?, title: String, style: TextStyle, width: Dp, height: Dp, alignment: Alignment = Alignment.BottomStart) {
  var failed by remember(look?.logo) { mutableStateOf(false) }
  val logo = look?.logo
  if (logo == null || failed) Text(title, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
  else Box(Modifier.size(width, height)) {
    AsyncImage(logo, title, Modifier.matchParentSize(), contentScale = ContentScale.Fit, alignment = alignment, onError = { failed = true })
  }
}
