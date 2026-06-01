package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.github.nebula.ktx.core.exec.ScriptRunner
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries

/**
 * `ktx cache` subcommand group: inspect and prune the script-compile cache
 * at `~/.cache/ktx/compiled/` (where main-kts writes one jar per compiled
 * script, named by sha256 of source + config).
 *
 * Out of scope: the daemon directory under `~/.cache/ktx/d/` which is
 * managed by `ktx daemon stop`. Pruning a daemon dir while a daemon is
 * running would break the running process; safe cleanup requires liveness
 * detection and is best left to a dedicated subcommand.
 */
class CacheCommand : CliktCommand(name = "cache") {
    override fun help(context: Context): String = "Inspect and prune the script compile cache"
    override fun helpEpilog(context: Context): String = """
        The compile cache lives at ~/.cache/ktx/compiled/ and stores one jar
        per compiled script. The daemon directory (~/.cache/ktx/d/) is not
        managed here — use `ktx daemon stop` instead.
    """.trimIndent()
    override fun run() = Unit
}

class CacheInfoCommand : CliktCommand(name = "info") {
    override fun help(context: Context): String = "Show cache path, entry count, and total size"
    override fun run() {
        val dir = ScriptRunner.defaultCacheDir()
        if (!dir.exists()) {
            echo("path:    $dir")
            echo("(empty: directory does not exist yet)")
            return
        }
        val files = dir.listDirectoryEntries().filter { it.toFile().isFile }
        val total = files.sumOf { it.fileSize() }
        echo("path:     $dir")
        echo("entries:  ${files.size}")
        echo("total:    ${formatBytes(total)}")
        if (files.isNotEmpty()) {
            val oldest = files.minBy { it.getLastModifiedTime().toMillis() }
            val newest = files.maxBy { it.getLastModifiedTime().toMillis() }
            echo("oldest:   ${oldest.fileName}  (${formatTime(oldest)})")
            echo("newest:   ${newest.fileName}  (${formatTime(newest)})")
        }
    }
}

class CacheCleanCommand : CliktCommand(name = "clean") {
    override fun help(context: Context): String = "Delete all cached compiled scripts"
    override fun run() {
        val dir = ScriptRunner.defaultCacheDir()
        if (!dir.exists()) {
            echo("(nothing to clean: $dir does not exist)")
            return
        }
        val files = dir.listDirectoryEntries().filter { it.toFile().isFile }
        if (files.isEmpty()) {
            echo("(nothing to clean: 0 entries)")
            return
        }
        val total = files.sumOf { it.fileSize() }
        for (f in files) Files.deleteIfExists(f)
        echo("removed ${files.size} entries (${formatBytes(total)})")
    }
}

/**
 * `ktx cache gc [--max-size 2GB]`
 *
 * LRU-style eviction. Files are sorted by mtime ascending (oldest first);
 * delete from the head until total size drops to or below `--max-size`.
 * No file-count cap and no max-age — keeping the rule simple. Re-running
 * gc is idempotent.
 */
class CacheGcCommand : CliktCommand(name = "gc") {
    override fun help(context: Context): String = "Evict oldest cache entries until total size is within cap"

    private val maxSizeArg by option(
        "--max-size",
        help = "Cap on total cache size, e.g. 2GB / 500MB / 100KB. Default 2GB.",
    ).default("2GB")

    override fun run() {
        val cap = parseSize(maxSizeArg)
        val dir = ScriptRunner.defaultCacheDir()
        if (!dir.exists()) {
            echo("(nothing to gc: $dir does not exist)")
            return
        }
        val files = dir.listDirectoryEntries()
            .filter { it.toFile().isFile }
            .sortedBy { it.getLastModifiedTime().toMillis() }
        var total = files.sumOf { it.fileSize() }
        if (total <= cap) {
            echo("under cap: ${formatBytes(total)} <= ${formatBytes(cap)}; nothing to evict")
            return
        }
        var removed = 0
        var freed = 0L
        for (f in files) {
            if (total <= cap) break
            val sz = f.fileSize()
            Files.deleteIfExists(f)
            total -= sz
            freed += sz
            removed++
        }
        echo("removed $removed entries, freed ${formatBytes(freed)}, total now ${formatBytes(total)}")
    }
}

// --- helpers (file-private; only this module needs them) ---

/** Parse "2GB" / "500MB" / "100KB" / "1024" into bytes. */
private fun parseSize(spec: String): Long {
    val s = spec.trim().uppercase()
    val (numPart, mul) = when {
        s.endsWith("GB") -> s.dropLast(2) to 1024L * 1024 * 1024
        s.endsWith("MB") -> s.dropLast(2) to 1024L * 1024
        s.endsWith("KB") -> s.dropLast(2) to 1024L
        s.endsWith("G") -> s.dropLast(1) to 1024L * 1024 * 1024
        s.endsWith("M") -> s.dropLast(1) to 1024L * 1024
        s.endsWith("K") -> s.dropLast(1) to 1024L
        s.endsWith("B") -> s.dropLast(1) to 1L
        else -> s to 1L
    }
    val num = numPart.trim().toDoubleOrNull()
        ?: error("cannot parse size '$spec' (expected forms: 2GB, 500MB, 100KB, 1024)")
    require(num >= 0) { "size cannot be negative: $spec" }
    return (num * mul).toLong()
}

/** Render bytes as 1.2MB / 543KB / 17B. Exact powers of 2, not 10. */
private fun formatBytes(bytes: Long): String {
    val abs = kotlin.math.abs(bytes)
    return when {
        abs >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        abs >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        abs >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatTime(p: Path): String =
    timeFormatter.format(Instant.ofEpochMilli(p.getLastModifiedTime().toMillis()))
