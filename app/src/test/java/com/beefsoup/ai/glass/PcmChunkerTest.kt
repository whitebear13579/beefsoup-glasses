package com.beefsoup.ai.glass

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmChunkerTest {
    @Test fun fourSecondWindowOverlapsAndFlushesOnlyNewTail() {
        val output = mutableListOf<PcmChunk>()
        val chunker = PcmChunker(output::add)
        chunker.append(ByteArray(16000 * 2 * 4), 1_000_000)
        assertEquals(1, output.size)
        assertEquals(0, output[0].sequence)
        assertEquals(1_000_000, output[0].startUs)
        chunker.append(ByteArray(16000 * 2 / 2), 4_500_000)
        chunker.finish()
        assertEquals(2, output.size)
        assertEquals(4_500_000, output[1].startUs)
        assertEquals(16000 * 2, output[1].pcm16.size)
        chunker.finish()
        assertEquals(2, output.size)
    }

    @Test fun converterRetainsResamplingPhaseAcrossBuffers() {
        val input = ByteBuffer.allocate(44100 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(44100) { index ->
            input.putShort(index.toShort())
            input.putShort(index.toShort())
        }
        val all = input.array()
        val whole = SpeechPcmConverter(44100, 2).convert(all)
        val split = SpeechPcmConverter(44100, 2).let { converter ->
            converter.convert(all.copyOfRange(0, 16000)) +
                converter.convert(all.copyOfRange(16000, all.size))
        }
        assertArrayEquals(whole, split)
        assertTrue(whole.size in 31996..32002)
        assertThrows(IllegalArgumentException::class.java) {
            SpeechPcmConverter(16000, 2).convert(byteArrayOf(1, 2))
        }
    }

    @Test fun converterFlushesFinalMonoSampleForExact16kInput() {
        val converter = SpeechPcmConverter(16000, 1)
        assertArrayEquals(byteArrayOf(1, 0, 2, 0), converter.convert(byteArrayOf(1, 0, 2, 0, 3, 0)))
        assertArrayEquals(byteArrayOf(3, 0), converter.finish())
    }
}
