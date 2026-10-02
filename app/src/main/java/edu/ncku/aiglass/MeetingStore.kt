package edu.ncku.aiglass

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Private, append-only timeline. Pending inference audio survives a process restart. */
class MeetingStore private constructor(val directory: File, val id: String) {
    private val timeline = File(directory, "timeline.jsonl")
    private val pending = File(directory, "pending").apply { mkdirs() }

    @Synchronized fun event(type: String, payload: JSONObject) {
        val line = JSONObject()
            .put("type", type)
            .put("sessionId", id)
            .put("recordedAtMs", System.currentTimeMillis())
            .put("data", payload)
            .toString() + "\n"
        FileOutputStream(timeline, true).use { stream ->
            stream.write(line.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    @Synchronized fun savePending(chunk: PcmChunk) {
        val temp = File(pending, "${chunk.sequence}.pcm.partial")
        FileOutputStream(temp).use { stream ->
            stream.write(chunk.pcm16)
            stream.fd.sync()
        }
        check(temp.renameTo(File(pending, "${chunk.sequence}.pcm"))) {
            "無法保存待轉譯音訊"
        }
        val metadata = File(pending, "${chunk.sequence}.json")
        val metadataTemp = File(pending, "${chunk.sequence}.json.partial")
        FileOutputStream(metadataTemp).use { stream ->
            stream.write(JSONObject().put("sequence", chunk.sequence).put("startUs", chunk.startUs)
                .toString().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(metadataTemp.renameTo(metadata)) { "無法保存待轉譯音訊索引" }
        event("audio_pending", JSONObject().put("sequence", chunk.sequence)
            .put("startUs", chunk.startUs).put("bytes", chunk.pcm16.size))
    }

    @Synchronized fun pendingChunks(limit: Int = Int.MAX_VALUE, exclude: Set<Long> = emptySet()): List<PcmChunk> = pending.listFiles()
        ?.filter { it.extension == "json" }
        ?.sortedBy { it.nameWithoutExtension.toLongOrNull() ?: Long.MAX_VALUE }
        ?.filter { (it.nameWithoutExtension.toLongOrNull() ?: -1) !in exclude }
        ?.take(limit)
        ?.mapNotNull { metadata ->
            val pcm = File(pending, "${metadata.nameWithoutExtension}.pcm")
            if (!pcm.isFile) return@mapNotNull null
            runCatching {
                val json = JSONObject(metadata.readText())
                PcmChunk(json.getLong("sequence"), json.getLong("startUs"), pcm.readBytes())
            }.getOrNull()
        }.orEmpty()

    @Synchronized fun pendingCount(): Int = pending.listFiles()
        ?.count { it.extension == "json" && File(pending, "${it.nameWithoutExtension}.pcm").isFile } ?: 0

    @Synchronized fun acknowledge(sequence: Long) {
        File(pending, "$sequence.pcm").delete()
        File(pending, "$sequence.json").delete()
    }

    @Synchronized fun hasPending(sequence: Long, startUs: Long): Boolean {
        val pcm = File(pending, "$sequence.pcm")
        val metadata = File(pending, "$sequence.json")
        return pcm.isFile && metadata.isFile &&
            JSONObject(metadata.readText()).optLong("startUs", -1) == startUs
    }

    fun exportText(): String = timeline.takeIf { it.exists() }?.readText().orEmpty()

    fun delete(context: Context) {
        val root = File(context.filesDir, "meetings").canonicalFile
        val target = directory.canonicalFile
        require(target.parentFile == root && target.name == id) { "會議路徑不在私有資料夾內" }
        check(target.deleteRecursively()) { "無法刪除會議資料" }
    }

    fun events(): List<JSONObject> = timeline.takeIf { it.exists() }
        ?.readLines()?.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.orEmpty()

    fun startEpoch(): Long? = events().firstOrNull { it.optString("type") == "start" }
        ?.optJSONObject("data")?.optLong("epoch")

    fun stoppedCleanly(): Boolean = events().any { it.optString("type") == "stop" }

    fun ocrObservations(): List<Pair<Long, OcrObservation>> = events()
        .filter { it.optString("type") == "ocr" }
        .mapNotNull { event ->
            val data = event.optJSONObject("data") ?: return@mapNotNull null
            if (!data.has("sequence")) return@mapNotNull null
            data.getLong("sequence") to OcrObservation(data.getLong("ptsUs"), data.getString("text"))
        }

    fun transcriptResults(): List<InferenceResult> = events()
        .filter { it.optString("type") == "transcript" }
        .mapNotNull { event ->
            val data = event.optJSONObject("data") ?: return@mapNotNull null
            InferenceResult(data.getLong("sequence"), data.getLong("startUs"),
                data.getString("text"), data.optString("translation"), data.optString("model"))
        }.associateBy { it.sequence }.values.sortedBy { it.sequence }

    companion object {
        fun create(context: Context): MeetingStore {
            val id = UUID.randomUUID().toString()
            val directory = File(context.filesDir, "meetings/$id")
            check(directory.mkdirs()) { "無法建立會議資料夾" }
            return MeetingStore(directory, id)
        }

        fun list(context: Context): List<File> = File(context.filesDir, "meetings")
            .listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() }.orEmpty()

        fun latest(context: Context): MeetingStore? {
            val folder = list(context).firstOrNull() ?: return null
            runCatching { UUID.fromString(folder.name) }.getOrNull() ?: return null
            return MeetingStore(folder, folder.name)
        }
    }
}
