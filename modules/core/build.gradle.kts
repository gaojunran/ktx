plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.kotlin.scripting.jvm.host)
    api(libs.kotlin.main.kts)
    implementation(libs.slf4j.api)
    implementation(libs.tomlj)
    // For merging GraalVM native-image metadata JSON files (NativeImageFlow).
    implementation(libs.kotlinx.serialization.json)
    // Expose shell DSL types to scripts compiled against KtsScriptDefinition.
    implementation(project(":modules:shell"))

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
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
