package dev.touchcompositor.input

/** A contact chooses its destination only at DOWN. Removed UI stays consumed until UP. */
class PhysicalUiRouter {
    data class Region(val id: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }
    data class Contact(val slot: Int, val trackingId: Int, val x: Float, val y: Float)
    data class Event(val target: String, val action: String, val x: Float, val y: Float)
    private data class Capture(var contact: Contact, val target: String?, var delivering: Boolean)
    private val captures = linkedMapOf<Int, Capture>()
    private var regions = emptyList<Region>()

    fun setRegions(value: List<Region>): List<Event> {
        regions = value
        val ids = value.map { it.id }.toSet()
        return captures.values.mapNotNull { capture ->
            if (capture.delivering && capture.target !in ids) {
                capture.delivering = false
                Event(capture.target!!, "CANCEL", capture.contact.x, capture.contact.y)
            } else null
        }
    }

    fun update(contacts: List<Contact>): List<Event> = buildList {
        val current = contacts.associateBy { it.slot }
        captures.keys.toList().forEach { slot ->
            val c = captures.getValue(slot)
            if (current[slot]?.trackingId != c.contact.trackingId) {
                if (c.delivering) add(Event(c.target!!, "UP", c.contact.x, c.contact.y))
                captures.remove(slot)
            }
        }
        contacts.forEach { contact ->
            val existing = captures[contact.slot]
            if (existing == null) {
                val target = regions.lastOrNull { it.contains(contact.x, contact.y) }?.id
                // A widget gets one coherent gesture; extra fingers remain consumed.
                val deliver = target != null && captures.values.none { it.target == target }
                captures[contact.slot] = Capture(contact, target, deliver)
                if (deliver) add(Event(target!!, "DOWN", contact.x, contact.y))
            } else {
                if (existing.delivering && (existing.contact.x != contact.x || existing.contact.y != contact.y))
                    add(Event(existing.target!!, "MOVE", contact.x, contact.y))
                existing.contact = contact
            }
        }
    }

    fun consumes(slot: Int): Boolean = captures[slot]?.target != null
    fun cancel(): List<Event> = captures.values.mapNotNull { c ->
        if (c.delivering) Event(c.target!!, "CANCEL", c.contact.x, c.contact.y) else null
    }.also { captures.clear(); regions = emptyList() }
}
