package io.github.nebula.ktx.core.deps

import io.github.nebula.ktx.core.exec.ScriptRunner
import io.github.nebula.ktx.core.exec.printReports
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.dependencies.CompoundDependenciesResolver
import kotlin.script.experimental.dependencies.FileSystemDependenciesResolver
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver

/**
 * Combines "normal run + observe resolution side-effects" with "write lockfile"
 * into a single action.
 *
 * Shared workflow between `ktx lock` and `ktx run` (which generates/refreshes the lockfile).
 */
object LockingFlow {

    private val log = LoggerFactory.getLogger("ktx.lock")

    /**
     * Run [scriptPath] once while observing main-kts's dependency resolution,
     * then write the recorded info to `<scriptPath>.lock`.
     *
     * @param runner reuses the CLI's ScriptRunner (keeps the cache directory consistent).
     * @param skipExecution compile only (including dependency resolution); don't
     *                      execute the script body. `kts lock` passes true: the
     *                      user just wants to refresh the lockfile, no need to
     *                      actually fire HTTP requests.
     */
    fun lockAndOptionallyRun(
        runner: ScriptRunner,
        scriptPath: Path,
        scriptArgs: Array<String>,
        skipExecution: Boolean,
    ): ResultWithDiagnostics<EvaluationResult> {
        val recording = RecordingResolver(defaultRealResolver())
        val finalPath = scriptPath.toAbsolutePath().normalize()
        val source = finalPath.readBytes()
        val scriptHash = sha256Hex(source)

        // skipExecution mode: replace the script body with `Unit`, keeping
        // only the `@file:` header so main-kts still triggers dependency
        // resolution. Also, to **bypass the compiled-artifact cache** (a cache
        // hit would skip the resolver entirely, leaving RecordingResolver with
        // nothing), use a temporary cache dir for this single run.
        //
        // This is `kts lock`'s core trade-off: the lock operation pays one
        // full compile, in exchange for accurate dependency tracking and zero
        // script side-effects. Subsequent `kts run --frozen` invocations hit
        // the main cache and remain instant.
        val result = if (skipExecution) {
            val wrapped = wrapForLockOnly(source.decodeToString())
            runner.runInline(
                wrapped,
                scriptArgs,
                virtualName = finalPath.fileName.toString(),
                resolver = recording,
                bypassCompiledCache = true,
            )
        } else {
            runner.run(finalPath, scriptArgs, resolver = recording)
        }

        // Always try to write the lockfile, regardless of compile success or
        // failure: a failed compile may have resolved a partial set of deps,
        // and partial info beats nothing. The user can re-run.
        val directs = recording.snapshot().mapValues { (_, files) ->
            files.mapNotNull { f -> FrozenResolver.toM2Relative(f).also { rel ->
                if (rel == null) log.warn("dependency jar is not under ~/.m2/repository, skipping in lockfile: {}", f.absolutePath)
            } }
        }.filterValues { it.isNotEmpty() }

        if (directs.isNotEmpty()) {
            val lockfile = Lockfile(
                scriptHash = scriptHash,
                directs = directs,
                repositories = recording.repositorySnapshot(),
            )
            val lockPath = Lockfile.pathFor(finalPath)
            lockfile.write(lockPath)
            log.info("wrote lockfile: {} ({} top-level deps)", lockPath, directs.size)
        } else {
            log.info("no Maven deps in script; skipping lockfile write")
        }

        return result
    }

    /**
     * Strip the script body but keep header annotations, used by `kts lock`.
     *
     * Strategy: locate the start of the script body (logic equivalent to
     * ToolchainHeaderScanner) and replace everything from there with `Unit`.
     * main-kts still goes through dependency resolution, but the script's
     * side effects (e.g. HTTP requests) don't fire.
     *
     * Phase 1.2 uses the most naive implementation: find the first non-`#!`,
     * non-blank, non-`//`, non-`@file:` line and truncate from there to `Unit`.
     * On failure, fall back to no truncation (full script execution — slower
     * but safe).
     */
    private fun wrapForLockOnly(source: String): String {
        val lines = source.lines()
        var headEnd = lines.size
        for ((idx, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#!")) continue
            if (line.startsWith("//")) continue
            if (line.startsWith("@file:")) continue
            // Block comments, imports, etc. all count as the start of the script body.
            headEnd = idx
            break
        }
        return lines.take(headEnd).joinToString("\n") + "\nUnit\n"
    }

    /**
     * Reconstruct main-kts's default resolver chain: `Compound(FileSystem, Maven)`.
     * We have to assemble this manually because [MainKtsConfigurator] keeps
     * its default resolver private.
     */
    fun defaultRealResolver(): CompoundDependenciesResolver =
        CompoundDependenciesResolver(FileSystemDependenciesResolver(), MavenDependenciesResolver())

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** Compatibility helper: prints the diagnostics on a ResultWithDiagnostics in one call. */
fun ResultWithDiagnostics<EvaluationResult>.printAll() = printReports()
