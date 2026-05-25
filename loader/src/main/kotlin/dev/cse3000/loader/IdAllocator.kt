package dev.cse3000.loader

import java.util.concurrent.atomic.AtomicInteger

/**
 * Allocates sequential IDs in the half-open range `[base, base + capacity)`.
 *
 * Convention for this loader: `base = smallestAssignedProjectId * 1_000_000L`,
 * `capacity = 1_000_000L`. With 10 projects total, ranges never overlap, so
 * Person/Organisation/Comment IDs allocated by different contributors never
 * collide.
 */
class IdAllocator(private val base: Int, private val capacity: Int = 1_000_000) {
    private val next = AtomicInteger(base)

    fun nextId(): Int {
        val id = next.getAndIncrement()
        check(id < base + capacity) {
            "IdAllocator exhausted: base=$base capacity=$capacity. Bump capacity in IdAllocator construction."
        }
        return id
    }
}
