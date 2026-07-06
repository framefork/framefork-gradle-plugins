package org.framefork.build

import com.adarshr.gradle.testlogger.TestLoggerExtension
import com.adarshr.gradle.testlogger.theme.ThemeType
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/**
 * Uniform testing setup for every library module: JUnit 5 on the JUnit Platform, readable test-logger output, and a
 * hard guarantee that stray JUnit 4 never reaches the classpath. Applied by both `library-*` plugins.
 *
 * The JUnit BOM version injected into consumer projects is declared in [TestConventionsVersions].
 *
 * All configuration is lazy (`configureEach`, project-level extension), so it stays configuration-cache-safe.
 */
internal fun Project.configureTestConventions() {
    pluginManager.apply("com.adarshr.test-logger")

    // JUnit 4 is unwanted across the fleet, yet it keeps sneaking in as a transitive (e.g. via testcontainers). Rather
    // than chase excludes per dependency, substitute it everywhere with an API-compatible no-op so it can never run.
    configurations.all {
        resolutionStrategy.dependencySubstitution {
            substitute(module("junit:junit"))
                .using(module("io.quarkus:quarkus-junit4-mock:3.0.0.Final"))
                .because(
                    "JUnit 4 is unwanted; it only shows up as an unneeded transitive (e.g. of testcontainers). " +
                        "See https://github.com/testcontainers/testcontainers-java/issues/970",
                )
        }
    }

    dependencies.apply {
        add("testImplementation", platform("org.junit:junit-bom:${TestConventionsVersions.JUNIT_BOM}"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    extensions.configure<TestLoggerExtension>("testlogger") {
        theme = ThemeType.STANDARD
        showExceptions = true
        showStackTraces = true
        showFullStackTraces = false
        showCauses = true
        // Keep the log tasteful: don't echo stdout/stderr for passing or skipped tests, only for failures where it aids diagnosis.
        showStandardStreams = false
        showPassedStandardStreams = false
        showSkippedStandardStreams = false
        showFailedStandardStreams = true
    }
}

/**
 * JUnit BOM version the test conventions inject into every consumer project. Consumer-injected versions live in Kotlin
 * `const`s rather than the suite's version catalog (see docs/design-decisions.md §4), so this value is intentionally a
 * separate home from the `junit` entry in `gradle/libs.versions.toml`, which pins the suite's *own* test dependency.
 * The two mean different things but should track the same release — bump both together so the suite tests on what it ships.
 */
internal object TestConventionsVersions {
    const val JUNIT_BOM: String = "5.11.4"
}
