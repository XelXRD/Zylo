// Your own media servers as sources: Plex and Jellyfin. Titles are matched by TMDB id (falling back to title + year
// for libraries on older agents), and play as direct files, so ExoPlayer handles them like any other source.
package tv.zylo

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

interface MediaServer {
  val name: String
  fun find(item: Item, season: Int?, episode: Int?): List<Source>
}

object Library {
  fun connected(store: Store): List<MediaServer> = listOfNotNull(Plex.from(store), Jellyfin.from(store))

  /** One id for this TV, sent to Plex and Jellyfin so they list it as a device. */
  fun deviceId(store: Store) = store.str("device_id").ifEmpty { UUID.randomUUID().toString().also { store.put("device_id", it) } }

  fun quality(width: Int, height: Int) = when {
    width >= 3200 || height >= 1600 -> "4K"
    width >= 1800 || height >= 1000 -> "1080p"
    width >= 1200 || height >= 700 -> "720p"
    width > 0 || height > 0 -> "480p"
    else -> null
  }

  fun size(bytes: Long) = when {
    bytes <= 0 -> null
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    else -> "${bytes / 1_000_000} MB"
  }

  fun fileName(path: String) = path.substringAfterLast('/').substringAfterLast('\\')
}

// --- Plex ---------------------------------------------------------------------------------------------

class Plex(private val uri: String, private val token: String, override val name: String) : MediaServer {
  private fun get(path: String) = JSONObject(http("$uri$path${if ('?' in path) '&' else '?'}X-Plex-Token=$token", headers = mapOf("Accept" to "application/json")))
    .getJSONObject("MediaContainer")

  override fun find(item: Item, season: Int?, episode: Int?): List<Source> {
    val wanted = if (item.type == "movie") "movie" else "show"
    val hits = get("/search?query=${enc(item.title)}&includeGuids=1").optJSONArray("Metadata")?.objects().orEmpty().filter { it.optString("type") == wanted }
    val match = hits.find { m -> m.optJSONArray("Guid")?.objects()?.any { it.optString("id") == "tmdb://${item.id}" } == true }
      ?: hits.find { it.optString("title").equals(item.title, true) && (item.year.isEmpty() || it.optInt("year").toString() == item.year) }
      ?: return emptyList()
    val key = if (item.type == "movie") match.optString("ratingKey") else {
      get("/library/metadata/${match.optString("ratingKey")}/allLeaves").optJSONArray("Metadata")?.objects()
        ?.find { it.optInt("parentIndex") == season && it.optInt("index") == episode }?.optString("ratingKey") ?: return emptyList()
    }
    val video = get("/library/metadata/$key").optJSONArray("Metadata")?.objects()?.firstOrNull() ?: return emptyList()
    // One source per version of the file (a 4K and a 1080p copy show as two).
    return video.optJSONArray("Media")?.objects().orEmpty().mapNotNull { m ->
      val part = m.optJSONArray("Part")?.objects()?.firstOrNull() ?: return@mapNotNull null
      val file = Library.fileName(part.optString("file"))
      val quality = when (m.optString("videoResolution").lowercase()) { "4k" -> "4K"; "1080" -> "1080p"; "720" -> "720p"; "" -> null; else -> "480p" }
      Source(name, "Plex", file, quality, hdrOf(file), Library.size(part.optLong("size")), file.ifEmpty { null }, url = "$uri${part.optString("key")}?X-Plex-Token=$token", library = "plex")
    }
  }

  companion object {
    private const val TV = "https://plex.tv/api/v2"

    fun from(store: Store) = store.str("plex_uri").takeIf { it.isNotEmpty() && store.str("plex_token").isNotEmpty() }
      ?.let { Plex(it, store.str("plex_token"), store.str("plex_name").ifEmpty { "Plex" }) }

    private fun headers(cid: String, token: String? = null) = mapOf(
      "Accept" to "application/json", "X-Plex-Product" to "Zylo", "X-Plex-Version" to "0.13.0", "X-Plex-Client-Identifier" to cid,
      "X-Plex-Platform" to "Android", "X-Plex-Device-Name" to "Zylo TV",
    ) + (token?.let { mapOf("X-Plex-Token" to it) } ?: emptyMap())

    class Pin(val id: Long, val code: String)

    /** A short code to enter at plex.tv/link. */
    fun pin(cid: String) = JSONObject(http("$TV/pins", "POST", headers(cid), form("strong" to "false"), "application/x-www-form-urlencoded")).let { Pin(it.getLong("id"), it.getString("code")) }

    /** The account token once the code has been entered, else null. */
    fun poll(cid: String, id: Long): String? = JSONObject(http("$TV/pins/$id", headers = headers(cid))).str("authToken")

    /** Finds the account's servers and keeps the first that answers from this TV, home-network addresses first. */
    fun connect(store: Store, cid: String, account: String) {
      val servers = JSONArray(http("$TV/resources?includeHttps=1&includeRelay=1", headers = headers(cid, account))).objects()
        .filter { "server" in it.optString("provides") }.sortedByDescending { it.optBoolean("owned") }
      if (servers.isEmpty()) throw Exception("There's no Plex Media Server on this Plex account.")
      for (server in servers) {
        val token = server.optString("accessToken").ifEmpty { account }
        val connections = server.optJSONArray("connections")?.objects().orEmpty().sortedWith(compareBy({ !it.optBoolean("local") }, { it.optBoolean("relay") }))
        for (c in connections) {
          val uri = c.optString("uri").trimEnd('/')
          if (runCatching { http("$uri/identity", headers = mapOf("X-Plex-Token" to token, "Accept" to "application/json"), timeout = 4_000) }.isSuccess) {
            store.put("plex_uri", uri)
            store.put("plex_token", token)
            store.put("plex_name", server.optString("name").ifEmpty { "Plex" })
            return
          }
        }
      }
      throw Exception("Found your Plex server, but this TV couldn't reach it. Check the server is on and on the same network.")
    }

    fun disconnect(store: Store) = listOf("plex_uri", "plex_token", "plex_name").forEach { store.put(it, "") }
  }
}

// --- Jellyfin -------------------------------------------------------------------------------------------

class Jellyfin(private val server: String, private val token: String, private val user: String, private val cid: String, override val name: String) : MediaServer {
  private fun get(path: String) = JSONObject(http("$server$path", headers = auth(cid, token)))

  override fun find(item: Item, season: Int?, episode: Int?): List<Source> {
    val kind = if (item.type == "movie") "Movie" else "Series"
    val hits = get("/Items?userId=$user&recursive=true&includeItemTypes=$kind&searchTerm=${enc(item.title)}&fields=ProviderIds,MediaSources&limit=20")
      .optJSONArray("Items")?.objects().orEmpty()
    val match = hits.find { it.optJSONObject("ProviderIds")?.optString("Tmdb") == item.id.toString() }
      ?: hits.find { it.optString("Name").equals(item.title, true) && (item.year.isEmpty() || it.optInt("ProductionYear").toString() == item.year) }
      ?: return emptyList()
    val video = if (item.type == "movie") match else {
      get("/Shows/${match.optString("Id")}/Episodes?userId=$user&season=$season&fields=MediaSources").optJSONArray("Items")?.objects()
        ?.find { it.optInt("IndexNumber") == episode } ?: return emptyList()
    }
    return video.optJSONArray("MediaSources")?.objects().orEmpty().map { ms ->
      val v = ms.optJSONArray("MediaStreams")?.objects()?.find { it.optString("Type") == "Video" }
      val file = Library.fileName(ms.optString("Path")).ifEmpty { ms.optString("Name") }
      val range = "${v?.optString("VideoRange").orEmpty()} ${v?.optString("VideoRangeType").orEmpty()}".replace("DOVI", "DV")
      Source(
        name, "Jellyfin", file, Library.quality(v?.optInt("Width") ?: 0, v?.optInt("Height") ?: 0), hdrOf("$file $range"), Library.size(ms.optLong("Size")), file.ifEmpty { null },
        url = "$server/Videos/${video.optString("Id")}/stream?static=true&mediaSourceId=${enc(ms.optString("Id"))}&api_key=$token", library = "jellyfin",
      )
    }
  }

  companion object {
    fun from(store: Store) = store.str("jf_server").takeIf { it.isNotEmpty() && store.str("jf_token").isNotEmpty() }
      ?.let { Jellyfin(it, store.str("jf_token"), store.str("jf_user"), Library.deviceId(store), store.str("jf_name").ifEmpty { "Jellyfin" }) }

    private fun auth(cid: String, token: String? = null): Map<String, String> {
      val value = "MediaBrowser Client=\"Zylo\", Device=\"Android TV\", DeviceId=\"$cid\", Version=\"0.13.0\"" + (token?.let { ", Token=\"$it\"" } ?: "")
      return mapOf("Authorization" to value, "X-Emby-Authorization" to value)
    }

    fun connect(store: Store, address: String, username: String, password: String) {
      val base = address.trim().trimEnd('/').let { if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it)) it else "http://$it" }
      val info = runCatching { JSONObject(http("$base/System/Info/Public")) }
        .getOrElse { throw Exception("Couldn't reach a Jellyfin server at $base. Check the address and port (usually :8096).") }
      val r = try {
        JSONObject(http("$base/Users/AuthenticateByName", "POST", auth(Library.deviceId(store)), JSONObject().put("Username", username).put("Pw", password).toString().toByteArray(), "application/json"))
      } catch (e: HttpError) {
        throw Exception(if (e.code == 401) "Jellyfin didn't accept that username and password." else "Jellyfin answered ${e.code}.")
      }
      store.put("jf_server", base)
      store.put("jf_token", r.getString("AccessToken"))
      store.put("jf_user", r.getJSONObject("User").getString("Id"))
      store.put("jf_name", info.optString("ServerName").ifEmpty { "Jellyfin" })
    }

    fun disconnect(store: Store) = listOf("jf_server", "jf_token", "jf_user", "jf_name").forEach { store.put(it, "") }
  }
}
