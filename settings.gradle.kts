rootProject.name = "rangetarget"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(":core", ":desktop")
// ":android" is added in Phase 6, once the analysis core passes validation.
