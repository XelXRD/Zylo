// Sports schedule: fixtures from ESPN's public scoreboard feeds (free, no key; many sports and leagues), and where to
// watch them: channels whose TV guide lists the event at that time (any country whose guide is loaded), the US
// broadcasters ESPN lists, and channels you've linked yourself. There's no free worldwide broadcaster list, so the
// guide search is what makes this work beyond the US.
package tv.zylo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Team(val name: String, val short: String, val logo: String?, val score: String?)
data class SportEvent(
  val id: String, val sport: String, val league: String, val name: String, val start: Long,
  val state: String, val detail: String, val teams: List<Team>, val broadcasters: List<String>, val keywords: List<List<String>>,
  val allDay: Boolean = false, // a tournament running all day (golf, tennis): any listing that day counts
) {
  val live get() = state == "in"
  val over get() = state == "post"
}

/** Where to watch: a label (a channel or broadcaster), what the guide calls the programme, and the channel to play. */
/** [guessed]: the channel was picked by its name looking alike, not linked or matched by the guide. */
data class WatchOption(val label: String, val programme: Programme?, val channel: Channel?, val from: String, val guessed: Boolean = false)

object Sports {
  /** Sport, ESPN path, league name; "kw" is what a TV listing for an event without two teams would mention. */
  class League(val sport: String, val path: String, val name: String, val kw: List<String> = emptyList(), val allDay: Boolean = false)
  val LEAGUES = listOf(
    League("Football", "soccer/eng.1", "Premier League"), League("Football", "soccer/eng.2", "Championship"),
    League("Football", "soccer/eng.fa", "FA Cup"), League("Football", "soccer/eng.league_cup", "Carabao Cup"),
    League("Football", "soccer/sco.1", "Scottish Premiership"), League("Football", "soccer/uefa.champions", "Champions League"),
    League("Football", "soccer/uefa.europa", "Europa League"), League("Football", "soccer/uefa.europa.conf", "Conference League"),
    League("Football", "soccer/esp.1", "LaLiga"), League("Football", "soccer/ger.1", "Bundesliga"), League("Football", "soccer/ita.1", "Serie A"),
    League("Football", "soccer/fra.1", "Ligue 1"), League("Football", "soccer/usa.1", "MLS"), League("Football", "soccer/fifa.friendly", "Internationals"),
    League("American Football", "football/nfl", "NFL"), League("Basketball", "basketball/nba", "NBA"), League("Ice Hockey", "hockey/nhl", "NHL"),
    League("Baseball", "baseball/mlb", "MLB"), League("MMA", "mma/ufc", "UFC", listOf("ufc")),
    League("Motor Racing", "racing/f1", "Formula 1", listOf("formula 1", "f1", "grand prix")),
    League("Golf", "golf/pga", "PGA Tour", listOf("golf"), allDay = true),
    League("Tennis", "tennis/atp", "ATP", listOf("tennis"), allDay = true), League("Tennis", "tennis/wta", "WTA", listOf("tennis"), allDay = true),
  )
  val SPORTS = listOf("All") + LEAGUES.map { it.sport }.distinct()

  private val day = SimpleDateFormat("yyyyMMdd", Locale.US)

  /**
   * Today and the next [days] days, every league, one request per league per day (ESPN answers 400 to a date range for
   * team sports). Requests run 8 at a time; one that fails is skipped, and if they all fail the error is passed on.
   */
  fun schedule(days: Int = 6, fetch: (String) -> String = { http(it) }, now: Long = System.currentTimeMillis()): List<SportEvent> = runBlocking(Dispatchers.IO) {
    val gate = Semaphore(8)
    val asks = LEAGUES.flatMap { l -> (0..days).map { d -> l to day.format(Date(now + d * 86_400_000L)) } }
    val got = asks.map { (l, date) ->
      async { gate.withPermit { runCatching { parse(l, fetch("https://site.api.espn.com/apis/site/v2/sports/${l.path}/scoreboard?dates=$date&limit=200")) } } }
    }.awaitAll()
    if (got.all { it.isFailure }) throw got.first().exceptionOrNull()!!
    got.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.id }.sortedBy { it.start }
  }

  fun parse(l: League, body: String): List<SportEvent> = JSONObject(body).optJSONArray("events")?.objects()?.mapNotNull { e ->
    // A race weekend lists each session: the race is the one people mean (else the last session).
    val comps = e.optJSONArray("competitions")?.objects().orEmpty()
    val comp = comps.firstOrNull { it.optJSONObject("type")?.optString("abbreviation").equals("Race", true) } ?: if (l.kw.isNotEmpty() && !l.allDay) comps.lastOrNull() else comps.firstOrNull()
    val start = comp?.str("date")?.let(::time) ?: time(e.optString("date")) ?: return@mapNotNull null
    val teams = comp?.optJSONArray("competitors")?.objects().orEmpty()
      .sortedBy { if (it.optString("homeAway") == "home") 0 else 1 }
      .mapNotNull { c ->
        val t = c.optJSONObject("team") ?: c.optJSONObject("athlete") ?: return@mapNotNull null
        Team(t.optString("displayName").ifEmpty { t.optString("fullName") }, t.optString("shortDisplayName").ifEmpty { t.optString("displayName") },
          t.str("logo") ?: t.optJSONObject("flag")?.str("href"), c.str("score"))
      }
    val status = (comp?.optJSONObject("status") ?: e.optJSONObject("status"))?.optJSONObject("type")
    val casts = comp?.optJSONArray("broadcasts")?.objects().orEmpty().flatMap { strings(it.optJSONArray("names")) }.distinct()
    val twoSides = teams.size == 2 && l.kw.isEmpty()
    // Without two sides: the sport's words, and for a tournament a distinctive word of its name ("Paris Masters" → paris).
    val named = words(e.optString("name")).split(' ').filter { it.length >= 5 && it !in COMMON }.take(3)
    val keywords = if (twoSides) teams.map { variants(it) } else listOfNotNull(l.kw.ifEmpty { listOf(l.name.lowercase()) }, named.takeIf { l.allDay && it.isNotEmpty() })
    SportEvent(e.optString("id"), l.sport, l.name, if (twoSides) "${teams[0].name} v ${teams[1].name}" else e.optString("name"), start,
      status?.optString("state") ?: "pre", status?.optString("shortDetail") ?: "", if (twoSides) teams else emptyList(), casts, keywords, l.allDay)
  }.orEmpty()

  /** Ways a TV listing might name a team: "Brighton & Hove Albion" → brighton and hove albion, brighton. */
  fun variants(t: Team): List<String> {
    val base = listOf(t.name, t.short, t.name.replace(Regex("\\b(FC|AFC|CF|SC)\\b"), ""), t.name.substringBefore(" & ")).map(::words)
    // How UK listings shorten names: "Man Utd", "Nott'm Forest", "Spurs", "Wolves"; else the distinctive first word.
    val short = base.flatMap { n -> listOf(n.replace("manchester", "man").replace("united", "utd"), n.replace("nottingham", "nottm"), n.replace(Regex(" (united|city|town|county|rovers|athletic|albion|wanderers|hotspur)$"), "")) }
    val first = base.mapNotNull { n -> n.substringBefore(' ').takeIf { it.length >= 5 && it !in AMBIGUOUS && ' ' in n } }
    return (base + short + first + NICKNAMES[words(t.name)].orEmpty()).filter { it.length >= 3 && it !in AMBIGUOUS }.distinct()
  }

  /** First words shared by several clubs ("Manchester United/City"): never enough alone. */
  private val AMBIGUOUS = setOf("man", "manchester", "athletic", "sporting", "inter", "atletico", "borussia", "olympique", "real", "racing", "deportivo", "hertha", "dynamo", "west", "sheffield", "queens", "bristol")
  private val NICKNAMES = mapOf(
    "tottenham hotspur" to listOf("spurs"), "wolverhampton wanderers" to listOf("wolves"), "nottingham forest" to listOf("forest", "nottm forest"),
    "manchester united" to listOf("man utd", "man united"), "manchester city" to listOf("man city"), "brighton and hove albion" to listOf("brighton"),
    "west ham united" to listOf("west ham"), "paris saint germain" to listOf("psg", "paris st germain"), "internazionale" to listOf("inter milan", "inter"),
    "bayern munich" to listOf("bayern", "bayern munchen"), "queens park rangers" to listOf("qpr"), "sheffield wednesday" to listOf("sheff wed"), "sheffield united" to listOf("sheff utd"),
  )

  private val COMMON = setOf("championship", "championships", "open", "presented", "masters", "classic", "invitational", "tournament", "grand", "cruises")

  private fun words(s: String) = s.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()

  private fun time(s: String): Long? = listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'").firstNotNullOfOrNull { f ->
    runCatching { SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)?.time }.getOrNull()
  }

  /** Whether a guide listing is this event: on around kick-off, and naming every side (or the competition). */
  fun matches(e: SportEvent, p: Programme, now: Long = System.currentTimeMillis()): Boolean {
    if (e.allDay) { if (p.end <= maxOf(e.start, now - 3_600_000L) || p.start >= maxOf(e.start, now) + 86_400_000L) return false }
    else if (p.end <= e.start - 15 * 60_000 || p.start >= e.start + 45 * 60_000) return false
    val text = " " + words(p.title + " " + p.description) + " "
    return e.keywords.all { group -> group.any { k -> " $k " in text } }
  }

  /**
   * Where to watch [e]: channels whose guide lists it (yours, or another country's guide — linkable to one of yours),
   * then the broadcasters ESPN names. [channels]: all of the provider's channels; [guides]: label, your channel, listings.
   */
  fun whereToWatch(e: SportEvent, channels: List<Channel>, guides: List<Triple<String, Channel?, List<Programme>>>, links: Map<String, Int>): List<WatchOption> {
    val byId = channels.associateBy { it.id }
    val listed = guides.flatMap { (label, c, list) -> list.filter { matches(e, it) }.map { p -> WatchOption(label, p, c ?: links[channelKey(label)]?.let(byId::get), "TV guide") } }
    val casts = e.broadcasters.map { b ->
      val ch = links[channelKey(b)]?.let(byId::get) ?: channels.firstOrNull { channelKey(it.name) == channelKey(b) }
      WatchOption(b, null, ch, "US broadcaster")
    }
    return (listed + casts).distinctBy { it.channel?.id?.toString() ?: "b:${it.label}" }
  }

  fun links(store: Store): Map<String, Int> = runCatching { JSONObject(store.str("xc_links")).let { o -> o.keys().asSequence().associateWith { o.getInt(it) } } }.getOrDefault(emptyMap())
  fun link(store: Store, label: String, channel: Channel?) =
    store.put("xc_links", JSONObject(links(store).toMutableMap().apply { if (channel == null) remove(channelKey(label)) else put(channelKey(label), channel.id) }).toString())
}
