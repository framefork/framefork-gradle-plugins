plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.pluginPublish)
}

group = "org.framefork.build"
version = "0.1.0-SNAPSHOT"

gradlePlugin {
    plugins {
        register("frameforkBuild") {
            id = "org.framefork.build"
            implementationClass = "org.framefork.build.FrameforkSettingsPlugin"
        }
        register("frameforkLibraryPublished") {
            id = "org.framefork.build.library-published"
            implementationClass = "org.framefork.build.LibraryPublishedPlugin"
        }
        register("frameforkLibraryInternal") {
            id = "org.framefork.build.library-internal"
            implementationClass = "org.framefork.build.LibraryInternalPlugin"
        }
    }
}

val functionalTest: SourceSet = sourceSets.create("functionalTest")
configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

// Wires the plugin-under-test classpath into the functionalTest source set so GradleRunner.withPluginClasspath() works.
gradlePlugin.testSourceSets(functionalTest)

dependencies {
    testImplementation(platform(libs.junitBom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "functionalTestImplementation"(gradleTestKit())
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs the functional tests against the plugin via GradleRunner."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn(functionalTestTask)
}

tasks.withType<Wrapper> {
    distributionType = Wrapper.DistributionType.ALL
}
