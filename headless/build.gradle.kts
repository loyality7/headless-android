plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
    `maven-publish`
}

android {
    namespace = "dev.headless.browser"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "dev.headless"
            artifactId = "headless-android"
            version = "1.0.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Headless Android")
                description.set("Playwright-shaped browser automation SDK for Android")
                url.set("https://github.com/loyality7/headless-android")
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("loyality7")
                        name.set("Sarath Babu")
                    }
                }
                scm {
                    connection.set("scm:git:github.com/loyality7/headless-android.git")
                    developerConnection.set("scm:git:ssh://github.com/loyality7/headless-android.git")
                    url.set("https://github.com/loyality7/headless-android")
                }
            }
        }
    }
}

// Three runtime dependencies. Adding a fourth needs a written justification.
dependencies {
    api(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.webkit)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(project(":fixtures"))

    androidTestImplementation(project(":fixtures"))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
