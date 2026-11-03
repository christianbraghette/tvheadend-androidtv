package dev.tvhinput

import android.content.Context
import android.content.pm.ApplicationInfo
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
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
        DefaultExtractorsFactory().setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES),
    )

    private val player: ExoPlayer = ExoPlayer.Builder(context)
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
    private var currentChannelUuid: String? = null
    private var retries = 0

    init {
        setOverlayViewEnabled(true)
    }

    override fun onTune(channelUri: Uri): Boolean {
        val uuid = channelUuid(channelUri) ?: return false
        currentChannelUuid = uuid
        retries = 0
        handler.removeCallbacksAndMessages(null)
        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
        play(uuid)
        notifyContentAllowed()
        return true
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

    override fun onCreateOverlayView(): View = SubtitleView(context).also { subtitleView = it }

    override fun onRelease() {
        handler.removeCallbacksAndMessages(null)
        player.removeListener(this)
        player.release()
    }

    // Player.Listener

    override fun onRenderedFirstFrame() {
        Log.i(TAG, "First video frame rendered")
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
    }
}
