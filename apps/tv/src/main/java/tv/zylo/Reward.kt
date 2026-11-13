@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
// Lights Up: the reward language of Zylo's game-like pages. Artwork you haven't earned yet sits in grey; watching it
// (or picking it) brings the colour up. Watch Challenges, Zylo Wrapped and This or That all speak it.
// These are the one place motion runs past 300ms: a reward is a moment, not a focus move. Reduce motion skips all of it.
package tv.zylo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private const val UNLIT = 0.5f // brightness of artwork still in grey

/** Artwork at a given light: 0 is grey and dimmed, 1 is full colour (no filter at all). */
fun lightFilter(light: Float): ColorFilter? {
  if (light >= 1f) return null
  val l = light.coerceIn(0f, 1f)
  val b = UNLIT + (1 - UNLIT) * l
  return ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(l); timesAssign(ColorMatrix().apply { setToScale(b, b, b, 1f) }) })
}

/** [Artwork] at a given light. The title (if any) shows underneath while the image loads. */
@Composable
fun LitArt(url: String?, light: Float, modifier: Modifier = Modifier.fillMaxSize(), title: String = "") =
  Box(modifier.background(Z.Card), contentAlignment = Alignment.Center) {
    if (title.isNotEmpty()) Text(title, Modifier.padding(10.dp), style = MaterialTheme.typography.titleSmall, color = Z.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
    AsyncImage(url, title.ifEmpty { null }, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = lightFilter(light))
  }

/** 1 when [lit], 0 when not. With [rise] a lit thing starts grey and comes up after [wait] ms. Instant with Reduce motion. */
@Composable
fun rememberLight(lit: Boolean, rise: Boolean = false, wait: Int = 0, length: Int = 900): Float {
  val reduce = LocalReduceMotion.current
  val a = remember { Animatable(if (lit && (!rise || reduce)) 1f else 0f) }
  LaunchedEffect(lit) {
    val to = if (lit) 1f else 0f
    if (a.value != to) {
      if (!reduce) delay(wait.toLong())
      a.animateTo(to, tween(if (reduce) 0 else length, easing = Z.EaseOut))
    }
  }
  return a.value
}

object Lights {
  /**
   * What's lit now that wasn't the last time [place] was shown, so it can light up in front of you. Remembers the
   * new set. The first showing returns nothing: there's no "before" to compare with.
   */
  fun fresh(store: Store, place: String, lit: Set<String>): Set<String> {
    val k = "lit:$place"
    val was = store.str(k)
    store.put(k, "|" + lit.joinToString("|"))
    return if (was.isEmpty()) emptySet() else lit - was.split('|').toSet()
  }

  /** True once per place: the first time it's asked after [now] turned true. */
  fun firstTime(store: Store, place: String, now: Boolean): Boolean {
    if (!now) return false
    val k = "lit:once:$place"
    if (store.str(k).isNotEmpty()) return false
    store.put(k, "1")
    return true
  }
}

/** A "Seen" stamp on a poster's corner. With [thud] it lands from above the poster after [wait] ms, overshooting a touch. */
@Composable
fun BoxScope.SeenStamp(thud: Boolean, wait: Int) {
  val reduce = LocalReduceMotion.current
  val a = remember { Animatable(if (thud && !reduce) 0f else 1f) }
  LaunchedEffect(Unit) { if (a.value < 1f) { delay(wait.toLong()); a.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 420f)) } }
  Text(
    "Seen",
    Modifier.align(Alignment.TopEnd).padding(8.dp)
      .graphicsLayer { val v = a.value; val s = 1.9f - 0.9f * v; scaleX = s; scaleY = s; alpha = v.coerceIn(0f, 1f); rotationZ = -8f }
      .background(Z.Bg.copy(alpha = 0.78f), Z.Small).border(1.5.dp, Z.Text, Z.Small).padding(horizontal = 8.dp, vertical = 3.dp),
    style = MaterialTheme.typography.labelLarge, color = Z.Text,
  )
}

/** A challenge's posters as a two-row mosaic, each one lit once it's been watched. Newly watched ones light up in turn. */
@Composable
fun Mosaic(items: List<Item>, lit: Set<String>, fresh: Set<String>, modifier: Modifier = Modifier) {
  val cols = ((items.size + 1) / 2).coerceAtLeast(1)
  val order = items.map { it.key }.filter { it in fresh }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
    repeat(2) { r ->
      Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(cols) { c ->
          val item = items.getOrNull(r * cols + c)
          Box(Modifier.weight(1f).fillMaxHeight().background(Z.Elevated)) {
            if (item != null) LitArt(img(item.poster, "w154"), rememberLight(item.key in lit, item.key in fresh, 450 + order.indexOf(item.key).coerceAtLeast(0) * 240))
          }
        }
      }
    }
  }
}

/**
 * A challenge finished: the whole screen dims, every poster flies into a mosaic and lights up in turn, then the
 * headline arrives. Focus stays inside until it's dismissed.
 */
@Composable
fun Ceremony(name: String, items: List<Item>, onDone: () -> Unit) {
  androidx.activity.compose.BackHandler { onDone() }
  val reduce = LocalReduceMotion.current
  val done = remember { FocusRequester() }
  val shown = items.take(15)
  val cols = if (shown.size <= 8) 4 else 5
  val dim = remember { Animatable(if (reduce) 1f else 0f) }
  val words = remember { Animatable(if (reduce) 1f else 0f) }
  LaunchedEffect(Unit) { delay(80); runCatching { done.requestFocus() } }
  LaunchedEffect(Unit) { dim.animateTo(1f, tween(if (reduce) 0 else 450, easing = Z.EaseOut)) }
  LaunchedEffect(Unit) { if (!reduce) { delay(shown.size * 90L + 700); words.animateTo(1f, tween(600, easing = Z.EaseOut)) } }
  Box(
    Modifier.fillMaxSize().graphicsLayer { alpha = dim.value }.background(Z.Bg.copy(alpha = 0.96f))
      .focusProperties { @Suppress("DEPRECATION") exit = { FocusRequester.Cancel } }.focusGroup(),
  ) {
    Row(Modifier.fillMaxSize().padding(horizontal = Z.Pad * 2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.chunked(cols).forEachIndexed { r, row ->
          Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEachIndexed { c, item -> Landing(item, r * cols + c, reduce) } }
        }
      }
      Column(Modifier.weight(1f).graphicsLayer { alpha = words.value; translationY = (1 - words.value) * 28.dp.toPx() }) {
        Text("Challenge complete", style = MaterialTheme.typography.displayMedium)
        Text(name, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.headlineSmall, color = Z.Text2)
        Text("All ${items.size} watched.", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodyLarge, color = Z.Text2)
        ZButton("Back to Challenges", onDone, Modifier.padding(top = 24.dp).focusRequester(done))
      }
    }
  }
}

/** One poster of the ceremony: drops in tilted, settles on a spring, then lights. */
@Composable
private fun Landing(item: Item, n: Int, reduce: Boolean) {
  val p = remember { Animatable(if (reduce) 1f else 0f) }
  LaunchedEffect(Unit) { if (!reduce) { delay(n * 90L + 150); p.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 240f)) } }
  val light = rememberLight(true, rise = true, wait = n * 90 + 550, length = 700)
  Box(
    Modifier.width(78.dp).aspectRatio(2f / 3f).graphicsLayer {
      val v = p.value
      alpha = v.coerceIn(0f, 1f); scaleX = 0.6f + 0.4f * v; scaleY = scaleX
      translationY = (1 - v) * 70.dp.toPx(); rotationZ = (1 - v) * if (n % 2 == 0) -9f else 9f
    }.clip(Z.Small),
  ) { LitArt(img(item.poster, "w185"), light) }
}
