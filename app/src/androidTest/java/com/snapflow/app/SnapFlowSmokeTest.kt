package com.snapflow.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapFlowSmokeTest {
    @Test
    fun launchActivityAndInitializeMediaEngine() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertNotNull(it.window.decorView) }
        }
        runBlocking {
            withTimeout(120_000L) {
                withContext(Dispatchers.IO) { MediaEngine.ensure(ctx) }
            }
        }
        assertTrue(MediaEngine.isDirect("https://example.org/video.mp4?token=123"))
    }
}
