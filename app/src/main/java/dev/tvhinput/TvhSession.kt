package dev.tvhinput

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.media.tv.TvTrackInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.SubtitleView

/** One playback session: plays a tvheadend MPEG-TS stream onto the surface given by the TV app. */
@OptIn(UnstableApi::class)
class TvhSession(
    private val context: Context,
    private val client: TvhClient,
) : TvInputService.Session(context), Player.Listener {

    private val handler = Handler(Looper.getMainLooper())

    private val mediaSourceFactory = ProgressiveMediaSource.Factory(
        OkHttpDataSource.Factory(client.http),
        // No FLAG_ALLOW_NON_IDR_KEYFRAMES: start decoding at a real IDR frame, otherwise joining a live
        // stream mid-GOP shows smeared/blocky pictures for the first moments.
        DefaultExtractorsFactory(),
    )

    private val trackSelector = DefaultTrackSelector(context)

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setTrackSelector(trackSelector)
        // A live stream only arrives in real time, so start with a little cushion and
        // refill more after a stall instead of stuttering at the live edge.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    START_BUFFER_MS,
                    REBUFFER_MS,
                )
                .build()
        )
        .setRenderersFactory(
            DefaultRenderersFactory(context).setEnableDecoderFallback(true)
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
    private var currentChannelUuid: String? = null
    private var retries = 0

    init {
        setOverlayViewEnabled(true)
    }

    override fun onTune(channelUri: Uri): Boolean {
        val uuid = channelUuid(channelUri) ?: return false
        currentChannelUuid = uuid
        retries = 0
        setTunneling(true)
        handler.removeCallbacksAndMessages(null)
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
        setShutter(true)
        play(uuid)
        notifyContentAllowed()
        return true
    }

    /**
     * Hardware A/V sync where the device supports it (e.g. Amlogic); ExoPlayer falls back
     * by itself when a decoder can't tunnel, but not when the audio output can't.
     */
    private fun setTunneling(enabled: Boolean) {
        trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingEnabled(enabled))
    }

    private fun play(uuid: String) {
        Log.i(TAG, "Playing ${client.streamUrl(uuid)}")
        val source = mediaSourceFactory.createMediaSource(MediaItem.fromUri(client.streamUrl(uuid)))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    private fun channelUuid(channelUri: Uri): String? =
        context.contentResolver.query(
            channelUri, arrayOf(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    override fun onSetSurface(surface: Surface?): Boolean {
        player.setVideoSurface(surface)
        return true
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
        if (trackId == null) {
            params.setTrackTypeDisabled(trackType, true)
        } else {
            val (groupIndex, trackIndex) = trackId.split(':').map { it.toInt() }
            val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return false
            params.setTrackTypeDisabled(trackType, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
        }
        player.trackSelectionParameters = params.build()
        notifyTrackSelected(type, trackId)
        return true
    }

    override fun onCreateOverlayView(): View {
        val subtitles = SubtitleView(context).also { subtitleView = it }
        val shutter = View(context).apply {
            setBackgroundColor(Color.BLACK)
            visibility = if (shutterVisible) View.VISIBLE else View.GONE
        }.also { shutterView = it }
        return FrameLayout(context).apply {
            addView(shutter, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(subtitles, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
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
        player.removeListener(this)
        player.release()
    }

    // Player.Listener

    override fun onRenderedFirstFrame() {
        Log.i(TAG, "First video frame rendered")
        setShutter(false)
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
                retries = 0
                if (player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) notifyVideoAvailable()
                else notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY)
            }
            else -> Unit
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.w(TAG, "Playback error: ${error.errorCodeName}", error)
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
        val uuid = currentChannelUuid ?: return
        // Some devices can't open a tunneled audio output for every format (e.g. mono MP2 on
        // Chromecast with Google TV): retry this channel right away without tunneling.
        if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED &&
            trackSelector.parameters.tunnelingEnabled
        ) {
            setTunneling(false)
            play(uuid)
            return
        }
        if (retries++ < MAX_RETRIES) {
            handler.postDelayed({ if (uuid == currentChannelUuid) play(uuid) }, RETRY_DELAY_MS)
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
                if (group.isTrackSelected(i)) selected[tvType] = id
            }
        }
        notifyTracksChanged(infos)
        for (type in listOf(TvTrackInfo.TYPE_AUDIO, TvTrackInfo.TYPE_VIDEO, TvTrackInfo.TYPE_SUBTITLE)) {
            notifyTrackSelected(type, selected[type])
        }
    }

    private companion object {
        const val TAG = "TvhSession"
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 2000L
        const val BUFFERING_NOTIFY_DELAY_MS = 1000L
        const val START_BUFFER_MS = 2500
        const val REBUFFER_MS = 5000
    }
}
