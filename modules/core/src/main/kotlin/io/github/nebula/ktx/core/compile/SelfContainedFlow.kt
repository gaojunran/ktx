package io.github.nebula.ktx.core.compile

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * `ktx compile --self-contained` flow.
 *
 * Wraps [CompileFlow]'s fat jar in a directory that also ships a minimal
 * `jlink`-built JRE plus a launcher script. End users only need to extract
 * the directory and run `bin/<name>`; no Java install required.
 *
 * Layout produced under [outputDir]:
 *   bin/<name>          launcher (sh on Unix, .bat on Windows)
 *   lib/app.jar         fat jar from [CompileFlow]
 *   runtime/            jlink-built minimal JRE
 *
 * Targets only the host platform — `jlink` cannot cross-compile.
 *
 * Caching is intentionally NOT done here. A jlink build takes ~3-5s, which
 * is small relative to script compile + dependency download. The cache
 * invariants (JDK version, module list, fat jar identity) would be tricky
 * to track and the disk savings would be marginal.
 */
class SelfContainedFlow {

    private val log = LoggerFactory.getLogger("ktx.compile.self-contained")

    fun produce(scriptPath: Path, outputDir: Path): ResultWithDiagnostics<*> {
        require(!outputDir.exists() || (outputDir.isDirectory() && outputDir.listDirectoryEntries().isEmpty())) {
            "output directory must be empty or non-existent: $outputDir"
        }

        // Step 0: validate JDK tools up front. We need both jdeps and jlink.
        // A JRE installation has neither. Failing here gives a much better
        // error than a confusing ProcessBuilder failure 30s later.
        val javaHome = Path.of(System.getProperty("java.home"))
        val jdeps = jdkBin(javaHome, "jdeps")
        val jlink = jdkBin(javaHome, "jlink")
        require(jdeps.exists()) {
            "jdeps not found at $jdeps — `ktx compile --self-contained` needs a full JDK, not a JRE."
        }
        require(jlink.exists()) {
            "jlink not found at $jlink — `ktx compile --self-contained` needs a full JDK, not a JRE."
        }

        val absScript = scriptPath.absolute().normalize()
        val tmpDir = Files.createTempDirectory("ktx-selfcontained-")

        try {
            // Step 1: produce the fat jar via the existing flow.
            val fatJar = tmpDir.resolve("app.jar")
            val compileResult = CompileFlow().compile(absScript, fatJar)
            if (compileResult is ResultWithDiagnostics.Failure) {
                return compileResult
            }
            log.info("fat jar built: {} ({} KB)", fatJar, fatJar.fileSize() / 1024)

            // Step 2: ask jdeps which JDK modules the fat jar needs.
            val detected = detectModules(jdeps, fatJar)

            // Step 3: union with modules we know are needed at runtime
            // but jdeps tends to under-report. kotlin-stdlib uses
            // sun.misc.Unsafe (jdk.unsupported), and many user deps load
            // classes via Class.forName from java.naming / java.logging
            // through reflective paths jdeps cannot trace.
            val modules = (detected + ALWAYS_INCLUDE).toSortedSet()
            log.info("modules: detected={}, final={}", detected, modules)

            // Step 4: jlink the runtime image.
            outputDir.createDirectories()
            val runtimeDir = outputDir.resolve("runtime")
            runJlink(jlink, modules.toList(), runtimeDir)

            // Step 5: copy the fat jar and write the launcher.
            val libDir = outputDir.resolve("lib").also { it.createDirectories() }
            val installedJar = libDir.resolve("app.jar")
            Files.copy(fatJar, installedJar)

            val binDir = outputDir.resolve("bin").also { it.createDirectories() }
            val name = outputDir.fileName.toString()
            writeLauncher(binDir, name)

            log.info("self-contained dist ready at {}", outputDir)
            return compileResult
        } finally {
            tmpDir.toFile().deleteRecursively()
        }
    }

    /**
     * Run `jdeps --print-module-deps --ignore-missing-deps --multi-release base`
     * over the fat jar. The flag set:
     *   - --print-module-deps: emit a comma-separated module list, nothing else.
     *   - --ignore-missing-deps: many libs reference compile-only optionals
     *     (e.g. servlet APIs from a library that also runs standalone).
     *     Without this jdeps refuses to print anything when it sees one.
     *   - --multi-release base: only inspect the base layer of multi-release
     *     jars; we don't need version-specific modules.
     */
    private fun detectModules(jdeps: Path, fatJar: Path): Set<String> {
        val output = runTool(
            jdeps,
            listOf("--print-module-deps", "--ignore-missing-deps", "--multi-release", "base", fatJar.toString()),
        ).trim()
        if (output.isEmpty()) return emptySet()
        // jdeps emits one comma-separated line per input jar; we only pass one.
        return output.lineSequence()
            .flatMap { it.split(",").asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun runJlink(jlink: Path, modules: List<String>, runtimeDir: Path) {
        // jlink refuses to write into an existing directory, so make sure
        // it doesn't exist yet (outputDir was checked empty earlier; this
        // is just being explicit about jlink's own contract).
        if (runtimeDir.exists()) error("runtime already exists: $runtimeDir")
        runTool(
            jlink,
            listOf(
                "--no-header-files",
                "--no-man-pages",
                "--strip-debug",
                "--compress=2",
                "--add-modules", modules.joinToString(","),
                "--output", runtimeDir.toString(),
            ),
        )
    }

    private fun writeLauncher(binDir: Path, name: String) {
        if (isWindows) {
            val bat = binDir.resolve("$name.bat")
            // %~dp0 is the directory of the .bat file with a trailing backslash.
            // Quoting the path supports installs under "Program Files" etc.
            bat.writeText(
                """
                @echo off
                set DIR=%~dp0
                "%DIR%..\runtime\bin\java.exe" -jar "%DIR%..\lib\app.jar" %*
                """.trimIndent() + "\r\n",
            )
        } else {
            val sh = binDir.resolve(name)
            // `readlink -f` would resolve symlinks, but it's not portable
            // (BSD readlink lacks -f). We keep the launcher dependent only on
            // POSIX shell built-ins and resolve relative to $0.
            sh.writeText(
                """
                #!/bin/sh
                DIR="$(cd "$(dirname "$0")" && pwd)"
                exec "${'$'}DIR/../runtime/bin/java" -jar "${'$'}DIR/../lib/app.jar" "$@"
                """.trimIndent() + "\n",
            )
            sh.setPosixFilePermissions(EXECUTABLE_PERMS)
        }
    }

    private fun runTool(bin: Path, args: List<String>): String {
        log.debug("exec: {} {}", bin, args.joinToString(" "))
        val pb = ProcessBuilder(listOf(bin.toString()) + args).redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) {
            error("${bin.name} failed (exit=$code):\n$out")
        }
        return out
    }

    private fun jdkBin(javaHome: Path, tool: String): Path {
        val name = if (isWindows) "$tool.exe" else tool
        return javaHome.resolve("bin").resolve(name)
    }

    companion object {
        private val isWindows: Boolean
            get() = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

        // Modules jdeps tends to miss. Cheap to include — including all of
        // these only adds a few MB to the image.
        private val ALWAYS_INCLUDE = setOf(
            "java.base",        // already implied, but explicit for readability
            "java.logging",     // SLF4J -> JUL bridge, kotlin-logging
            "java.naming",      // okhttp / netty resolver paths
            "jdk.unsupported",  // kotlin-stdlib uses sun.misc.Unsafe
        )

        // rwxr-xr-x — owner can edit/exec, world can read/exec.
        private val EXECUTABLE_PERMS = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
    }
}
