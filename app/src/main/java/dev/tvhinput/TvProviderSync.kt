package dev.tvhinput

import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContract
import android.media.tv.TvContract.Programs.Genres
import android.util.Log

/** Mirrors tvheadend channels and EPG into the system TvProvider, so every TIF-aware app can see them. */
class TvProviderSync(private val context: Context, private val client: TvhClient) {
    private val resolver = context.contentResolver
    private val inputId = TvhInputService.inputId(context)
    private val logoCache = context.getSharedPreferences("tvh_logos", Context.MODE_PRIVATE)

    /** Syncs the channel list and returns tvheadend channel uuid -> TvProvider channel id. */
    fun syncChannels(): Map<String, Long> {
        val remote = client.channels()
        val existing = existingChannels()
        val result = LinkedHashMap<String, Long>()

        for (channel in remote) {
            val values = ContentValues().apply {
                put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
                put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, channel.uuid)
                put(TvContract.Channels.COLUMN_DISPLAY_NAME, channel.name)
                put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, channel.number)
                put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
                put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO)
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

    /** Replaces the program guide of every channel. Returns the number of programs written. */
    fun syncPrograms(channelIds: Map<String, Long>): Int {
        val byChannel = client.events().groupBy { it.channelUuid }
        var count = 0
        for ((uuid, channelId) in channelIds) {
            val events = byChannel[uuid].orEmpty()
            resolver.delete(TvContract.buildProgramsUriForChannel(channelId), null, null)
            if (events.isEmpty()) continue
            count += resolver.bulkInsert(
                TvContract.Programs.CONTENT_URI,
                events.map { programValues(channelId, it) }.toTypedArray(),
            )
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
        e.subtitle?.let { put(TvContract.Programs.COLUMN_EPISODE_TITLE, it) }
        (e.summary ?: e.description)?.let { put(TvContract.Programs.COLUMN_SHORT_DESCRIPTION, it) }
        e.description?.let { put(TvContract.Programs.COLUMN_LONG_DESCRIPTION, it) }
        e.season?.let { put(TvContract.Programs.COLUMN_SEASON_DISPLAY_NUMBER, it.toString()) }
        e.episode?.let { put(TvContract.Programs.COLUMN_EPISODE_DISPLAY_NUMBER, it.toString()) }
        e.image?.let { img -> client.resolve(img)?.let { put(TvContract.Programs.COLUMN_POSTER_ART_URI, it.toString()) } }
        val genres = e.genres.mapNotNull(::canonicalGenre).distinct()
        if (genres.isNotEmpty()) put(TvContract.Programs.COLUMN_CANONICAL_GENRE, Genres.encode(*genres.toTypedArray()))
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

    private companion object {
        const val TAG = "TvProviderSync"
    }
}
