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

rootProject.name = "webdroid"

include(":headless")

// Pages for tests to drive. Test-only: no module ships it.
include(":fixtures")

// The probe is throwaway: it measures the device before the library relies on it.
include(":probe")

// Interactive example application showcasing headless scraping & automation
include(":example")
