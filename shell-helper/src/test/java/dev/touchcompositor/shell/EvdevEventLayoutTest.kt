package dev.touchcompositor.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class EvdevEventLayoutTest {
    @Test fun acceptsNative32And64BitLayouts() {
        assertEquals(16, EvdevEventLayout.fromReady("EVGRAB_READY /dev/input/event12 timeout=0 event_size=16"))
        assertEquals(24, EvdevEventLayout.fromReady("EVGRAB_READY /dev/input/event2 timeout=0 event_size=24"))
    }
    @Test(expected = IllegalStateException::class)
    fun rejectsUnknownLayout() { EvdevEventLayout.fromReady("EVGRAB_READY event_size=32") }
    @Test(expected = IllegalStateException::class)
    fun rejectsOldHelperWithoutLayout() { EvdevEventLayout.fromReady("EVGRAB_READY /dev/input/event5 timeout=0") }
    @Test(expected = IllegalStateException::class)
    fun preservesPermissionFailure() { EvdevEventLayout.fromReady("open: Permission denied") }
}
