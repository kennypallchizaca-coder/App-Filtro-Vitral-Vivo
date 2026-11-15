package com.example.interciclovision

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

@RunWith(AndroidJUnit4::class)
class NativeEffectInstrumentedTest {
    @Test fun jniProcessesAllViewsWithoutChangingInput() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val rgba = Mat(48, 64, CvType.CV_8UC4, Scalar(70.0, 120.0, 180.0, 255.0))
                val original = rgba.clone()
                val output = Mat()
                try {
                    for (view in 0..8) {
                        val times = activity.procesarFrame(rgba.nativeObjAddr, output.nativeObjAddr,
                            ParametrosMoviles.iniciales(), false, view, false)
                        assertTrue(times[0] > 0)
                        assertEquals(0.0, times[5], 0.0)
                        assertEquals(rgba.size(), output.size())
                        assertEquals(CvType.CV_8UC4, output.type())
                        if (view == 2) assertEquals(0.0, Core.norm(original, output, Core.NORM_INF), 0.0)
                    }
                    assertEquals(0.0, Core.norm(original, rgba, Core.NORM_INF), 0.0)
                    val times = activity.procesarFrame(rgba.nativeObjAddr, output.nativeObjAddr,
                        ParametrosMoviles.iniciales(), true, 1, true)
                    assertTrue(times[0] > 0)
                    if (times[5] == 1.0) assertTrue(times[1] > 0) else assertTrue(times[1].isNaN())
                } finally {
                    rgba.release()
                    original.release()
                    output.release()
                }
            }
        }
    }
}
