// Live TV subtitles made on the TV from the audio, for channels that send none: sherpa-onnx (k2-fsa/sherpa-onnx,
// Apache 2.0) with an English streaming Zipformer model. Nothing ships in the APK: the engine and model (about 110 MB)
// are downloaded only when you turn this on in Settings > Live TV and confirm, each file checked against its SHA-256.
// The audio never leaves the TV. While they show, sound and picture run a few seconds behind so each line is ready
// before it's said.
package tv.zylo

import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SherpaNative
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.ZipInputStream

object LiveCaptions {
  /** One file to fetch: where from, how big, and the SHA-256 it must have (else it's thrown away). */
  private class Part(val url: String, val size: Long, val sha256: String, val name: String)

  // sherpa-onnx v2.0.1, onnxruntime linked in: one library per CPU type, inside the release's .aar (a zip).
  private val ENGINE = Part("https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-static-link-onnxruntime-2.0.1.aar",
    38_577_050, "07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575", "engine.aar")
  private const val MODEL = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/"
  private val PARTS = listOf(
    Part(MODEL + "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 71_083_163, "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1", "encoder.onnx"),
    Part(MODEL + "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 1_307_236, "98da299f471e38bb4e1a8df579b8cc9122d6039576a77e357b3c60f17dd83b02", "decoder.onnx"),
    Part(MODEL + "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 259_335, "d944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297", "joiner.onnx"),
    Part(MODEL + "tokens.txt", 5_048, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb", "tokens.txt"),
  )
  /** The one-off download, in MB, as told before you agree to it. */
  val downloadMb = ((ENGINE.size + PARTS.sumOf { it.size }) / 1_000_000).toInt()

  lateinit var dir: File
  private val home get() = File(dir, "captions")
  private val lib get() = File(home, "libsherpa-onnx-jni.so")

  /** This TV's CPU type, if sherpa-onnx has a single-file library for it (32-bit x86 needs two: not offered). */
  val abi: String? = Build.SUPPORTED_ABIS.firstOrNull { it in setOf("arm64-v8a", "armeabi-v7a", "x86_64") }

  /** Settings > Live TV: wanted, and downloaded. */
  var on by mutableStateOf(false)
  /** Downloading: what's done so far ("45 of 110 MB"); null when not. */
  var progress by mutableStateOf<String?>(null)

  fun load(store: Store) { on = store.str("live_captions") == "on" && ready() }
  fun ready() = ::dir.isInitialized && lib.exists() && PARTS.all { File(home, it.name).exists() }
  fun set(store: Store, value: Boolean) { on = value && ready(); store.put("live_captions", if (on) "on" else "") }

  /** Frees the space (Settings > Live TV > Remove download). */
  fun remove(store: Store) { set(store, false); home.deleteRecursively() }

  /**
   * Fetches the engine and model, checking each file's SHA-256, then turns subtitles on. Runs on a background thread;
   * [done] gets null when it worked, or what went wrong, on the main thread.
   */
  fun download(store: Store, done: (String?) -> Unit) {
    val cpu = abi ?: return done("This TV's processor isn't one sherpa-onnx makes a library for.")
    Thread {
      val result = runCatching {
        home.mkdirs()
        val total = ENGINE.size + PARTS.sumOf { it.size }
        var before = 0L
        fun fetch(p: Part, into: File) {
          if (into.exists() && into.length() == p.size && sha256(into) == p.sha256) { before += p.size; return }
          val tmp = File(home, into.name + ".part")
          val digest = MessageDigest.getInstance("SHA-256")
          openStream(p.url).use { input ->
            tmp.outputStream().use { out ->
              val buf = ByteArray(64 * 1024)
              var got = 0L
              var shown = -1L
              while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n); digest.update(buf, 0, n); got += n
                val mb = (before + got) / 1_000_000
                if (mb != shown) { shown = mb; main.post { progress = "$mb of ${total / 1_000_000} MB" } }
              }
            }
          }
          val hex = digest.digest().joinToString("") { "%02x".format(it) }
          if (hex != p.sha256) { tmp.delete(); throw Exception("A downloaded file didn't match what was expected, so it wasn't used. Try again later.") }
          tmp.renameTo(into)
          before += p.size
        }
        // The engine: the .aar is checked whole, then only this TV's library is kept from it.
        if (!lib.exists()) {
          val aar = File(home, ENGINE.name)
          fetch(ENGINE, aar)
          ZipInputStream(aar.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null && e.name != "jni/$cpu/libsherpa-onnx-jni.so") e = zip.nextEntry
            if (e == null) throw Exception("The download has no library for this TV's processor.")
            File(home, "lib.part").outputStream().use { zip.copyTo(it) } // reads just this entry
          }
          File(home, "lib.part").renameTo(lib)
          aar.delete()
        } else before += ENGINE.size
        PARTS.forEach { fetch(it, File(home, it.name)) }
      }
      main.post {
        progress = null
        result.onSuccess { set(store, true); done(null) }
          .onFailure { done(if (it is HttpError) "The download didn't finish (${it.message}). Try again later." else it.message ?: "The download didn't finish. Try again later.") }
      }
    }.start()
  }

  private fun sha256(f: File): String {
    val d = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; d.update(b, 0, n) } }
    return d.digest().joinToString("") { "%02x".format(it) }
  }

  private val main = Handler(Looper.getMainLooper())

  /** While subtitles show, sound and picture are held back this long, so each line is written before it's said. */
  private const val AHEAD_MS = 3_000L // ponytail: fixed; make it a setting if slow TVs can't keep 3 s ahead
  /** The model writes a word a little after hearing it: lines show this much before the audio they came from. */
  private const val LEAD_MS = 400L

  /**
   * First in the Live TV player's audio path. Hands the sound to the recogniser as soon as it's decoded and, while
   * subtitles are on, plays it [AHEAD_MS] later: silence first (the picture waits with it, see [Chain]); turned off,
   * it skips back up to date. Straight through when subtitles are off.
   */
  class Tap : BaseAudioProcessor() {
    @Volatile var listener: Captioner? = null
    /** Frames of the programme heard so far (since the last flush): when a timed line is due. */
    @Volatile var heard = 0L
      private set
    /** Changes on every flush (seek, new stream): lines timed before it are dropped. */
    @Volatile var generation = 0
      private set
    private val held = ArrayDeque<ByteBuffer>()
    private var heldBytes = 0L
    private var decoded = 0L
    /** Silence played minus audio skipped, in frames: the clock leaves it out. [mark]: its value at the last speed change. */
    var shift = 0L
      private set
    var mark = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) =
      if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
      val size = inputBuffer.remaining()
      if (size == 0) return
      val fb = inputAudioFormat.bytesPerFrame
      val frames = size / fb
      listener?.let { feed(it, inputBuffer, frames) }
      decoded += frames
      val want = if (listener != null) AHEAD_MS * inputAudioFormat.sampleRate / 1000 * fb else 0L
      val out = replaceOutputBuffer(size)
      if (want == 0L && heldBytes == 0L) { out.put(inputBuffer).flip(); heard = decoded; return } // not holding back
      held.addLast(ByteBuffer.allocate(size).put(inputBuffer).apply { flip() })
      heldBytes += size
      // Holding more than wanted (subtitles just turned off): skip ahead.
      val extra = heldBytes - size - want
      if (extra > 0) { take(extra, null); shift -= extra / fb }
      // Play what's due; short of it, silence.
      val due = (heldBytes - want).coerceIn(0, size.toLong())
      take(due, out)
      repeat((size - due).toInt()) { out.put(0) }
      shift += (size - due) / fb
      out.flip()
      heard = decoded - heldBytes / fb
    }

    private fun take(n: Long, into: ByteBuffer?) {
      var left = n
      while (left > 0) {
        val b = held.first()
        val k = minOf(left, b.remaining().toLong()).toInt()
        into?.put(b.duplicate().apply { limit(position() + k) })
        b.position(b.position() + k)
        if (!b.hasRemaining()) held.removeFirst()
        left -= k; heldBytes -= k
      }
    }

    override fun onQueueEndOfStream() {
      if (heldBytes > 0) { val out = replaceOutputBuffer(heldBytes.toInt()); take(heldBytes, out); out.flip() }
    }

    override fun onFlush() { held.clear(); heldBytes = 0; decoded = 0; heard = 0; shift = 0; mark = 0; generation++ }

    /** The clock's correction since the last speed change, in microseconds. */
    fun shiftUs() = if (inputAudioFormat.sampleRate > 0) (shift - mark) * 1_000_000 / inputAudioFormat.sampleRate else 0L

    private fun feed(l: Captioner, input: ByteBuffer, frames: Int) {
      val b = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
      val channels = inputAudioFormat.channelCount
      // Mixed down to one channel, as floats: what the model takes (it resamples to 16 kHz itself).
      val mono = FloatArray(frames)
      for (i in 0 until frames) {
        var sum = 0
        repeat(channels) { sum += b.short.toInt() }
        mono[i] = sum / (channels * 32768f)
      }
      l.feed(mono, inputAudioFormat.sampleRate, decoded + frames, generation)
    }
  }

  /**
   * The Live TV player's audio chain: [Tap], then the usual speed changer. The clock the picture follows leaves out
   * the Tap's silence and skips, so picture and sound stay together however far back they're held.
   */
  class Chain(private val tap: Tap) : AudioProcessorChain {
    private val sonic = SonicAudioProcessor()
    private val all = arrayOf<AudioProcessor>(tap, sonic)
    override fun getAudioProcessors() = all
    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
      sonic.setSpeed(playbackParameters.speed); sonic.setPitch(playbackParameters.pitch)
      tap.mark = tap.shift // the sink counts from here now
      return playbackParameters
    }
    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean) = false
    override fun getMediaDuration(playoutDuration: Long) =
      ((if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration) - tap.shiftUs()).coerceAtLeast(0)
    override fun getSkippedOutputFrameCount() = 0L
  }

  /**
   * The recogniser for one player: loads the model (a second or two), then turns audio into text on its own thread.
   * Each update is timed to the audio it came from and shown as that's heard: [lines] is the last finished sentence
   * and the one being said now.
   */
  class Captioner(private val tap: Tap) {
    var lines by mutableStateOf("")
      private set
    var starting by mutableStateOf(true)
      private set
    private class Chunk(val samples: FloatArray, val rate: Int, val end: Long, val generation: Int)
    private class Line(val at: Long, val text: String, val generation: Int)
    private val queue = LinkedBlockingQueue<Chunk>(400)
    private val due = java.util.concurrent.ConcurrentLinkedQueue<Line>()
    @Volatile private var stopped = false
    private var last = ""

    // Full: dropped, so it never falls far behind.
    fun feed(samples: FloatArray, rate: Int, end: Long, generation: Int) { if (!stopped) queue.offer(Chunk(samples, rate, end, generation)) }

    /** On the main thread, ten times a second: the latest line whose moment has come. */
    private val show = object : Runnable {
      override fun run() {
        if (stopped) return
        var line: Line? = null
        while (true) {
          val l = due.peek() ?: break
          if (l.generation == tap.generation && l.at > tap.heard) break
          due.poll()
          if (l.generation == tap.generation) line = l
        }
        line?.let { lines = it.text }
        main.postDelayed(this, 100)
      }
    }

    fun start(): Captioner {
      main.post(show)
      Thread {
        val recognizer = runCatching {
          SherpaNative.path = lib.absolutePath
          OnlineRecognizer(config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OnlineModelConfig(
              transducer = OnlineTransducerModelConfig(File(home, "encoder.onnx").path, File(home, "decoder.onnx").path, File(home, "joiner.onnx").path),
              tokens = File(home, "tokens.txt").path, numThreads = 2, modelType = "zipformer2",
            ),
            // A sentence ends after about a second of quiet (TV rarely has long silences), or at 15 seconds.
            endpointConfig = EndpointConfig(EndpointRule(false, 2.0f, 0f), EndpointRule(true, 0.8f, 0f), EndpointRule(false, 0f, 15f)),
            enableEndpoint = true,
          ))
        }.getOrNull()
        main.post { starting = false; if (recognizer == null) lines = "Subtitles couldn't start on this TV." }
        recognizer ?: return@Thread
        var stream: OnlineStream = recognizer.createStream()
        var shown = ""
        var generation = -1
        var rate = 0
        while (!stopped) {
          val c = queue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
          // Seek or new channel: a fresh stream. sherpa-onnx keeps its resampler for a stream's life and exits the whole
          // app if the sample rate changes (one channel at 48 kHz, the next at 44.1), so reset() isn't enough.
          if (c.generation != generation || c.rate != rate) { generation = c.generation; rate = c.rate; stream.release(); stream = recognizer.createStream(); last = ""; shown = "" }
          stream.acceptWaveform(c.samples, c.rate)
          while (recognizer.isReady(stream)) recognizer.decode(stream)
          val now = recognizer.getResult(stream).text.trim()
          val ended = recognizer.isEndpoint(stream)
          if (ended) {
            if (now.isNotEmpty()) last = now
            recognizer.reset(stream)
          }
          val text = sentence(listOf(last, if (ended) "" else now).filter { it.isNotEmpty() }.joinToString("\n"))
          if (text != shown) { shown = text; due.add(Line(c.end - LEAD_MS * c.rate / 1000, text, c.generation)) }
        }
        stream.release()
        recognizer.release()
      }.apply { name = "zylo-captions"; isDaemon = true }.start()
      return this
    }

    fun stop() { stopped = true; queue.clear(); due.clear(); main.removeCallbacks(show) }

    /** The model writes in capitals without punctuation: shown in sentence case, the last ~two lines' worth. */
    private fun sentence(s: String) = s.lines().joinToString("\n") { l -> l.lowercase().replaceFirstChar { it.uppercase() }.replace(Regex("\\bi\\b"), "I") }
      .let { if (it.length > 160) "…" + it.takeLast(160).substringAfter(' ') else it }
  }
}
