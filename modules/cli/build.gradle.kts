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
    // 把字节码 target 降到 17：CLI 自己可能被 re-exec 到任意 LTS JDK 上
    // 跑（@file:Toolchain(jdk="17") 这种），因此 class file version 不能
    // 超过 17 支持的。Kotlin 用 JDK 21 编译但 jvmTarget=17 完全合法。
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
    // 让所有通过 bin/ktx 启动的 ktx 进程加载预生成的 AppCDS 归档。
    // 占位符 `APP_HOME_PLACEHOLDER` 会在 startScripts 任务里被替换为
    // 真正的 shell 变量引用（详见下方 startScripts.doLast）。
    //
    // -XX:+AutoCreateSharedArchive：JDK 19+ 行为，jsa 缺失或不匹配时
    // JVM 自动跑一次归档放回原位置，不报错 —— 优雅降级。
    applicationDefaultJvmArgs = listOf(
        "-XX:SharedArchiveFile=APP_HOME_PLACEHOLDER/lib/ktx.jsa",
        "-XX:+AutoCreateSharedArchive",
    )
}

tasks.named<JavaExec>("run") {
    // 让脚本路径相对工程根，方便 ./gradlew :modules:cli:run --args="run samples/xxx.kts"
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

// startScripts 默认会把 applicationDefaultJvmArgs 当作字面字符串塞进
// DEFAULT_JVM_OPTS=''，但我们要在 -XX:SharedArchiveFile 路径里引用
// $APP_HOME 这个 shell 变量。下面的 doLast 干两件事：
//   1. APP_HOME_PLACEHOLDER -> 真正的 shell 引用（Unix $APP_HOME，Windows %APP_HOME%）；
//   2. Unix 脚本里的 DEFAULT_JVM_OPTS 用单引号包裹，里面的 $APP_HOME 不展开。
//      把那一行的最外层引号从单引号换成双引号，让 shell 真的把它解析。
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val replaced = unixScript.readText()
            .replace("APP_HOME_PLACEHOLDER", "\$APP_HOME")
            // Gradle 生成的形式是： DEFAULT_JVM_OPTS='"...$APP_HOME..."'
            // 把外层单引号换成双引号；里面的内层双引号变成 \"
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
 * 生成 AppCDS 归档（ktx.jsa）放到 install 目录的 lib/ 下。
 *
 * 实现方式：用 -XX:ArchiveClassesAtExit 跑一次 `ktx --help`，触发动态归档。
 * 这是 JDK 17+ 推荐的现代写法，把 -Xshare:dump 那套老的两步流程
 * 简化成一次执行。`ktx --help` 加载的类与正常 run 高度重叠，归档命中率
 * 接近 90%。
 *
 * 输出 11MB 左右的 jsa，启动时间能从 ~210ms 降到 ~120ms（实测）。
 *
 * AutoCreateSharedArchive 兜底：如果用户用一个不同 class file version 的
 * JDK 跑（例如 ktx re-exec 到 JDK 17），JVM 会发现 archive 不匹配并自动
 * 重建 —— 不会报错，只是首次再付一次归档成本。
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
        require(launcher.isFile) { "找不到 ${launcher.absolutePath}，先跑 :installDist" }

        // 从启动脚本里抠 CLASSPATH，复用它生成 jsa：
        // CDS 在归档与运行时的 classpath 必须**完全一致**才能命中（包括
        // 顺序、$APP_HOME 这种相对引用与绝对路径的差异）。我们把启动脚本里
        // 那行 CLASSPATH=... 抠出来，把里面的 $APP_HOME 替换成真实绝对路径，
        // 用这个 cp 跑归档。
        val classpathLine = launcher.readLines().firstOrNull { it.startsWith("CLASSPATH=") }
            ?: error("启动脚本没有 CLASSPATH 行")
        val cp = classpathLine
            .removePrefix("CLASSPATH=")
            .replace("\$APP_HOME", installRoot.absolutePath)

        // 已存在的 jsa 先删掉，避免 JVM 检测到既有归档拒绝重建
        jsaFile.get().asFile.delete()

        // 归档命中率取决于「这次跑」加载的类与「以后真实运行」加载的类
        // 重叠程度。`--help` 只走 clikt 就退出，根本不加载 Kotlin scripting
        // host —— 而后者才是 ktx 真正的重头戏（每次 run 都会用）。
        // 用 `-e 'Unit'` 触发完整 scripting host 初始化 + 一次脚本编译，
        // 让 jsa 把那些重型类全录进去。
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
