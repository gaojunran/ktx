package io.github.nebula.ktx.core.deps

import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.dependencies.ArtifactWithLocation
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver.Options
import kotlin.script.experimental.dependencies.RepositoryCoordinates
import java.io.File

/**
 * Wraps a real [ExternalDependenciesResolver] and **records as a side effect**,
 * during resolution, the jar files produced by each top-level coordinate plus
 * every declared repository URL.
 *
 * After one normal run we therefore have everything needed to build a lockfile:
 *   - top-level `@file:DependsOn(...)` coordinates → resolved jar lists
 *   - all `@file:Repository(...)` URLs declared
 *
 * Implementation notes:
 *   - all methods forward to [delegate]; behavior is unchanged;
 *   - we only observe, never decide — transparent to main-kts;
 *   - thread safety: parallelism is low for a single script's resolution,
 *     but main-kts uses a synchronized list internally for repositories,
 *     so we mirror that style with synchronized collections.
 *
 * The [ExternalDependenciesResolver] interface has both single-coordinate and
 * batch `resolve` overloads, and which one main-kts calls isn't fixed, so we
 * must override both. The batch default delegates to single-coordinate, but
 * in [MavenDependenciesResolver] it's overridden to make a single Aether call,
 * so we can't assume.
 */
class RecordingResolver(
    private val delegate: ExternalDependenciesResolver,
) : ExternalDependenciesResolver {

    private val resolved = mutableMapOf<String, List<File>>()
    private val repositories = mutableListOf<String>()

    /** Top-level coord -> resolved jar list (including transitives). */
    fun snapshot(): Map<String, List<File>> = synchronized(resolved) { resolved.toMap() }

    /** Repository URLs declared in the script (deduplicated, in declaration order). */
    fun repositorySnapshot(): List<String> = synchronized(repositories) { repositories.toList() }

    override fun acceptsArtifact(artifactCoordinates: String): Boolean =
        delegate.acceptsArtifact(artifactCoordinates)

    override fun acceptsRepository(repositoryCoordinates: RepositoryCoordinates): Boolean =
        delegate.acceptsRepository(repositoryCoordinates)

    override suspend fun resolve(
        artifactCoordinates: String,
        options: Options,
        sourceCodeLocation: SourceCode.LocationWithId?,
    ): ResultWithDiagnostics<List<File>> {
        val result = delegate.resolve(artifactCoordinates, options, sourceCodeLocation)
        if (result is ResultWithDiagnostics.Success) {
            synchronized(resolved) { resolved[artifactCoordinates] = result.value }
        }
        return result
    }

    override suspend fun resolve(
        artifactsWithLocations: List<ArtifactWithLocation>,
        options: Options,
    ): ResultWithDiagnostics<List<File>> {
        // main-kts's batch resolver hands all artifacts to Aether at once and
        // returns a single combined jar list; we can't split the result back
        // by coordinate. Compromise: attach every jar to every top-level
        // coordinate, and dedupe later when writing the lockfile. Sufficient
        // for Phase 1.2's reproducibility goal.
        val result = delegate.resolve(artifactsWithLocations, options)
        if (result is ResultWithDiagnostics.Success) {
            val jars = result.value
            synchronized(resolved) {
                for (entry in artifactsWithLocations) {
                    resolved[entry.artifact] = jars
                }
            }
        }
        return result
    }

    override fun addRepository(
        repositoryCoordinates: RepositoryCoordinates,
        options: Options,
        sourceCodeLocation: SourceCode.LocationWithId?,
    ): ResultWithDiagnostics<Boolean> {
        val result = delegate.addRepository(repositoryCoordinates, options, sourceCodeLocation)
        if (result is ResultWithDiagnostics.Success && result.value == true) {
            synchronized(repositories) {
                val s = repositoryCoordinates.string
                if (s !in repositories) repositories.add(s)
            }
        }
        return result
    }
}
