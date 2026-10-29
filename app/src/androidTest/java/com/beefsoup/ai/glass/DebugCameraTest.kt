package com.beefsoup.ai.glass

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.htc.viveglass.sdk.StreamingEvent
import com.htc.viveglass.sdk.VideoStreamingFormat.VideoQuality
import com.htc.viveglass.sdk.VideoStreamingFormat.FrameRate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class DebugCameraTest {
    private class FakeLink : GlassLink {
        override val connected = MutableStateFlow(true)
        var permission: CompletableDeferred<Boolean>? = null
        var quality = VideoQuality.DEFAULT
        var frameRate = FrameRate.FPS_15
        var photo = CompletableDeferred<ByteArray>()
        var photoCalls = 0
        var starts = 0
        var stops = 0
        var events: GlassEvents? = null
        var epoch = 0L
        override fun connect() = Unit
        override fun disconnect() = Unit
        override suspend fun ensurePermissions(activity: Activity, mode: StreamMode) = permission?.await() ?: true
        override fun start(mode: StreamMode, epoch: Long, events: GlassEvents, videoQuality: VideoQuality, videoFrameRate: FrameRate): Boolean {
            frameRate = videoFrameRate
            quality = videoQuality
            starts++; this.events = events; this.epoch = epoch
            return true
        }
        override fun stop(mode: StreamMode) { stops++ }
        override suspend fun capturePhoto(): ByteArray { photoCalls++; return photo.await() }
    }

    @Test fun stoppingWhilePermissionIsPendingCannotStartALateStream() = runBlocking {
        withContext(Dispatchers.Main) {
            val source = FakeLink().apply { permission = CompletableDeferred() }
            val controller = DebugCamera(InstrumentationRegistry.getInstrumentation().targetContext, { source }, { true })
            val job = launch { controller.start(Activity()) }
            kotlinx.coroutines.yield()
            assertTrue(controller.state.value.active)
            controller.stop()
            source.permission!!.complete(true)
            job.join()
            assertEquals(0, source.starts)
            assertFalse(controller.state.value.active)
        }
    }

    @Test fun activeMeetingBlocksPreviewAndLateEventsCannotStopANewPreview() = runBlocking {
        val source = FakeLink()
        var available = false
        val controller = DebugCamera(InstrumentationRegistry.getInstrumentation().targetContext, { source }, { available })
        withContext(Dispatchers.Main) {
            controller.start(Activity())
            assertEquals(0, source.starts)
            available = true
            controller.start(Activity())
            assertTrue(controller.state.value.active)
            val oldEvents = source.events!!
            val oldEpoch = source.epoch
            controller.stop()
            assertEquals(1, source.stops)
            controller.start(Activity())
            oldEvents.onStreamEvent(oldEpoch, StreamingEvent.STOPPED)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        withContext(Dispatchers.Main) {
            try { assertTrue(controller.state.value.active) }
            finally { controller.stop() }
        }
    }

    @Test fun selectedQualityReachesStreamAndSettingsStayLockedUntilStopped() = runBlocking {
        withContext(Dispatchers.Main) {
            val source = FakeLink()
            val controller = DebugCamera(InstrumentationRegistry.getInstrumentation().targetContext, { source }, { true })
            controller.setQuality(VideoQuality.FULL_HD)
            controller.setPreviewFps(10)
            try {
                controller.start(Activity())
                assertEquals(VideoQuality.FULL_HD, source.quality)
                controller.setQuality(VideoQuality.LOW)
                controller.setPreviewFps(5)
                assertEquals(VideoQuality.FULL_HD, controller.state.value.quality)
                assertEquals(10, controller.state.value.previewFps)
                controller.stop()
                controller.setQuality(VideoQuality.LOW)
                controller.setPreviewFps(30)
                controller.start(Activity())
                assertEquals(VideoQuality.LOW, source.quality)
                assertEquals(30, controller.state.value.previewFps)
                assertEquals(FrameRate.FPS_30, source.frameRate)
            } finally { controller.stop() }
        }
    }

    @Test fun originalStillCapturePreservesJpegAndCompressionChoicesRemainReadable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it) }.toByteArray()
        bitmap.recycle()
        val photo = decodeStillPhoto(bytes)
        assertArrayEquals(bytes, encodeStillPhoto(photo, PhotoSaveQuality.ORIGINAL).bytes)
        for (quality in listOf(PhotoSaveQuality.HIGH, PhotoSaveQuality.SMALL)) {
            val encoded = encodeStillPhoto(photo, quality).bytes
            val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
            assertEquals(96, decoded.width); assertEquals(64, decoded.height); decoded.recycle()
        }
        val source = FakeLink().apply { this.photo.complete(bytes) }
        val controller = DebugCamera(context, { source }, { true })
        withContext(Dispatchers.Main) { controller.captureStill(Activity()) }
        assertNull("Capture must not save automatically", controller.state.value.savedUri)
        assertNotNull(controller.state.value.photoPreview)
        withContext(Dispatchers.Main) { controller.saveStill() }
        val uri = controller.state.value.savedUri!!
        try {
            assertEquals(1, source.photoCalls)
            assertFalse(controller.state.value.takingPhoto)
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun pngCapturePreviewsBeforeSavingWithCorrectOriginalMimeAndExtension() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val png = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val source = FakeLink().apply { this.photo.complete(png) }
        val controller = DebugCamera(context, { source }, { true })
        withContext(Dispatchers.Main) { controller.captureStill(Activity()) }
        assertNull(controller.state.value.savedUri)
        assertTrue(controller.state.value.photoInfo.contains("image/png"))
        withContext(Dispatchers.Main) { controller.saveStill() }
        val uri = controller.state.value.savedUri!!
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DISPLAY_NAME), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals("image/png", it.getString(0)); assertTrue(it.getString(1).endsWith(".png"))
            }
            assertArrayEquals(png, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun validPaddedJpegIsAcceptedAndGarbageIsRejected() {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val jpeg = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        bitmap.recycle()
        val padded = jpeg + ByteArray(16)
        val photo = decodeStillPhoto(padded)
        assertEquals("image/jpeg", photo.mimeType)
        assertEquals(64, photo.preview.width)
        assertArrayEquals(padded, encodeStillPhoto(photo, PhotoSaveQuality.ORIGINAL).bytes)
        photo.preview.recycle()
        try { decodeStillPhoto(ByteArray(128)); fail("Garbage accepted") } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("無法解碼"))
        }
    }

    @Test fun discardPreviewDoesNotPublishAnImage() = runBlocking {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        bitmap.recycle()
        val source = FakeLink().apply { this.photo.complete(bytes) }
        val controller = DebugCamera(InstrumentationRegistry.getInstrumentation().targetContext, { source }, { true })
        withContext(Dispatchers.Main) { controller.captureStill(Activity()); controller.discardStill(); controller.saveStill() }
        assertNull(controller.state.value.photoPreview)
        assertNull(controller.state.value.savedUri)
    }

    @Test fun leavingDuringStillCaptureDiscardsTheLatePhoto() = runBlocking {
        withContext(Dispatchers.Main) {
            val source = FakeLink()
            val controller = DebugCamera(InstrumentationRegistry.getInstrumentation().targetContext, { source }, { true })
            val job = launch { controller.captureStill(Activity()) }
            kotlinx.coroutines.yield()
            assertTrue(controller.state.value.takingPhoto)
            controller.start(Activity())
            assertEquals(0, source.starts)
            controller.stop()
            source.photo.complete(byteArrayOf(1, 2, 3))
            job.join()
            assertNull(controller.state.value.savedUri)
            assertFalse(controller.state.value.takingPhoto)
        }
    }

    @Test fun capturePublishesReadableJpegToGallery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val frame = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        val uri = saveDebugPhoto(context, frame)
        try {
            context.contentResolver.openInputStream(uri).use {
                val saved = BitmapFactory.decodeStream(it)
                assertNotNull(saved)
                assertEquals(96, saved.width)
                assertEquals(64, saved.height)
                saved.recycle()
            }
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.RELATIVE_PATH), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
                assertEquals("Pictures/AI Glass Debug/", it.getString(1))
            }
        } finally {
            context.contentResolver.delete(uri, null, null)
            frame.recycle()
        }
    }
}
