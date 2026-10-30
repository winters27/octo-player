pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            content { includeGroup("org.jetbrains.kotlin-desktop-toolkit") }
        }
    }
}

rootProject.name = "OctoPlayer"
include(":app", ":design", ":subsonic", ":shared:core", ":desktop", ":desktop-design")
include(":wayland-probe")
include(":desktop-wayland")
