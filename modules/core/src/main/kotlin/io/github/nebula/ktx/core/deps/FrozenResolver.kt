package io.github.nebula.ktx.core.deps

import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.asSuccess
import kotlin.script.experimental.api.makeFailureResult
import kotlin.script.experimental.dependencies.ArtifactWithLocation
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver.Options
import kotlin.script.experimental.dependencies.RepositoryCoordinates
import java.io.File

/**
 * Fully offline resolver: reads only the [Lockfile] and maps coordinates to
 * jar files under `~/.m2/repository/...`.
 *
 * Relationship to [RecordingResolver]: recording does "real resolve + observe
 * on the side"; frozen does "never invoke a real resolver". They are never
 * used together, and the resolver chain handed to main-kts differs:
 *   - normal mode: `Compound(FileSystem, Recording(Compound(FileSystem, Maven)))`
 *   - frozen mode: `Compound(FileSystem, Frozen)`
 *
 * `addRepository`: in frozen mode any `@file:Repository(...)` declared by the
 * script must not cause network side effects, so we always return `false`
 * ("I don't accept this repository"), letting main-kts treat the repository as
 * unaccepted by any resolver and continue — the practical effect is that the
 * declaration is ignored. That's fine: in frozen mode we already know where
 * every jar lives, so repositories are irrelevant.
 */
class FrozenResolver(
    private val lockfile: Lockfile,
    private val localRepoDir: File = defaultLocalRepoDir(),
) : ExternalDependenciesResolver {

    override fun acceptsArtifact(artifactCoordinates: String): Boolean =
        artifactCoordinates in lockfile.directs

    override fun acceptsRepository(repositoryCoordinates: RepositoryCoordinates): Boolean = false

    override suspend fun resolve(
        artifactCoordinates: String,
        options: Options,
        sourceCodeLocation: SourceCode.LocationWithId?,
    ): ResultWithDiagnostics<List<File>> {
        val rels = lockfile.directs[artifactCoordinates]
            ?: return makeFailureResult(
                "frozen mode: coordinate $artifactCoordinates not found in lockfile. " +
                    "Run online once to rebuild the lockfile, or drop --frozen.",
            )
        val files = rels.map { File(localRepoDir, it) }
        val missing = files.filterNot { it.exists() }
        if (missing.isNotEmpty()) {
            return makeFailureResult(
                "frozen mode: the following jars are missing from the local repository:\n" +
                    missing.joinToString("\n") { "  - ${it.absolutePath}" } +
                    "\nRun online once to populate the local m2 cache.",
            )
        }
        return files.asSuccess()
    }

    override suspend fun resolve(
        artifactsWithLocations: List<ArtifactWithLocation>,
        options: Options,
    ): ResultWithDiagnostics<List<File>> {
        val all = mutableListOf<File>()
        val errors = mutableListOf<String>()
        for (entry in artifactsWithLocations) {
            when (val r = resolve(entry.artifact, options, entry.sourceCodeLocation)) {
                is ResultWithDiagnostics.Success -> all.addAll(r.value)
                is ResultWithDiagnostics.Failure -> errors += r.reports.joinToString("\n") { it.message }
            }
        }
        return if (errors.isEmpty()) all.asSuccess() else makeFailureResult(errors.joinToString("\n"))
    }

    override fun addRepository(
        repositoryCoordinates: RepositoryCoordinates,
        options: Options,
        sourceCodeLocation: SourceCode.LocationWithId?,
    ): ResultWithDiagnostics<Boolean> = false.asSuccess()

    companion object {
        /** Aether's default local repository is `~/.m2/repository`. */
        fun defaultLocalRepoDir(): File =
            File(System.getProperty("user.home"), ".m2/repository")

        /**
         * Convert an absolute jar path into a string relative to the m2 repo.
         * Returns null if the path isn't under m2 (the caller decides whether
         * to fall back to absolute paths or raise an error).
         */
        fun toM2Relative(absJar: File, localRepoDir: File = defaultLocalRepoDir()): String? {
            val a = absJar.absoluteFile.toPath()
            val b = localRepoDir.absoluteFile.toPath()
            return runCatching { b.relativize(a).toString() }
                .getOrNull()
                ?.takeIf { !it.startsWith("..") }
        }
    }
}
