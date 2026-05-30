plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.kotlin.scripting.jvm.host)
    api(libs.kotlin.main.kts)
    implementation(libs.slf4j.api)
}

kotlin {
    jvmToolchain(21)
}
