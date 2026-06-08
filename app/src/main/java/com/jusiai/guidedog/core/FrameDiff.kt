package com.jusiai.guidedog.core

import kotlin.math.abs

/**
 * Skip static scenes. Reduces each frame's luma to a 16x16 grid of block averages
 * (kills sensor noise) and compares the mean-abs-difference against the last
 * frame that was actually SENT. Below [threshold] the scene counts as unchanged
 * and the cycle is skipped; after [forceMs] of stillness it re-sends anyway as a
 * safety valve. Mirrors the device firmware's frame_change.cc.
 *
 * Not thread-safe; call from a single loop.
 */
class FrameDiff(private val threshold: Int, private val forceMs: Long) {
    private var lastSig: IntArray? = null   // 256 block averages of the last SENT frame
    private var lastSentAt = 0L

    /** @return (send, mad). The baseline updates only when send==true. */
    fun shouldSend(luma: ByteArray, width: Int, height: Int, rowStride: Int, nowMs: Long): Pair<Boolean, Int> {
        val sig = signature(luma, width, height, rowStride)
        val prev = lastSig
        if (prev == null) {                 // first frame always sends
            lastSig = sig; lastSentAt = nowMs
            return true to 0
        }
        var sum = 0
        for (i in sig.indices) sum += abs(sig[i] - prev[i])
        val mad = sum / sig.size
        val forced = forceMs > 0 && (nowMs - lastSentAt) >= forceMs
        val send = mad > threshold || forced
        if (send) { lastSig = sig; lastSentAt = nowMs }
        return send to mad
    }

    private fun signature(y: ByteArray, w: Int, h: Int, rowStride: Int): IntArray {
        val out = IntArray(GRID * GRID)
        for (by in 0 until GRID) {
            val y0 = by * h / GRID
            val y1 = (by + 1) * h / GRID
            for (bx in 0 until GRID) {
                val x0 = bx * w / GRID
                val x1 = (bx + 1) * w / GRID
                var sum = 0L
                var count = 0
                var yy = y0
                while (yy < y1) {
                    val base = yy * rowStride
                    var xx = x0
                    while (xx < x1) {
                        sum += (y[base + xx].toInt() and 0xFF)
                        count++
                        xx++
                    }
                    yy++
                }
                out[by * GRID + bx] = if (count > 0) (sum / count).toInt() else 0
            }
        }
        return out
    }

    private companion object {
        const val GRID = 16
    }
}
