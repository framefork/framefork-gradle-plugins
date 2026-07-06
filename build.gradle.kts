plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.pluginPublish)
}

group = "org.framefork.build"
version = "0.1.0-SNAPSHOT"

gradlePlugin {
    website = "https://github.com/framefork/framefork-gradle-plugins"
    vcsUrl = "https://github.com/framefork/framefork-gradle-plugins"

    plugins {
        register("frameforkBuild") {
            id = "org.framefork.build"
            implementationClass = "org.framefork.build.FrameforkSettingsPlugin"
            displayName = "Framefork Build — settings entrypoint"
            description = "Settings plugin applied (with a version) in a consumer's settings.gradle.kts: centralizes repository management, " +
                "discovers modules/ and testing/ subprojects, and propagates the framefork {} build knobs to every project. " +
                "Applying it puts the library-published/library-internal convention plugins on the classpath for version-less use."
            tags = listOf("convention-plugins", "settings-plugin", "java", "maven-publish")
        }
        register("frameforkLibraryPublished") {
            id = "org.framefork.build.library-published"
            implementationClass = "org.framefork.build.LibraryPublishedPlugin"
            displayName = "Framefork Build — published library conventions"
            description = "Convention plugin (applied version-less) for a published library module: Java toolchain and --release conventions, " +
                "Error Prone + NullAway + JSpecify strictness, JUnit 5 + test-logger, and maven-publish staging with a full POM."
            tags = listOf("convention-plugins", "java", "errorprone", "nullaway", "jspecify", "maven-publish")
        }
        register("frameforkLibraryInternal") {
            id = "org.framefork.build.library-internal"
            implementationClass = "org.framefork.build.LibraryInternalPlugin"
            displayName = "Framefork Build — internal library conventions"
            description = "Convention plugin (applied version-less) for a non-published testing/ module: identical to library-published " +
                "minus publishing — Java toolchain and --release conventions, Error Prone + NullAway + JSpecify strictness, and JUnit 5 + test-logger."
            tags = listOf("convention-plugins", "java", "errorprone", "nullaway", "jspecify")
        }
    }
}

val functionalTest: SourceSet = sourceSets.create("functionalTest")
configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

// Wires the plugin-under-test classpath into the functionalTest source set so GradleRunner.withPluginClasspath() works.
gradlePlugin.testSourceSets(functionalTest)

dependencies {
    // Pinned so the helper functions can apply these plugins by id and configure them with their real DSL types.
    implementation(libs.errorpronePlugin)
    implementation(libs.nullawayPlugin)
    implementation(libs.testLoggerPlugin)

    testImplementation(platform(libs.junitBom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "functionalTestImplementation"(gradleTestKit())
}

// A build-dir Maven repository the marker-resolution integration test publishes the suite into (jar + plugin markers),
// then resolves from as a real consumer would — hermetic because it lives under build/, never touching ~/.m2.
val localPluginRepoDir = layout.buildDirectory.dir("local-plugin-repository")

publishing {
    repositories {
        maven {
            name = "localPlugin"
            url = uri(localPluginRepoDir)
        }
    }
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs the functional tests against the plugin via GradleRunner."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()

    // PluginResolutionFunctionalTest resolves the freshly-published suite from this repo instead of withPluginClasspath().
    dependsOn("publishAllPublicationsToLocalPluginRepository")
    systemProperty("framefork.localPluginRepo", localPluginRepoDir.get().asFile.absolutePath)
    systemProperty("framefork.pluginVersion", version.toString())

    // Empty ⇒ TestKit's embedded/current Gradle; `-PtestedGradleVersion=9.0` replays the whole suite against the consumer baseline.
    systemProperty("framefork.testedGradleVersion", providers.gradleProperty("testedGradleVersion").orNull ?: "")
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
