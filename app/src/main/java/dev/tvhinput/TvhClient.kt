package dev.tvhinput

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TvhServerInfo(val name: String, val version: String)

data class TvhChannel(
    val uuid: String,
    val name: String,
    val number: String,
    val icon: String?,
)

data class TvhEvent(
    val eventId: Long,
    val channelUuid: String,
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val subtitle: String?,
    val summary: String?,
    val description: String?,
    val genres: List<Int>,
    val image: String?,
    val season: Int?,
    val episode: Int?,
    /** Minimum viewer age from the broadcast's parental rating, or null when unrated. */
    val ageRating: Int? = null,
)

/** Thin client for the tvheadend HTTP/JSON API. All calls are blocking. */
class TvhClient(val config: TvhConfig) {

    // newBuilder() shares the base client's connection pool and threads across all TvhClients.
    val http: OkHttpClient = BASE_HTTP.newBuilder()
        .authenticator(TvhAuthenticator(config.username, config.password))
        .build()

    private val baseUrl: HttpUrl = HttpUrl.Builder()
        .scheme(if (config.https) "https" else "http")
        .host(config.host)
        .port(config.port)
        .apply { config.webRoot.split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }
        .build()

    fun url(path: String): HttpUrl = baseUrl.newBuilder().addPathSegments(path).build()

    fun serverInfo(): TvhServerInfo {
        val json = getJson(url("api/serverinfo"))
        return TvhServerInfo(json.optString("name", "Tvheadend"), json.optString("sw_version"))
    }

    fun channels(): List<TvhChannel> {
        val json = getJson(
            url("api/channel/grid").newBuilder()
                .addQueryParameter("limit", "100000")
                .addQueryParameter("sort", "number")
                .addQueryParameter("dir", "ASC")
                .build()
        )
        val entries = json.optJSONArray("entries") ?: return emptyList()
        return (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { it.optBoolean("enabled", true) }
            .mapIndexed { index, o ->
                TvhChannel(
                    uuid = o.getString("uuid"),
                    name = o.optString("name"),
                    number = o.optString("number").removeSuffix(".0")
                        .takeIf { it.isNotEmpty() && it != "0" } ?: (index + 1).toString(),
                    icon = o.optStringOrNull("icon_public_url"),
                )
            }
    }

    /** Fetches the whole EPG tvheadend knows about (current and future events), paging through the grid. */
    fun events(pageSize: Int = 2000, maxEvents: Int = 100_000): List<TvhEvent> {
        val result = ArrayList<TvhEvent>()
        var start = 0
        while (start < maxEvents) {
            val json = getJson(
                url("api/epg/events/grid").newBuilder()
                    .addQueryParameter("start", start.toString())
                    .addQueryParameter("limit", pageSize.toString())
                    .build()
            )
            val entries = json.optJSONArray("entries") ?: break
            for (i in 0 until entries.length()) {
                val o = entries.getJSONObject(i)
                val genres = o.optJSONArray("genre")
                result += TvhEvent(
                    eventId = o.optLong("eventId"),
                    channelUuid = o.optString("channelUuid"),
                    startMs = o.optLong("start") * 1000,
                    stopMs = o.optLong("stop") * 1000,
                    title = o.optString("title"),
                    subtitle = o.optStringOrNull("subtitle"),
                    summary = o.optStringOrNull("summary"),
                    description = o.optStringOrNull("description"),
                    genres = genres?.let { g -> (0 until g.length()).map { g.optInt(it) } }.orEmpty(),
                    image = o.optStringOrNull("image"),
                    season = o.optInt("seasonNumber").takeIf { it > 0 },
                    episode = o.optInt("episodeNumber").takeIf { it > 0 },
                    ageRating = o.optInt("ageRating").takeIf { it > 0 },
                )
            }
            start += entries.length()
            if (entries.length() < pageSize || start >= json.optInt("totalCount", 0)) break
        }
        return result
    }

    fun streamUrl(channelUuid: String): String =
        url("stream/channel/$channelUuid").newBuilder()
            .apply { if (config.profile.isNotBlank()) addQueryParameter("profile", config.profile) }
            .build()
            .toString()

    /**
     * HTSP identifies channels by a number derived from the channel uuid: its first 4 bytes read
     * as a little-endian int, top bit cleared (tvheadend idnode_get_short_uuid).
     */
    fun htspChannelId(channelUuid: String): Int {
        var id = 0
        for (i in 3 downTo 0) id = (id shl 8) or channelUuid.substring(i * 2, i * 2 + 2).toInt(16)
        return id and 0x7fffffff
    }

    /** Resolves an icon/image reference, which tvheadend gives either as absolute URL or as e.g. "imagecache/42". */
    fun resolve(ref: String): HttpUrl? =
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref.toHttpUrlOrNull()
        else url(ref.trimStart('/'))

    fun download(url: HttpUrl): ByteArray =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            r.body?.bytes() ?: throw IOException("Empty body for $url")
        }

    private fun getJson(url: HttpUrl): JSONObject =
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.code == 401) throw IOException("Authentication failed")
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for ${url.encodedPath}")
            JSONObject(r.body?.string() ?: throw IOException("Empty body for ${url.encodedPath}"))
        }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private companion object {
        val BASE_HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
