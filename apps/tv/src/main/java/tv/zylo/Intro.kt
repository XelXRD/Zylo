// The opening: house lights down. The room dims, a projector beam reaches across, the Z lights up in it and the wordmark
// follows, with a sound to match (res/raw/zylo_intro.wav, made by tools/intro_sound.py). It plays on a cold start only,
// over the app as it loads, so it never adds waiting: about two seconds, and any button skips it. Reduce motion skips it.
package tv.zylo

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

object Intro {
  /** While the intro is up, every key goes to it (MainActivity.dispatchKeyEvent): any press skips it. */
  var showing by mutableStateOf(false)
  var skip: () -> Unit = {}
}

private const val LENGTH = 2000f // the timeline; the fade into the app follows

private fun span(ms: Float, a: Float, b: Float) = ((ms - a) / (b - a)).coerceIn(0f, 1f)
private fun out(x: Float) = Z.EaseOut.transform(x)

@Composable
fun IntroScreen(sound: Boolean, done: () -> Unit) {
  val ctx = LocalContext.current
  val time = remember { Animatable(0f) }
  val exit = remember { Animatable(1f) }
  var leaving by remember { mutableStateOf(false) }
  var skipped by remember { mutableStateOf(false) }
  // Released by itself when the sound finishes (its tail runs on past the fade into the app).
  val player = remember {
    if (!sound) null else runCatching {
      MediaPlayer.create(ctx, R.raw.zylo_intro, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(), 0)
        ?.apply { setOnCompletionListener { it.release() } }
    }.getOrNull()
  }
  DisposableEffect(Unit) {
    Intro.showing = true
    Intro.skip = { if (!leaving) { skipped = true; leaving = true } }
    onDispose { Intro.showing = false }
  }
  LaunchedEffect(Unit) {
    player?.start()
    time.animateTo(LENGTH, tween(LENGTH.toInt(), easing = LinearEasing))
    leaving = true
  }
  LaunchedEffect(leaving) {
    if (!leaving) return@LaunchedEffect
    if (skipped) player?.let { p -> // a quick fade rather than a cut
      runCatching { for (v in 9 downTo 0) { p.setVolume(v / 10f, v / 10f); delay(25) }; p.stop(); p.release() }
    }
    exit.animateTo(0f, tween(if (skipped) 250 else 450, easing = Z.EaseOut))
    done()
  }

  val ms = time.value
  val lightsDown = out(span(ms, 50f, 550f))
  val beam = span(ms, 450f, 700f) * (1 - span(ms, 1500f, 1950f)) * (0.9f + 0.1f * sin(ms / 1000f * 2 * PI.toFloat() * 24)) // 24 fps flicker
  val mark = out(span(ms, 950f, 1300f))
  val word = out(span(ms, 1350f, 1750f))

  Box(Modifier.fillMaxSize().graphicsLayer { alpha = exit.value }, contentAlignment = Alignment.Center) {
    Canvas(Modifier.fillMaxSize()) {
      // The house lights going down.
      drawRect(lerp(Color(0xFF1E1F25), Color(0xFF050506), lightsDown))
      // The Z sits left of centre in the logo (mark 180dp, gap 64dp, wordmark 315dp): the beam and glow aim at it.
      val centre = Offset(size.width * 0.5f - 190.dp.toPx(), size.height * 0.5f)
      // The projector beam: a soft cone from high on the left, reaching the middle of the screen.
      if (beam > 0f) {
        val from = Offset(-size.width * 0.04f, size.height * 0.16f)
        val reach = hypot(centre.x - from.x, centre.y - from.y) * 1.25f
        // Three cones, each a little wider and fainter, so the beam has soft edges like real projector light.
        listOf(0.6f, 1f, 1.45f).forEach { spread ->
          val cone = Path().apply {
            moveTo(from.x, from.y)
            lineTo(centre.x + size.width * 0.16f, centre.y - size.height * 0.23f * spread)
            lineTo(centre.x + size.width * 0.16f, centre.y + size.height * 0.21f * spread)
            close()
          }
          drawPath(cone, Brush.radialGradient(listOf(Color.White.copy(alpha = 0.075f * beam), Color.White.copy(alpha = 0.025f * beam), Color.Transparent), from, reach))
        }
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f * beam), Color.Transparent), centre, size.height * 0.45f), size.height * 0.45f, centre)
      }
      // The Z's own glow as it lights.
      if (mark > 0f) {
        val r = size.height * 0.32f
        drawCircle(Brush.radialGradient(listOf(Z.Red.copy(alpha = 0.26f * mark * (1 - 0.4f * span(ms, 1300f, 1900f))), Color.Transparent), centre, r), r, centre)
      }
    }
    // The mark and wordmark, in the artwork's proportions (as Logo() draws them).
    val h = 120.dp
    Row(verticalAlignment = Alignment.CenterVertically) {
      ZMark(Modifier.height(h).graphicsLayer { alpha = mark; val s = 0.94f + 0.06f * mark; scaleX = s; scaleY = s })
      Spacer(Modifier.width(h * 0.53f))
      Image(painterResource(R.drawable.zylo_wordmark), "Zylo", Modifier.height(h * 0.384f).aspectRatio(340.53f / 49.75f)
        .graphicsLayer { alpha = word; translationX = (1 - word) * -16.dp.toPx() }, colorFilter = ColorFilter.tint(Z.Text))
    }
  }
}
