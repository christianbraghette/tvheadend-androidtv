package dev.tvhinput

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.media.MediaCodec
import android.media.tv.TvContentRating
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.media.tv.TvTrackInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.MimeTypes
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.util.Locale
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.ui.SubtitleView
import dev.tvhinput.htsp.HtspConnection
import dev.tvhinput.htsp.HtspDataSource
import dev.tvhinput.htsp.HtspExtractor
import java.io.IOException

/** One playback session: plays a tvheadend channel (HTTP MPEG-TS or HTSP) onto the TV app's surface. */
@OptIn(UnstableApi::class)
class TvhSession(
    private val context: Context,
    private val client: TvhClient,
) : TvInputService.Session(context), Player.Listener {

    private val handler = Handler(Looper.getMainLooper())

    private val useHtsp = client.config.protocol == StreamProtocol.HTSP

    // One HTSP connection per session, reused across tunes so a zap is just unsubscribe + subscribe.
    private val htspLock = Any()
    @Volatile private var htspConnection: HtspConnection? = null
    @Volatile private var released = false

    private val mediaSourceFactory = if (useHtsp) {
        ProgressiveMediaSource.Factory(
            HtspDataSource.Factory(::connectHtsp) { error -> handler.post { onServerStatus(error) } },
            ExtractorsFactory { arrayOf(HtspExtractor(DefaultSubtitleParserFactory())) },
        )
    } else {
        ProgressiveMediaSource.Factory(
            OkHttpDataSource.Factory(client.http),
            // No FLAG_ALLOW_NON_IDR_KEYFRAMES: start decoding at a real IDR frame, otherwise joining a live
            // stream mid-GOP shows smeared/blocky pictures for the first moments.
            DefaultExtractorsFactory(),
        )
    }

    private val prewarmer = if (useHtsp) NeighbourPrewarmer(context, client, ::connectHtsp) else null

    // No tunneled playback: on the Chromecast it started audio up to ~2 s before the first picture
    // on most channels and failed on mono audio, with no measurable smoothness gain.
    // Dolby passthrough, chosen per track in the TV app's audio menu and remembered (see onSelectTrack).
    private val audioPrefs = context.getSharedPreferences("tvh_audio", Context.MODE_PRIVATE)
    @Volatile private var passthrough = audioPrefs.getBoolean(PREF_PASSTHROUGH, false)
    private val trackSelector = object : DefaultTrackSelector(context) {
        /** Re-runs track selection, so the audio renderers are asked again whether they can play it. */
        fun reselect() = invalidate()
    }

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setRenderersFactory(
            FreshVideoDecoderRenderersFactory(context) { passthrough }.setEnableDecoderFallback(true)
                // Broadcast audio is often MP2/AC3; fall back to FFmpeg when the platform can't decode it.
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setMediaCodecSelector { mime, secure, tunneling ->
                    // The emulator's host-backed decoders (c2.goldfish.*) often never output a frame for
                    // broadcast H.264, so try them last. No effect on real devices.
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                        .sortedBy { it.name.startsWith("c2.goldfish.") }
                }
        )
        .build()
        .also {
            it.addListener(this)
            it.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                    audioStarted = true
                    maybeReveal()
                }
            })
            // Called on the playback thread for every frame about to be shown: the first one after
            // the sound started is timed against the audio clock, i.e. in sync.
            it.setVideoFrameMetadataListener { _, _, _, _ ->
                if (audioStarted && !syncedFrameShown) {
                    syncedFrameShown = true
                    handler.post(::maybeReveal)
                }
            }
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                it.addAnalyticsListener(EventLogger(TAG))
            }
        }

    // Short rebuffers are common on live streams; only show the TV app's spinner if one lasts.
    private val notifyBuffering = Runnable {
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING)
    }

    private var subtitleView: SubtitleView? = null
    private var shutterView: View? = null
    private var shutterVisible = true
    private var statusView: TextView? = null
    private var statusText: String? = null
    private var currentChannelUri: Uri? = null
    private var currentChannelUuid: String? = null
    private var retries = 0

    // Start of playback: the TV app's loading screen (and our shutter) stay up until the sound
    // plays and a video frame timed against it is shown, so the picture never appears frozen or
    // ahead of the audio. Reset by every play().
    /** Whether this playback was shown to the viewer. */
    private var revealed = false
    @Volatile private var audioStarted = false
    @Volatile private var syncedFrameShown = false
    private var firstFrameRendered = false
    /** Shows the picture anyway if the sound never starts (silent stream, audio decoder trouble). */
    private val revealTimeout = Runnable {
        if (!revealed) Log.w(TAG, "Audio didn't start in time: showing the picture anyway")
        reveal()
    }
    /** The surface the TV app gave us last. */
    private var surface: Surface? = null
    /** The last surface the decoder failed on: it's tried again only once. */
    private var failedSurface: Surface? = null
    /** Counts play() calls, so a delayed action can tell its playback was replaced. */
    private var playToken = 0
    private var prewarmedToken = -1

    private val tvInputManager = context.getSystemService(TvInputManager::class.java)
    /** Whether the current channel is meant to play: false until the parental check passes. */
    private var started = false
    private var blockedRating: TvContentRating? = null
    /** Ratings the user unlocked with the PIN on this channel. */
    private val unblockedRatings = HashSet<TvContentRating>()
    private val ratingCheck = Runnable { checkRating() }
    private val ratingSettingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = checkRating()
    }

    init {
        setOverlayViewEnabled(true)
        ContextCompat.registerReceiver(
            context, ratingSettingsReceiver,
            IntentFilter().apply {
                addAction(TvInputManager.ACTION_BLOCKED_RATINGS_CHANGED)
                addAction(TvInputManager.ACTION_PARENTAL_CONTROLS_ENABLED_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED, // still gets these system broadcasts
        )
        // Left over from the per-channel buffer tuning that default buffering replaced.
        for (name in listOf("tvh_unstable_channels", "tvh_start_buffer_htsp", "tvh_start_buffer_http")) {
            context.deleteSharedPreferences(name)
        }
    }

    override fun onTune(channelUri: Uri): Boolean {
        val uuid = channelUuid(channelUri) ?: return false
        currentChannelUri = channelUri
        currentChannelUuid = uuid
        retries = 0
        showStatus(null)
        handler.removeCallbacksAndMessages(null)
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
        setShutter(true)
        started = false
        blockedRating = null
        unblockedRatings.clear()
        checkRating() // starts playback unless parental controls block the program on air
        return true
    }

    override fun onUnblockContent(unblockedRating: TvContentRating) {
        unblockedRatings += unblockedRating
        checkRating()
    }

    /**
     * Parental controls are the TV input's job: when the program on air has a rating the user
     * blocked, stop playing, cover the picture and tell the TV app, which then offers the PIN.
     * Checked on tune, when the next program starts and when the settings change.
     */
    private fun checkRating() {
        handler.removeCallbacks(ratingCheck)
        val uuid = currentChannelUuid ?: return
        val uri = currentChannelUri ?: return
        val now = System.currentTimeMillis()
        val program = currentProgram(uri, now)
        val nextCheck = program?.endMs?.takeIf { it > now } ?: (now + RATING_RECHECK_MS)
        handler.postDelayed(ratingCheck, (nextCheck - now).coerceIn(1_000L, RATING_RECHECK_MS))

        val blocked = if (tvInputManager?.isParentalControlsEnabled == true) {
            program?.ratings?.firstOrNull { it !in unblockedRatings && tvInputManager.isRatingBlocked(it) }
        } else null
        if (blocked != null) {
            if (blocked == blockedRating) return
            Log.i(TAG, "Blocked by parental controls: ${blocked.flattenToString()}")
            blockedRating = blocked
            started = false
            player.stop()
            handler.removeCallbacks(notifyBuffering)
            setShutter(true)
            notifyContentBlocked(blocked)
        } else {
            blockedRating = null
            if (started) return
            started = true
            play(uuid)
            notifyContentAllowed()
        }
    }

    private class Program(val ratings: List<TvContentRating>, val endMs: Long)

    private fun currentProgram(channelUri: Uri, now: Long): Program? = try {
        context.contentResolver.query(
            TvContract.buildProgramsUriForChannel(channelUri, now, now),
            arrayOf(TvContract.Programs.COLUMN_CONTENT_RATING, TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val ratings = c.getString(0).orEmpty().split(',').mapNotNull { flat ->
                flat.trim().takeIf { it.isNotEmpty() }?.let { runCatching { TvContentRating.unflattenFromString(it) }.getOrNull() }
            }
            Program(ratings, c.getLong(1))
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "Couldn't read the program on air: ${e.message}")
        null
    }

    /** Called on the loader thread when an HTSP subscription starts. */
    private fun connectHtsp(): HtspConnection = synchronized(htspLock) {
        if (released) throw IOException("Session released")
        htspConnection?.takeIf { !it.isClosed }?.let { return it }
        HtspConnection.connect(client.config).also {
            htspConnection = it
            if (released) it.close()
        }
    }

    private fun play(uuid: String) {
        if (!started) return // blocked by parental controls: retries and restarts wait for the PIN
        playToken++
        revealed = false
        audioStarted = false
        syncedFrameShown = false
        firstFrameRendered = false
        handler.removeCallbacks(revealTimeout)
        val uri = if (useHtsp) "htsp://channel/${client.htspChannelId(uuid)}" else client.streamUrl(uuid)
        Log.i(TAG, "Playing $uri")
        val source = mediaSourceFactory.createMediaSource(MediaItem.fromUri(uri))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    private fun channelUuid(channelUri: Uri): String? =
        context.contentResolver.query(
            channelUri, arrayOf(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    override fun onSetSurface(surface: Surface?): Boolean {
        this.surface = surface
        handler.removeCallbacks(restoreSurface)
        player.setVideoSurface(surface)
        return true
    }

    /**
     * The TV app's surface can die (Home pressed, activity stopped) before its onSetSurface(null)
     * reaches us, and the decoder then fails on the next frame. That's no playback problem: keep
     * playing without video and give the picture back when the app hands us a surface again.
     */
    private fun isSurfaceLost(error: PlaybackException): Boolean =
        generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<MediaCodec.CodecException>()
            .any { it.message.orEmpty().contains("surface", ignoreCase = true) }

    /** If the app never sends a new surface, the old one may still be fine: try it again. */
    private val restoreSurface = Runnable {
        if (!released) player.setVideoSurface(surface)
    }

    override fun onSetStreamVolume(volume: Float) {
        player.volume = volume
    }

    override fun onSetCaptionEnabled(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .build()
    }

    override fun onSelectTrack(type: Int, trackId: String?): Boolean {
        val trackType = when (type) {
            TvTrackInfo.TYPE_AUDIO -> C.TRACK_TYPE_AUDIO
            TvTrackInfo.TYPE_VIDEO -> C.TRACK_TYPE_VIDEO
            TvTrackInfo.TYPE_SUBTITLE -> C.TRACK_TYPE_TEXT
            else -> return false
        }
        val params = player.trackSelectionParameters.buildUpon()
        var wantPassthrough = passthrough
        if (trackId == null) {
            params.setTrackTypeDisabled(trackType, true)
        } else {
            // The id comes from the TV app: reject anything we didn't hand out instead of crashing.
            val dolby = trackId.endsWith(PASSTHROUGH_SUFFIX)
            val parts = trackId.removeSuffix(PASSTHROUGH_SUFFIX).split(':').mapNotNull { it.toIntOrNull() }
            if (parts.size != 2) return false
            val (groupIndex, trackIndex) = parts
            val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return false
            if (group.type != trackType || trackIndex !in 0 until group.length) return false
            if (dolby && !canPassthrough(group.getTrackFormat(trackIndex))) return false
            // Picking a Dolby entry turns passthrough on, picking any other audio track turns it off.
            if (type == TvTrackInfo.TYPE_AUDIO) wantPassthrough = dolby
            params.setTrackTypeDisabled(trackType, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
        }
        val passthroughChanged = wantPassthrough != passthrough
        if (passthroughChanged) {
            Log.i(TAG, "Dolby passthrough ${if (wantPassthrough) "on" else "off"}")
            passthrough = wantPassthrough
            audioPrefs.edit().putBoolean(PREF_PASSTHROUGH, wantPassthrough).apply()
        }
        player.trackSelectionParameters = params.build()
        // Same track, other output: the audio renderers must be asked again (passthrough or FFmpeg).
        if (passthroughChanged) trackSelector.reselect()
        notifyTrackSelected(type, trackId)
        return true
    }

    override fun onCreateOverlayView(): View {
        val subtitles = SubtitleView(context).also { subtitleView = it }
        val shutter = View(context).apply {
            setBackgroundColor(Color.BLACK)
            visibility = if (shutterVisible) View.VISIBLE else View.GONE
        }.also { shutterView = it }
        val status = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            setBackgroundColor(0xB0000000.toInt())
            setPadding(48, 24, 48, 24)
            text = statusText
            visibility = if (statusText != null) View.VISIBLE else View.GONE
        }.also { statusView = it }
        return FrameLayout(context).apply {
            addView(shutter, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(subtitles, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(status, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            ))
        }
    }

    /** Shows a message over the video, e.g. why tvheadend can't play the channel; null hides it. */
    private fun showStatus(text: String?) {
        statusText = text
        statusView?.apply {
            this.text = text
            visibility = if (text != null) View.VISIBLE else View.GONE
        }
    }

    /** A tvheadend subscription problem (HTSP "subscriptionError"), or null once it cleared. */
    private fun onServerStatus(error: String?) {
        if (error == null) {
            showStatus(null)
            return
        }
        Log.i(TAG, "Server status: $error")
        val (message, reason) = when (error) {
            "noFreeAdapter" -> context.getString(R.string.status_no_tuner) to REASON_INSUFFICIENT_RESOURCE
            "scrambled" -> context.getString(R.string.status_scrambled) to REASON_SCRAMBLED
            "badSignal", "weakStream" -> context.getString(R.string.status_no_signal) to TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL
            "tuningFailed" -> context.getString(R.string.status_tuning_failed) to TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN
            "subscriptionOverridden" -> context.getString(R.string.status_overridden) to REASON_INSUFFICIENT_RESOURCE
            "userLimit", "userAccess" -> context.getString(R.string.status_user_limit) to TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN
            else -> context.getString(R.string.status_other, error) to TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN
        }
        showStatus(message)
        notifyVideoUnavailable(reason)
    }

    /**
     * Playback found no video stream at all: it's a radio channel. Remember it and tell TV apps,
     * which can then show it as audio-only. A sync with HTSP replaces this with tvheadend's own
     * service types; without HTSP the next sync keeps it.
     */
    private fun markRadio(uuid: String, channelUri: Uri) {
        if (uuid in RadioChannels.load(context)) return
        Log.i(TAG, "Channel $uuid has no video: marking it as radio")
        RadioChannels.add(context, uuid)
        val values = ContentValues().apply {
            put(TvContract.Channels.COLUMN_SERVICE_TYPE, TvProviderSync.serviceType(radio = true))
        }
        try {
            context.contentResolver.update(channelUri, values, null, null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't update channel type: ${e.message}")
        }
    }

    /**
     * The surface keeps the previous channel's last frame until the new decoder draws, and
     * not every TV app hides it while tuning, so cover it ourselves until the first new frame.
     */
    private fun setShutter(visible: Boolean) {
        shutterVisible = visible
        shutterView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onRelease() {
        handler.removeCallbacksAndMessages(null)
        context.unregisterReceiver(ratingSettingsReceiver)
        player.removeListener(this)
        player.release()
        prewarmer?.release() // closing the connection below also ends its subscriptions
        released = true
        htspConnection?.close()
    }

    // Player.Listener

    override fun onRenderedFirstFrame() {
        Log.i(TAG, "First video frame rendered")
        firstFrameRendered = true
        maybeReveal()
    }

    /** Shows the picture once audio and video play in sync (see [revealed]). */
    private fun maybeReveal() {
        if (revealed || player.playbackState != Player.STATE_READY) return
        val tracks = player.currentTracks
        if (!tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) return // radio: handled on READY
        val ready = if (tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) syncedFrameShown
            else firstFrameRendered && player.isPlaying // no sound to wait for
        if (ready) reveal()
    }

    private fun reveal() {
        if (revealed) return
        revealed = true
        handler.removeCallbacks(revealTimeout)
        Log.i(TAG, "Picture shown: audio and video playing")
        setShutter(false)
        if (player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) notifyVideoAvailable()
    }

    override fun onPlaybackStateChanged(state: Int) {
        Log.i(TAG, "Playback state $state, video selected=${player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)}")
        when (state) {
            Player.STATE_BUFFERING -> {
                handler.removeCallbacks(notifyBuffering)
                handler.postDelayed(notifyBuffering, BUFFERING_NOTIFY_DELAY_MS)
            }
            Player.STATE_READY -> {
                handler.removeCallbacks(notifyBuffering)
                // Once this channel plays, keep its neighbours tuned for the next zap.
                if (prewarmedToken != playToken) {
                    prewarmedToken = playToken
                    val token = playToken
                    val uuid = currentChannelUuid
                    if (prewarmer != null && uuid != null) {
                        handler.postDelayed({ if (token == playToken) prewarmer.update(uuid) }, PREWARM_DELAY_MS)
                    }
                }
                retries = 0
                showStatus(null)
                if (player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                    if (revealed) {
                        notifyVideoAvailable() // back from a rebuffer
                    } else {
                        maybeReveal()
                        if (!revealed) {
                            handler.removeCallbacks(revealTimeout)
                            handler.postDelayed(revealTimeout, REVEAL_TIMEOUT_MS)
                        }
                    }
                } else {
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY)
                    val uuid = currentChannelUuid
                    val uri = currentChannelUri
                    // Only a stream without any video track is radio, not one whose video can't be decoded.
                    if (uuid != null && uri != null && player.currentTracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }) {
                        markRadio(uuid, uri)
                    }
                }
            }
            Player.STATE_ENDED -> {
                // A live channel never ends: the stream closed or tvheadend changed its streams.
                currentChannelUuid?.let {
                    Log.i(TAG, "Stream ended, restarting channel")
                    play(it)
                }
            }
            else -> Unit
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val uuid = currentChannelUuid ?: return
        if (isSurfaceLost(error)) {
            Log.i(TAG, "Video surface went away: continuing without it")
            // Give a surface one second chance only, or a really dead one would fail forever.
            if (surface != failedSurface) handler.postDelayed(restoreSurface, SURFACE_RESTORE_DELAY_MS)
            failedSurface = surface
            player.clearVideoSurface()
            play(uuid)
            return
        }
        Log.w(TAG, "Playback error: ${error.errorCodeName}", error)
        // tvheadend answers an HTTP stream request it can't serve (no free tuner, ...) with 503.
        val httpCode = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
        if (httpCode == 503) onServerStatus("noFreeAdapter")
        else notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
        if (retries++ < MAX_RETRIES) {
            handler.postDelayed({ if (uuid == currentChannelUuid) play(uuid) }, RETRY_DELAY_MS)
        } else if (statusText == null) {
            showStatus(context.getString(R.string.status_failed))
        }
    }

    override fun onCues(cueGroup: CueGroup) {
        subtitleView?.setCues(cueGroup.cues)
    }

    override fun onTracksChanged(tracks: Tracks) {
        val infos = ArrayList<TvTrackInfo>()
        val selected = HashMap<Int, String>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            val tvType = when (group.type) {
                C.TRACK_TYPE_AUDIO -> TvTrackInfo.TYPE_AUDIO
                C.TRACK_TYPE_VIDEO -> TvTrackInfo.TYPE_VIDEO
                C.TRACK_TYPE_TEXT -> TvTrackInfo.TYPE_SUBTITLE
                else -> return@forEachIndexed
            }
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val id = "$groupIndex:$i"
                val b = TvTrackInfo.Builder(tvType, id)
                f.language?.let { b.setLanguage(it) }
                f.label?.let { b.setDescription(it) }
                when (tvType) {
                    TvTrackInfo.TYPE_AUDIO -> {
                        if (f.channelCount > 0) b.setAudioChannelCount(f.channelCount)
                        if (f.sampleRate > 0) b.setAudioSampleRate(f.sampleRate)
                    }
                    TvTrackInfo.TYPE_VIDEO -> {
                        if (f.width > 0) b.setVideoWidth(f.width)
                        if (f.height > 0) b.setVideoHeight(f.height)
                        if (f.frameRate > 0) b.setVideoFrameRate(f.frameRate)
                        if (f.pixelWidthHeightRatio > 0) b.setVideoPixelAspectRatio(f.pixelWidthHeightRatio)
                    }
                }
                infos += b.build()
                val dolby = tvType == TvTrackInfo.TYPE_AUDIO && canPassthrough(f)
                if (dolby) {
                    // The same track again, sent undecoded to the TV/receiver: "Italiano · Dolby Digital".
                    val ptId = id + PASSTHROUGH_SUFFIX
                    infos += TvTrackInfo.Builder(TvTrackInfo.TYPE_AUDIO, ptId).apply {
                        f.language?.let { setLanguage(it) }
                        setDescription(passthroughLabel(f))
                        if (f.channelCount > 0) setAudioChannelCount(f.channelCount)
                        if (f.sampleRate > 0) setAudioSampleRate(f.sampleRate)
                    }.build()
                    if (group.isTrackSelected(i) && passthrough) selected[tvType] = ptId
                }
                if (group.isTrackSelected(i) && selected[tvType] == null) selected[tvType] = id
            }
        }
        Log.i(TAG, "Audio tracks: " + infos.filter { it.type == TvTrackInfo.TYPE_AUDIO }.joinToString {
            "${it.id}=${it.language}${it.description?.let { d -> " \"$d\"" } ?: ""}"
        } + ", selected ${selected[TvTrackInfo.TYPE_AUDIO]}")
        notifyTracksChanged(infos)
        for (type in listOf(TvTrackInfo.TYPE_AUDIO, TvTrackInfo.TYPE_VIDEO, TvTrackInfo.TYPE_SUBTITLE)) {
            notifyTrackSelected(type, selected[type])
        }
    }

    /** Whether [f] is Dolby audio the HDMI output accepts undecoded. */
    private fun canPassthrough(f: Format): Boolean {
        val encoding = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> C.ENCODING_AC3
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> C.ENCODING_E_AC3
            else -> return false
        }
        return try {
            AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null).supportsEncoding(encoding)
        } catch (e: RuntimeException) {
            false
        }
    }

    /** "Italiano · Dolby Digital 5.1": the language too, as TV apps show the description instead of it. */
    private fun passthroughLabel(f: Format): String {
        val language = f.language?.takeIf { it != "und" && it != "mul" }
            ?.let { Locale.forLanguageTag(it).displayLanguage.replaceFirstChar { c -> c.titlecase() } }
            ?.takeIf { it.isNotBlank() }
        val codec = if (f.sampleMimeType == MimeTypes.AUDIO_AC3) "Dolby Digital" else "Dolby Digital Plus"
        val layout = when (f.channelCount) {
            6 -> " 5.1"
            8 -> " 7.1"
            else -> ""
        }
        return listOfNotNull(language, codec + layout).joinToString(" · ")
    }

    /**
     * Creates a new video decoder whenever the video format changes, i.e. on every zap, instead of
     * flushing and reusing it. A reused Amlogic decoder got stuck in a "resolution changing" state
     * after the first zap and logged ~40 errors/s for as long as the channel played.
     */
    private class FreshVideoDecoderRenderersFactory(
        context: Context,
        private val passthrough: () -> Boolean,
    ) : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) {
            super.buildVideoRenderers(
                context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
            )
            val index = out.indexOfFirst { it is MediaCodecVideoRenderer }
            if (index < 0) return
            val builder = MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
            out[index] = object : MediaCodecVideoRenderer(builder) {
                override fun canReuseCodec(codecInfo: MediaCodecInfo, oldFormat: Format, newFormat: Format) =
                    DecoderReuseEvaluation(
                        codecInfo.name, oldFormat, newFormat,
                        DecoderReuseEvaluation.REUSE_RESULT_NO, DecoderReuseEvaluation.DISCARD_REASON_APP_OVERRIDE,
                    )
            }
        }

        /**
         * PCM output unless the user picked a "Dolby Digital" track (passthrough): AC3/E-AC3 get
         * decoded here (FFmpeg) like MP2. Sent as a bitstream, the TV or receiver muted while
         * switching to Dolby, so the sound started audibly after the picture even though the
         * player's audio clock was already running; fine for those who want surround on a receiver.
         */
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean,
        ): AudioSink {
            val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
                ?: DefaultAudioSink.Builder(context).build()
            return object : ForwardingAudioSink(sink) {
                override fun getFormatSupport(format: Format): Int =
                    if (format.sampleMimeType != MimeTypes.AUDIO_RAW && !passthrough()) AudioSink.SINK_FORMAT_UNSUPPORTED
                    else super.getFormatSupport(format)

                override fun supportsFormat(format: Format) =
                    getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
            }
        }
    }

    private companion object {
        const val TAG = "TvhSession"
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 2000L
        const val SURFACE_RESTORE_DELAY_MS = 1000L
        const val REVEAL_TIMEOUT_MS = 2000L
        const val PREF_PASSTHROUGH = "dolby_passthrough"
        /** Track id suffix of the "Dolby Digital" (passthrough) twin of an AC3/E-AC3 track. */
        const val PASSTHROUGH_SUFFIX = ":dolby"
        const val BUFFERING_NOTIFY_DELAY_MS = 1000L
        const val PREWARM_DELAY_MS = 2000L
        /** Re-checks the rating at least this often, e.g. when the guide arrives after the tune. */
        const val RATING_RECHECK_MS = 5 * 60_000L

        // These reasons exist from Android 11; older TV apps get the generic one.
        val REASON_INSUFFICIENT_RESOURCE =
            if (Build.VERSION.SDK_INT >= 30) TvInputManager.VIDEO_UNAVAILABLE_REASON_INSUFFICIENT_RESOURCE
            else TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN
        val REASON_SCRAMBLED =
            if (Build.VERSION.SDK_INT >= 30) TvInputManager.VIDEO_UNAVAILABLE_REASON_CAS_UNKNOWN
            else TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN
    }
}
