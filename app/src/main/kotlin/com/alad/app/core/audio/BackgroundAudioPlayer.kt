package com.alad.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * FIX29 (cách B) — plays the voice-filtered background of the other app's
 * audio (music + effects, dialogue removed by [VocalRemover]) while the
 * translated voice is dubbed over it.
 *
 * The track uses USAGE_ALARM on purpose: in this mode the service mutes
 * the other app by setting STREAM_MUSIC to 0, so anything ALAD plays must
 * leave the music stream — otherwise the background and the dub would be
 * muted along with the original. The alarm stream has its own volume,
 * which AudioPlayerManager sets to match the user's music loudness when
 * the mode starts and restores when it stops.
 *
 * The capture config (see AudioCaptureManager) only matches
 * USAGE_MEDIA/GAME/UNKNOWN, so this USAGE_ALARM output is never
 * re-captured — no feedback loop.
 *
 * [feedPcm] is called from the audio-capture thread; AudioTrack.write
 * blocks when the track buffer is full, which is the backpressure that
 * keeps playback in step with the capture (~100-250 ms behind the
 * picture — unnoticeable for background music and effects).
 */
class BackgroundAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "BackgroundAudioPlayer"
        const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        /** One 10 ms stereo chunk at 48 kHz: 480 frames x 2 ch x 2 bytes. */
        private const val CHUNK_BYTES = 1920
    }

    private var audioTrack: AudioTrack? = null
    private val vocalRemover = VocalRemover()

    @Volatile private var volumeRatio: Float = 1.0f
    @Volatile private var muted: Boolean = false

    /** Fired when the mono guard flips (mono film -> original passes
     * through unfiltered, or back to filtering). */
    var onPassthroughChanged: ((Boolean) -> Unit)? = null

    // Scratch buffers, reused every chunk (capture delivers 10 ms chunks).
    private val shortIn = ShortArray(CHUNK_BYTES / 2)
    private val shortOut = ShortArray(CHUNK_BYTES / 2)
    private val byteOut = ByteArray(CHUNK_BYTES)

    /** True while the input is (near-)mono and the original is passing
     * through unfiltered. */
    val isPassthrough: Boolean get() = vocalRemover.isPassthrough

    fun start() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, CHUNK_BYTES * 6) // ~60 ms of headroom

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        audioTrack?.play()
        applyTrackVolume()
        Log.i(TAG, "Background player started (alarm stream, ${SAMPLE_RATE}Hz stereo)")
    }

    /**
     * Filter one stereo PCM16 chunk (little-endian, 48 kHz) and play it.
     * Any length is accepted; it is processed in 10 ms blocks and a
     * trailing partial frame (fewer than 4 bytes) is dropped.
     */
    fun feedPcm(stereoPcm: ByteArray) {
        val track = audioTrack ?: return
        var offset = 0
        while (offset + 4 <= stereoPcm.size) {
            val blockLen = minOf(CHUNK_BYTES, (stereoPcm.size - offset) / 4 * 4)
            val samples = blockLen / 2
            for (i in 0 until samples) {
                val b = offset + i * 2
                shortIn[i] = ((stereoPcm[b + 1].toInt() shl 8) or (stereoPcm[b].toInt() and 0xFF)).toShort()
            }
            val changed = filterBlock(samples)
            if (changed) {
                try { onPassthroughChanged?.invoke(vocalRemover.isPassthrough) } catch (_: Exception) { }
            }
            // Software gain (same vol^2 curve as the dub voice) + to bytes.
            val vol = if (muted) 0f else volumeRatio.coerceIn(0f, 1f)
            val gain = vol * vol * 1.35f
            for (i in 0 until samples) {
                val scaled = (shortOut[i].toFloat() * gain).coerceIn(-32767f, 32767f).toInt()
                byteOut[i * 2] = (scaled and 0xFF).toByte()
                byteOut[i * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
            }
            try {
                track.write(byteOut, 0, blockLen)
            } catch (e: Exception) {
                Log.w(TAG, "Background write failed", e)
                return
            }
            offset += blockLen
        }
    }

    /**
     * Filter the first [samples] shorts of [shortIn] into [shortOut].
     * VocalRemover works on whole arrays, so a short block is filtered
     * through a right-sized copy; the common 10 ms case uses the scratch
     * arrays directly with no allocation.
     */
    private fun filterBlock(samples: Int): Boolean {
        if (samples == shortIn.size) {
            return vocalRemover.process(shortIn, shortOut)
        }
        val inCopy = ShortArray(samples)
        val outCopy = ShortArray(samples)
        shortIn.copyInto(inCopy, 0, 0, samples)
        val changed = vocalRemover.process(inCopy, outCopy)
        outCopy.copyInto(shortOut, 0, 0, samples)
        return changed
    }

    /** The "original media" slider now drives this background loudness
     * (STREAM_MUSIC stays 0 in this mode). */
    fun setVolume(ratio: Float, isMuted: Boolean) {
        volumeRatio = ratio.coerceIn(0f, 1f)
        muted = isMuted
        applyTrackVolume()
    }

    private fun applyTrackVolume() {
        try {
            audioTrack?.setVolume(if (muted) 0f else volumeRatio.coerceIn(0f, 1f))
        } catch (e: Exception) {
            Log.w(TAG, "Could not set background track volume", e)
        }
    }

    fun stop() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping background track", e)
        }
        audioTrack = null
    }
}
