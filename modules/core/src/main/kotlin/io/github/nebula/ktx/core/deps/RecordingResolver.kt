package io.github.nebula.ktx.core.deps

import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.dependencies.ArtifactWithLocation
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver.Options
import kotlin.script.experimental.dependencies.RepositoryCoordinates
import java.io.File

/**
 * 包装一个真实的 [ExternalDependenciesResolver]，在解析过程中**旁路记录**
 * 每个顶层坐标解析出来的 jar 文件列表，以及曾经声明过的仓库 URL。
 *
 * 这样一次正常 run 之后，我们就有了构造 lockfile 的全部信息：
 *   - 顶层 `@file:DependsOn(...)` 声明的坐标 → 解析得到的 jar 列表
 *   - `@file:Repository(...)` 声明的所有仓库 URL
 *
 * 实现要点：
 *   - 转发所有方法到 [delegate]，不改变行为；
 *   - 仅追加观察，不参与决策，对 main-kts 透明；
 *   - 线程安全：单脚本的解析并发度极低，但 main-kts 内部对仓库列表使用
 *     synchronized list，这里用同步集合保持一致风格。
 *
 * 注意 [ExternalDependenciesResolver] 接口里有「单坐标」和「批量」两个
 * `resolve`，main-kts 实际调的是哪个不固定，所以两个都要 override。批量
 * 接口默认实现会内部转单坐标，但在 [MavenDependenciesResolver] 里被重写
 * 为一次性 Aether 调用，不能假设。
 */
class RecordingResolver(
    private val delegate: ExternalDependenciesResolver,
) : ExternalDependenciesResolver {

    private val resolved = mutableMapOf<String, List<File>>()
    private val repositories = mutableListOf<String>()

    /** 顶层 coord -> 解析出的 jar 列表（含传递依赖）。 */
    fun snapshot(): Map<String, List<File>> = synchronized(resolved) { resolved.toMap() }

    /** 脚本里声明过的仓库 URL（去重，按声明顺序）。 */
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
        // main-kts 的批量解析会把所有 artifact 一并扔给 Aether，单次返回全部 jar；
        // 我们没法把结果按坐标拆回去。折中：把所有 jar 挂在每个顶层坐标下，
        // 后续 lockfile 写入时去重。Phase 1.2 的可重现性目标对此够用。
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
