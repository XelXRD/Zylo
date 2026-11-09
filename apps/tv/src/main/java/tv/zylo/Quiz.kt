// "Pick for Me": a few quick questions (mood, era, new or rewatch, length or status, how picky), then a pick with its
// trailer playing in a pop-out. Left/Right re-rolls through the shortlist; OK opens it. A little slot-machine on each roll.
package tv.zylo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.random.Random

/** One answer: what it shows, and what it adds to the TMDB discover search (or a filter applied after). */
data class Answer(val label: String, val hint: String, val params: String = "", val seen: Boolean? = null)
data class Question(val ask: String, val answers: List<Answer>)

fun quiz(type: String): List<Question> {
  val year = Calendar.getInstance().get(Calendar.YEAR)
  val date = if (type == "movie") "primary_release_date" else "first_air_date"
  val tv = type != "movie"
  // Anime: TMDB's animation genre (16) AND the mood's genre (a comma means both), Japanese titles only (quizPath).
  val mood = if (type == "anime") Question("What are you in the mood for?", listOf(
    Answer("Big battles", "Action, adventure", "&with_genres=16,10759"),
    Answer("A laugh", "Comedy", "&with_genres=16,35"),
    Answer("Another world", "Fantasy, isekai, sci-fi", "&with_genres=16,10765"),
    Answer("All the feels", "Drama, romance", "&with_genres=16,18"),
    Answer("A mystery", "Mystery, thriller", "&with_genres=16,9648"),
    Answer("Something dark", "Crime, horror", "&with_genres=16,80"),
    Answer("For everyone", "Family, kids", "&with_genres=16,10751"),
    Answer("Surprise me", "Any anime"),
  )) else null
  return (listOfNotNull(mood) + listOf(
    Question("What are you in the mood for?", listOf(
      Answer("A laugh", "Comedy", "&with_genres=35"),
      Answer("Edge of my seat", if (tv) "Action, crime" else "Action, thriller", if (tv) "&with_genres=10759|80" else "&with_genres=28|53"),
      Answer("A good scare", if (tv) "Mystery, dark" else "Horror", if (tv) "&with_genres=9648" else "&with_genres=27"),
      Answer("All the feels", "Drama, romance", if (tv) "&with_genres=18" else "&with_genres=18|10749"),
      Answer("Another world", "Sci-fi, fantasy", if (tv) "&with_genres=10765" else "&with_genres=878|14"),
      Answer("A whodunnit", "Crime, mystery", "&with_genres=80|9648"),
      Answer("Something true", "Documentary", "&with_genres=99"),
      Answer("Surprise me", "Anything"),
    )),
    Question("Pick an era", listOf(
      Answer("Brand new", "${year - 1}–$year", "&$date.gte=${year - 1}-01-01"),
      Answer("The 2010s", "2010–2019", "&$date.gte=2010-01-01&$date.lte=2019-12-31"),
      Answer("The 2000s", "2000–2009", "&$date.gte=2000-01-01&$date.lte=2009-12-31"),
      Answer("The 90s", "1990–1999", "&$date.gte=1990-01-01&$date.lte=1999-12-31"),
      Answer("Classics", "Before 1990", "&$date.lte=1989-12-31"),
      Answer("Any era", "Doesn't matter"),
    )),
    Question("Something new, or an old favourite?", listOf(
      Answer("New to me", "Haven't seen it", seen = false),
      Answer("A rewatch", "Something I've seen", seen = true),
      Answer("Either", "Don't mind"),
    )),
    if (!tv) Question("How long have you got?", listOf(
      Answer("Quick one", "Under 100 minutes", "&with_runtime.lte=100"),
      Answer("A proper film", "Around two hours", "&with_runtime.gte=95&with_runtime.lte=135"),
      Answer("Settle in", "Epic, 2½ hours+", "&with_runtime.gte=140"),
      Answer("Doesn't matter", "Any length"),
    )) else Question("Finished, or still going?", listOf(
      Answer("Finished", "Ended, no cliffhanger wait", "&with_status=3"),
      Answer("Still going", "New seasons coming", "&with_status=0"),
      Answer("Doesn't matter", "Either"),
    )),
    Question("How picky are we?", listOf(
      Answer("Crowd favourites", "Loved by thousands", "&vote_average.gte=7.4&vote_count.gte=800"),
      Answer("Hidden gems", "Great, less known", "&vote_average.gte=7&vote_count.gte=80&vote_count.lte=900"),
      Answer("Anything goes", "Just pick"),
    )),
  )).let { qs -> if (mood != null) qs.filterIndexed { i, _ -> i != 1 } else qs } // anime: its own moods instead
}

/** The discover search for a set of answers. */
fun quizPath(type: String, answers: List<Answer>, page: Int = 1) =
  "/discover/${media(type)}?sort_by=popularity.desc&include_adult=false&page=$page" + answers.joinToString("") { it.params } +
    (if (answers.none { "vote_count" in it.params }) "&vote_count.gte=50" else "") +
    (if (type == "anime") "&with_original_language=ja" + (if (answers.none { "with_genres" in it.params }) "&with_genres=16" else "") else "")

/** What TMDB calls it: anime is series. */
fun media(type: String) = if (type == "anime") "tv" else type

/** Whether this title's been watched, here or on Trakt (for a series: any episode). */
fun seenBefore(store: Store, item: Item) =
  if (item.type == "movie") store.watched("movie:${item.id}") else ("tv:${item.id}:").let { p -> store.traktWatched().any { it.startsWith(p) } || store.continueWatching().any { it.item.key == item.key } }

/** The shortlist: a random spread of pages of matches, shuffled, filtered by new/rewatch. */
fun quizDeck(store: Store, t: Tmdb, type: String, answers: List<Answer>, rnd: Random = Random): List<Item> {
  val first = t.get(quizPath(type, answers))
  val pages = first.optInt("total_pages", 1).coerceIn(1, 15)
  val more = (2..pages).shuffled(rnd).take(2).flatMap { p -> runCatching { t.list(quizPath(type, answers, p), media(type)) }.getOrDefault(emptyList()) }
  val all = (t.safe(first.optJSONArray("results")?.objects()?.mapNotNull { Tmdb.item(it, media(type)) }.orEmpty()) + more).distinctBy { it.key }.filter { it.backdrop != null }
  val seen = answers.firstNotNullOfOrNull { it.seen }
  return all.filter { seen == null || seenBefore(store, it) == seen }.shuffled(rnd)
}

@Composable
fun QuizPage(store: Store, type: String, nav: Nav) {
  val questions = remember { quiz(type) }
  var step by rememberSaveable { mutableIntStateOf(0) }
  val picks = remember { mutableStateListOf<Int>() }
  // Back on a question: the one before. Back on your pick: out of the quiz, to Movies / Series / Anime.
  BackHandler(step in 1 until questions.size) { step--; picks.removeAt(picks.lastIndex) }
  Box(Modifier.fillMaxSize().background(Z.Bg)) {
    // One answer per question: a second press while the next question slides in is ignored (it used to run past the end).
    if (step < questions.size) QuestionStep(type, questions, step) { asked, i -> if (asked == step && picks.size == step) { picks += i; step++ } }
    else Result(store, type, picks.take(questions.size).mapIndexed { q, i -> questions[q].answers[i] }, nav, again = { picks.clear(); step = 0 })
  }
}

@Composable
private fun QuestionStep(type: String, questions: List<Question>, step: Int, answer: (asked: Int, Int) -> Unit) {
  val first = remember(step) { FocusRequester() }
  LaunchedEffect(step) { delay(60); runCatching { first.requestFocus() } }
  val slide = ms(260)
  val fade = ms(200)
  val out = ms(120)
  Column(Modifier.fillMaxSize().padding(horizontal = Z.Pad, vertical = 44.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(when (type) { "movie" -> "PICK A FILM FOR ME"; "anime" -> "PICK AN ANIME FOR ME"; else -> "PICK A SERIES FOR ME" }, style = MaterialTheme.typography.labelLarge, color = Z.Red)
      Spacer(Modifier.weight(1f))
      // Progress: one pip per question.
      questions.indices.forEach { i ->
        Box(Modifier.padding(start = 6.dp).size(if (i == step) 26.dp else 10.dp, 6.dp).clip(Z.Small).background(if (i <= step) Z.Red else Z.Border))
      }
    }
    AnimatedContent(step, transitionSpec = {
      (slideInHorizontally(tween(slide, easing = Z.EaseOut)) { it / 6 } + fadeIn(tween(fade))) togetherWith fadeOut(tween(out))
    }, label = "question") { shown ->
      val q = questions[shown] // the question this copy shows (the old one is still on screen while it slides out)
      Column {
        Text(q.ask, Modifier.padding(top = 56.dp), style = MaterialTheme.typography.displaySmall)
        Text("Question ${shown + 1} of ${questions.size}" + if (shown > 0) "  ·  Back to change your last answer" else "", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2)
        // Answers as big tiles, four to a row.
        Column(Modifier.padding(top = 36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          q.answers.chunked(4).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
              row.forEachIndexed { c, a ->
                val i = r * 4 + c
                ZCard({ answer(shown, i) }, (if (i == 0 && shown == step) Modifier.focusRequester(first) else Modifier).width(200.dp).height(104.dp)) {
                  Column(Modifier.fillMaxSize().background(Z.Card).padding(16.dp), verticalArrangement = Arrangement.Bottom) {
                    Text(a.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(a.hint, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = Z.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

/** The pick: backdrop, logo, why it matched, and the trailer in a pop-out. Left/Right re-rolls. */
@Composable
private fun Result(store: Store, type: String, answers: List<Answer>, nav: Nav, again: () -> Unit) {
  val t = remember { Tmdb(store.tmdb) }
  var deck by remember { mutableStateOf<List<Item>?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var index by rememberSaveable { mutableIntStateOf(0) }
  var rolls by rememberSaveable { mutableIntStateOf(0) }
  var spinning by remember { mutableStateOf(true) }
  LaunchedEffect(Unit) {
    runCatching { withContext(Dispatchers.IO) { quizDeck(store, t, type, answers) } }.onSuccess { deck = it }.onFailure { error = plainError(it, "TMDB") }
  }
  val d = deck
  if (d == null || d.isEmpty()) {
    Column(Modifier.fillMaxSize().padding(horizontal = Z.Pad, vertical = 60.dp)) {
      Text(when { error != null -> error!!; d == null -> "Finding something…"; else -> "Nothing matched all of that." }, style = MaterialTheme.typography.headlineSmall)
      if (d != null || error != null) {
        val f = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(60); runCatching { f.requestFocus() } }
        Text("Try a different era or mood.", Modifier.padding(top = 8.dp), color = Z.Text2)
        ZButton("Start Over", again, Modifier.padding(top = 24.dp).focusRequester(f), primary = true)
      }
    }
    return
  }
  val item = d[index % d.size]
  // A short slot-machine spin through other posters before each pick lands.
  var reel by remember { mutableStateOf(item) }
  val still = LocalReduceMotion.current
  LaunchedEffect(index) {
    spinning = true
    if (!still) repeat(7) { n -> reel = d[(index + n * 3 + 1) % d.size]; delay(55L + n * 18L) }
    reel = item
    spinning = false
  }
  fun roll(step: Int) { if (!spinning) { index = (index + step + d.size) % d.size; rolls++ } }
  val details by produceState<org.json.JSONObject?>(null, item) { value = withContext(Dispatchers.IO) { runCatching { t.details(item.type, item.id) }.getOrNull() } }
  val trailer = Tmdb.trailer(details)
  val look = rememberLook(store, item)
  val watch = remember { FocusRequester() }
  LaunchedEffect(Unit) { delay(80); runCatching { watch.requestFocus() } }

  Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (e.key) { Key.DirectionLeft -> { roll(-1); true }; Key.DirectionRight -> { roll(1); true }; else -> false }
  }) {
    AsyncImage(img(reel.backdrop, "w1280"), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Z.Bg, 0.5f to Z.Bg.copy(alpha = 0.85f), 1f to Z.Bg.copy(alpha = 0.25f))))
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Z.Bg)))
    Column(Modifier.padding(start = Z.Pad, top = 44.dp).width(390.dp)) {
      Text(if (spinning) "ROLLING…" else "YOUR PICK" + if (rolls > 0) "  ·  ROLL ${rolls + 1}" else "", style = MaterialTheme.typography.labelLarge, color = Z.Red)
      Box(Modifier.padding(top = 18.dp).height(110.dp), contentAlignment = Alignment.BottomStart) {
        if (spinning) Text(reel.title, style = MaterialTheme.typography.displaySmall, color = Z.Text2, maxLines = 2)
        else TitleLogo(look, item.title, MaterialTheme.typography.displaySmall, 380.dp, 110.dp)
      }
      if (!spinning) {
        MetaLine(listOfNotNull(item.year.ifEmpty { null }, item.rating.takeIf { it > 0 }?.let { "★ %.1f".format(it) }, Tmdb.certification(details ?: org.json.JSONObject()),
          if (seenBefore(store, item)) "You've seen this" else null), Modifier.padding(top = 12.dp))
        Text(item.overview, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = Z.Text2, maxLines = 4, overflow = TextOverflow.Ellipsis)
        // Why it matched: the answers given (skipping the "any" ones).
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          answers.filter { it.params.isNotEmpty() || it.seen != null }.take(3).forEach { a ->
            Text(a.label, Modifier.background(Z.Card.copy(alpha = 0.85f), Z.Small).padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = Z.Text2)
          }
        }
        Column(Modifier.padding(top = 22.dp).width(240.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          ZButton(if (item.type == "movie") "Watch This" else "Watch This Series", { nav.push(Screen.Title(item)) }, Modifier.fillMaxWidth().focusRequester(watch), primary = true)
          ZButton("Play Now", { nav.play(item) }, Modifier.fillMaxWidth())
          ZButton("Start Over", again, Modifier.fillMaxWidth())
        }
      }
    }
    // The trailer, in a pop-out window.
    if (!spinning) Column(Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = Z.Pad).width(450.dp)) {
      Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Z.Large).background(Color.Black), contentAlignment = Alignment.Center) {
        if (trailer != null) key(item.key) { Trailer(trailer, Modifier.fillMaxSize(), sound = store.trailerSound) }
        else Text(if (details == null) "Loading trailer…" else "No trailer for this one.", color = Z.Muted)
      }
      Text("TRAILER", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = Z.Muted)
    }
    Text("◀  ▶   Re-roll  ·  ${d.size} matches", Modifier.align(Alignment.BottomEnd).padding(end = Z.Pad, bottom = 36.dp), style = MaterialTheme.typography.labelLarge, color = Z.Text2)
  }
}
