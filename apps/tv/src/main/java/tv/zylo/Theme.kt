// Zylo design system: tokens from the design guidelines, the Z mark, and the shared components every screen uses.
package tv.zylo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

object Z {
  val Red = Color(0xFFBD1422)
  val RedFocus = Color(0xFFD41A29)
  val Bg = Color(0xFF101114)
  val Surface = Color(0xFF191A1E)
  val Card = Color(0xFF222329)
  val Elevated = Color(0xFF292A31)
  val Text = Color(0xFFF5F5F5)
  val Text2 = Color(0xFFA4A5AB)
  val Muted = Color(0xFF73747D)
  val Border = Color(0xFF34353D)
  val Success = Color(0xFF36B37E)
  val Warning = Color(0xFFE5A83B)
  val Error = Color(0xFFE05252)
  val Info = Color(0xFF5799DB)
  val Small = RoundedCornerShape(6.dp)
  val Medium = RoundedCornerShape(10.dp)
  val Large = RoundedCornerShape(14.dp)
  val Pad = 48.dp // TV overscan-safe side margin
  const val Fast = 180
  const val Normal = 240
  const val PreviewDelay = 800L // focus rest before a card expands and its trailer starts
}

val Inter = FontFamily(
  Font(R.font.inter_regular, FontWeight.Normal),
  Font(R.font.inter_medium, FontWeight.Medium),
  Font(R.font.inter_semibold, FontWeight.SemiBold),
  Font(R.font.inter_bold, FontWeight.Bold),
)

// The guideline sizes are for a 1080p canvas; TV dp is half that, so 64px ≈ 32sp.
private fun style(size: Float, weight: FontWeight, line: Float = 1.25f) = TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = (size * line).sp)

private val ZType = Typography(
  displayLarge = style(32f, FontWeight.Bold), // featured title
  displayMedium = style(28f, FontWeight.Bold), // detail title
  displaySmall = style(24f, FontWeight.SemiBold), // page heading
  headlineSmall = style(20f, FontWeight.SemiBold),
  titleLarge = style(17f, FontWeight.SemiBold), // section heading
  titleMedium = style(15f, FontWeight.SemiBold),
  titleSmall = style(14f, FontWeight.Medium), // card title
  bodyLarge = style(15f, FontWeight.Normal, 1.45f),
  bodyMedium = style(14f, FontWeight.Normal, 1.45f),
  bodySmall = style(12.5f, FontWeight.Normal, 1.4f), // metadata
  labelLarge = style(13.5f, FontWeight.Medium),
  labelMedium = style(12f, FontWeight.Medium),
  labelSmall = style(11f, FontWeight.Medium),
)

val LocalReduceMotion = staticCompositionLocalOf { false }

/** Animation length, or 0 when Reduce motion is on. */
@Composable fun ms(n: Int) = if (LocalReduceMotion.current) 0 else n

@Composable
fun ZTheme(reduceMotion: Boolean, content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = darkColorScheme(
      primary = Z.Red, onPrimary = Color.White, background = Z.Bg, onBackground = Z.Text, surface = Z.Bg, onSurface = Z.Text,
      surfaceVariant = Z.Card, onSurfaceVariant = Z.Text2, border = Z.Border, error = Z.Error,
    ),
    typography = ZType,
  ) {
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
      Surface(Modifier.fillMaxSize(), colors = SurfaceDefaults.colors(containerColor = Z.Bg, contentColor = Z.Text)) { content() }
    }
  }
}

// --- Brand ----------------------------------------------------------------------------------

private val LogoRed = Color(0xFFE3121E)
private val LogoShade = Color(0xFF8E0B14)

/**
 * The Zylo Z, redrawn as a vector from the concept image (the bitmap there is only ~75px wide).
 * ponytail: swap for the original artwork file when Alex has it.
 */
@Composable
fun ZMark(modifier: Modifier = Modifier) = Canvas(modifier.aspectRatio(1.45f)) {
  fun poly(color: Color, vararg p: Float) = drawPath(Path().apply {
    moveTo(p[0] * size.width, p[1] * size.height)
    for (i in 2 until p.size step 2) lineTo(p[i] * size.width, p[i + 1] * size.height)
    close()
  }, color)
  poly(LogoShade, 0.56f, 0.30f, 0.87f, 0.30f, 0.45f, 0.70f, 0.14f, 0.70f) // the folded diagonal
  poly(LogoRed, 0.13f, 0f, 1f, 0f, 0.87f, 0.30f, 0f, 0.30f)
  poly(LogoRed, 0.14f, 0.70f, 1f, 0.70f, 0.87f, 1f, 0f, 1f)
}

@Composable
fun Logo(height: Dp = 22.dp, wordmark: Boolean = true, color: Color = Z.Text) = Row(verticalAlignment = Alignment.CenterVertically) {
  ZMark(Modifier.height(height))
  if (wordmark) {
    Spacer(Modifier.width(height * 0.4f))
    Text("Zylo", style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = (height.value * 0.95f).sp), color = color)
  }
}

// --- Components -------------------------------------------------------------------------------

@Composable
fun Glyph(icon: ImageVector, size: Dp = 18.dp, tint: Color = LocalContentColor.current) = Image(icon, null, Modifier.size(size), colorFilter = ColorFilter.tint(tint))

/** Primary: Zylo red. Secondary: translucent dark, turns white on focus. */
@Composable
fun ZButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, icon: ImageVector? = null) {
  Button(
    onClick, modifier,
    scale = ButtonDefaults.scale(focusedScale = if (LocalReduceMotion.current) 1f else 1.05f),
    shape = ButtonDefaults.shape(Z.Small),
    colors = if (primary) ButtonDefaults.colors(containerColor = Z.Red, contentColor = Color.White, focusedContainerColor = Z.RedFocus, focusedContentColor = Color.White)
    else ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.12f), contentColor = Z.Text, focusedContainerColor = Z.Text, focusedContentColor = Z.Bg),
    border = if (primary) ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = Z.Small)) else ButtonDefaults.border(),
    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
  ) {
    if (icon != null) {
      Glyph(icon)
      Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
  }
}

@Composable
fun ZCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) = Card(
  onClick, modifier,
  shape = CardDefaults.shape(Z.Medium),
  colors = CardDefaults.colors(containerColor = Z.Card),
  scale = CardDefaults.scale(focusedScale = if (LocalReduceMotion.current) 1f else 1.06f),
  border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Z.Text), shape = Z.Medium)),
  glow = CardDefaults.glow(focusedGlow = Glow(Color.Black, 14.dp)),
) { content() }

/** Clickable row/tab surface: transparent, white with dark text when focused. */
@Composable
fun ZSurface(onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, container: Color = Color.Transparent, content: @Composable BoxScope.() -> Unit) = Surface(
  onClick, modifier,
  shape = ClickableSurfaceDefaults.shape(Z.Small),
  colors = ClickableSurfaceDefaults.colors(
    containerColor = if (selected) Z.Red.copy(alpha = 0.18f) else container,
    contentColor = if (selected) Z.Text else Z.Text2,
    focusedContainerColor = Z.Text,
    focusedContentColor = Z.Bg,
  ),
  scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
  content = content,
)

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) =
  Box(modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f))) {
    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(Z.Red))
  }

@Composable
fun StatusChip(text: String, color: Color) = Row(
  Modifier.background(color.copy(alpha = 0.14f), Z.Small).padding(horizontal = 9.dp, vertical = 4.dp),
  verticalAlignment = Alignment.CenterVertically,
) {
  Box(Modifier.size(7.dp).background(color, CircleShape))
  Spacer(Modifier.width(6.dp))
  Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

/** Square tile with initials, standing in for a service logo. */
@Composable
fun Tile(text: String, size: Dp = 40.dp) = Box(Modifier.size(size).background(Z.Elevated, Z.Small), contentAlignment = Alignment.Center) {
  Text(text, style = MaterialTheme.typography.labelLarge, color = Z.Text)
}

@Composable
fun MetaLine(parts: List<String>, modifier: Modifier = Modifier, color: Color = Z.Text2) =
  Text(parts.filter { it.isNotBlank() }.joinToString("  ·  "), modifier, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)

/** Poster card for grids: artwork, progress bar when part-watched, tick when watched. */
@Composable
fun PosterCard(store: Store, item: Item, modifier: Modifier = Modifier, onClick: () -> Unit) = ZCard(onClick, modifier.aspectRatio(2f / 3f)) {
  Box(Modifier.fillMaxSize()) {
    Artwork(img(item.poster, "w342"), item.title)
    WatchMarks(store.latest(item)?.takeIf { item.type == "movie" || !it.finished }) // a finished episode isn't a finished series
  }
}

/** Artwork with the title underneath, so a missing or slow image never leaves a blank card. */
@Composable
fun Artwork(url: String?, title: String, modifier: Modifier = Modifier.fillMaxSize()) = Box(modifier.background(Z.Card), contentAlignment = Alignment.Center) {
  Text(title, Modifier.padding(10.dp), style = MaterialTheme.typography.titleSmall, color = Z.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
  AsyncImage(url, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
}

@Composable
fun BoxScope.WatchMarks(w: Watch?) {
  if (w == null) return
  if (w.finished) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(Z.Bg.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
    Glyph(Icons.Filled.Check, 14.dp, Z.Text)
  }
  else if (w.started) ProgressBar(w.fraction, Modifier.align(Alignment.BottomCenter))
}

// --- Focus memory: put focus back where it was when returning to a screen -------------------------

class FocusMemory {
  var key: String? = null
  val requester = FocusRequester()
}

fun Modifier.focusMemory(m: FocusMemory, key: String, default: Boolean = false): Modifier =
  (if (m.key == key || (m.key == null && default)) focusRequester(m.requester) else this).onFocusChanged { if (it.isFocused) m.key = key }

@Composable
fun RestoreFocus(m: FocusMemory, ready: Boolean) = LaunchedEffect(ready) {
  if (ready) {
    delay(80)
    runCatching { m.requester.requestFocus() }
  }
}
