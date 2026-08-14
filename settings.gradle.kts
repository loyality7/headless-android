pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "headless-android"

include(":headless")

// Pages for tests to drive. Test-only: no module ships it.
include(":fixtures")

// The probe is throwaway: it measures the device before the library relies on it.
include(":probe")
