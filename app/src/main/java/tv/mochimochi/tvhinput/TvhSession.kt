package tv.mochimochi.tvhinput

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
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoTimeoutException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.ui.SubtitleView
import tv.mochimochi.tvhinput.htsp.HtspConnection
import tv.mochimochi.tvhinput.htsp.HtspDataSource
import tv.mochimochi.tvhinput.htsp.HtspExtractor
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
            ExtractorsFactory { arrayOf(HtspExtractor(DefaultSubtitleParserFactory(), ::onDualMono)) },
        )
    } else {
        ProgressiveMediaSource.Factory(
            OkHttpDataSource.Factory(client.http),
            // No FLAG_ALLOW_NON_IDR_KEYFRAMES: start decoding at a real IDR frame, otherwise joining a live
            // stream mid-GOP shows smeared/blocky pictures for the first moments.
            DefaultExtractorsFactory(),
        )
    }

    private val prewarmer = if (useHtsp && client.config.prewarm) NeighbourPrewarmer(context, client, ::connectHtsp) else null

    private val audioOutput = AudioOutput(context)
    private val trackMenu = TrackMenu(context, audioOutput)
    private var lastTracks = Tracks.EMPTY
    private val trackSelector = object : DefaultTrackSelector(context) {
        /** Re-runs track selection, so the audio renderers are asked again whether they can play it. */
        fun reselect() = invalidate()
    }

    // No tunneled playback: on the Chromecast it started audio up to ~2 s before the first picture
    // on most channels and failed on mono audio, with no measurable smoothness gain.
    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        .setRenderersFactory(
            PlaybackRenderersFactory(context, audioOutput).setEnableDecoderFallback(true)
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
        .also { player ->
            player.addListener(this)
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                    // Events arrive posted: one from the channel before a zap must not count for the new one.
                    if (eventTime.realtimeMs < playStartedMs) return
                    audioStarted = true
                    maybeReveal()
                }
            })
            // Called on the playback thread for every frame about to be shown: the first one after
            // the sound started is timed against the audio clock, i.e. in sync.
            player.setVideoFrameMetadataListener { _, _, _, _ ->
                if (audioStarted && !syncedFrameShown) {
                    syncedFrameShown = true
                    handler.post(::maybeReveal)
                }
            }
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                player.addAnalyticsListener(EventLogger(TAG))
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
    /** When the current play() started (SystemClock.elapsedRealtime, like player event times). */
    private var playStartedMs = 0L
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
    }

    override fun onTune(channelUri: Uri): Boolean {
        val uuid = channelUuid(channelUri) ?: return false
        currentChannelUri = channelUri
        currentChannelUuid = uuid
        retries = 0
        trackMenu.dualMonoSecond = false
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
        playStartedMs = SystemClock.elapsedRealtime()
        revealed = false
        audioStarted = false
        syncedFrameShown = false
        firstFrameRendered = false
        handler.removeCallbacks(revealTimeout)
        trackMenu.reset()
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
        causes(error).filterIsInstance<MediaCodec.CodecException>()
            .any { it.message.orEmpty().contains("surface", ignoreCase = true) }

    private fun isSurfaceSwapTimeout(error: PlaybackException): Boolean =
        causes(error).filterIsInstance<ExoTimeoutException>()
            .any { it.timeoutOperation == ExoTimeoutException.TIMEOUT_OPERATION_DETACH_SURFACE }

    private fun causes(error: Throwable) = generateSequence(error) { it.cause }

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
        val trackType = TrackMenu.trackType(type) ?: return false
        val params = player.trackSelectionParameters.buildUpon()
        var passthroughChanged = false
        if (trackId == null) {
            params.setTrackTypeDisabled(trackType, true)
        } else {
            // The id comes from the TV app: reject anything we didn't hand out instead of crashing.
            val choice = trackMenu.resolve(player.currentTracks, trackType, trackId) ?: return false
            if (trackType == C.TRACK_TYPE_AUDIO) {
                // A Dolby entry turns passthrough on, any other audio entry turns it off.
                val passthrough = choice.variant == TrackMenu.Variant.PASSTHROUGH
                passthroughChanged = passthrough != audioOutput.passthrough
                if (passthroughChanged) Log.i(TAG, "Dolby passthrough ${if (passthrough) "on" else "off"}")
                audioOutput.passthrough = passthrough
                trackMenu.dualMonoSecond = choice.variant == TrackMenu.Variant.DUAL_MONO_SECOND
            }
            params.setTrackTypeDisabled(trackType, false)
                .setOverrideForType(TrackSelectionOverride(choice.group.mediaTrackGroup, choice.trackIndex))
        }
        player.trackSelectionParameters = params.build()
        // Same track, other output: the audio renderers must be asked again (passthrough or FFmpeg).
        if (passthroughChanged) trackSelector.reselect()
        // The dual-mono side applies at once (same track, no reselection needed).
        audioOutput.dualMono.mode = trackMenu.dualMonoMode(lastTracks)
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
                val tracks = player.currentTracks
                val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                when {
                    tracks.isTypeSelected(C.TRACK_TYPE_VIDEO) -> if (revealed) {
                        notifyVideoAvailable() // back from a rebuffer
                    } else {
                        maybeReveal()
                        if (!revealed) {
                            handler.removeCallbacks(revealTimeout)
                            handler.postDelayed(revealTimeout, REVEAL_TIMEOUT_MS)
                        }
                    }
                    videoGroups.isEmpty() -> {
                        // No video track at all: a radio channel.
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY)
                        val uuid = currentChannelUuid
                        val uri = currentChannelUri
                        if (uuid != null && uri != null) markRadio(uuid, uri)
                    }
                    else -> {
                        if (videoGroups.none { g -> (0 until g.length).any(g::isTrackSupported) }) {
                            // The stream has video, but no decoder on this device can play it (a codec
                            // or profile the hardware lacks): say so instead of a silent black screen.
                            val video = videoGroups.first().getTrackFormat(0)
                            Log.w(TAG, "No decoder for the video: ${video.sampleMimeType} ${video.codecs ?: ""} ${video.width}x${video.height}")
                            showStatus(context.getString(R.string.status_video_unsupported))
                        }
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
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
        // The TV app swapped surfaces while the player was busy (e.g. a decoder error on the old
        // surface, or audio stalled on Bluetooth): Media3 gives up waiting for the old one to be
        // released and stops, but it already uses the new one. Start again on it right away, as a
        // retry so a player that stays stuck still ends in the usual error handling below.
        if (isSurfaceSwapTimeout(error) && retries++ < MAX_RETRIES) {
            Log.i(TAG, "Swapping the video surface timed out: restarting playback on the new one")
            play(uuid)
            return
        }
        Log.w(TAG, "Playback error: ${error.errorCodeName}", error)
        // tvheadend answers an HTTP stream request it can't serve (no free tuner, ...) with 503.
        val httpCode = causes(error).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
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
        lastTracks = tracks
        publishTracks()
    }

    /**
     * The picture's real size and aspect: the TV app sizes the picture from the track info, which
     * is otherwise only sent at tune-in, so a 4:3 <-> 16:9 switch mid-programme must resend it.
     */
    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return // surface going away
        val previous = trackMenu.videoSize
        if (videoSize == previous) return
        trackMenu.videoSize = videoSize
        if (previous != null) Log.i(TAG, "Picture now ${videoSize.width}x${videoSize.height} par=${videoSize.pixelWidthHeightRatio}")
        publishTracks()
    }

    /** Tells the TV app about the tracks of [lastTracks] (see [onTracksChanged]). */
    private fun publishTracks() {
        val menu = trackMenu.build(lastTracks)
        Log.d(TAG, "Audio menu: " + menu.tracks.filter { it.type == TvTrackInfo.TYPE_AUDIO }.joinToString {
            "${it.id}=${it.language}${it.description?.let { d -> " \"$d\"" } ?: ""}"
        } + ", selected ${menu.selected[TvTrackInfo.TYPE_AUDIO]}")
        notifyTracksChanged(menu.tracks)
        for (type in listOf(TvTrackInfo.TYPE_AUDIO, TvTrackInfo.TYPE_VIDEO, TvTrackInfo.TYPE_SUBTITLE)) {
            notifyTrackSelected(type, menu.selected[type])
        }
        audioOutput.dualMono.mode = trackMenu.dualMonoMode(lastTracks)
    }

    /** Called (on the loading thread) by the extractor when an MP2 stream enters or leaves dual mono. */
    private fun onDualMono(streamId: String?, dual: Boolean) {
        handler.post {
            val id = streamId ?: return@post
            if (released) return@post // the extractor may still be reading when the session ends
            val streams = trackMenu.dualMonoStreams
            if (if (dual) streams.add(id) else streams.remove(id)) publishTracks()
        }
    }

    private companion object {
        const val TAG = "TvhSession"
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 2000L
        const val SURFACE_RESTORE_DELAY_MS = 1000L
        const val REVEAL_TIMEOUT_MS = 2000L
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
