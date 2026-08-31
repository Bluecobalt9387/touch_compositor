package dev.touchcompositor.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchAdbControllerTest {
    @Test
    fun parsesIPv4AndIPv6Endpoints() {
        assertEquals(TouchAdbController.Endpoint("192.168.1.8", 37123), TouchAdbController.parseEndpoint("192.168.1.8:37123"))
        assertEquals(TouchAdbController.Endpoint("fe80::1", 40001), TouchAdbController.parseEndpoint("[fe80::1]:40001"))
        assertNull(TouchAdbController.parseEndpoint("fe80::1:40001"))
        assertNull(TouchAdbController.parseEndpoint("127.0.0.1:not-a-port"))
    }

    @Test
    fun selectsDirectTypeBTouchscreenInsteadOfFingerprintArea() {
        val dump = """
            add device 3: /dev/input/event7
              name:     "vivo_fp"
              events:
                ABS (0003): ABS_MT_POSITION_X     : value 0, min 0, max 1000, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 1000, fuzz 0, flat 0, resolution 0
              input props:
                <none>
            add device 5: /dev/input/event5
              name:     "vivo_ts"
              events:
                ABS (0003): ABS_MT_SLOT           : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_X     : value 0, min 0, max 12599, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 27999, fuzz 0, flat 0, resolution 0
                            ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
                            ABS_MT_PRESSURE       : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
              input props:
                INPUT_PROP_DIRECT
        """.trimIndent()
        val result = TouchAdbController.parseTouchscreen(dump)!!
        assertEquals("/dev/input/event5", result.devicePath)
        assertEquals(12599, result.rawMaxX)
        assertEquals(27999, result.rawMaxY)
        assertTrue(result.hasPressure)
    }

    @Test
    fun rejectsNonTypeBOrIndirectDevices() {
        val dump = """
            add device 1: /dev/input/event4
              events:
                ABS (0003): ABS_MT_POSITION_X     : value 0, min 0, max 12599, fuzz 0, flat 0, resolution 0
                            ABS_MT_POSITION_Y     : value 0, min 0, max 27999, fuzz 0, flat 0, resolution 0
                            ABS_MT_TRACKING_ID    : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
              input props:
                INPUT_PROP_DIRECT
        """.trimIndent()
        assertNull(TouchAdbController.parseTouchscreen(dump))
    }

    @Test
    fun usesOverrideDisplaySizeAndViewportRotation() {
        assertEquals(1080 to 2400, TouchAdbController.parseWmSize("Physical size: 1260x2800\nOverride size: 1080x2400\n"))
        assertEquals(90, TouchAdbController.parseRotation("Viewport INTERNAL: displayId=0, orientation=1, logicalFrame=[0, 0, 2400, 1080]"))
        assertEquals(270, TouchAdbController.parseRotation("Viewport INTERNAL: displayId=0, orientation=3"))
        assertEquals(0, TouchAdbController.parseRotation("Viewport INTERNAL: displayId=0"))
    }
}
