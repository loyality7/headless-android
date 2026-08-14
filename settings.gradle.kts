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

// The probe is throwaway: it measures the device before the library relies on it.
include(":probe")
