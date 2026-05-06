package dev.cse3000.loader

import java.util.concurrent.atomic.AtomicLong

/**
 * Allocates sequential IDs in the half-open range `[base, base + capacity)`.
 *
 * Convention for this loader: `base = smallestAssignedProjectId * 1_000_000L`,
 * `capacity = 1_000_000L`. With 10 projects total, ranges never overlap, so
 * Person/Organisation/Comment IDs allocated by different contributors never
 * collide.
 */
class IdAllocator(private val base: Long, private val capacity: Long = 1_000_000L) {
    private val next = AtomicLong(base)

    fun nextId(): Long {
        val id = next.getAndIncrement()
        check(id < base + capacity) {
            "IdAllocator exhausted: base=$base capacity=$capacity. Bump capacity in IdAllocator construction."
        }
        return id
    }
}
