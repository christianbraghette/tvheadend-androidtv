package dev.tvhinput

import android.annotation.SuppressLint
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvContract.Programs.Genres
import android.util.Log
import dev.tvhinput.htsp.HtsMsg
import dev.tvhinput.htsp.HtspConnection
import java.io.IOException

/** Mirrors tvheadend channels and EPG into the system TvProvider, so every TIF-aware app can see them. */
class TvProviderSync(private val context: Context, private val client: TvhClient) {
    private val resolver = context.contentResolver
    private val inputId = TvhInputService.inputId(context)
    private val logoCache = context.getSharedPreferences("tvh_logos", Context.MODE_PRIVATE)

    /** Syncs the channel list and returns tvheadend channel uuid -> TvProvider channel id. */
    fun syncChannels(): Map<String, Long> {
        val remote = client.channels()
        val radio = detectRadioChannels(remote)
        val existing = existingChannels()
        val result = LinkedHashMap<String, Long>()

        for (channel in remote) {
            val values = ContentValues().apply {
                put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, channel.uuid)
                put(TvContract.Channels.COLUMN_DISPLAY_NAME, channel.name)
                put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, channel.number)
                put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                put(TvContract.Channels.COLUMN_SERVICE_TYPE, serviceType(channel.uuid in radio))
            }
            val id = existing[channel.uuid]
            if (id != null) {
                resolver.update(TvContract.buildChannelUri(id), values, null, null)
                result[channel.uuid] = id
            } else {
                val uri = resolver.insert(TvContract.Channels.CONTENT_URI, values) ?: continue
                result[channel.uuid] = uri.lastPathSegment!!.toLong()
            }
        }

        for ((uuid, id) in existing) {
            if (uuid !in result) {
                resolver.delete(TvContract.buildChannelUri(id), null, null)
                logoCache.edit().remove(uuid).apply()
            }
        }

        syncLogos(remote, result)
        return result
    }

    /**
     * Updates the program guide of every channel in place, matching programs by tvheadend event id,
     * so TV apps never see an empty guide while a sync runs. Returns the number of programs.
     */
    fun syncPrograms(channelIds: Map<String, Long>): Int {
        val byChannel = client.events().groupBy { it.channelUuid }
        var count = 0
        for ((uuid, channelId) in channelIds) {
            val events = byChannel[uuid].orEmpty()
            val existing = existingPrograms(channelId)
            val ops = ArrayList<ContentProviderOperation>()
            for (event in events) {
                val values = programValues(channelId, event)
                val rowId = existing.remove(event.eventId)
                ops += if (rowId != null) {
                    ContentProviderOperation.newUpdate(TvContract.buildProgramUri(rowId)).withValues(values).build()
                } else {
                    ContentProviderOperation.newInsert(TvContract.Programs.CONTENT_URI).withValues(values).build()
                }
            }
            existing.values.forEach { ops += ContentProviderOperation.newDelete(TvContract.buildProgramUri(it)).build() }
            ops.chunked(BATCH_SIZE).forEach { resolver.applyBatch(TvContract.AUTHORITY, ArrayList(it)) }
            count += events.size
        }
        return count
    }

    fun syncAll(): Int = syncPrograms(syncChannels())

    private fun existingChannels(): Map<String, Long> {
        val map = HashMap<String, Long>()
        resolver.query(
            TvContract.buildChannelsUriForInput(inputId),
            arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val uuid = c.getString(1)
                if (uuid != null) map[uuid] = c.getLong(0) else resolver.delete(TvContract.buildChannelUri(c.getLong(0)), null, null)
            }
        }
        return map
    }

    /** tvheadend event id -> program row id; programs without a usable event id are dropped. */
    private fun existingPrograms(channelId: Long): HashMap<Long, Long> {
        val map = HashMap<Long, Long>()
        resolver.query(
            TvContract.buildProgramsUriForChannel(channelId),
            arrayOf(TvContract.Programs._ID, TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val eventId = c.getBlob(1)?.let { String(it).toLongOrNull() }
                if (eventId != null && eventId !in map) map[eventId] = c.getLong(0)
                else resolver.delete(TvContract.buildProgramUri(c.getLong(0)), null, null)
            }
        }
        return map
    }

    /**
     * Radio channels. tvheadend is the authority: when HTSP is reachable, its service types (the
     * HTTP API only exposes them to admins) replace the saved set, including channels playback
     * marked as radio. HTSP is optional: without it the channels detected at playback are kept.
     */
    private fun detectRadioChannels(channels: List<TvhChannel>): Set<String> {
        val fromHtsp = try {
            HtspConnection.connect(client.config).use { htsp ->
                channels.filter { channel ->
                    val info = htsp.request(
                        HtsMsg.of("method" to "getChannel", "channelId" to client.htspChannelId(channel.uuid))
                    )
                    val content = info.list("services").mapNotNull { (it as? HtsMsg)?.int("content") }
                    content.isNotEmpty() && content.all { it == SERVICE_CONTENT_RADIO }
                }.mapTo(HashSet()) { it.uuid }
            }
        } catch (e: IOException) {
            Log.i(TAG, "Radio detection over HTSP not available: ${e.message}")
            null
        }
        return if (fromHtsp != null) {
            RadioChannels.replace(context, fromHtsp)
            fromHtsp
        } else {
            RadioChannels.load(context)
        }
    }

    private fun syncLogos(channels: List<TvhChannel>, ids: Map<String, Long>) {
        for (channel in channels) {
            val icon = channel.icon ?: continue
            val id = ids[channel.uuid] ?: continue
            if (logoCache.getString(channel.uuid, null) == icon) continue
            try {
                val url = client.resolve(icon) ?: continue
                val bytes = client.download(url)
                resolver.openOutputStream(TvContract.buildChannelLogoUri(id))?.use { it.write(bytes) }
                logoCache.edit().putString(channel.uuid, icon).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Logo download failed for ${channel.name}: ${e.message}")
            }
        }
    }

    private fun programValues(channelId: Long, e: TvhEvent) = ContentValues().apply {
        put(TvContract.Programs.COLUMN_CHANNEL_ID, channelId)
        put(TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA, e.eventId.toString().toByteArray())
        put(TvContract.Programs.COLUMN_TITLE, e.title)
        put(TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS, e.startMs)
        put(TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS, e.stopMs)
        // Explicit nulls, so an update clears fields tvheadend no longer has.
        put(TvContract.Programs.COLUMN_EPISODE_TITLE, e.subtitle)
        put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, e.summary ?: e.description)
        put(TvContract.Programs.COLUMN_LONG_DESCRIPTION, e.description)
        put(TvContract.Programs.COLUMN_SEASON_DISPLAY_NUMBER, e.season?.toString())
        put(TvContract.Programs.COLUMN_EPISODE_DISPLAY_NUMBER, e.episode?.toString())
        put(TvContract.Programs.COLUMN_POSTER_ART_URI, e.image?.let { client.resolve(it)?.toString() })
        val genres = e.genres.mapNotNull(::canonicalGenre).distinct()
        // All values come from canonicalGenre's Genres constants; lint can't follow them through the array.
        @SuppressLint("WrongConstant")
        val encodedGenres = if (genres.isEmpty()) null else Genres.encode(*genres.toTypedArray())
        put(TvContract.Programs.COLUMN_CANONICAL_GENRE, encodedGenres)
    }

    /** Maps the DVB content descriptor (EN 300 468) top nibble to Android's canonical genres. */
    private fun canonicalGenre(dvb: Int): String? = when (dvb shr 4) {
        0x1 -> Genres.MOVIES
        0x2, 0x8 -> Genres.NEWS
        0x3 -> Genres.ENTERTAINMENT
        0x4 -> Genres.SPORTS
        0x5 -> Genres.FAMILY_KIDS
        0x6 -> Genres.MUSIC
        0x7 -> Genres.ARTS
        0x9 -> Genres.EDUCATION
        0xA -> Genres.LIFE_STYLE
        else -> null
    }

    companion object {
        private const val TAG = "TvProviderSync"
        private const val BATCH_SIZE = 500
        private const val SERVICE_CONTENT_RADIO = 0x02

        fun serviceType(radio: Boolean) =
            if (radio) TvContract.Channels.SERVICE_TYPE_AUDIO else TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO
    }
}

/** Channel uuids known to be radio (audio only), kept across syncs. */
object RadioChannels {
    private const val PREFS = "tvh_radio_channels"
    private const val KEY = "uuids"

    fun load(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet()).orEmpty().toSet()

    fun add(context: Context, uuid: String) = replace(context, load(context) + uuid)

    fun replace(context: Context, uuids: Set<String>) {
        prefs(context).edit().putStringSet(KEY, HashSet(uuids)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
