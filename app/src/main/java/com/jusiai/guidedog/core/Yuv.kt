package com.jusiai.guidedog.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * CameraX frame helpers: pull the YUV_420_888 frame out as packed NV21 (so we can
 * close the ImageProxy immediately), run the cheap frame-diff on its luma prefix,
 * and — only when a frame is actually sent — encode it to an upright, downscaled
 * JPEG for upload.
 */
object Yuv {

    /** Copy a CameraX YUV_420_888 frame into a tightly-packed NV21 byte array. */
    fun toNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val out = ByteArray(ySize + ySize / 2)

        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]

        copyPlane(y.buffer, y.rowStride, y.pixelStride, width, height, out, 0, 1)
        // NV21 chroma is interleaved V,U at half resolution.
        val cw = width / 2
        val ch = height / 2
        copyPlane(v.buffer, v.rowStride, v.pixelStride, cw, ch, out, ySize, 2)
        copyPlane(u.buffer, u.rowStride, u.pixelStride, cw, ch, out, ySize + 1, 2)
        return out
    }

    private fun copyPlane(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        w: Int,
        h: Int,
        out: ByteArray,
        offset: Int,
        outStride: Int,
    ) {
        val dup = buffer.duplicate()
        val rowBytes = ByteArray(rowStride)
        var outPos = offset
        for (row in 0 until h) {
            dup.position(row * rowStride)
            val toRead = minOf(rowStride, dup.remaining())
            dup.get(rowBytes, 0, toRead)
            var col = 0
            var inPos = 0
            while (col < w && inPos < toRead) {
                out[outPos] = rowBytes[inPos]
                outPos += outStride
                inPos += pixelStride
                col++
            }
        }
    }

    /**
     * Encode packed NV21 to a JPEG, rotated upright by [rotationDegrees] and scaled
     * so the longest side is <= [maxDim] (0 = no scaling), at [quality] (1..100).
     */
    fun nv21ToJpeg(
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        maxDim: Int,
        quality: Int,
    ): ByteArray? {
        val baos = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), 100, baos)
        val full = baos.toByteArray()

        // Power-of-2 downsample toward maxDim while decoding (cheap).
        val longSide = maxOf(width, height)
        var sample = 1
        if (maxDim > 0) while (longSide / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeByteArray(full, 0, full.size, opts) ?: return null

        // Exact-scale to maxDim and rotate to upright, then re-encode at quality.
        val curLong = maxOf(bmp.width, bmp.height)
        val scale = if (maxDim > 0 && curLong > maxDim) maxDim.toFloat() / curLong else 1f
        if (rotationDegrees != 0 || scale != 1f) {
            val m = Matrix().apply {
                if (scale != 1f) postScale(scale, scale)
                if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
            }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        bmp.recycle()
        return out.toByteArray()
    }
}
