package edu.ncku.aiglass

import android.Manifest
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MeetingRuntimeDemoTest {
    @Test fun foregroundDemoSessionStartsStopsAndKeepsMedia() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var runtime: MeetingRuntime
            scenario.onActivity { activity ->
                runtime = (activity.application as MeetingApplication).runtime
                runtime.setDemo(true)
                runtime.connect()
                activity.lifecycleScope.launch { runtime.start(activity, StreamMode.AUDIO, null) }
            }
            waitUntil { runtime.state.value.session.phase == SessionPhase.RECORDING }
            Thread.sleep(4600)
            scenario.onActivity { runtime.stop() }
            waitUntil { runtime.state.value.session.phase == SessionPhase.READY }
            val directory = File(runtime.state.value.savedDirectory)
            assertTrue(directory.isDirectory)
            assertTrue(File(directory, "audio").listFiles()?.any { it.extension == "m4a" } == true)
            assertTrue(runtime.exportTimeline().contains("\"type\":\"stop\""))
            assertTrue(runtime.state.value.pendingAudio > 0)
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        repeat(200) {
            if (predicate()) return
            Thread.sleep(100)
        }
        fail("Timed out waiting for meeting state; latest state was not expected")
    }
}
