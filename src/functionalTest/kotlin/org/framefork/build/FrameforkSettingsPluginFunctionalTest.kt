package org.framefork.build

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FrameforkSettingsPluginFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    @Test
    fun `discovers modules and propagates resolved framefork values to projects`() {
        writeSettings(
            """
            plugins {
                id("org.framefork.build")
            }

            framefork {
                minJavaVersion = 11
                jdkVersion = 25
                jspecifyMode = false
            }

            rootProject.name = "consumer"
            """.trimIndent(),
        )
        write("build.gradle.kts", "")

        // The module's own build script reads FrameforkProjectExtension and echoes the propagated values.
        // If beforeProject captured the defaults instead of the resolved framefork {} values, these assertions
        // would see 17/21/true rather than the 11/25/false the consumer configured.
        write(
            "modules/foo/build.gradle.kts",
            """
            val ext = extensions.getByType(org.framefork.build.FrameforkProjectExtension::class.java)
            tasks.register("printFramefork") {
                // Captured as lambda locals (plain scalars) so the doLast action stays configuration-cache-safe.
                val min = ext.minJavaVersion.get()
                val jdk = ext.jdkVersion.get()
                val testsJdk = ext.testsJdkVersion.orNull
                val jspecify = ext.jspecifyMode.get()
                doLast {
                    println("FRAMEFORK minJavaVersion=" + min)
                    println("FRAMEFORK jdkVersion=" + jdk)
                    println("FRAMEFORK testsJdkVersion=" + testsJdk)
                    println("FRAMEFORK jspecifyMode=" + jspecify)
                }
            }
            """.trimIndent(),
        )

        val result = frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(":foo:printFramefork", "--configuration-cache", "--stacktrace")
            .build()

        // Reaching :foo:printFramefork at all proves modules/foo was discovered and included as :foo.
        assertTrue(result.output.contains("FRAMEFORK minJavaVersion=11"), result.output)
        assertTrue(result.output.contains("FRAMEFORK jdkVersion=25"), result.output)
        assertTrue(result.output.contains("FRAMEFORK testsJdkVersion=null"), result.output)
        assertTrue(result.output.contains("FRAMEFORK jspecifyMode=false"), result.output)
    }

    @Test
    fun `applies knob defaults when framefork block is absent`() {
        writeSettings(
            """
            plugins {
                id("org.framefork.build")
            }

            rootProject.name = "consumer"
            """.trimIndent(),
        )
        write("build.gradle.kts", "")
        write(
            "modules/bar/build.gradle.kts",
            """
            val ext = extensions.getByType(org.framefork.build.FrameforkProjectExtension::class.java)
            tasks.register("printFramefork") {
                val min = ext.minJavaVersion.get()
                val jdk = ext.jdkVersion.get()
                val jspecify = ext.jspecifyMode.get()
                doLast {
                    println("FRAMEFORK minJavaVersion=" + min)
                    println("FRAMEFORK jdkVersion=" + jdk)
                    println("FRAMEFORK jspecifyMode=" + jspecify)
                }
            }
            """.trimIndent(),
        )

        val result = frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(":bar:printFramefork", "--stacktrace")
            .build()

        assertTrue(result.output.contains("FRAMEFORK minJavaVersion=17"), result.output)
        assertTrue(result.output.contains("FRAMEFORK jdkVersion=21"), result.output)
        assertTrue(result.output.contains("FRAMEFORK jspecifyMode=true"), result.output)
    }

    @Test
    fun `per-project knobs are locked against module-level overrides`() {
        writeSettings(
            """
            plugins {
                id("org.framefork.build")
            }

            framefork {
                minJavaVersion = 17
            }

            rootProject.name = "consumer"
            """.trimIndent(),
        )
        write("build.gradle.kts", "")

        // A module script running after the plugin apply tries to override the propagated knob. The settings
        // `framefork {}` block is the single parametrization surface, so this write must fail loudly rather than
        // silently diverge — FrameforkProjectInitAction calls disallowChanges() on every knob.
        write(
            "modules/foo/build.gradle.kts",
            """
            val ext = extensions.getByType(org.framefork.build.FrameforkProjectExtension::class.java)
            ext.minJavaVersion.set(11)
            """.trimIndent(),
        )

        val result = frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(":foo:help", "--configuration-cache", "--stacktrace")
            .buildAndFail()

        assertTrue(result.output.contains("cannot be changed any further"), result.output)
    }

    @Test
    fun `warns when no subprojects are discovered`() {
        writeSettings(
            """
            plugins {
                id("org.framefork.build")
            }

            rootProject.name = "consumer"
            """.trimIndent(),
        )
        write("build.gradle.kts", "")

        // Neither modules/ nor testing/ exists, so the plugin wires nothing. It must say so rather than no-op silently,
        // but must not fail — a repo may be mid-migration with no modules yet.
        val result = frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments("help", "--configuration-cache", "--stacktrace")
            .build()

        assertTrue(result.output.contains("framefork: no subprojects discovered"), result.output)
        assertTrue(result.output.contains("'modules/'"), result.output)
        assertTrue(result.output.contains("'testing/'"), result.output)
    }

    @Test
    fun `absorbs root housekeeping - version propagation, allDependencies, wrapper distribution`() {
        writeSettings(
            """
            plugins {
                id("org.framefork.build")
            }

            rootProject.name = "consumer"
            """.trimIndent(),
        )
        // Trailing whitespace is preserved by java.util.Properties; project.version must come out trimmed.
        write("gradle.properties", "version=1.2.3-TEST   \n")

        // The root build script runs after beforeProject, so it observes the propagated version and the
        // ALL-configured wrapper the plugin wired in.
        write(
            "build.gradle.kts",
            """
            tasks.register("printRoot") {
                // Captured as task-local vals (plain scalars) so the doLast action stays configuration-cache-safe.
                val wrapperDist = tasks.named<Wrapper>("wrapper").get().distributionType
                val rootVersion = project.version.toString()
                doLast {
                    println("ROOT version=" + rootVersion)
                    println("ROOT wrapperDistribution=" + wrapperDist)
                }
            }
            """.trimIndent(),
        )
        write(
            "modules/foo/build.gradle.kts",
            """
            tasks.register("printModule") {
                val moduleVersion = project.version.toString()
                doLast {
                    println("MODULE version=" + moduleVersion)
                }
            }
            """.trimIndent(),
        )

        val arguments = arrayOf(":printRoot", ":foo:printModule", ":allDependencies", ":foo:allDependencies", "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace")

        val result = frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments)
            .build()

        // Version flows from gradle.properties into every project (root and module), trimmed.
        assertTrue(result.output.contains("ROOT version=1.2.3-TEST"), result.output)
        assertTrue(result.output.contains("MODULE version=1.2.3-TEST"), result.output)
        // Wrapper distribution type is configured to ALL on the root.
        assertTrue(result.output.contains("ROOT wrapperDistribution=ALL"), result.output)
        // allDependencies runs per project and writes its report.
        assertTrue(result.output.contains("BUILD SUCCESSFUL"), result.output)
        assertTrue(projectDir.resolve("build/reports/dependencies.txt").isFile, "root allDependencies report should exist")
        assertTrue(projectDir.resolve("modules/foo/build/reports/dependencies.txt").isFile, "module allDependencies report should exist")

        // CC store then reuse must keep working.
        val reuse = frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*arguments)
            .build()
        assertTrue(reuse.output.contains("Reusing configuration cache") || reuse.output.contains("Configuration cache entry reused"), reuse.output)
    }

    @Test
    fun `leaves version untouched when the version property is absent`() {
        writeSettings(
            """
            plugins {
                id("org.framefork.build")
            }

            rootProject.name = "consumer"
            """.trimIndent(),
        )
        write("build.gradle.kts", "")
        write(
            "modules/foo/build.gradle.kts",
            """
            tasks.register("printModule") {
                val moduleVersion = project.version.toString()
                doLast {
                    println("MODULE version=" + moduleVersion)
                }
            }
            """.trimIndent(),
        )

        val result = frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(":foo:printModule", "--configuration-cache", "--stacktrace")
            .build()

        // No `version` gradle property ⇒ the plugin leaves Gradle's "unspecified" default rather than crashing.
        assertTrue(result.output.contains("MODULE version=unspecified"), result.output)
    }

    private fun writeSettings(content: String) = write("settings.gradle.kts", content)

    private fun write(relativePath: String, content: String) {
        val file = projectDir.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
