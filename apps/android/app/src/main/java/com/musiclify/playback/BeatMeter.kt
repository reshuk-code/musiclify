package com.musiclify.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reads the beat from the audio ExoPlayer is playing, with no microphone permission: a
 * [TeeAudioProcessor] hands us the decoded PCM, we low-pass it to the kick/bass range and turn
 * jumps in that energy into a 0..1 pulse that the ambient light reads every frame.
 */
@OptIn(UnstableApi::class)
object BeatMeter : TeeAudioProcessor.AudioBufferSink {

    /** Current pulse, 0 (quiet) to 1 (a hard kick). Written on the audio thread. */
    @Volatile
    var level = 0f
        private set

    private var sampleRate = 44_100
    private var channels = 2
    private var pcm16 = true
    private var lowpassCoeff = coeff(44_100)
    private var lowpass = 0f
    private var average = 0f

    private fun coeff(rate: Int) = (1 - exp(-2 * Math.PI * CUTOFF_HZ / rate)).toFloat()

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = max(1, channelCount)
        pcm16 = encoding == C.ENCODING_PCM_16BIT
        lowpassCoeff = coeff(sampleRateHz)
        lowpass = 0f
        average = 0f
        level = 0f
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!pcm16) return
        val data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frames = data.remaining() / channels
        if (frames == 0) return
        var sum = 0.0
        for (f in 0 until frames) {
            var mono = 0f
            for (c in 0 until channels) mono += data.get()
            mono /= channels * 32768f
            lowpass += lowpassCoeff * (mono - lowpass)
            sum += lowpass * lowpass
        }
        val energy = sqrt(sum / frames).toFloat()
        // A beat is bass energy jumping above its recent average; the pulse then decays.
        average = average * 0.96f + energy * 0.04f
        val kick = max(0f, (energy - average * 1.1f) / max(0.02f, average))
        level = max(level * 0.82f, min(1f, kick * 0.9f))
    }

    private const val CUTOFF_HZ = 150.0
}
