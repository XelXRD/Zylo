package tv.zylo

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** Live TV subtitles hold the sound back: silence first, the same audio later, and the clock told about both. */
class CaptionsTest {
  private val rate = 1_000 // 1 frame per ms keeps the sums readable; mono 16-bit: 2 bytes a frame

  private fun chunk(tap: LiveCaptions.Tap, from: Int, frames: Int): ShortArray {
    val b = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
    repeat(frames) { b.putShort((from + it).toShort()) }
    b.flip()
    tap.queueInput(b)
    val out = tap.output.order(ByteOrder.LITTLE_ENDIAN)
    return ShortArray(out.remaining() / 2) { out.short }
  }

  @Test fun holdsBackThenSkipsUpToDate() {
    val tap = LiveCaptions.Tap()
    tap.configure(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
    tap.flush()
    // Off: straight through.
    assertEquals(1, chunk(tap, 1, 100)[0].toInt())
    // On: 3 s (3000 frames) of silence before the audio comes out, and the clock leaves that silence out.
    tap.listener = LiveCaptions.Captioner(tap)
    var t = 101
    repeat(30) { assert(chunk(tap, t, 100).all { it.toInt() == 0 }); t += 100 }
    assertEquals(3_000_000L, tap.shiftUs())
    assertEquals(100L, tap.heard)
    assertEquals(101, chunk(tap, t, 100)[0].toInt()) // the first held frame, 3 s late
    t += 100
    // Off again: it skips the held 3 s and plays the newest audio.
    tap.listener = null
    assertEquals(t, chunk(tap, t, 100)[0].toInt())
    assertEquals(0L, tap.shiftUs())
  }
}
