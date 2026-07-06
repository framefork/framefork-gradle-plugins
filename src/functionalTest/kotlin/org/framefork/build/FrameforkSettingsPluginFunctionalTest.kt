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

    private fun writeSettings(content: String) = write("settings.gradle.kts", content)

    private fun write(relativePath: String, content: String) {
        val file = projectDir.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
