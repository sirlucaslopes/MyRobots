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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MyRobots"
include(":app")

// Módulos "core": base compartilhada por todo o app (dados, rede, tema...)
include(":core:common")
include(":core:model")
include(":core:database")
include(":core:network")
include(":core:data")
include(":core:designsystem")

// Módulos "feature": cada um é uma parte do app (tela + regras dela)
include(":feature:splash")
include(":feature:robots")
include(":feature:backup")
include(":feature:codeeditor")
include(":feature:dashboard")
include(":feature:terminal")
include(":feature:settings")
 