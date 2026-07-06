plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.pluginPublish)
}

group = "org.framefork.build"
version = "0.1.0-SNAPSHOT"

tasks.withType<Wrapper> {
    distributionType = Wrapper.DistributionType.ALL
}
