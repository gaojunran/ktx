package io.github.nebula.ktx.core.bootstrap

import java.io.File

/**
 * Entry point baked into ktx native binaries.
 *
 * The Kotlin scripting host's `JvmScriptingHostConfigurationKt.<clinit>` calls
 * `File(System.getProperty("java.home"))`. Inside a native-image binary there
 * is no JVM, so by default `java.home` is null and the script crashes with NPE
 * before its first line runs.
 *
 * This shim runs BEFORE the scripting host's static initializers and stamps
 * sane fallback values for the few JVM properties Kotlin's scripting code
 * inspects. The values themselves are never consumed at runtime (we don't
 * spawn a sub-JVM, don't compile new scripts, don't read JDK jars from these
 * paths) — only the existence of a non-null path matters.
 *
 * The Main-Class attribute of the fat jar is rewritten to point here by
 * [io.github.nebula.ktx.core.compile.NativeImageFlow] when `--native` is
 * requested. The original Main-Class (e.g. `Hello_main`) is passed in via the
 * `ktx.script.main` system property — `NativeImageFlow` adds
 * `-Dktx.script.main=<class>` to the native-image args so the value is baked
 * into the binary at build time. (Reading from the manifest at runtime does
 * not work: native binaries don't expose a JAR-like classpath, so
 * `ClassLoader.getResources("META-INF/MANIFEST.MF")` returns nothing.)
 *
 * We intentionally avoid kotlin-reflect / KClass APIs to keep our own static
 * footprint minimal — pure java.lang.reflect / Class.forName.
 */
object NativeBootstrap {
    @JvmStatic
    fun main(args: Array<String>) {
        // Inject defaults — only set them if not already provided externally.
        // Native binaries can still inherit env-supplied values from a
        // launcher script, which we leave alone.
        if (System.getProperty("java.home") == null) {
            System.setProperty("java.home", System.getenv("JAVA_HOME") ?: "/")
        }
        if (System.getProperty("user.home") == null) {
            System.setProperty("user.home", System.getenv("HOME") ?: "/")
        }
        if (System.getProperty("user.dir") == null) {
            System.setProperty("user.dir", File(".").absolutePath)
        }

        // The original script entry class (e.g. `Hello_main`) is baked into
        // the fat jar by [io.github.nebula.ktx.core.compile.NativeImageFlow]
        // as a one-line resource at `/ktx/script-main.txt`. native-image
        // includes this resource in the binary because of an explicit entry
        // in `resource-config.json`. Reading it via the ClassLoader works
        // both in the JVM (test path) and in the native binary.
        val scriptMainClassName = NativeBootstrap::class.java.classLoader
            .getResourceAsStream("ktx/script-main.txt")
            ?.bufferedReader()?.use { it.readText().trim() }
            ?: error(
                "ktx/script-main.txt resource is missing. This binary was not " +
                    "produced by `ktx compile --native` or its build step is broken.",
            )
        val cls = Class.forName(scriptMainClassName)
        val main = cls.getMethod("main", Array<String>::class.java)
        main.invoke(null, args)
    }
}
