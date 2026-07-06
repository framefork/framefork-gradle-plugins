plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.pluginPublish) apply false
    alias(libs.plugins.versionCheck)
    id("idea")
}

allprojects {
    group = "org.framefork.build"
    version = "0.0.1-SNAPSHOT"
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

tasks.withType<Wrapper> {
    distributionType = Wrapper.DistributionType.ALL
}
