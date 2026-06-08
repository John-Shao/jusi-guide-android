package com.jusiai.guidedog.core

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Streams s16le mono PCM to the speaker via one persistent AudioTrack (MODE_STREAM).
 *
 * The track's internal buffer is sized to ~3 s, so writing a whole utterance's PCM
 * usually returns without blocking and the audio drains in the background while the
 * perception loop moves on to the next frame — the same "don't wait for the tail"
 * overlap the device firmware gets from its background player thread. When the
 * buffer does fill (sustained speech), write() blocks, which is correct
 * backpressure (never get more than ~3 s ahead).
 *
 * USAGE_ASSISTANCE_NAVIGATION_GUIDANCE marks this as turn-by-turn guidance audio,
 * so it ducks media and follows the right volume stream.
 */
class AudioPlayer {
    private var track: AudioTrack? = null
    private var sampleRate = 0

    @Synchronized
    fun ensure(sr: Int) {
        if (track != null && sampleRate == sr) return
        release()
        sampleRate = sr
        val minBuf = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufSize = maxOf(minBuf, sr * 2 * 3)  // ~3 s of s16 mono
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufSize)
            .build()
        track?.play()
    }

    /** Append PCM to the playback buffer. Blocks only when the buffer is full. */
    @Synchronized
    fun write(data: ByteArray, len: Int) {
        val t = track ?: return
        var off = 0
        while (off < len) {
            val n = t.write(data, off, len - off)
            if (n <= 0) { Log.w(TAG, "AudioTrack.write returned $n"); break }
            off += n
        }
    }

    @Synchronized
    fun release() {
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
        sampleRate = 0
    }

    private companion object {
        const val TAG = "guide.audio"
    }
}
