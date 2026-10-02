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
        val sdkRepository = file("../Android_ViveGlassSDK_0.6.0_beta/ViveGlassSample/repository")
        if (sdkRepository.isDirectory) {
            maven { url = uri(sdkRepository) }
        }
    }
}

rootProject.name = "AiGlassMeeting"
include(":app")

