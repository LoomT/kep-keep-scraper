package dev.cse3000.gh.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

class RateLimiter(
    permits: Int = 8,
    private val floor: Int = 100,
) {
    private val semaphore = Semaphore(permits)
    private val gate = Mutex()
    private val remaining = AtomicInteger(Int.MAX_VALUE)
    private val resetAtEpochSec = AtomicLong(0L)
    private val log = LoggerFactory.getLogger(RateLimiter::class.java)

    val remainingSnapshot: Int get() = remaining.get()

    suspend fun <T> withPermit(block: suspend () -> T): T {
        semaphore.acquire()
        try {
            waitIfDepleted()
            return block()
        } finally {
            semaphore.release()
        }
    }

    fun observe(remainingHeader: String?, resetHeader: String?) {
        remainingHeader?.toIntOrNull()?.let { remaining.set(it) }
        resetHeader?.toLongOrNull()?.let { resetAtEpochSec.set(it) }
    }

    private suspend fun waitIfDepleted() {
        if (remaining.get() >= floor) return
        gate.withLock {
            if (remaining.get() >= floor) return@withLock
            val nowSec = System.currentTimeMillis() / 1000
            val wait = (resetAtEpochSec.get() - nowSec + 1).seconds
            if (wait.isPositive()) {
                log.warn("Only {} requests remaining; sleeping {} until reset", remaining.get(), wait)
                delay(wait)
                remaining.set(Int.MAX_VALUE)
            }
        }
    }
}
