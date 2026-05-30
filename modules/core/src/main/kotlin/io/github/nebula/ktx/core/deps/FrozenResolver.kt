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
 * 完全离线的 resolver：只读 [Lockfile]，把坐标映射到 `~/.m2/repository/...`
 * 下的 jar 文件。
 *
 * 与 [RecordingResolver] 的关系：record 是「真实 resolve + 旁路记录」，frozen
 * 是「不调用任何真实 resolver」。所以这两个不会同时使用，传给 main-kts 的
 * resolver 链结构也不同：
 *   - 正常模式：`Compound(FileSystem, Recording(Compound(FileSystem, Maven)))`
 *   - frozen 模式：`Compound(FileSystem, Frozen)`
 *
 * `addRepository`：frozen 模式下脚本声明的 `@file:Repository(...)` 不应触发
 * 任何网络副作用，统一返回 `false`（表示「我不接受这个仓库」），让 main-kts
 * 把 repository 视作「不被任何 resolver 接受」并继续 —— 实际效果是仓库声明
 * 被忽略。这没问题：frozen 模式下我们已经知道每个 jar 在哪，不需要仓库。
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
                "frozen 模式：lockfile 中找不到坐标 $artifactCoordinates。" +
                    "请先在线运行一次以重建 lockfile，或移除 --frozen。",
            )
        val files = rels.map { File(localRepoDir, it) }
        val missing = files.filterNot { it.exists() }
        if (missing.isNotEmpty()) {
            return makeFailureResult(
                "frozen 模式：本地仓库缺失下列 jar：\n" +
                    missing.joinToString("\n") { "  - ${it.absolutePath}" } +
                    "\n请先在线运行一次以填充本地 m2 缓存。",
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
        /** Aether 默认本地仓库就是 `~/.m2/repository`。 */
        fun defaultLocalRepoDir(): File =
            File(System.getProperty("user.home"), ".m2/repository")

        /**
         * 把绝对 jar 路径转成「相对 m2 仓库」的字符串。如果路径不在 m2 之下，
         * 返回 null（调用方自行决定是落绝对路径还是报错）。
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
