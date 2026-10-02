package edu.ncku.aiglass

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Commits ten-second AAC segments as playable M4A files. An interrupted .partial is never reported complete. */
class AudioArchive(
    private val directory: File,
    private val onCommitted: (File) -> Unit,
) {
    private var muxer: MediaMuxer? = null
    private var partial: File? = null
    private var startPtsUs = 0L
    private var lastPtsUs = -1L
    private var index = 0

    init { directory.mkdirs() }

    fun write(config: AacConfig, packet: EncodedPacket) {
        if (packet.bytes.isEmpty()) return
        val accessUnit = AacHeader.rawAccessUnit(packet.bytes)
        if (muxer == null || packet.ptsUs - startPtsUs >= 10_000_000L) {
            closeSegment()
            startSegment(config, packet.ptsUs)
        }
        val pts = (packet.ptsUs - startPtsUs).coerceAtLeast(lastPtsUs + 1)
        lastPtsUs = pts
        val info = MediaCodec.BufferInfo().apply { set(0, accessUnit.size, pts, 0) }
        muxer!!.writeSampleData(0, ByteBuffer.wrap(accessUnit), info)
    }

    fun close() = closeSegment()

    private fun startSegment(config: AacConfig, ptsUs: Long) {
        val file = File(directory, "audio-${index++}.m4a.partial")
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, config.sampleRate, config.channels)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(config.asc))
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
        val created = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        created.addTrack(format)
        created.start()
        muxer = created
        partial = file
        startPtsUs = ptsUs
        lastPtsUs = -1L
    }

    private fun closeSegment() {
        val active = muxer ?: return
        val file = partial ?: return
        muxer = null
        partial = null
        try {
            active.stop()
        } finally {
            active.release()
        }
        val committed = File(directory, file.name.removeSuffix(".partial"))
        check(file.renameTo(committed)) { "Unable to commit AAC segment" }
        onCommitted(committed)
    }
}
