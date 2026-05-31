plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:toolchain"))
    implementation(project(":modules:protocol"))
    implementation(project(":modules:daemon"))
    implementation(libs.clikt)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
    // Lower bytecode target to 17: ktx itself may be re-exec'd onto an older
    // LTS JDK (e.g. when a script declares @file:Toolchain(jdk = "17")), so the
    // class file version must not exceed 17. Compiling with JDK 21 toolchain
    // but emitting JVM_17 bytecode is fully supported by Kotlin.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("io.github.nebula.ktx.cli.MainKt")
    applicationName = "ktx"
    // Make every ktx invocation through bin/ktx load the prebuilt AppCDS
    // archive. The placeholder APP_HOME_PLACEHOLDER is replaced with the
    // real shell-variable reference in the startScripts.doLast hook below.
    //
    // -XX:+AutoCreateSharedArchive (JDK 19+): if the .jsa file is missing
    // or its class-file version mismatches the running JVM, the JVM
    // silently rebuilds it in place. Graceful degradation, no errors.
    applicationDefaultJvmArgs = listOf(
        "-XX:SharedArchiveFile=APP_HOME_PLACEHOLDER/lib/ktx.jsa",
        "-XX:+AutoCreateSharedArchive",
    )
}

tasks.named<JavaExec>("run") {
    // Resolve script paths relative to the project root, so
    // `./gradlew :modules:cli:run --args="run samples/xxx.kts"` just works.
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

// startScripts inserts applicationDefaultJvmArgs as a literal string into
// DEFAULT_JVM_OPTS='...', but we need $APP_HOME to expand inside the
// -XX:SharedArchiveFile path. The doLast block does two things:
//   1. Replace APP_HOME_PLACEHOLDER with the proper shell reference
//      ($APP_HOME on Unix, %APP_HOME% on Windows).
//   2. Switch the outer single quotes around DEFAULT_JVM_OPTS to double
//      quotes so the shell actually expands $APP_HOME (POSIX never expands
//      variables inside single quotes).
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val replaced = unixScript.readText()
            .replace("APP_HOME_PLACEHOLDER", "\$APP_HOME")
            // Gradle emits: DEFAULT_JVM_OPTS='"...$APP_HOME..."'
            // We rewrite the outer quotes to double-quotes and escape any
            // inner double quotes.
            .replace(
                Regex("""(?m)^DEFAULT_JVM_OPTS='(.*)'$"""),
            ) { match ->
                val inner = match.groupValues[1].replace("\"", "\\\"")
                "DEFAULT_JVM_OPTS=\"$inner\""
            }
        unixScript.writeText(replaced)

        windowsScript.writeText(
            windowsScript.readText().replace("APP_HOME_PLACEHOLDER", "%APP_HOME%"),
        )
    }
}

/**
 * Generate the AppCDS archive (ktx.jsa) under the install dir's lib/.
 *
 * Strategy: run `ktx -e 'Unit'` once with -XX:ArchiveClassesAtExit, which
 * triggers dynamic archiving. This is the modern JDK 17+ flow, replacing
 * the older two-step -Xshare:dump dance.
 *
 * Why `-e 'Unit'` and not `--help`: the archive's hit rate is bounded by
 * the overlap between classes loaded during archival and classes loaded
 * at runtime. `--help` only goes through clikt and exits without loading
 * the Kotlin scripting host, which is the bulk of every real `ktx run`.
 * `-e 'Unit'` exercises the whole scripting-host init path, recording all
 * the heavy classes into the archive.
 *
 * Produces ~11 MB of jsa and shaves ~80-90 ms off startup.
 *
 * AutoCreateSharedArchive (set in applicationDefaultJvmArgs above) is the
 * safety net: if a user re-execs ktx onto a JDK with a different class
 * file version (e.g. JDK 17 instead of 21), the JVM detects the
 * mismatch and rebuilds the archive on first run. No errors, just one
 * extra cold start.
 */
val generateAppCdsArchive by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Generate AppCDS archive (ktx.jsa) under install dir"
    dependsOn("installDist")

    val installDir = layout.buildDirectory.dir("install/ktx")
    val jsaFile = installDir.map { it.file("lib/ktx.jsa") }
    val javaLauncher = javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }

    doFirst {
        val installRoot = installDir.get().asFile
        val launcher = installRoot.resolve("bin/ktx")
        require(launcher.isFile) { "Missing ${launcher.absolutePath}; run :installDist first." }

        // Pull CLASSPATH out of the start script and reuse it verbatim:
        // CDS requires that the archive-time classpath matches the runtime
        // classpath byte-for-byte (order, $APP_HOME-vs-absolute, all matter).
        // We grab the CLASSPATH= line, substitute $APP_HOME with the real
        // absolute path, and feed that exact cp into the archival run.
        val classpathLine = launcher.readLines().firstOrNull { it.startsWith("CLASSPATH=") }
            ?: error("CLASSPATH line not found in start script")
        val cp = classpathLine
            .removePrefix("CLASSPATH=")
            .replace("\$APP_HOME", installRoot.absolutePath)

        // Remove any existing jsa so the JVM does not refuse to overwrite it.
        jsaFile.get().asFile.delete()

        commandLine(
            javaLauncher.get().executablePath.asFile.absolutePath,
            "-XX:ArchiveClassesAtExit=${jsaFile.get().asFile.absolutePath}",
            "-cp", cp,
            "io.github.nebula.ktx.cli.MainKt",
            "-e", "Unit",
        )
    }
}

tasks.named("installDist") { finalizedBy(generateAppCdsArchive) }
