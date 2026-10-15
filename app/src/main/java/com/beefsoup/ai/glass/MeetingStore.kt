package com.beefsoup.ai.glass

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class MeetingHighlight(val id: String, val sequence: Long, val ptsUs: Long,
    val imagePath: String, val text: String, val rawText: String, val category: String,
    val reason: String, val roi: List<Double>, val model: String,
    val confirmed: Boolean = false, val excluded: Boolean = false)

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

    @Synchronized fun saveHighlight(sequence: Long, frame: VisualFrame, review: VisualReview): MeetingHighlight {
        require(review.keep && frame.jpeg.size <= 512_000 && (review.text.isNotBlank() || frame.manual))
        val id = UUID.randomUUID().toString()
        val folder = File(directory, "images").apply { check(isDirectory || mkdirs()) }
        val file = File(folder, "$id.jpg")
        val partial = File(folder, "$id.jpg.partial")
        try {
            FileOutputStream(partial).use { it.write(frame.jpeg); it.fd.sync() }
            check(partial.renameTo(file)) { "無法保存重點照片" }
            event("highlight", JSONObject().put("id", id).put("sequence", sequence).put("ptsUs", frame.ptsUs)
                .put("image", "images/$id.jpg").put("text", review.text).put("rawText", frame.text)
                .put("category", review.category).put("reason", review.reason).put("model", review.model)
                .put("roi", org.json.JSONArray(review.roi)))
        } catch (error: Exception) {
            partial.delete(); file.delete()
            throw error
        }
        return MeetingHighlight(id, sequence, frame.ptsUs, file.absolutePath, review.text, frame.text,
            review.category, review.reason, review.roi, review.model)
    }

    @Synchronized fun highlights(): List<MeetingHighlight> {
        val result = linkedMapOf<String, MeetingHighlight>()
        events().forEach { event ->
            val data = event.optJSONObject("data") ?: return@forEach
            runCatching {
                val id = data.getString("id")
                when (event.optString("type")) {
                    "highlight" -> {
                        UUID.fromString(id)
                        val relative = data.getString("image")
                        require(relative == "images/$id.jpg")
                        val roi = data.getJSONArray("roi")
                        result[id] = MeetingHighlight(id, data.getLong("sequence"), data.getLong("ptsUs"),
                            File(directory, relative).absolutePath, data.getString("text"), data.getString("rawText"),
                            data.getString("category"), data.getString("reason"), (0..3).map { roi.getDouble(it) },
                            data.optString("model"))
                    }
                    "highlight_update" -> result[id]?.let { prior ->
                        result[id] = prior.copy(text = data.getString("text"),
                            confirmed = data.getBoolean("confirmed"), excluded = data.getBoolean("excluded"))
                    }
                }
            }
        }
        return result.values.toList()
    }

    @Synchronized fun updateHighlight(id: String, text: String, confirmed: Boolean, excluded: Boolean): MeetingHighlight {
        val prior = highlights().first { it.id == id && !it.excluded }
        require(text.length <= 4000 && (!confirmed || text.isNotBlank()))
        event("highlight_update", JSONObject().put("id", id).put("text", text)
            .put("confirmed", confirmed && !excluded).put("excluded", excluded))
        if (excluded) File(prior.imagePath).delete()
        return prior.copy(text = text, confirmed = confirmed && !excluded, excluded = excluded)
    }

    fun confirmedHighlightObservations(): List<Pair<Long, OcrObservation>> = highlights()
        .filter { it.confirmed && !it.excluded }.map { it.sequence to OcrObservation(it.ptsUs, it.text) }

    fun utterances(): List<TranslatedUtterance> = events().filter { it.optString("type") == "translation_utterance" }
        .mapNotNull { runCatching { InferenceProtocol.parseUtterance(it.getJSONObject("data")) }.getOrNull() }
        .distinctBy { it.id }

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
