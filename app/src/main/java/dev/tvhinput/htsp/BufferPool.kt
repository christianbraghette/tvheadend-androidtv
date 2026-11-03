package dev.tvhinput.htsp

import java.util.concurrent.ArrayBlockingQueue

/**
 * Recycles HTSP message buffers so the reader thread doesn't allocate (and zero) a new array
 * for every packet. Arrays are power-of-two sized; each size class keeps at most ~1 MiB, and
 * buffers above 1 MiB are never pooled. Recycling is optional: a buffer that isn't returned is
 * simply garbage collected.
 */
internal object BufferPool {
    private const val MIN_SHIFT = 12 // 4 KiB
    private const val MAX_SHIFT = 20 // 1 MiB
    private const val BYTES_PER_CLASS = 1 shl 20

    private val classes = Array(MAX_SHIFT - MIN_SHIFT + 1) { i ->
        ArrayBlockingQueue<ByteArray>(maxOf(1, BYTES_PER_CLASS ushr (i + MIN_SHIFT)))
    }

    /** An array of at least [minSize] bytes; its contents are undefined. */
    fun obtain(minSize: Int): ByteArray {
        val shift = maxOf(MIN_SHIFT, 32 - Integer.numberOfLeadingZeros(maxOf(minSize, 1) - 1))
        if (shift > MAX_SHIFT) return ByteArray(minSize)
        return classes[shift - MIN_SHIFT].poll() ?: ByteArray(1 shl shift)
    }

    /** Returns [buf] for reuse; the caller must not touch it afterwards. */
    fun recycle(buf: ByteArray) {
        val shift = Integer.numberOfTrailingZeros(buf.size)
        if (buf.size != 1 shl shift || shift < MIN_SHIFT || shift > MAX_SHIFT) return
        classes[shift - MIN_SHIFT].offer(buf)
    }
}
