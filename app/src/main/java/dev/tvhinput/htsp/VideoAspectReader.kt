package dev.tvhinput.htsp

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import kotlin.math.abs

/**
 * Follows the picture shape a video stream signals in-band (H.264 SPS, MPEG-2 sequence header),
 * read at each keyframe. tvheadend only reports it when the subscription starts, so a channel
 * switching between 4:3 and 16:9 (or SD and HD) mid-programme kept the shape it had at tune-in.
 *
 * The first sighting is the reference: while the stream signals the same as then, tvheadend's
 * aspect stays (it fixed the 960x1080 Rai channels); once it changes, the stream's own is used.
 */
@OptIn(UnstableApi::class)
internal class VideoAspectReader(private val codec: String, private val name: String) {

    private var lastSps: ByteArray? = null
    private var lastMpeg2Code = -1
    private var refSourcePar = 0f
    private var refDeclaredPar = 0f
    private var lastSourcePar = 0f
    private var lastWidth = 0
    private var lastHeight = 0

    /** The format from the keyframe at `data[offset, offset + size)` on: [format] itself unless the picture changed. */
    fun read(format: Format, data: ByteArray, offset: Int, size: Int): Format = when (codec) {
        "H264" -> readH264(format, data, offset, size)
        "MPEG2VIDEO" -> readMpeg2(format, data, offset, size)
        else -> format // HEVC: not followed
    }

    private fun readH264(format: Format, data: ByteArray, offset: Int, size: Int): Format {
        var result = format
        StartCodes.forEachH264Nal(data, offset, size) { start, end, type ->
            if (type != NAL_SPS) return@forEachH264Nal true
            val sps = data.copyOfRange(start, end)
            if (!sps.contentEquals(lastSps)) {
                lastSps = sps
                runCatching { NalUnitUtil.parseSpsNalUnit(sps, 3, sps.size) }.getOrNull()?.let {
                    result = withPicture(format, it.pixelWidthHeightRatio, it.width, it.height)
                }
            }
            false
        }
        return result
    }

    private fun readMpeg2(format: Format, data: ByteArray, offset: Int, size: Int): Format {
        val limit = offset + size
        var i = StartCodes.find(data, offset, limit)
        while (i < limit) {
            if (i + 7 < limit && data[i + 3] == SEQUENCE_HEADER) {
                val b = { k: Int -> data[i + 4 + k].toInt() and 0xff }
                val width = (b(0) shl 4) or (b(1) shr 4)
                val height = ((b(1) and 0x0f) shl 8) or b(2)
                val code = b(3) shr 4
                if (code == lastMpeg2Code || width <= 0 || height <= 0) return format
                lastMpeg2Code = code
                val displayAspect = when (code) {
                    1 -> width.toFloat() / height // square samples
                    2 -> 4f / 3
                    3 -> 16f / 9
                    4 -> 2.21f
                    else -> return format
                }
                return withPicture(format, displayAspect * height / width, width, height)
            }
            i = StartCodes.find(data, i + 3, limit)
        }
        return format
    }

    private fun withPicture(format: Format, sourcePar: Float, width: Int, height: Int): Format {
        if (sourcePar <= 0f || width <= 0 || height <= 0) return format
        if (refSourcePar == 0f) {
            refSourcePar = sourcePar
            refDeclaredPar = format.pixelWidthHeightRatio
        } else if (abs(sourcePar - lastSourcePar) < TOLERANCE && width == lastWidth && height == lastHeight) {
            return format
        }
        lastSourcePar = sourcePar
        lastWidth = width
        lastHeight = height
        val par = if (abs(sourcePar - refSourcePar) < TOLERANCE) refDeclaredPar else sourcePar
        val sameSize = format.width <= 0 || (width == format.width && height == format.height)
        if (abs(par - format.pixelWidthHeightRatio) < TOLERANCE && sameSize) return format
        Log.i(TAG, "$name picture changed: ${format.width}x${format.height} par=${format.pixelWidthHeightRatio} -> ${width}x$height par=$par")
        return format.buildUpon().setWidth(width).setHeight(height).setPixelWidthHeightRatio(par).build()
    }

    private companion object {
        const val TAG = "HtspExtractor"
        const val NAL_SPS = 7
        const val SEQUENCE_HEADER = 0xb3.toByte()
        const val TOLERANCE = 0.01f
    }
}
