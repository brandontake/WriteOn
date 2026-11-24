// WriteOn 2.1 Settings
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        google()
        // Explicit Maven Central host: the alias endpoint (repo.maven.apache.org) terminates the
        // TLS handshake through this machine's proxy, while repo1.maven.org works. Same content.
        maven(url = "https://repo1.maven.org/maven2")
        mavenCentral()
        gradlePluginPortal()

    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven(url = "https://repo1.maven.org/maven2")
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "WriteOn"
include(":app")
 