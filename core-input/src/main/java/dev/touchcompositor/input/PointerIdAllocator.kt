package dev.touchcompositor.input

/** Stable external-id -> Android pointer-id mapping with collision-free source namespaces. */
class PointerIdAllocator(private val maxPointers: Int = 32) {
    data class Key(val origin: PointerOrigin, val externalId: Int)

    private val ids = linkedMapOf<Key, Int>()

    @Synchronized
    fun acquire(origin: PointerOrigin, externalId: Int): Int {
        val key = Key(origin, externalId)
        ids[key]?.let { return it }
        val used = ids.values.toHashSet()
        val preferredRange = when (origin) {
            PointerOrigin.PHYSICAL -> 0 until minOf(16, maxPointers)
            PointerOrigin.SYNTHETIC, PointerOrigin.PLAYBACK -> minOf(16, maxPointers) until maxPointers
        }
        val id = preferredRange.firstOrNull { it !in used }
            ?: (0 until maxPointers).firstOrNull { it !in used }
            ?: error("No free pointer ids")
        ids[key] = id
        return id
    }

    @Synchronized
    fun release(origin: PointerOrigin, externalId: Int): Int? = ids.remove(Key(origin, externalId))

    @Synchronized
    fun lookup(origin: PointerOrigin, externalId: Int): Int? = ids[Key(origin, externalId)]

    @Synchronized
    fun reset() = ids.clear()
}
