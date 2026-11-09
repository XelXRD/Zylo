// Text entry and short notices, drawn by Zylo instead of Android's stock dialog and toast.
// ask() and toast() keep their old signatures, so every caller (keys, PINs, links, names) gets the new look at once.
package tv.zylo

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.doAfterTextChanged
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * One text question. [hint] sits under the title (what's expected, where to find it). [check] returns a problem to show
 * under the field, or null when the answer can be saved. [phone] offers sending the answer from a phone instead, with
 * that line as the phone page's instruction.
 */
class AskRequest(
  val title: String, val current: String, val secret: Boolean, val pin: Boolean, val hint: String,
  val phone: String?, val check: (String) -> String?, val done: (String) -> Unit,
)

private object Asks {
  var current by mutableStateOf<AskRequest?>(null)
  var notice by mutableStateOf<Pair<Long, String>?>(null)
}

private val main = Handler(Looper.getMainLooper())

/** Ask for some text. The TV keyboard (and the Google TV phone app's keyboard) works best with a plain EditText, so one sits inside. */
@Suppress("UNUSED_PARAMETER")
fun ask(
  ctx: Context, title: String, current: String, secret: Boolean = false, pin: Boolean = false,
  hint: String = "", phone: String? = null, check: (String) -> String? = { null }, done: (String) -> Unit,
) = main.post { Asks.current = AskRequest(title, current, secret, pin, hint, phone, check, done) }

/** A short message near the foot of the screen, in Zylo's own panel. */
@Suppress("UNUSED_PARAMETER")
fun toast(ctx: Context, text: String) = main.post { Asks.notice = System.nanoTime() to text }

/**
 * What went wrong, in words a person can act on. Low-level network and parsing failures get a plain message with the next
 * step; Zylo's own messages (already written for people) pass through unchanged.
 */
fun plainError(e: Throwable?, service: String): String {
  val m = e?.message.orEmpty()
  return when {
    e is javax.net.ssl.SSLException || e is java.security.cert.CertificateException || m.contains("Chain validation", true) || m.contains("trust anchor", true) ->
      "Couldn't make a secure connection to $service. If the TV's date and time are wrong, correct them and try again."
    e is java.net.UnknownHostException -> "Couldn't reach $service. Check the TV's internet connection."
    e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException -> "$service took too long to answer. Try again in a moment."
    e is java.net.ConnectException -> "Couldn't connect to $service. It may be down, or the address may be wrong."
    e is HttpError && e.code == 401 -> "$service didn't accept that key. Check you copied all of it."
    e is org.json.JSONException -> "$service sent back something Zylo couldn't read. Try again later."
    m.isBlank() -> "Something went wrong with $service. Try again."
    else -> m
  }
}

/** Place once, over everything: shows whichever question or notice is current. */
@Composable
fun AskHost() {
  Asks.current?.let { r -> key(r) { AskDialog(r) { Asks.current = null } } }
  NoticeBar()
}

@Composable
// usePlatformDefaultWidth off: the platform caps dialogs narrower than this one needs on a TV.
private fun AskDialog(r: AskRequest, close: () -> Unit) = Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
  var text by remember { mutableStateOf(r.current) }
  var problem by remember { mutableStateOf<String?>(null) }
  var shown by remember { mutableStateOf(false) } // a secret, revealed on request
  var drop by remember { mutableStateOf<PhoneDrop?>(null) }
  var field by remember { mutableStateOf<EditText?>(null) }
  DisposableEffect(Unit) { onDispose { drop?.stop() } }
  val save = remember { FocusRequester() }
  fun submit() {
    val answer = text.trim()
    problem = r.check(answer)
    if (problem != null) return
    close()
    r.done(answer)
  }
  Column(Modifier.width(700.dp).background(Z.Surface, Z.Large).padding(28.dp)) {
    Text(r.title, style = MaterialTheme.typography.headlineSmall, color = Z.Text)
    if (r.hint.isNotEmpty()) Text(r.hint, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
    AndroidView(
      modifier = Modifier.padding(top = 18.dp).fillMaxWidth().height(52.dp),
      factory = { ctx ->
        EditText(ctx).apply {
          val density = resources.displayMetrics.density
          fun box(focused: Boolean) = GradientDrawable().apply {
            cornerRadius = 10 * density
            setColor(Z.Card.toArgb())
            setStroke((2 * density).toInt(), (if (problem != null) Z.Error else if (focused) Z.Text else Z.Border).toArgb())
          }
          setSingleLine()
          typeface = ResourcesCompat.getFont(ctx, R.font.inter_regular)
          textSize = 17f
          setTextColor(Z.Text.toArgb())
          setHintTextColor(Z.Muted.toArgb())
          highlightColor = Z.Red.copy(alpha = 0.45f).toArgb()
          background = box(true)
          setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
          setOnFocusChangeListener { _, f -> background = box(f) }
          inputType = when {
            r.pin -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            r.secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
          }
          if (r.pin) filters = arrayOf(InputFilter.LengthFilter(4))
          imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
          setText(r.current)
          setSelection(r.current.length)
          doAfterTextChanged { text = it?.toString().orEmpty(); if (problem != null) { problem = null; background = box(true) } }
          // The keyboard's tick saves; Down goes to Save (not Cancel, the nearest button to a full-width field).
          setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }
          setOnKeyListener { _, code, e ->
            if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) { runCatching { save.requestFocus() }; true } else false
          }
          field = this
          post { requestFocus() }
        }
      },
    )
    problem?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = Z.Error) }
    Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      ZButton("Save", ::submit, Modifier.focusRequester(save), primary = true)
      ZButton("Cancel", close)
      Spacer(Modifier.weight(1f))
      if (r.secret) ZButton(if (shown) "Hide" else "Show", {
        shown = !shown
        field?.let { f ->
          f.inputType = InputType.TYPE_CLASS_TEXT or if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
          f.typeface = ResourcesCompat.getFont(f.context, R.font.inter_regular)
          f.setSelection(f.text.length)
        }
      })
      if (r.phone != null) ZButton(if (drop == null) "Send from Phone" else "Hide Code", {
        drop = drop?.let { it.stop(); null } ?: runCatching {
          PhoneDrop(r.phone) { sent -> main.post { field?.setText(sent.trim()); field?.setSelection(field?.text?.length ?: 0) }; "Sent. Check the TV, then press Save." }
        }.getOrNull()
      }, icon = Icons.Filled.Phone)
    }
    drop?.let { p ->
      Row(Modifier.padding(top = 18.dp).fillMaxWidth().background(Z.Card, Z.Medium).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Qr(p.url, 120.dp)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
          Text("Scan with your phone", style = MaterialTheme.typography.titleMedium, color = Z.Text)
          Text("Your phone needs to be on the same Wi-Fi as the TV. Paste on the page that opens and it lands in the box above.",
            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text2)
        }
      }
    }
  }
  LaunchedEffect(Unit) { delay(60); field?.requestFocus() }
}

@Composable
private fun BoxScope.NoticeContent(text: String) = Row(
  Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp).widthIn(max = 640.dp).background(Z.Elevated, Z.Large).padding(horizontal = 20.dp, vertical = 14.dp),
  verticalAlignment = Alignment.CenterVertically,
) {
  Box(Modifier.size(8.dp).background(Z.Info, CircleShape))
  Spacer(Modifier.width(12.dp))
  Text(text, style = MaterialTheme.typography.bodyMedium, color = Z.Text)
}

@Composable
private fun NoticeBar() {
  val n = Asks.notice
  var last by remember { mutableStateOf("") }
  if (n != null) last = n.second
  LaunchedEffect(n) { if (n != null) { delay(5_000); if (Asks.notice == n) Asks.notice = null } }
  Box(Modifier.fillMaxSize()) {
    AnimatedVisibility(n != null, Modifier.align(Alignment.BottomCenter),
      enter = fadeIn(tween(ms(Z.Normal), easing = Z.EaseOut)) + slideInVertically(tween(ms(Z.Normal), easing = Z.EaseOut)) { it / 3 },
      exit = fadeOut(tween(ms(Z.Fast))) + slideOutVertically(tween(ms(Z.Fast))) { it / 3 }) {
      Box(Modifier.fillMaxWidth()) { NoticeContent(last) }
    }
  }
}
