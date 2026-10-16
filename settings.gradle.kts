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
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Tabelapp"

// :core é Kotlin puro (regras de negócio + testes) e compila sem Android SDK.
// Para rodar só ele numa máquina sem SDK:  ./gradlew -PsemAndroid :core:test
include(":core")
if (providers.gradleProperty("semAndroid").orNull != "true") {
    include(":app")
}
