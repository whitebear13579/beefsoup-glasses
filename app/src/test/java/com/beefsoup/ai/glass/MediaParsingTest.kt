package com.beefsoup.ai.glass

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class MediaParsingTest {
    @Test fun bufferCopyUsesOffsetSizeAndDoesNotMutateSdkBuffer() {
        val input = ByteBuffer.wrap(byteArrayOf(9, 8, 1, 2, 3, 7))
        input.position(1)
        val packet = BufferSlice.copy(input, 2, 3, 123, 4)
        assertArrayEquals(byteArrayOf(1, 2, 3), packet.bytes)
        assertEquals(1, input.position())
        assertEquals(123, packet.ptsUs)
        assertThrows(IllegalArgumentException::class.java) {
            BufferSlice.copy(input, 4, 3, 0, 0)
        }
    }

    @Test fun aacAscAdtsAndTruncatedHeader() {
        val asc = byteArrayOf(0x14, 0x08)
        val config = AacHeader.fromAsc(asc)
        assertEquals(16000, config.sampleRate)
        assertEquals(1, config.channels)
        val adts = byteArrayOf(0xff.toByte(), 0xf1.toByte(), 0x60, 0x40, 0x01, 0x1f, 0xfc.toByte(), 1, 2)
        assertEquals(16000, AacHeader.fromAdts(adts).sampleRate)
        assertArrayEquals(byteArrayOf(1, 2), AacHeader.rawAccessUnit(adts))
        assertThrows(IllegalArgumentException::class.java) {
            AacHeader.rawAccessUnit(adts.copyOfRange(0, 7))
        }
    }

    @Test fun annexBParameterSetsFoundAcrossStartCodeWidths() {
        val data = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3)
        val found = H264Config.find(data)
        assertEquals(listOf(7, 8), found.map { it.first })
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 1, 2), found[0].second)
    }
}
