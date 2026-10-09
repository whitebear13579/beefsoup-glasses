package com.beefsoup.ai.glass

import java.nio.ByteBuffer

data class EncodedPacket(
    val bytes: ByteArray,
    val ptsUs: Long,
    val flags: Int,
)

object BufferSlice {
    fun copy(buffer: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int): EncodedPacket {
        require(offset >= 0 && size >= 0 && offset.toLong() + size <= buffer.limit()) {
            "Invalid encoded media buffer range"
        }
        val bytes = ByteArray(size)
        buffer.duplicate().apply {
            position(offset)
            limit(offset + size)
            get(bytes)
        }
        return EncodedPacket(bytes, ptsUs, flags)
    }
}

data class AacConfig(val sampleRate: Int, val channels: Int, val asc: ByteArray)

object AacHeader {
    private val sampleRates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000,
        22050, 16000, 12000, 11025, 8000, 7350)

    fun fromAsc(bytes: ByteArray): AacConfig {
        require(bytes.size >= 2) { "AAC codec configuration is missing" }
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val frequencyIndex = ((first and 7) shl 1) or (second ushr 7)
        val channels = (second ushr 3) and 15
        require(frequencyIndex in sampleRates.indices && channels in 1..2) {
            "Unsupported AAC sample rate or channel count"
        }
        return AacConfig(sampleRates[frequencyIndex], channels, bytes.copyOf())
    }

    fun isAdts(bytes: ByteArray): Boolean = bytes.size >= 7 &&
        (bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xf0) == 0xf0

    fun fromAdts(bytes: ByteArray): AacConfig {
        require(isAdts(bytes)) { "Invalid ADTS header" }
        val profile = ((bytes[2].toInt() ushr 6) and 3) + 1
        val frequencyIndex = (bytes[2].toInt() ushr 2) and 15
        val channels = ((bytes[2].toInt() and 1) shl 2) or ((bytes[3].toInt() ushr 6) and 3)
        val asc = byteArrayOf(
            ((profile shl 3) or (frequencyIndex ushr 1)).toByte(),
            (((frequencyIndex and 1) shl 7) or (channels shl 3)).toByte(),
        )
        return fromAsc(asc)
    }

    fun rawAccessUnit(bytes: ByteArray): ByteArray {
        if (!isAdts(bytes)) return bytes
        val headerSize = if ((bytes[1].toInt() and 1) == 1) 7 else 9
        require(bytes.size > headerSize) { "Empty ADTS frame" }
        return bytes.copyOfRange(headerSize, bytes.size)
    }
}
