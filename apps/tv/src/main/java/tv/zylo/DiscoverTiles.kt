// Discover's game tiles. Each one shows real artwork and, when focused, acts out its game in a single short move:
// Blind Date comes into focus, Double Feature's pair slides together, Time Machine dials back the years, and so on.
// The move plays once per focus (about 0.65s) and Reduce motion shows its end state at once.
package tv.zylo

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

val TILE_W = 260.dp
val TILE_H = 146.dp

/** A game on Discover: artwork, its move on focus, and its name and line over a scrim. */
@Composable
fun ModeTile(store: Store, m: Mode, pool: List<Item>, seed: Int, modifier: Modifier, onClick: () -> Unit) {
  var focused by remember { mutableStateOf(false) }
  val f by animateFloatAsState(if (focused) 1f else 0f, tween(ms(650), easing = Z.EaseOut), label = "trick")
  ZCard(onClick, modifier.width(TILE_W).height(TILE_H).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "${m.name}. ${m.line}" }) {
    Box(Modifier.fillMaxSize().background(Z.Card)) {
      Trick(store, m.name, f, focused, pool, seed)
      Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Z.Bg.copy(alpha = 0.95f))))
      Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(m.name, style = MaterialTheme.typography.titleMedium, color = Z.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(m.line, style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun BoxScope.Trick(store: Store, name: String, f: Float, focused: Boolean, pool: List<Item>, seed: Int) {
  fun pick(k: Int): Item? = if (pool.isEmpty()) null else pool[(seed + k * 7).mod(pool.size)]
  when (name) {
    // A stack of posters fans out like cards being swiped.
    "Movie Night Match" -> {
      Backdrop(pick(0), Modifier.fillMaxSize(), dim = 0.55f)
      Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 30.dp)) {
        listOf(-1, 1, 0).forEach { j ->
          Poster(pick(1 + j + 1), 56.dp, Modifier.graphicsLayer {
            transformOrigin = TransformOrigin(0.5f, 1f)
            rotationZ = j * (3f + 11f * f); translationX = j * (6f + 22f * f) * density
          })
        }
      }
    }
    // Two titles square up; "or" appears between them.
    "Help Me Choose" -> {
      Backdrop(pick(3), Modifier.fillMaxSize(), dim = 0.6f)
      Row(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Poster(pick(4), 52.dp, Modifier.graphicsLayer { rotationZ = -7f * (1 - f) })
        Text("or", Modifier.padding(horizontal = 6.dp + 6.dp * f).graphicsLayer { alpha = f }, style = MaterialTheme.typography.titleMedium, color = Z.Text)
        Poster(pick(5), 52.dp, Modifier.graphicsLayer { rotationZ = 7f * (1 - f) })
      }
    }
    // Clues only: the film is a blur until you look properly.
    "Blind Date" -> {
      Backdrop(pick(6), Modifier.fillMaxSize().blur(20.dp * (1 - f)), light = 0.35f + 0.65f * f)
      Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF3A0A12).copy(alpha = 0.75f * (1 - f) + 0.2f), Color.Transparent))))
    }
    // The pair slides in from either side to sit together.
    "Double Feature" -> {
      Backdrop(pick(7), Modifier.fillMaxSize(), dim = 0.7f)
      Row(Modifier.align(Alignment.TopCenter).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Poster(pick(8), 52.dp, Modifier.graphicsLayer { translationX = -70f * (1 - f) * density; alpha = 0.4f + 0.6f * f })
        Poster(pick(9), 52.dp, Modifier.graphicsLayer { translationX = 70f * (1 - f) * density; alpha = 0.4f + 0.6f * f })
      }
    }
    // Falling in: the picture zooms toward its centre as the edges close.
    "Rabbit Hole" -> {
      Backdrop(pick(10), Modifier.fillMaxSize().graphicsLayer { val z = 1f + 0.4f * f; scaleX = z; scaleY = z })
      Box(Modifier.fillMaxSize().background(Brush.radialGradient(0.55f - 0.2f * f to Color.Transparent, 1f to Z.Bg.copy(alpha = 0.55f + 0.2f * f))))
    }
    // A case file: grey and redacted until you open it.
    "Deep Cuts" -> {
      Backdrop(pick(11), Modifier.fillMaxSize(), light = 0.25f + 0.75f * f)
      Box(Modifier.align(Alignment.TopStart).padding(top = 46.dp).fillMaxWidth().height(16.dp).graphicsLayer { translationX = size.width * f }.background(Color.Black))
      Text("CASE FILE", Modifier.align(Alignment.TopStart).padding(12.dp).background(Color.Black.copy(alpha = 0.8f), Z.Small).padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = Z.Red)
    }
    // A pile of a universe lays itself out in order.
    "Franchise Journeys" -> {
      Backdrop(pick(12), Modifier.fillMaxSize(), dim = 0.7f)
      Box(Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp)) {
        (3 downTo 0).forEach { i -> Poster(pick(13 + i), 44.dp, Modifier.graphicsLayer { translationX = i * (8f + 44f * f) * density; rotationZ = (1 - f) * (i - 1.5f) * 3f }) }
      }
    }
    // A feeling, not a genre: one mood's colour gives way to the next.
    "Moodboard" -> {
      val a = MOODS[seed.mod(MOODS.size)]
      val b = MOODS[(seed + 1).mod(MOODS.size)]
      Backdrop(pick(17), Modifier.fillMaxSize(), light = 0.5f)
      Box(Modifier.fillMaxSize().background(lerp(Color(a.colour), Color(b.colour), f).copy(alpha = 0.55f)))
      Text(if (f < 0.5f) a.name else b.name, Modifier.align(Alignment.TopStart).padding(14.dp), style = MaterialTheme.typography.labelLarge, color = Z.Text)
    }
    // The years dial back to one you might visit.
    "Time Machine" -> {
      val year = remember(seed) { 1950 + seed.mod(66) }
      Backdrop(pick(18), Modifier.fillMaxSize(), light = 0.3f)
      Box(Modifier.fillMaxSize().background(Color(decadeLook(year).first).copy(alpha = 0.4f)))
      val shown = if (focused) (2026 - (2026 - year) * f).toInt() else year
      Text("$shown", Modifier.align(Alignment.TopStart).padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"), color = Z.Text)
    }
    // Your challenge posters, lit where you've watched them.
    "Watch Challenges" -> {
      val list = remember { CHALLENGES.firstNotNullOfOrNull { c -> Challenges.cached(store, c)?.takeIf { it.isNotEmpty() } } }
      if (list != null) {
        val lit = remember(list) { list.filter { seenBefore(store, it) }.map { it.key }.toSet() }
        Mosaic(list, lit, emptySet(), Modifier.fillMaxSize().graphicsLayer { val z = 1f + 0.05f * f; scaleX = z; scaleY = z })
      } else Backdrop(pick(19), Modifier.fillMaxSize(), light = 0.3f + 0.7f * f)
    }
    // The next four weeks flick past, then it settles back on today.
    "Calendar" -> {
      Backdrop(pick(20), Modifier.fillMaxSize(), dim = 0.65f)
      val day = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, if (focused) (27 * kotlin.math.sin(Math.PI * f)).toInt() else 0) }.time
      Column(Modifier.align(Alignment.TopStart).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(SimpleDateFormat("d", Locale.UK).format(day), style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"), color = Z.Text)
        Text(SimpleDateFormat("MMMM", Locale.UK).format(day), style = MaterialTheme.typography.labelLarge, color = Z.Text)
      }
    }
    // Your year: what you watched, lighting up.
    else -> if (name.endsWith("Wrapped")) {
      val mine = remember { store.watches().sortedByDescending { it.at }.map { it.item }.filter { it.poster != null }.distinctBy { it.key }.take(6) }
      val shown = mine.ifEmpty { List(6) { pick(21 + it) }.filterNotNull() }
      Row(Modifier.fillMaxSize()) {
        shown.forEach { AsyncImage(img(it.poster, "w185"), null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop, colorFilter = lightFilter(0.2f + 0.8f * f)) }
      }
    } else Backdrop(pick(22), Modifier.fillMaxSize(), dim = 0.6f)
  }
}

@Composable
private fun Backdrop(item: Item?, modifier: Modifier, dim: Float = 0f, light: Float = 1f) {
  if (item == null) return
  Box(modifier) {
    AsyncImage(img(item.backdrop, "w780"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = lightFilter(light))
    if (dim > 0f) Box(Modifier.fillMaxSize().background(Z.Bg.copy(alpha = dim)))
    // A soft shade along the top, under any date, year or label the tile draws there.
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Z.Bg.copy(alpha = 0.6f), 0.4f to Color.Transparent)))
  }
}

@Composable
private fun Poster(item: Item?, width: Dp, modifier: Modifier = Modifier) {
  if (item == null) return
  AsyncImage(img(item.poster, "w185"), null, modifier.width(width).aspectRatio(2f / 3f).clip(Z.Small).background(Z.Elevated), contentScale = ContentScale.Crop)
}
