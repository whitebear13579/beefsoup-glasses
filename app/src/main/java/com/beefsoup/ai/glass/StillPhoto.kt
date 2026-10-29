package com.beefsoup.ai.glass

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal data class CapturedPhoto(val bytes: ByteArray, val mimeType: String, val width: Int,
    val height: Int, val preview: Bitmap)
internal data class EncodedPhoto(val bytes: ByteArray, val mimeType: String)

/** Let Android identify/decode the SDK image; JPEG marker positions are not a format contract. */
internal fun decodeStillPhoto(bytes: ByteArray, previewOnly: Boolean = true): CapturedPhoto {
    require(bytes.size in 4..30_000_000) { "照片資料大小不正確（${bytes.size} bytes）" }
    var mime = "unknown"
    var width = 0
    var height = 0
    val bitmap = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            mime = info.mimeType
            width = info.size.width
            height = info.size.height
            require(width > 0 && height > 0 && width.toLong() * height <= 24_000_000) { "照片尺寸超出支援範圍（$width × $height）" }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Reject incomplete images instead of silently saving a partly decoded photo.
            decoder.setOnPartialImageListener { false }
            if (previewOnly && maxOf(width, height) > 1600) {
                val scale = 1600.0 / maxOf(width, height)
                decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
            }
        }
    } catch (error: ImageDecoder.DecodeException) {
        val header = bytes.take(8).joinToString(" ") { "%02X".format(it.toInt() and 255) }
        throw IllegalArgumentException("無法解碼 SDK 照片（$mime，${bytes.size} bytes，檔頭 $header，解碼錯誤 ${error.error}）", error)
    }
    return CapturedPhoto(bytes, mime, width, height, bitmap)
}

internal fun encodeStillPhoto(photo: CapturedPhoto, quality: PhotoSaveQuality): EncodedPhoto {
    if (quality == PhotoSaveQuality.ORIGINAL) return EncodedPhoto(photo.bytes, photo.mimeType)
    // Decode the original again at full size. ImageDecoder applies EXIF orientation.
    val full = decodeStillPhoto(photo.bytes, previewOnly = false).preview
    return try {
        val output = ByteArrayOutputStream()
        check(full.compress(Bitmap.CompressFormat.JPEG, quality.jpegQuality!!, output)) { "無法編碼照片" }
        EncodedPhoto(output.toByteArray(), "image/jpeg")
    } finally { full.recycle() }
}

internal fun imageExtension(mime: String): String = when (mime) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/heic" -> "heic"
    "image/heif" -> "heif"
    "image/avif" -> "avif"
    "image/gif" -> "gif"
    "image/bmp", "image/x-ms-bmp" -> "bmp"
    "image/x-icon", "image/vnd.microsoft.icon" -> "ico"
    else -> error("不支援儲存原始格式 $mime，請選擇 JPEG 儲存品質。")
}
