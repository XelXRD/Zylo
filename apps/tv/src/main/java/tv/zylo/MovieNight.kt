// Movie Night Match: everyone scans the code on the TV and swipes yes / no on their phone through the same pile of
// films (or series). The first one everybody said yes to is the match. Runs over the home Wi-Fi, like PhoneDrop.
package tv.zylo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** The votes. Pure, so it can be tested without a network. */
class MatchVotes(val deck: List<Item>) {
  /** voter id → (item key → yes?) */
  val votes = ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>()
  val names = ConcurrentHashMap<String, String>()
  val passed: MutableSet<String> = ConcurrentHashMap.newKeySet() // matches you chose to skip on the TV

  fun vote(voter: String, key: String, yes: Boolean) { votes.getOrPut(voter) { ConcurrentHashMap() }[key] = yes }

  /** The first title, in pile order, that every voter (two or more) said yes to. */
  fun match(): Item? = if (votes.size < 2) null else deck.firstOrNull { i -> i.key !in passed && votes.values.all { it[i.key] == true } }

  fun progress(voter: String) = votes[voter]?.size ?: 0
}

class MovieNightServer(val votes: MatchVotes) {
  private val token = UUID.randomUUID().toString().take(8)
  private val server = ServerSocket(0)
  val url = "http://${PhoneDrop.lanAddress()}:${server.localPort}/?k=$token"

  init {
    thread(isDaemon = true) {
      while (!server.isClosed) runCatching { server.accept() }.getOrNull()?.let { s -> thread(isDaemon = true) { s.use { serve(it) } } }
    }
  }

  private fun serve(s: java.net.Socket) = runCatching {
    val input = s.getInputStream().bufferedReader()
    val target = input.readLine().split(" ").getOrElse(1) { "/" }
    generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
    val path = target.substringBefore('?')
    val q = target.substringAfter('?', "").split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to URLDecoder.decode(it[1], "UTF-8") } }.toMap()
    val (status, type, reply) = when {
      q["k"] != token -> Triple("403 Forbidden", "text/plain", "Scan the code on the TV again.")
      path == "/deck" -> Triple("200 OK", "application/json", JSONArray(votes.deck.map {
        JSONObject().put("key", it.key).put("title", it.title).put("year", it.year).put("poster", img(it.poster, "w342") ?: "").put("overview", it.overview.take(220))
      }).toString())
      path == "/vote" -> {
        val v = q["v"].orEmpty().take(40)
        if (v.isNotEmpty()) { q["n"]?.takeIf { it.isNotBlank() }?.let { votes.names[v] = it.take(20) }; q["i"]?.let { votes.vote(v, it, q["y"] == "1") } }
        Triple("200 OK", "text/plain", "ok")
      }
      path == "/state" -> Triple("200 OK", "application/json", JSONObject().put("match", votes.match()?.title ?: JSONObject.NULL).toString())
      else -> Triple("200 OK", "text/html", PAGE)
    }
    val bytes = reply.toByteArray()
    s.getOutputStream().write("HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
  }

  fun stop() = runCatching { server.close() }

  private companion object {
    val PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Zylo Movie Night</title>
<style>body{background:#101114;color:#F5F5F5;font:16px system-ui,sans-serif;margin:0;padding:18px;text-align:center}h1{font-size:20px;margin:0 0 10px}
img{width:72%;max-width:300px;border-radius:14px;box-shadow:0 10px 30px #0008}h2{margin:12px 0 4px;font-size:20px}p{color:#A4A5AB;font-size:14px;margin:0 0 14px}
.b{display:flex;gap:14px;justify-content:center}button{flex:1;max-width:160px;padding:16px;border:0;border-radius:40px;font-size:22px;font-weight:700;color:#fff}
#no{background:#34353D}#yes{background:#BD1422}input{background:#191A1E;color:#fff;border:1px solid #34353D;border-radius:10px;padding:12px;font-size:16px;width:70%}
#m{display:none;background:#BD1422;padding:18px;border-radius:14px;font-size:20px;font-weight:700;margin-top:14px}</style></head>
<body><h1><span style="color:#BD1422">Z</span> Movie Night Match</h1>
<div id="who"><p>Your name, so the TV knows who's voting</p><input id="n" placeholder="Name"><div class="b" style="margin-top:14px"><button id="yes" onclick="go()">Start</button></div></div>
<div id="card" style="display:none"><img id="img"><h2 id="t"></h2><p id="o"></p><div class="b"><button id="no" onclick="vote(0)">✕ Nope</button><button id="yes" onclick="vote(1)">♥ Yes</button></div><p id="c" style="margin-top:12px"></p></div>
<div id="m"></div>
<script>const k=location.search;let deck=[],i=0;let v=localStorage.getItem('zv');if(!v){v=Math.random().toString(36).slice(2);localStorage.setItem('zv',v)}
const name=()=>document.getElementById('n').value||'Someone';
async function go(){deck=await (await fetch('/deck'+k)).json();document.getElementById('who').style.display='none';document.getElementById('card').style.display='block';
fetch('/vote'+k+'&v='+v+'&n='+encodeURIComponent(name()));show()}
function show(){const c=document.getElementById('card');if(i>=deck.length){c.innerHTML='<h2>That\'s the lot!</h2><p>Keep an eye on the TV.</p>';return}
const d=deck[i];document.getElementById('img').src=d.poster;document.getElementById('t').textContent=d.title+(d.year?' ('+d.year+')':'');
document.getElementById('o').textContent=d.overview;document.getElementById('c').textContent=(i+1)+' of '+deck.length}
async function vote(y){const d=deck[i];i++;show();try{await fetch('/vote'+k+'&v='+v+'&n='+encodeURIComponent(name())+'&i='+encodeURIComponent(d.key)+'&y='+y)}catch(e){}}
setInterval(async()=>{try{const s=await (await fetch('/state'+k)).json();const m=document.getElementById('m');if(s.match){m.style.display='block';m.textContent='🍿 It\'s a match: '+s.match}else m.style.display='none'}catch(e){}},2500)</script></body></html>"""
  }
}

/** The pile: popular, well-liked titles from a few random pages, minus anything you've given a thumbs down. */
fun movieNightDeck(store: Store, type: String): List<Item> {
  val t = Tmdb(store.tmdb)
  val no = Thumbs.disliked(store)
  val pages = (1..8).shuffled().take(2)
  return pages.flatMap { p ->
    runCatching { t.list("/discover/$type?sort_by=popularity.desc&vote_count.gte=${if (type == "movie") 800 else 300}&vote_average.gte=6.5&page=$p", type) }.getOrDefault(emptyList())
  }.filter { it.key !in no }.distinctBy { it.key }.shuffled().take(30)
}

@Composable
fun MovieNightPage(store: Store, nav: Nav) {
  var type by remember { mutableStateOf("movie") }
  var votes by remember { mutableStateOf<MatchVotes?>(null) }
  var server by remember { mutableStateOf<MovieNightServer?>(null) }
  LaunchedEffect(type) {
    server?.stop(); server = null; votes = null
    val deck = withContext(Dispatchers.IO) { movieNightDeck(store, type) }
    val v = MatchVotes(deck)
    votes = v
    server = withContext(Dispatchers.IO) { MovieNightServer(v) }
  }
  DisposableEffect(Unit) { onDispose { server?.stop() } }
  // The TV checks the votes every second.
  var tick by remember { mutableIntStateOf(0) }
  LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }
  val v = votes
  val match = remember(tick, v) { v?.match() }
  val first = remember { FocusRequester() }
  LaunchedEffect(match) { delay(100); runCatching { first.requestFocus() } }
  Row(Modifier.fillMaxSize().padding(horizontal = Z.Pad, vertical = 36.dp)) {
    Column(Modifier.weight(1f)) {
      Text("🍿 Movie Night Match", style = MaterialTheme.typography.displaySmall)
      Text("Everyone scans the code and swipes on their phone. The first one you all say yes to is the match.",
        Modifier.padding(top = 8.dp).fillMaxWidth(0.9f), style = MaterialTheme.typography.bodyLarge, color = Z.Text2)
      Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Chip("Films", type == "movie") { type = "movie" }
        Chip("Series", type == "tv") { type = "tv" }
      }
      Text("Who's in", Modifier.padding(top = 26.dp), style = MaterialTheme.typography.titleMedium)
      if (v == null || v.votes.isEmpty()) Text("Nobody yet. Phones need to be on the same Wi-Fi as the TV.", Modifier.padding(top = 6.dp), color = Z.Text2)
      else v.votes.keys.sorted().forEach { id ->
        val n = remember(tick) { v.progress(id) }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(v.names[id] ?: "Someone", Modifier.width(160.dp), style = MaterialTheme.typography.bodyLarge)
          ProgressBar(n.toFloat() / v.deck.size.coerceAtLeast(1), Modifier.width(220.dp))
          Text("  $n / ${v.deck.size}", style = MaterialTheme.typography.labelLarge, color = Z.Text2)
        }
      }
      if (v != null && v.votes.size == 1) Text("Waiting for at least one more person.", Modifier.padding(top = 10.dp), color = Z.Text2)
      if (match != null) Row(Modifier.padding(top = 26.dp).background(Z.Surface, Z.Large).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(90.dp).aspectRatio(2f / 3f).clip(Z.Small)) { Artwork(img(match.poster, "w185"), match.title) }
        Spacer(Modifier.width(16.dp))
        Column {
          Text("IT'S A MATCH", style = MaterialTheme.typography.labelLarge, color = Z.Red)
          Text(match.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ZButton("Play", { nav.play(match) }, Modifier.focusRequester(first), primary = true)
            ZButton("More Info", { nav.push(Screen.Title(match)) })
            ZButton("Find Another", { v?.passed?.add(match.key); tick++ })
          }
        }
      }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      val url = server?.url
      if (url == null) Text("Shuffling the pile…", color = Z.Text2)
      else {
        Box(Modifier.background(androidx.compose.ui.graphics.Color.White, Z.Medium).padding(10.dp)) { Qr(url, 260.dp) }
        Text(url.substringBefore("?"), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium, color = Z.Muted)
      }
      v?.deck?.take(5)?.let { peek ->
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          peek.forEach { AsyncImage(img(it.poster, "w92"), null, Modifier.width(44.dp).aspectRatio(2f / 3f).clip(Z.Small), contentScale = ContentScale.Crop) }
        }
      }
    }
  }
}
