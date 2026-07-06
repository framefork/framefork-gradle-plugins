pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }

    // should auto-pickup the 'libs.versions.toml'
}

rootProject.name = "framefork-build"

include(":conventions")
