package dev.cse3000.gh.scraper

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("dev.cse3000.gh.scraper.StopFileWatcher")

/**
 * Polls [stopFile] every 2s. When it appears, deletes it and cancels the
 * enclosing scope's job — same graceful-shutdown path as a JVM signal.
 *
 * Why this exists: on Windows, Ctrl-C against a Gradle-forked JavaExec rarely
 * propagates, and IntelliJ's stop button uses TerminateProcess, which bypasses
 * JVM shutdown hooks entirely. A polled sentinel file works regardless of OS,
 * Gradle daemon mode, or IDE.
 *
 * To cancel a running scrape:  touch <dataDir>/STOP   (or `New-Item` on Windows)
 * The file is removed automatically once detected.
 */
fun CoroutineScope.launchStopFileWatcher(stopFile: Path): Job {
    val parent = coroutineContext.job
    return launch {
        log.info("Stop-file watcher armed: create {} to gracefully cancel", stopFile)
        while (isActive) {
            if (Files.exists(stopFile)) {
                log.warn("Stop file detected at {} — cancelling scrape gracefully", stopFile)
                runCatching { Files.deleteIfExists(stopFile) }
                parent.cancel(CancellationException("Stop file detected: $stopFile"))
                return@launch
            }
            delay(2.seconds)
        }
    }
}
