rootProject.name = "auralis"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared")
include(":jvmDemo")

val localProperties = file("local.properties")
val hasAndroidSdk = localProperties.exists() &&
    localProperties.readLines().any { it.startsWith("sdk.dir=") }
if (hasAndroidSdk) {
    include(":androidApp")
}
