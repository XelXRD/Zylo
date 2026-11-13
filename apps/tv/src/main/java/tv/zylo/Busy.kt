// What Zylo is doing in the background (channels, guide, Trakt, an update), shown as a chip in the top bar.
package tv.zylo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

object Busy {
  val now = mutableStateListOf<String>()

  fun <T> run(label: String, block: () -> T): T {
    Snapshot.withMutableSnapshot { now += label }
    try { return block() } finally { Snapshot.withMutableSnapshot { now.remove(label) } }
  }
}

/** Not focusable: it only says what's happening. */
@Composable
fun BusyChip() {
  val label = Busy.now.lastOrNull() ?: return
  Row(Modifier.padding(end = 10.dp).background(Z.Card.copy(alpha = 0.85f), Z.Small).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Pulse()
    Spacer(Modifier.width(8.dp))
    Text(label + (if (Busy.now.size > 1) "  +${Busy.now.size - 1}" else "") + "…", style = MaterialTheme.typography.labelMedium, color = Z.Text2)
  }
}

@Composable
private fun Pulse() {
  // Reduce motion: a steady dot.
  if (LocalReduceMotion.current) return Box(Modifier.size(8.dp).background(Z.Red, CircleShape))
  val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "busy")
  val a by t.animateFloat(0.3f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
  Box(Modifier.size(8.dp).background(Z.Red.copy(alpha = a), CircleShape))
}
