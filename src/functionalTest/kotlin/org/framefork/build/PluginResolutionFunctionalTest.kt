package org.framefork.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The real marker-resolution proof: unlike every other functional test here, this one deliberately does NOT use
 * `withPluginClasspath()`.
 *
 * It resolves the `org.framefork.build` settings plugin from a Maven repo the build freshly published under `build/`
 * (never `~/.m2`, so the test is hermetic) — exactly as a consumer would, via
 * `plugins { id("org.framefork.build") version "…" }` inside `pluginManagement` — and then applies
 * `org.framefork.build.library-published` in a submodule **without a version**. That version-less application can only
 * resolve if the settings plugin's jar (pulled in from its published marker) is on the settings buildscript classpath,
 * so a green build here exercises the published-artifact path end to end: marker POM resolution plus the classpath
 * injection that makes the sub-plugins applyable version-less.
 */
class PluginResolutionFunctionalTest {

    @field:TempDir
    lateinit var consumerDir: File

    private val pluginRepo: String = requireNotNull(System.getProperty("framefork.localPluginRepo")) {
        "framefork.localPluginRepo system property must be set by the functionalTest task"
    }

    private val pluginVersion: String = requireNotNull(System.getProperty("framefork.pluginVersion")) {
        "framefork.pluginVersion system property must be set by the functionalTest task"
    }

    @Test
    fun `resolves the published settings plugin from a temp repo and applies a version-less sub-plugin`() {
        val repoUri = File(pluginRepo).toURI()

        write(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    maven { url = uri("$repoUri") }
                    gradlePluginPortal()
                    mavenCentral()
                }
            }

            plugins {
                id("org.framefork.build") version "$pluginVersion"
            }

            rootProject.name = "consumer"
            """.trimIndent(),
        )
        write("build.gradle.kts", "")

        // No version on the sub-plugin: it resolves only because the settings plugin (resolved from its published marker)
        // put the suite jar on the settings buildscript classpath. The probe asserts the plugin genuinely applied, rather
        // than only that its marker POM resolved.
        write(
            "modules/foo/build.gradle.kts",
            """
            plugins {
                id("org.framefork.build.library-published")
            }

            val applied = plugins.hasPlugin("org.framefork.build.library-published")
            tasks.register("frameforkApplied") {
                doLast {
                    println("FRAMEFORK library-published applied=" + applied)
                }
            }
            """.trimIndent(),
        )

        val result = GradleRunner.create()
            .withProjectDir(consumerDir)
            // --refresh-dependencies so a re-run always sees the just-published SNAPSHOT rather than a cached one.
            .withArguments(":foo:frameforkApplied", "--refresh-dependencies", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:frameforkApplied")?.outcome, result.output)
        assertTrue(result.output.contains("FRAMEFORK library-published applied=true"), result.output)
    }

    private fun write(relativePath: String, content: String) {
        val file = consumerDir.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
