pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ZENITH"
include(":core")
// `-Pzenith.coreOnly=true` skips the Android module so the pure-Kotlin core
// can be tested on machines without an Android SDK (e.g. Termux).
if (providers.gradleProperty("zenith.coreOnly").orNull != "true") {
    include(":app")
}
