package io.github.nebula.ktx.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.nebula.ktx.core.compile.CompileFlow
import io.github.nebula.ktx.core.compile.NativeImageFlow
import io.github.nebula.ktx.core.compile.SelfContainedFlow
import io.github.nebula.ktx.core.exec.printReports
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.walk
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.system.exitProcess

/**
 * `ktx compile <script> [-o <output>] [--self-contained | --native] [--collect-metadata]`
 *
 * Three output flavours, mutually exclusive:
 *
 * - **fat jar (default)** — single jar runnable via `java -jar`. Default output
 *   `<script>.jar` next to the script.
 * - **`--self-contained`** — directory `<output>/{bin/<name>, lib/app.jar, runtime/}`
 *   with a jlink-built minimal JRE. End users only need to run the launcher.
 * - **`--native`** — single native binary built by GraalVM `native-image`. ~10-20 MB,
 *   ~10-50 ms cold start. End users need nothing — just run the file.
 *
 * The `--collect-metadata` flag only applies with `--native`: it runs the fat jar
 * once on the JVM with `native-image-agent` first to capture reflection traces,
 * persisting them under `<script>.native-meta/` for re-use.
 *
 * Both `--self-contained` and `--native` only target the host platform (jlink and
 * native-image both can't cross-compile). For multi-platform distribution, build
 * each platform on its own runner.
 */
class CompileCommand : CliktCommand(name = "compile") {

    private val scriptArg by argument(name = "SCRIPT", help = "Script path to compile")

    private val outputOption by option(
        "-o", "--output",
        help = "Output path. With --self-contained this is a directory; with --native a binary; " +
            "otherwise a jar file (defaults to <script>.jar / <script>/ / <script> next to the script).",
    )

    private val selfContained by option(
        "--self-contained",
        help = "Bundle a minimal JRE alongside the fat jar so end users do not need Java installed. " +
            "Output is a directory tree (host platform only).",
    ).flag()

    private val nativeImage by option(
        "--native",
        help = "Build a single-file native binary via GraalVM native-image. " +
            "Requires `native-image` on PATH or under \$GRAALVM_HOME / \$JAVA_HOME. " +
            "Produces a ~10-20 MB binary that needs no JRE. Mutually exclusive with --self-contained.",
    ).flag()

    private val collectMetadata by option(
        "--collect-metadata",
        help = "Only meaningful with --native: run the fat jar once under native-image-agent " +
            "to capture reflection / resource accesses before native-image starts. The captured " +
            "metadata is saved to <script>.native-meta/ and re-used on subsequent builds.",
    ).flag()

    override fun run() {
        val scriptPath = Path(scriptArg)
        require(scriptPath.exists() && scriptPath.isRegularFile()) { "script not found: $scriptArg" }
        require(!(selfContained && nativeImage)) {
            "--self-contained and --native are mutually exclusive"
        }
        require(!(collectMetadata && !nativeImage)) {
            "--collect-metadata only applies with --native"
        }

        when {
            selfContained -> runSelfContained(scriptPath)
            nativeImage -> runNative(scriptPath)
            else -> runFatJar(scriptPath)
        }
    }

    private fun runFatJar(scriptPath: Path) {
        val outputJar = outputOption?.let { Path(it) }
            ?: scriptPath.resolveSibling(stripScriptSuffix(scriptPath.fileName.toString()) + ".jar")
        echo("compiling ${scriptPath.fileName} -> $outputJar")
        val result = CompileFlow().compile(scriptPath, outputJar)
        result.printReports()
        if (result is ResultWithDiagnostics.Failure) exitProcess(1)
        val sizeKb = outputJar.toFile().length() / 1024
        echo("artifact: $outputJar ($sizeKb KB)")
        echo("run with: java -jar $outputJar [args...]")
    }

    private fun runSelfContained(scriptPath: Path) {
        val outputDir = outputOption?.let { Path(it) }
            ?: scriptPath.resolveSibling(stripScriptSuffix(scriptPath.fileName.toString()))
        // Mirror SelfContainedFlow's pre-check up here so users get the
        // error before we kick off the (slow) compile.
        require(!outputDir.exists() || (outputDir.isDirectory() && outputDir.listDirectoryEntries().isEmpty())) {
            "output directory must be empty or non-existent: $outputDir " +
                "(remove it or pass a different -o)"
        }

        echo("compiling ${scriptPath.fileName} -> $outputDir/ (self-contained)")
        val result = SelfContainedFlow().produce(scriptPath, outputDir)
        result.printReports()
        if (result is ResultWithDiagnostics.Failure) exitProcess(1)

        val name = outputDir.fileName.toString()
        val launcher = outputDir.resolve("bin").resolve(if (isWindows) "$name.bat" else name)
        val jarSizeMb = outputDir.resolve("lib/app.jar").fileSize() / (1024 * 1024)
        val totalMb = totalSizeBytes(outputDir) / (1024 * 1024)
        val runtimeMb = totalSizeBytes(outputDir.resolve("runtime")) / (1024 * 1024)

        echo("artifact: $outputDir/  (~${totalMb} MB)")
        echo("layout:")
        echo("  bin/$name${if (isWindows) ".bat" else ""}    launcher")
        echo("  lib/app.jar           compiled script (~${jarSizeMb} MB)")
        echo("  runtime/              embedded JRE (~${runtimeMb} MB)")
        echo("run with: $launcher [args...]")
    }

    private fun runNative(scriptPath: Path) {
        val outputBin = outputOption?.let { Path(it) }
            ?: scriptPath.resolveSibling(
                stripScriptSuffix(scriptPath.fileName.toString()) + if (isWindows) ".exe" else "",
            )
        echo("compiling ${scriptPath.fileName} -> $outputBin (native binary)")
        if (collectMetadata) {
            echo("note: --collect-metadata will execute the script once under JVM agent before native-image runs.")
        }
        val result = NativeImageFlow().produce(scriptPath, outputBin, collectMetadata)
        result.printReports()
        if (result is ResultWithDiagnostics.Failure) exitProcess(1)

        val sizeMb = outputBin.fileSize() / (1024 * 1024)
        echo("artifact: $outputBin (~${sizeMb} MB)")
        echo("run with: $outputBin [args...]")
    }

    /** Strip `.kts` / `.main.kts` suffix to get the base script name. */
    private fun stripScriptSuffix(name: String): String = when {
        name.endsWith(".main.kts") -> name.removeSuffix(".main.kts")
        name.endsWith(".kts") -> name.removeSuffix(".kts")
        else -> name
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun totalSizeBytes(root: Path): Long =
        if (!root.exists()) 0L
        else root.walk().filter { it.toFile().isFile }.sumOf { it.fileSize() }

    private val isWindows: Boolean
        get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
