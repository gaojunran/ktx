plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":modules:core"))
    implementation(project(":modules:toolchain"))
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
}

tasks.named<JavaExec>("run") {
    // 让脚本路径相对工程根，方便 ./gradlew :modules:cli:run --args="run samples/xxx.kts"
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
