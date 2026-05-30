plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":modules:core"))
    implementation(libs.clikt)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
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
