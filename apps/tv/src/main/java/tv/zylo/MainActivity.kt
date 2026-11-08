package tv.zylo

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Screen {
  data object Main : Screen
  data object Settings : Screen
  data object Search : Screen
  data class Title(val item: Item) : Screen
  data class Sources(val item: Item, val season: Int?, val episode: Int?, val episodeName: String = "", val autoplay: Boolean = false) : Screen
  data class Play(
    val item: Item, val season: Int?, val episode: Int?, val url: String, val headers: Map<String, String>, val subtitle: String, val source: String,
    val subs: List<Sub> = emptyList(), val signature: String = "",
  ) : Screen
  data object Profiles : Screen
}

enum class Section(val label: String) { Home("Home"), Movies("Movies"), Series("Series"), Anime("Anime"), Discover("Discover"), MyList("My List") }

/** State that outlives a screen, so rows, scroll and focus are where you left them when you come back. */
class UiState(store: Store) {
  var section by mutableStateOf(Section.Home)
  var stayInNav by mutableStateOf(false) // just switched section from the top bar: leave focus there
  val home = RowsState({ homeRows(store) }, ::latestReleases)
  val discover = RowsState({ DISCOVER_ROWS })
  val browse = mapOf(Section.Movies to BrowseState("movie"), Section.Series to BrowseState("tv"), Section.Anime to BrowseState("tv", anime = true))
  val myList = FocusMemory()
  val search = FocusMemory()
  val titles = HashMap<String, FocusMemory>()
  val content = FocusRequester() // the current page, so Down from the top bar always lands back in it
  val profile = store.profile
  var listTab by mutableStateOf("mine")
}

class Nav(private val stack: SnapshotStateList<Screen>, private val store: Store, val ui: UiState) {
  fun push(s: Screen) {
    ui.stayInNav = false
    stack.add(s)
  }

  fun replace(s: Screen) {
    stack[stack.lastIndex] = s
  }

  fun back() {
    if (stack.size > 1) stack.removeAt(stack.lastIndex)
  }

  /** Play from the right place: where you stopped, or the next episode after a finished one. */
  fun play(item: Item) {
    val w = store.latest(item)
    if (item.type == "movie") return push(Screen.Sources(item, null, null, autoplay = true))
    val episode = w?.episode?.let { if (w.finished) it + 1 else it } ?: 1
    push(Screen.Sources(item, w?.season ?: 1, episode, autoplay = true))
  }
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val store = Store(this)
    val ui = UiState(store)
    coil.Coil.setImageLoader(coil.ImageLoader.Builder(this).crossfade(220).build()) // artwork fades in rather than popping
    Trakt.io.launch { runCatching { Trakt.sync(store) } } // history and paused-elsewhere titles, at most every 15 minutes
    setContent {
      ZTheme(store.reduceMotion) {
        // Several people on this TV: ask who's watching when the app opens (not when it rebuilds after a switch).
        val stack = remember { mutableStateListOf<Screen>(Screen.Main).apply { if (savedInstanceState == null && store.profiles.size > 1) add(Screen.Profiles) } }
        val nav = remember { Nav(stack, store, ui) }
        val saved = rememberSaveableStateHolder()
        BackHandler(stack.size > 1) {
          saved.removeState("${stack.lastIndex}${stack.last()}")
          stack.removeAt(stack.lastIndex)
        }
        val s = stack.last()
        saved.SaveableStateProvider("${stack.lastIndex}$s") {
          when (s) {
            Screen.Main -> if (store.tmdb.isEmpty()) Welcome(nav) else Main(store, nav, ui)
            Screen.Settings -> SettingsScreen(store)
            Screen.Search -> SearchPage(store, nav, ui.search)
            is Screen.Title -> TitlePage(store, s.item, nav)
            is Screen.Sources -> SourcesPage(store, s, nav)
            is Screen.Play -> PlayerPage(store, s, nav)
            Screen.Profiles -> ProfilesPage(store, nav)
          }
        }
      }
    }
  }
}

@Composable
fun Main(store: Store, nav: Nav, ui: UiState) {
  BackHandler(ui.section != Section.Home) { ui.stayInNav = true; ui.section = Section.Home }
  val restore = !ui.stayInNav
  Box(Modifier.fillMaxSize()) {
    // focusRestorer: coming back down from the top bar returns to the card you left, even if it scrolled away.
    Box(Modifier.fillMaxSize().focusRequester(ui.content).focusRestorer().focusGroup()) { key(ui.section) {
      when (val s = ui.section) {
        Section.Home -> RowsPage(store, ui.home, nav, restore, hero = true)
        Section.Discover -> RowsPage(store, ui.discover, nav, restore, title = "Discover")
        Section.MyList -> MyListPage(store, nav, ui.myList, restore)
        else -> BrowsePage(store, ui.browse.getValue(s), nav, s.label, restore)
      }
    } }
    TopNav(ui, nav)
  }
}

// --- Top navigation: logo, sections, search, settings. No sidebar outside Settings. ---------------

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopNav(ui: UiState, nav: Nav) {
  val active = remember { FocusRequester() }
  fun go(s: Section) {
    ui.stayInNav = true
    ui.section = s
  }
  val down = Modifier.focusProperties { this.down = ui.content }
  Row(
    Modifier.fillMaxWidth()
      // A soft scroll-edge fade, not a bar: artwork stays visible behind the navigation.
      .background(Brush.verticalGradient(listOf(Z.Bg.copy(alpha = 0.8f), Z.Bg.copy(alpha = 0.35f), Color.Transparent)))
      .padding(start = Z.Pad - 10.dp, end = Z.Pad - 10.dp, top = 14.dp, bottom = 22.dp)
      .focusProperties { @Suppress("DEPRECATION") enter = { active } } // coming up from content lands on the current section
      .focusGroup(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    NavSurface({ go(Section.Home) }, down) { Box(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) { Logo(20.dp, color = LocalContentColor.current) } }
    Spacer(Modifier.width(20.dp))
    Section.entries.forEach { s ->
      val selected = ui.section == s
      NavSurface({ go(s) }, down.then(if (selected) Modifier.focusRequester(active) else Modifier), selected) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(s.label, style = MaterialTheme.typography.labelLarge)
          Box(Modifier.padding(top = 3.dp).width(16.dp).height(2.dp).background(if (selected) Z.Red else Color.Transparent))
        }
      }
    }
    Spacer(Modifier.weight(1f))
    NavIcon(Icons.Filled.Search, down) { nav.push(Screen.Search) }
    NavIcon(Icons.Filled.Settings, down) { nav.push(Screen.Settings) }
    NavSurface({ nav.push(Screen.Profiles) }, down) { Box(Modifier.padding(5.dp)) { Avatar(ui.profile, 28.dp) } }
  }
}

@Composable
private fun NavSurface(onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, content: @Composable BoxScope.() -> Unit) = Surface(
  onClick, modifier.padding(horizontal = 2.dp),
  shape = ClickableSurfaceDefaults.shape(Z.Small),
  colors = ClickableSurfaceDefaults.colors(
    containerColor = Color.Transparent, contentColor = if (selected) Z.Text else Z.Text2, focusedContainerColor = Z.Text, focusedContentColor = Z.Bg,
  ),
  scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
  content = content,
)

@Composable
private fun NavIcon(icon: ImageVector, modifier: Modifier, onClick: () -> Unit) = NavSurface(onClick, modifier) { Box(Modifier.padding(9.dp)) { Glyph(icon, 20.dp) } }

@Composable
fun Welcome(nav: Nav) {
  val r = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(80); runCatching { r.requestFocus() } }
  Column(Modifier.fillMaxSize().padding(horizontal = Z.Pad * 2), verticalArrangement = Arrangement.Center) {
    Logo(44.dp)
    Text("One interface. Your entire media world.", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleLarge, color = Z.Text2)
    Text(
      "To get started, add your TMDB key. It's free: sign up at themoviedb.org, then go to Settings > API. " +
        "The 32-character API key is the easiest to type; the Google TV app on your phone works as a keyboard.",
      Modifier.padding(top = 24.dp).fillMaxWidth(0.6f), style = MaterialTheme.typography.bodyLarge, color = Z.Text2,
    )
    ZButton("Open Settings", { nav.push(Screen.Settings) }, Modifier.padding(top = 28.dp).focusRequester(r), primary = true)
  }
}

@Composable
fun Loading(error: String?) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    ZMark(Modifier.height(40.dp).alpha(0.5f))
    if (error != null) Text(error, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
  }
}

@Composable
fun rememberForeground(): Boolean {
  val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
  return state.isAtLeast(Lifecycle.State.RESUMED)
}

/**
 * YouTube trailer through the official IFrame Player API, in a WebView that never takes focus. Muted unless
 * Settings > Playback > Trailer sound is on. Fades in once it is really playing (onPlaying lets the card hide its
 * text); gone when it ends, after 10 s without starting, or when the app is in the background.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Trailer(
  youtubeKey: String, modifier: Modifier, sound: Boolean,
  cover: Boolean = false, // crop to fill a box wider than 16:9 (the Home hero) instead of fitting it exactly
  onPlaying: (Boolean) -> Unit = {}, onEnded: () -> Unit = {}, onFailed: () -> Unit = {},
) {
  var playing by remember { mutableStateOf(false) }
  var ended by remember { mutableStateOf(false) }
  LaunchedEffect(playing) { onPlaying(playing) }
  LaunchedEffect(ended) { if (ended) onEnded() }
  DisposableEffect(Unit) { onDispose { onPlaying(false) } }
  var gaveUp by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) {
    delay(10_000)
    if (!playing) { gaveUp = true; onFailed() }
  }
  val alpha by animateFloatAsState(if (playing) 1f else 0f, tween(ms(250)), label = "trailer")
  if (!rememberForeground() || gaveUp) return
  AndroidView(
    modifier = modifier.alpha(alpha),
    factory = { ctx ->
      WebView(ctx).apply {
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.javaScriptEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = WebChromeClient()
        addJavascriptInterface(object {
          @JavascriptInterface fun state(s: Int) = post { if (s == 1) playing = true else if (s == 0) { playing = false; ended = true } }
        }, "Zylo")
        // An https base URL gives YouTube the referrer it requires for embeds.
        loadDataWithBaseURL("https://zylo.tv/", trailerHtml(youtubeKey, sound, cover), "text/html", "utf-8", null)
      }
    },
    onRelease = { it.destroy() },
  )
}

// The player fills the WebView exactly (the card is 16:9, like the video), so nothing is letterboxed or cut off.
private fun trailerHtml(key: String, sound: Boolean, cover: Boolean) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}${
  if (cover) "#p,iframe{position:fixed;left:0;top:50%;width:100%!important;height:56.25vw!important;transform:translateY(-50%);border:0}"
  else "#p,iframe{position:fixed;top:0;left:0;width:100%!important;height:100%!important;border:0}"
}</style>
</head><body><div id="p"></div>
<script>
function onYouTubeIframeAPIReady(){new YT.Player('p',{width:'100%',height:'100%',videoId:'$key',playerVars:{autoplay:1,mute:${if (sound) 0 else 1},controls:0,rel:0,playsinline:1,iv_load_policy:3,disablekb:1,fs:0,modestbranding:1,origin:'https://zylo.tv'},
events:{onReady:function(e){${if (sound) "e.target.unMute();e.target.setVolume(100);" else "e.target.mute();"}e.target.playVideo()},onStateChange:function(e){Zylo.state(e.data)}}})}
</script><script src="https://www.youtube.com/iframe_api"></script></body></html>
"""
