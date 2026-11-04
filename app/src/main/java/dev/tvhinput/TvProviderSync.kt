package dev.tvhinput

import android.annotation.SuppressLint
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.media.tv.TvContentRating
import android.media.tv.TvContract
import android.media.tv.TvContract.Programs.Genres
import android.util.Log
import java.io.IOException
import java.security.MessageDigest

/** Mirrors tvheadend channels and EPG into the system TvProvider, so every TIF-aware app can see them. */
class TvProviderSync(private val context: Context, private val client: TvhClient) {
    private val resolver = context.contentResolver
    private val inputId = TvhInputService.inputId(context)
    private val logoCache = context.getSharedPreferences("tvh_logos", Context.MODE_PRIVATE)

    /**
     * Everything tvheadend has, over HTSP when possible: it also tells radio channels apart and is
     * much lighter than paging through the HTTP API's EPG, which remains the fallback.
     */
    fun fetch(): GuideSnapshot = try {
        HtspGuide.fetch(client)
    } catch (e: IOException) {
        Log.i(TAG, "Guide over HTSP not available, using the HTTP API: ${e.message}")
        GuideSnapshot(client.channels(), radio = null, events = client.events())
    }

    /** Syncs the channel list and returns tvheadend channel uuid -> TvProvider channel id. */
    fun syncChannels(snapshot: GuideSnapshot): Map<String, Long> {
        val radio = radioChannels(snapshot)
        val existing = existingChannels()
        val result = LinkedHashMap<String, Long>()

        for (channel in snapshot.channels) {
            val values = channelValues(channel, channel.uuid in radio)
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
            if (uuid !in result) deleteChannel(uuid, id)
        }

        for (channel in snapshot.channels) result[channel.uuid]?.let { syncLogo(channel, it) }
        return result
    }

    /**
     * Updates the program guide of every channel in place, matching programs by tvheadend event id,
     * so TV apps never see an empty guide while a sync runs. Programs whose content is unchanged
     * are left alone. Returns the number of programs.
     */
    fun syncPrograms(events: List<TvhEvent>, channelIds: Map<String, Long>): Int {
        val byChannel = events.groupBy { it.channelUuid }
        var count = 0
        var written = 0
        for ((uuid, channelId) in channelIds) {
            val channelEvents = byChannel[uuid].orEmpty()
            val existing = existingPrograms(channelId)
            val ops = ArrayList<ContentProviderOperation>()
            for (event in channelEvents) {
                val values = programValues(channelId, event)
                val row = existing.remove(event.eventId)
                when {
                    row == null -> ops += ContentProviderOperation.newInsert(TvContract.Programs.CONTENT_URI).withValues(values).build()
                    row.fingerprint != fingerprintOf(values) ->
                        ops += ContentProviderOperation.newUpdate(TvContract.buildProgramUri(row.id)).withValues(values).build()
                }
            }
            existing.values.forEach { ops += ContentProviderOperation.newDelete(TvContract.buildProgramUri(it.id)).build() }
            ops.chunked(BATCH_SIZE).forEach { resolver.applyBatch(TvContract.AUTHORITY, ArrayList(it)) }
            count += channelEvents.size
            written += ops.size
        }
        Log.i(TAG, "Guide: $count programs, $written written")
        return count
    }

    fun syncAll(): Int {
        val snapshot = fetch()
        return syncPrograms(snapshot.events, syncChannels(snapshot))
    }

    fun deleteChannel(uuid: String, id: Long) {
        resolver.delete(TvContract.buildChannelUri(id), null, null)
        logoCache.edit().remove(uuid).apply()
    }

    fun existingChannels(): Map<String, Long> {
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

    /** A program row: its id and the [fingerprintOf] the values it was last written with. */
    class ProgramRow(val id: Long, val fingerprint: String)

    /** tvheadend event id -> program row; programs without a usable event id are dropped. */
    fun existingPrograms(channelId: Long): HashMap<Long, ProgramRow> {
        val map = HashMap<Long, ProgramRow>()
        resolver.query(
            TvContract.buildProgramsUriForChannel(channelId),
            arrayOf(TvContract.Programs._ID, TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                // "eventId:fingerprint"; rows written by older versions hold just the event id.
                val data = c.getBlob(1)?.let { String(it) }
                val eventId = data?.substringBefore(':')?.toLongOrNull()
                if (eventId != null && eventId !in map) map[eventId] = ProgramRow(c.getLong(0), data.substringAfter(':', ""))
                else resolver.delete(TvContract.buildProgramUri(c.getLong(0)), null, null)
            }
        }
        return map
    }

    /**
     * Radio channels. tvheadend is the authority: when HTSP is reachable, its service types (the
     * HTTP API only exposes them to admins) replace the saved set, including channels playback
     * marked as radio. Without HTSP the channels detected at playback are kept.
     */
    private fun radioChannels(snapshot: GuideSnapshot): Set<String> {
        val radio = snapshot.radio ?: return RadioChannels.load(context)
        RadioChannels.replace(context, radio)
        return radio
    }

    fun channelValues(channel: TvhChannel, radio: Boolean) = ContentValues().apply {
        put(TvContract.Channels.COLUMN_INPUT_ID, inputId)
        put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, channel.uuid)
        put(TvContract.Channels.COLUMN_DISPLAY_NAME, channel.name)
        put(TvContract.Channels.COLUMN_DISPLAY_NUMBER, channel.number)
        put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_OTHER)
        put(TvContract.Channels.COLUMN_SERVICE_TYPE, serviceType(radio))
    }

    /** Downloads the channel's logo unless it is the one already stored. */
    fun syncLogo(channel: TvhChannel, id: Long) {
        val icon = channel.icon ?: return
        try {
            // HTSP and the HTTP API refer to the same image differently: compare resolved URLs.
            val url = client.resolve(icon) ?: return
            if (logoCache.getString(channel.uuid, null) == url.toString()) return
            val bytes = client.download(url)
            resolver.openOutputStream(TvContract.buildChannelLogoUri(id))?.use { it.write(bytes) }
            logoCache.edit().putString(channel.uuid, url.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Logo download failed for ${channel.name}: ${e.message}")
        }
    }

    /** The row for [e], with its event id and fingerprint in the internal provider data. */
    fun programValues(channelId: Long, e: TvhEvent): ContentValues {
        val values = ContentValues().apply {
            put(TvContract.Programs.COLUMN_CHANNEL_ID, channelId)
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
            put(TvContract.Programs.COLUMN_CONTENT_RATING, contentRating(e.ageRating))
        }
        values.put(TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA, "${e.eventId}:${fingerprintOf(values)}".toByteArray())
        return values
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

        fun serviceType(radio: Boolean) =
            if (radio) TvContract.Channels.SERVICE_TYPE_AUDIO else TvContract.Channels.SERVICE_TYPE_AUDIO_VIDEO

        /**
         * The DVB parental rating (a minimum age, EN 300 468) in Android's built-in "DVB" rating
         * system, which defines DVB_4 to DVB_18.
         */
        fun contentRating(age: Int?): String? = age?.takeIf { it > 0 }?.let {
            TvContentRating.createRating("com.android.tv", "DVB", "DVB_${it.coerceIn(4, 18)}").flattenToString()
        }

        /** Identifies a program row's content, so unchanged programs aren't rewritten. */
        fun fingerprintOf(values: ContentValues): String {
            val text = values.keySet().filter { it != TvContract.Programs.COLUMN_INTERNAL_PROVIDER_DATA }.sorted()
                .joinToString("\u0001") { "$it=${values.get(it)}" }
            val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
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
