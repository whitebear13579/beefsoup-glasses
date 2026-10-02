package edu.ncku.aiglass

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.htc.viveglass.sdk.StreamingEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DemoAudioPipelineTest {
    @Test fun syntheticSdkCallbacksProducePlayableM4aAndPcmChunk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "demo-pipeline-${System.nanoTime()}")
        val chunks = mutableListOf<PcmChunk>()
        val errors = mutableListOf<String>()
        val finished = CountDownLatch(1)
        val started = CountDownLatch(1)
        val pipeline = AudioPipeline(directory, { synchronized(chunks) { chunks.add(it) } },
            { synchronized(errors) { errors.add(it) } }, {})
        val link = DemoGlassLink()
        link.connect()
        assertTrue(link.start(StreamMode.AUDIO, 1, object : GlassEvents {
            override fun onAudio(epoch: Long, packet: EncodedPacket) {
                assertEquals(1, epoch)
                assertTrue(pipeline.offer(packet))
            }
            override fun onVideo(epoch: Long, packet: EncodedPacket) = Unit
            override fun onStreamEvent(epoch: Long, event: StreamingEvent) {
                if (event == StreamingEvent.STARTED) started.countDown()
            }
        }))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        Thread.sleep(4600)
        link.stop(StreamMode.AUDIO)
        pipeline.finish { finished.countDown() }
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue("PCM chunk missing", chunks.isNotEmpty())
        val segment = directory.listFiles()?.singleOrNull { it.extension == "m4a" }
        assertNotNull("committed M4A missing", segment)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(segment!!.absolutePath)
            assertEquals(1, extractor.trackCount)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC,
                extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME))
        } finally {
            extractor.release()
            directory.deleteRecursively()
        }
    }
}
