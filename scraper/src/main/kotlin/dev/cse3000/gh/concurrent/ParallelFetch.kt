package dev.cse3000.gh.concurrent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow

/**
 * Runs [fetcher] over [items] with up to [concurrency] requests in flight at once,
 * emitting `(item, result)` pairs in completion order (which is generally not the
 * input order — callers that care should re-sort).
 *
 * The global [dev.cse3000.gh.client.RateLimiter] (a Semaphore on the GitHub client)
 * still caps total in-flight HTTP requests across the whole scraper, so passing a
 * larger [concurrency] here just lets one collector saturate the available permits
 * faster — it never exceeds them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T, R> parallelFetch(
    items: Iterable<T>,
    concurrency: Int,
    fetcher: suspend (T) -> R,
): Flow<Pair<T, R>> =
    items.asFlow().flatMapMerge(concurrency) { item ->
        flow { emit(item to fetcher(item)) }
    }
