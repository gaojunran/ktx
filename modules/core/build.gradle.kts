plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.kotlin.scripting.jvm.host)
    api(libs.kotlin.main.kts)
    implementation(libs.slf4j.api)
    implementation(libs.tomlj)

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
