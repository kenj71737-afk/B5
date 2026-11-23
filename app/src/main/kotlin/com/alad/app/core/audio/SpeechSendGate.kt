package com.alad.app.core.audio

/**
 * FIX30: VAD send gate — only forwards captured audio to Gemini once real
 * speech has been observed (at least 150 ms above the speech RMS
 * threshold). Background music, sound effects and room noise never reach
 * the model, so it cannot invent translations for them.
 *
 * Mirrors the v1.7 gate in the Gemini web userscript. The speech span is
 * measured as lastSpeech - utteranceStart (not the utterance's age), so a
 * short transient such as a door slam — only 1-2 frames above the
 * threshold — never opens the gate. The first frames of a sentence are
 * held in a pre-roll buffer and flushed when the span reaches 150 ms, so
 * the start of a sentence is never cut; if the utterance closes first,
 * the pre-roll is dropped.
 *
 * Pure Kotlin, no Android dependencies: the caller passes a monotonic
 * timestamp (SystemClock.elapsedRealtime()), so the logic is unit-testable.
 * All state access is synchronized; the gate is fed from the audio
 * capture threads.
 */
class SpeechSendGate(
    private val sampleRateHz: Int = 16000,
    private val speechRms: Float = 600f / 32768f,
    private val silenceToEndMs: Long = 450L,
    private val minSpeechSpanMs: Long = 150L,
    private val maxPreRollBytes: Int = 16000 // ~500 ms at 16 kHz 16-bit mono
) {
    private var utteranceStartMs: Long = -1L
    private var lastSpeechMs: Long = -1L
    // FIX32: start of the most recent utterance (kept after it closes, so
    // the service can measure per-turn translation latency: first
    // translated audio of a turn minus the utterance's start).
    @Volatile private var lastUtteranceStartMs: Long = -1L
    // FIX34: time of the most recent speech frame (kept after the utterance
    // closes, so the watchdog can tell a quiet scene from a dead key, and
    // can wait for a quiet moment before rotating keys).
    @Volatile private var lastSpeechAtMs: Long = -1L
    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollBytes: Int = 0
    private var sending: Boolean = false

    /**
     * Feed one PCM block (16-bit mono at [sampleRateHz]). Returns the
     * blocks to send to Gemini now (pre-roll flushed + this block), or
     * an empty list when the block is held or dropped.
     */
    @Synchronized
    fun feed(pcm: ByteArray, nowMs: Long): List<ByteArray> {
        val rms = rmsOf(pcm)
        if (rms >= speechRms) {
            if (utteranceStartMs < 0) {
                utteranceStartMs = nowMs
                lastUtteranceStartMs = nowMs
            }
            lastSpeechMs = nowMs
            lastSpeechAtMs = nowMs
        } else if (utteranceStartMs >= 0 && lastSpeechMs >= 0 &&
            nowMs - lastSpeechMs >= silenceToEndMs
        ) {
            utteranceStartMs = -1L
            lastSpeechMs = -1L
        }
        if (utteranceStartMs < 0) {
            // No utterance open: drop any buffered transient, close the gate.
            preRoll.clear()
            preRollBytes = 0
            sending = false
            return emptyList()
        }
        val speechSpan = lastSpeechMs - utteranceStartMs
        if (sending || speechSpan >= minSpeechSpanMs) {
            val out = ArrayList<ByteArray>(preRoll.size + 1)
            out.addAll(preRoll)
            preRoll.clear()
            preRollBytes = 0
            sending = true
            out.add(pcm)
            return out
        }
        preRoll.addLast(pcm)
        preRollBytes += pcm.size
        while (preRollBytes > maxPreRollBytes && preRoll.isNotEmpty()) {
            preRollBytes -= preRoll.removeFirst().size
        }
        return emptyList()
    }

    @Synchronized
    fun reset() {
        utteranceStartMs = -1L
        lastSpeechMs = -1L
        lastUtteranceStartMs = -1L
        lastSpeechAtMs = -1L
        preRoll.clear()
        preRollBytes = 0
        sending = false
    }

    /** Start of the most recent utterance, or -1 if none has been seen. */
    fun lastUtteranceStartMs(): Long = lastUtteranceStartMs

    /** Time of the most recent speech frame, or -1 if none has been seen. */
    fun lastSpeechAtMs(): Long = lastSpeechAtMs

    /** RMS over every 4th sample — plenty for a speech/noise decision. */
    private fun rmsOf(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val low = pcm[i].toInt() and 0xFF
            val high = pcm[i + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            val f = sample / 32768f
            sum += (f * f).toDouble()
            count++
            i += 8
        }
        if (count == 0) return 0f
        return kotlin.math.sqrt(sum / count).toFloat()
    }
}
