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

rootProject.name = "ZED"
include(":core")
// `-Pzed.coreOnly=true` skips the Android module so the pure-Kotlin core
// can be tested on machines without an Android SDK (e.g. Termux).
if (providers.gradleProperty("zed.coreOnly").orNull != "true") {
    include(":app")
}
