package dev.touchcompositor.input

import org.junit.Assert.*
import org.junit.Test

class PhysicalUiRouterTest {
    private val panel = PhysicalUiRouter.Region("panel", 10f, 10f, 100f, 100f)
    private fun c(x: Float, y: Float, slot: Int = 0, id: Int = 1) = PhysicalUiRouter.Contact(slot, id, x, y)

    @Test fun uiGestureStaysConsumedOutsideItsWindow() {
        val r = PhysicalUiRouter(); r.setRegions(listOf(panel))
        assertEquals("DOWN", r.update(listOf(c(20f, 20f))).single().action)
        assertEquals("MOVE", r.update(listOf(c(200f, 200f))).single().action)
        assertTrue(r.consumes(0))
        assertEquals("UP", r.update(emptyList()).single().action)
        assertFalse(r.consumes(0))
    }

    @Test fun applicationGestureCannotBeStolenByUiOrNewWindows() {
        val r = PhysicalUiRouter()
        assertTrue(r.update(listOf(c(20f, 20f))).isEmpty())
        r.setRegions(listOf(panel))
        assertTrue(r.update(listOf(c(30f, 30f))).isEmpty())
        assertFalse(r.consumes(0))
    }

    @Test fun removingWindowCancelsWithoutLeakingRemainingGesture() {
        val r = PhysicalUiRouter(); r.setRegions(listOf(panel)); r.update(listOf(c(20f, 20f)))
        assertEquals("CANCEL", r.setRegions(emptyList()).single().action)
        assertTrue(r.update(listOf(c(200f, 200f))).isEmpty())
        assertTrue(r.consumes(0))
        assertTrue(r.update(emptyList()).isEmpty())
        assertFalse(r.consumes(0))
    }

    @Test fun topmostWindowWinsAndSlotReuseStartsNewGesture() {
        val r = PhysicalUiRouter(); r.setRegions(listOf(panel, panel.copy(id = "top")))
        assertEquals("top", r.update(listOf(c(20f, 20f))).single().target)
        val events = r.update(listOf(c(200f, 200f, id = 2)))
        assertEquals(listOf("UP"), events.map { it.action })
        assertFalse(r.consumes(0))
    }

    @Test fun secondUiFingerIsConsumedWithoutDuplicateClicks() {
        val r = PhysicalUiRouter(); r.setRegions(listOf(panel))
        r.update(listOf(c(20f, 20f)))
        assertTrue(r.update(listOf(c(20f, 20f), c(30f, 30f, 1, 2))).isEmpty())
        assertTrue(r.consumes(0)); assertTrue(r.consumes(1))
        assertEquals("UP", r.update(listOf(c(30f, 30f, 1, 2))).single().action)
        assertTrue(r.update(listOf(c(40f, 40f, 1, 2))).isEmpty())
        assertTrue(r.consumes(1))
    }

    @Test fun applicationAndUiContactsRemainIndependent() {
        val r = PhysicalUiRouter(); r.setRegions(listOf(panel))
        assertEquals("DOWN", r.update(listOf(c(20f, 20f), c(200f, 200f, 1, 2))).single().action)
        assertTrue(r.consumes(0)); assertFalse(r.consumes(1))
        assertEquals("MOVE", r.update(listOf(c(30f, 30f), c(20f, 20f, 1, 2))).single().action)
        assertFalse(r.consumes(1))
    }
}
