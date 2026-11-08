// AniList and MyAnimeList. Ported from apps/api/src/anime.ts.
// Finishing an anime episode in Zylo moves every connected tracker forward (progress only ever rises, and is sent
// as an absolute episode number). Matching uses Kometa's anime ID list (AniDB → AniList/MAL/TVDB/IMDb with TVDB
// season and episode offset), so a split-cour series maps to the right entry.
// Sign-in on a TV: the TV shows a QR code, you approve on your phone, then paste what the phone shows back into
// the TV (the Google TV phone app's keyboard can paste).
package tv.zylo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

object Anime {
  val NAMES = linkedMapOf("anilist" to "AniList", "mal" to "MyAnimeList")
  private const val IDS = "https://raw.githubusercontent.com/Kometa-Team/Anime-IDs/master/anime_ids.json"

  fun connected(store: Store, t: String) = store.str("${t}_token").isNotEmpty()

  fun disconnect(store: Store, t: String) = listOf("_token", "_refresh", "_user", "_userid").forEach { store.put("$t$it", "") }

  // --- Sign-in ------------------------------------------------------------------------

  /** AniList API client whose redirect URL is https://anilist.co/api/v2/oauth/pin shows the token on the phone. */
  fun anilistUrl(clientId: String) = "https://anilist.co/api/v2/oauth/authorize?client_id=${enc(clientId)}&response_type=token"

  fun anilistConnect(store: Store, pasted: String) {
    val token = Regex("access_token=([^&]+)").find(pasted)?.groupValues?.get(1) ?: pasted.trim()
    val v = anilistQuery(token, "{ Viewer { id name } }").getJSONObject("Viewer")
    store.put("anilist_token", token)
    store.put("anilist_user", v.optString("name"))
    store.put("anilist_userid", v.optString("id"))
  }

  /** MyAnimeList: PKCE with the plain method, so no client secret is needed. */
  fun malUrl(store: Store, clientId: String): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    val r = SecureRandom()
    val verifier = (1..64).map { chars[r.nextInt(chars.length)] }.joinToString("")
    store.put("mal_verifier", verifier)
    store.put("mal_client", clientId.trim())
    return "https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=${enc(clientId.trim())}&code_challenge=$verifier&code_challenge_method=plain&state=zylo"
  }

  /** Paste the address the phone landed on (or just the code from it). */
  fun malConnect(store: Store, pasted: String) {
    val code = Regex("[?&]code=([^&#]+)").find(pasted)?.groupValues?.get(1) ?: pasted.trim()
    val t = JSONObject(http("https://myanimelist.net/v1/oauth2/token", "POST", body = form(
      "client_id" to store.str("mal_client"), "grant_type" to "authorization_code", "code" to code, "code_verifier" to store.str("mal_verifier"),
    ), type = "application/x-www-form-urlencoded"))
    saveMal(store, t)
    store.put("mal_user", JSONObject(http("https://api.myanimelist.net/v2/users/@me", headers = mapOf("Authorization" to "Bearer ${store.str("mal_token")}"))).optString("name"))
  }

  private fun saveMal(store: Store, t: JSONObject) {
    store.put("mal_token", t.getString("access_token"))
    store.put("mal_refresh", t.optString("refresh_token"))
    store.putLong("mal_expires", System.currentTimeMillis() + t.optLong("expires_in", 2_592_000) * 1000)
  }

  @Synchronized
  private fun malToken(store: Store): String {
    if (store.long("mal_expires") - System.currentTimeMillis() < 86_400_000) {
      val t = try {
        JSONObject(http("https://myanimelist.net/v1/oauth2/token", "POST", body = form(
          "client_id" to store.str("mal_client"), "grant_type" to "refresh_token", "refresh_token" to store.str("mal_refresh"),
        ), type = "application/x-www-form-urlencoded"))
      } catch (e: HttpError) {
        if (e.code == 400 || e.code == 401) disconnect(store, "mal")
        throw HttpError(401, "MyAnimeList needs you to connect again (Settings > Integrations).")
      }
      saveMal(store, t)
    }
    return store.str("mal_token")
  }

  // --- Tracker calls ------------------------------------------------------------------

  private fun anilistQuery(token: String, query: String, vars: JSONObject = JSONObject()): JSONObject {
    val r = JSONObject(http("https://graphql.anilist.co", "POST", mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"),
      JSONObject().put("query", query).put("variables", vars).toString().toByteArray(), "application/json"))
    r.optJSONArray("errors")?.objects()?.firstOrNull()?.let { throw HttpError(it.optInt("status", 400), "AniList: ${it.optString("message")}") }
    return r.getJSONObject("data")
  }

  private fun mal(store: Store, path: String, method: String = "GET", body: ByteArray? = null) =
    JSONObject(http("https://api.myanimelist.net/v2$path", method, mapOf("Authorization" to "Bearer ${malToken(store)}"), body, if (body != null) "application/x-www-form-urlencoded" else null))

  /** (progress so far, total episodes) on this tracker. */
  private fun progress(store: Store, t: String, id: Int): Pair<Int, Int?> = if (t == "anilist") {
    val m = anilistQuery(store.str("anilist_token"), "query(\$id:Int){ Media(id:\$id){ episodes mediaListEntry{ progress } } }", JSONObject().put("id", id)).getJSONObject("Media")
    (m.optJSONObject("mediaListEntry")?.optInt("progress") ?: 0) to m.optInt("episodes").takeIf { it > 0 }
  } else {
    val a = mal(store, "/anime/$id?fields=num_episodes,my_list_status")
    (a.optJSONObject("my_list_status")?.optInt("num_episodes_watched") ?: 0) to a.optInt("num_episodes").takeIf { it > 0 }
  }

  /** Move one tracker entry forward to this episode (never backwards). Returns what happened, for the activity log. */
  fun push(store: Store, t: String, id: Int, episode: Int): String {
    if (!connected(store, t)) throw HttpError(401, "${NAMES[t]} isn't connected on this profile.")
    val (now, total) = progress(store, t, id)
    if (now >= episode) return "Already at episode $now on ${NAMES[t]}."
    val completed = total != null && episode >= total
    save(store, t, id, episode, completed)
    return "${NAMES[t]} updated to episode $episode" + if (completed) " (completed)." else "."
  }

  private fun save(store: Store, t: String, id: Int, progress: Int, completed: Boolean) {
    if (t == "anilist") anilistQuery(store.str("anilist_token"), "mutation(\$id:Int,\$p:Int,\$s:MediaListStatus){ SaveMediaListEntry(mediaId:\$id, progress:\$p, status:\$s){ id } }",
      JSONObject().put("id", id).put("p", progress).put("s", if (completed) "COMPLETED" else "CURRENT"))
    else mal(store, "/anime/$id/my_list_status", "PUT", form("status" to if (completed) "completed" else "watching", "num_watched_episodes" to "$progress"))
  }

  /** Tracker ids on a list: "watching" or "planning". */
  fun list(store: Store, t: String, status: String): List<Int> = if (t == "anilist") {
    anilistQuery(store.str("anilist_token"), "query(\$u:Int,\$s:MediaListStatus){ MediaListCollection(userId:\$u, type:ANIME, status:\$s){ lists{ entries{ mediaId } } } }",
      JSONObject().put("u", store.str("anilist_userid").toIntOrNull()).put("s", if (status == "watching") "CURRENT" else "PLANNING"))
      .getJSONObject("MediaListCollection").getJSONArray("lists").objects().flatMap { it.getJSONArray("entries").objects().map { e -> e.optInt("mediaId") } }.distinct()
  } else {
    mal(store, "/users/@me/animelist?status=${if (status == "watching") "watching" else "plan_to_watch"}&limit=100&sort=list_updated_at")
      .getJSONArray("data").objects().map { it.getJSONObject("node").optInt("id") }
  }

  // --- Kometa anime IDs -------------------------------------------------------------------

  class Row(val tvdb: Int?, val season: Int?, val offset: Int, val imdb: List<String>, val anilist: List<Int>, val mal: List<Int>) {
    fun ids(t: String) = if (t == "anilist") anilist else mal
  }

  class Ids(val byTracker: Map<String, Map<Int, Row>>, val byTvdb: Map<Int, List<Row>>, val byImdb: Map<String, List<Row>>)

  @Volatile private var ids: Ids? = null

  /** Downloaded once a week (~1.7 MB) and kept in the app's files. */
  @Synchronized
  fun ids(ctx: Context): Ids {
    ids?.let { return it }
    val f = File(ctx.filesDir, "anime_ids.json")
    if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 7 * 86_400_000L) runCatching { f.writeText(http(IDS)) }
    val raw = JSONObject(f.readText())
    fun nums(v: Any?) = v?.toString().orEmpty().split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
    val rows = raw.keys().asSequence().map { k ->
      val v = raw.getJSONObject(k)
      Row(nums(v.opt("tvdb_id")).firstOrNull(), if (v.has("tvdb_season")) v.optInt("tvdb_season") else null, v.optInt("tvdb_epoffset"),
        v.optString("imdb_id").split(",").filter { it.matches(Regex("tt\\d+")) }, nums(v.opt("anilist_id")), nums(v.opt("mal_id")))
    }.toList()
    return Ids(
      mapOf("anilist" to rows.flatMap { r -> r.anilist.map { it to r } }.toMap(), "mal" to rows.flatMap { r -> r.mal.map { it to r } }.toMap()),
      rows.filter { it.tvdb != null }.groupBy { it.tvdb!! },
      rows.flatMap { r -> r.imdb.map { it to r } }.groupBy({ it.first }, { it.second }),
    ).also { ids = it }
  }

  /** The tracker entry for this episode: same TVDB season with the largest offset below it, else absolute order. */
  fun pickEntry(rows: List<Row>, t: String, season: Int, episode: Int, absolute: Int?): Pair<Int, Int>? {
    fun best(s: Int, n: Int) = rows.filter { it.season == s && it.offset < n && it.ids(t).isNotEmpty() }.maxByOrNull { it.offset }
    best(season, episode)?.let { return it.ids(t)[0] to episode - it.offset }
    if (absolute != null) best(-1, absolute)?.let { return it.ids(t)[0] to absolute - it.offset }
    return null
  }

  /**
   * Finished a film or episode: find it on each connected tracker and queue the update in the Outbox (sent now,
   * retried if it fails). A title that can't be matched is written to the activity log, not silently skipped.
   */
  fun watched(ctx: Context, store: Store, item: Item, season: Int?, episode: Int?) {
    val trackers = NAMES.keys.filter { connected(store, it) }
    if (trackers.isEmpty()) return
    val label = item.title + if (season != null) " S${season}E$episode" else ""
    Trakt.io.launch {
      val d = runCatching { Tmdb(store.tmdb).details(item.type, item.id) }.getOrElse {
        trackers.forEach { t -> Outbox.log(store, t, label, "Couldn't look the title up on TMDB: ${it.message}", false) }
        return@launch
      }
      val genres = d.optJSONArray("genres")?.objects()?.map { it.optInt("id") }.orEmpty()
      if (16 !in genres) return@launch // only animation can be anime
      val all = runCatching { ids(ctx) }.getOrElse {
        trackers.forEach { t -> Outbox.log(store, t, label, "Couldn't load the anime ID list: ${it.message}", false) }
        return@launch
      }
      for (t in trackers) {
        val hit = if (item.type == "movie") {
          d.optJSONObject("external_ids")?.str("imdb_id")?.let { all.byImdb[it] }?.firstOrNull { it.ids(t).isNotEmpty() }?.let { it.ids(t)[0] to 1 }
        } else {
          val rows = d.optJSONObject("external_ids")?.optInt("tvdb_id")?.let { all.byTvdb[it] }.orEmpty()
          // ponytail: assumes TMDB and TVDB number episodes the same; absolute order from TMDB season sizes covers most of the rest
          val absolute = d.optJSONArray("seasons")?.objects()?.filter { it.optInt("season_number") in 1 until season!! }?.sumOf { it.optInt("episode_count") }?.plus(episode!!)
          if (rows.isEmpty()) null else pickEntry(rows, t, season!!, episode!!, absolute)
        }
        if (hit == null) Outbox.log(store, t, label, "Couldn't find this title on ${NAMES[t]} (not in the anime ID list).", false)
        else Outbox.add(store, label, JSONObject().put("service", t).put("id", hit.first).put("episode", hit.second))
      }
    }
  }

  /** A tracker list as cards: tracker id → TMDB via IMDb (films) or TVDB (series). */
  fun items(ctx: Context, store: Store, t: String, status: String): List<Item> = runBlocking(Dispatchers.IO) {
    val all = ids(ctx)
    val tmdb = Tmdb(store.tmdb)
    val gate = Semaphore(6)
    list(store, t, status).take(40).map { id ->
      async {
        gate.withPermit {
          runCatching {
            val row = all.byTracker[t]?.get(id) ?: return@runCatching null
            val imdb = row.imdb.firstOrNull()
            if (imdb != null && (row.season == null || row.season == 0)) tmdb.find(imdb, "imdb_id", "movie")?.let { return@runCatching it }
            row.tvdb?.let { tmdb.find("$it", "tvdb_id", "tv") } ?: imdb?.let { tmdb.find(it, "imdb_id", "tv") }
          }.getOrNull()
        }
      }
    }.awaitAll().filterNotNull().distinctBy { it.key }
  }
}
