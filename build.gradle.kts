plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "org.framefork.build"

gradlePlugin {
    plugins {
        register("frameforkBuild") {
            id = "org.framefork.build"
            implementationClass = "org.framefork.build.FrameforkSettingsPlugin"
            displayName = "Framefork Build — settings entrypoint"
            description = "Settings plugin applied (with a version) in a consumer's settings.gradle.kts: centralizes repository management, " +
                "discovers modules/ and testing/ subprojects, and propagates the framefork {} build knobs to every project. " +
                "Applying it puts the library-published/library-internal convention plugins on the classpath for version-less use."
        }
        register("frameforkLibraryPublished") {
            id = "org.framefork.build.library-published"
            implementationClass = "org.framefork.build.LibraryPublishedPlugin"
            displayName = "Framefork Build — published library conventions"
            description = "Convention plugin (applied version-less) for a published library module: Java toolchain and --release conventions, " +
                "Error Prone + NullAway + JSpecify strictness, JUnit 5 + test-logger, and maven-publish staging with a full POM."
        }
        register("frameforkLibraryInternal") {
            id = "org.framefork.build.library-internal"
            implementationClass = "org.framefork.build.LibraryInternalPlugin"
            displayName = "Framefork Build — internal library conventions"
            description = "Convention plugin (applied version-less) for a non-published testing/ module: identical to library-published " +
                "minus publishing — Java toolchain and --release conventions, Error Prone + NullAway + JSpecify strictness, and JUnit 5 + test-logger."
        }
    }
}

java {
    withJavadocJar()
    withSourcesJar()
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

    // On the classpath so consumer modules can apply kotlin("jvm") / kotlin("plugin.serialization") / kotlin("kapt")
    // version-less, and so the helpers can configure Kotlin with its real DSL types (KotlinJvmProjectExtension / JvmTarget).
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.kotlinSerialization)

    testImplementation(platform(libs.junitBom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "functionalTestImplementation"(gradleTestKit())
}

// A build-dir Maven repository the marker-resolution integration test publishes the suite into (jar + plugin markers),
// then resolves from as a real consumer would — hermetic because it lives under build/, never touching ~/.m2.
val localPluginRepoDir = layout.buildDirectory.dir("local-plugin-repository")

publishing {
    // java-gradle-plugin generates the `pluginMaven` publication plus one `*PluginMarkerMaven` marker per registered
    // plugin id. Maven Central requires full POM metadata and GPG signatures on every one of them, markers included,
    // so the POM is configured across all publications rather than only the main jar.
    publications {
        withType<MavenPublication>().configureEach {
            pom {
                name = "${project.group}:${project.name}"
                description = "Framefork Gradle convention plugin suite: a settings entrypoint plus published/internal library convention plugins " +
                    "wiring Java toolchain conventions, Error Prone + NullAway + JSpecify strictness, JUnit 5, and maven-publish staging."
                url = "https://github.com/framefork/framefork-gradle-plugins"
                inceptionYear = "2025"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://spdx.org/licenses/Apache-2.0.html"
                    }
                }
                organization {
                    name = "Framefork"
                    url = "https://github.com/framefork"
                }
                developers {
                    developer {
                        id = "fprochazka"
                        name = "Filip Procházka"
                        email = "mr@fprochazka.cz"
                        url = "https://filip-prochazka.com/"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/framefork/framefork-gradle-plugins.git"
                    developerConnection = "scm:git:ssh://github.com/framefork/framefork-gradle-plugins.git"
                    url = "https://github.com/framefork/framefork-gradle-plugins"
                }
                issueManagement {
                    system = "GitHub"
                    url = "https://github.com/framefork/framefork-gradle-plugins/issues"
                }
            }
        }
    }

    repositories {
        maven {
            name = "localPlugin"
            url = uri(localPluginRepoDir)
        }
        // JReleaser deploys this staging tree to Maven Central; cleanAllPublications wipes it before each publish run.
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

tasks.register<Delete>("cleanAllPublications") {
    outputs.upToDateWhen { false }
    delete(layout.buildDirectory.dir("staging-deploy"))
}

tasks.named("publish") {
    dependsOn("cleanAllPublications")
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
