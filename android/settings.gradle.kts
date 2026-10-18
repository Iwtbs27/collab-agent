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
        maven {
            url = uri("https://maven.rikka.dev/releases")
            content {
                includeGroupByRegex("io\\.github\\.vvb2060.*")
                includeGroupByRegex("org\\.lsposed.*")
                includeGroupByRegex("dev\\.rikka.*")
            }
        }
    }
}

rootProject.name = "OpenCyvis"
include(":app")
include(":flagsecure-demo")
project(":flagsecure-demo").projectDir = file("../tests/flagsecure-demo")
