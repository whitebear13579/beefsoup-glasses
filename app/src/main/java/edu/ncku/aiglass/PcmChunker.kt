package edu.ncku.aiglass

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

data class PcmChunk(val sequence: Long, val startUs: Long, val pcm16: ByteArray)

/** Converts signed 16-bit interleaved PCM to 16 kHz mono while retaining resampling phase. */
class SpeechPcmConverter(private var sourceRate: Int, private var channels: Int) {
    private var inputFrames = 0L
    private var nextOutputFrame = 0.0
    private var previousSample: Short? = null

    init {
        require(sourceRate > 0 && channels in 1..2)
    }

    fun convert(pcm: ByteArray): ByteArray {
        require(pcm.size % (channels * 2) == 0) { "Incomplete PCM frame" }
        val frameCount = pcm.size / (channels * 2)
        if (frameCount == 0) return ByteArray(0)
        val samples = ShortArray(frameCount)
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            var total = 0
            repeat(channels) { total += input.short.toInt() }
            samples[i] = (total / channels).toShort()
        }
        val output = ByteArrayOutputStream((frameCount * 32000 / sourceRate) + 4)
        val firstIndex = inputFrames
        val lastIndex = firstIndex + frameCount - 1
        val step = sourceRate / 16000.0
        while (nextOutputFrame < lastIndex) {
            val leftIndex = floor(nextOutputFrame).toLong()
            val left = if (leftIndex == firstIndex - 1) previousSample ?: samples[0]
                else samples[(leftIndex - firstIndex).toInt()]
            val right = samples[(leftIndex + 1 - firstIndex).toInt()]
            val weight = nextOutputFrame - leftIndex
            val value = (left * (1.0 - weight) + right * weight).toInt().coerceIn(-32768, 32767)
            output.write(value and 0xff)
            output.write((value ushr 8) and 0xff)
            nextOutputFrame += step
        }
        inputFrames += frameCount
        previousSample = samples.last()
        return output.toByteArray()
    }

    fun finish(): ByteArray {
        val sample = previousSample ?: return ByteArray(0)
        if (nextOutputFrame >= inputFrames) return ByteArray(0)
        return byteArrayOf((sample.toInt() and 0xff).toByte(), (sample.toInt() ushr 8).toByte())
    }
}

/** Emits four-second overlapping chunks to a non-blocking inference sender. */
class PcmChunker(
    private val onChunk: (PcmChunk) -> Unit,
    private val sampleRate: Int = 16000,
    private val chunkSeconds: Int = 4,
    private val overlapMillis: Int = 500,
) {
    private val buffer = ByteArrayOutputStream()
    private val chunkBytes = sampleRate * chunkSeconds * 2
    private val overlapBytes = sampleRate * overlapMillis * 2 / 1000
    private var startUs = 0L
    private var initialized = false
    private var sequence = 0L

    init {
        require(sampleRate > 0 && chunkSeconds > 0 && overlapBytes < chunkBytes)
    }

    fun append(pcm16: ByteArray, ptsUs: Long) {
        require(pcm16.size % 2 == 0)
        if (!initialized) {
            startUs = ptsUs
            initialized = true
        }
        buffer.write(pcm16)
        while (buffer.size() >= chunkBytes) {
            val all = buffer.toByteArray()
            onChunk(PcmChunk(sequence++, startUs, all.copyOfRange(0, chunkBytes)))
            buffer.reset()
            buffer.write(all, chunkBytes - overlapBytes, all.size - chunkBytes + overlapBytes)
            startUs += (chunkBytes - overlapBytes).toLong() * 1_000_000 / (sampleRate * 2)
        }
    }

    fun finish() {
        if (initialized && buffer.size() > overlapBytes) {
            onChunk(PcmChunk(sequence++, startUs, buffer.toByteArray()))
        }
        buffer.reset()
        initialized = false
    }
}
