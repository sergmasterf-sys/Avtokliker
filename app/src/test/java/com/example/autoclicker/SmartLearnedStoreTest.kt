package com.example.autoclicker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.autoclicker.data.SmartLearnedStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmartLearnedStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SmartLearnedStore.init(context)
        SmartLearnedStore.clear()
    }

    @Test
    fun testPutAndGetBuiltinRule() {
        SmartLearnedStore.put(
            screenW = 1920,
            screenH = 1080,
            ruleId = "start",
            multiplier = 1.05f,
            xFrac = 0.25f,
            yFrac = 0.75f,
            score = 0.92f,
            hitCount = 1,
            configName = "builtin"
        )

        val item = SmartLearnedStore.get(1920, 1080, "start", "builtin")
        assertNotNull(item)
        assertEquals(1.05f, item!!.multiplier, 0.001f)
        assertEquals(0.25f, item.xFrac, 0.001f)
        assertEquals(0.75f, item.yFrac, 0.001f)
        assertEquals(0.92f, item.score, 0.001f)
        assertEquals(1, item.hitCount)
        assertTrue(item.recordedAtMs > 0L)
    }

    @Test
    fun testCustomConfigKeySeparation() {
        // Builtin config
        SmartLearnedStore.put(
            screenW = 1920,
            screenH = 1080,
            ruleId = "rule_a",
            multiplier = 1.0f,
            xFrac = 0.1f,
            yFrac = 0.2f,
            score = 0.85f,
            configName = "builtin"
        )

        // Custom config with same rule ID
        SmartLearnedStore.put(
            screenW = 1920,
            screenH = 1080,
            ruleId = "rule_a",
            multiplier = 1.2f,
            xFrac = 0.6f,
            yFrac = 0.8f,
            score = 0.95f,
            configName = "custom_cfg"
        )

        val builtinItem = SmartLearnedStore.get(1920, 1080, "rule_a", "builtin")
        val customItem = SmartLearnedStore.get(1920, 1080, "rule_a", "custom_cfg")

        assertNotNull(builtinItem)
        assertNotNull(customItem)

        assertEquals(0.1f, builtinItem!!.xFrac, 0.001f)
        assertEquals(0.6f, customItem!!.xFrac, 0.001f)
    }

    @Test
    fun testScreenResolutionSeparation() {
        SmartLearnedStore.put(
            screenW = 1920,
            screenH = 1080,
            ruleId = "start",
            multiplier = 1.0f,
            xFrac = 0.3f,
            yFrac = 0.4f,
            score = 0.88f
        )

        val landscape = SmartLearnedStore.get(1920, 1080, "start")
        val portrait = SmartLearnedStore.get(1080, 1920, "start")

        assertNotNull(landscape)
        assertNull(portrait)
    }

    @Test
    fun testClear() {
        SmartLearnedStore.put(
            screenW = 1920,
            screenH = 1080,
            ruleId = "r1",
            multiplier = 1.0f,
            xFrac = 0.5f,
            yFrac = 0.5f,
            score = 0.9f
        )
        assertTrue(SmartLearnedStore.count() > 0)

        SmartLearnedStore.clear()
        assertEquals(0, SmartLearnedStore.count())
        assertNull(SmartLearnedStore.get(1920, 1080, "r1"))
    }
}
