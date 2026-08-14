plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Test-only. Never a dependency of :headless main sources, so nothing here can
// reach the published artifact.
dependencies {
    api(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp)
    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(17)
}
