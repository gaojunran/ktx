plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.main.kts)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.nebula.ktx.core.exec.Phase0SpikeKt")
}

tasks.named<JavaExec>("run") {
    // 让脚本路径相对工程根，方便 ./gradlew run --args="samples/xxx.kts"
    workingDir = rootProject.projectDir
}

tasks.named<JavaExec>("run") {
    // 让脚本路径相对工程根，方便 ./gradlew run --args="samples/xxx.kts"
    workingDir = rootProject.projectDir
}
