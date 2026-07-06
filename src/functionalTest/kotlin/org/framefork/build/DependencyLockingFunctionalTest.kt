package org.framefork.build

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DependencyLockingFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    @Test
    fun `enabled toggle registers resolveAndLockAll and --write-locks generates a lockfile`() {
        writeConsumerProject(dependencyLocking = true)

        // resolveAndLockAll self-marks as configuration-cache-incompatible (it filters and resolves configurations at
        // execution time), so this maintenance invocation opts out of the cache the shared runner would otherwise enable.
        val lockResult = runner(":foo:resolveAndLockAll", "--write-locks", "--no-configuration-cache").build()
        assertTrue(lockResult.output.contains("BUILD SUCCESSFUL"), lockResult.output)

        val lockfile = projectDir.resolve("modules/foo/gradle.lockfile")
        assertTrue(lockfile.isFile, "resolveAndLockAll --write-locks must produce modules/foo/gradle.lockfile")
        assertTrue(lockfile.readText().contains("org.jspecify:jspecify:1.0.0"), lockfile.readText())

        // A normal build with the lockfile committed still resolves (DEFAULT lock mode, config cache on).
        val normalResult = runner(":foo:dependencies", "--configuration-cache").build()
        assertTrue(normalResult.output.contains("BUILD SUCCESSFUL"), normalResult.output)
    }

    @Test
    fun `resolveAndLockAll without --write-locks fails with the guard message`() {
        writeConsumerProject(dependencyLocking = true)

        val result = runner(":foo:resolveAndLockAll", "--no-configuration-cache").buildAndFail()

        assertTrue(result.output.contains(":foo:resolveAndLockAll must be run with --write-locks"), result.output)
    }

    @Test
    fun `disabled by default registers no lock task and demands no lockfile`() {
        writeConsumerProject(dependencyLocking = false)

        // The task must not exist when locking is off.
        val missing = runner(":foo:resolveAndLockAll", "--no-configuration-cache").buildAndFail()
        assertTrue(missing.output.contains("resolveAndLockAll"), missing.output)
        assertTrue(missing.output.contains("not found") || missing.output.contains("Cannot locate"), missing.output)

        // A normal build resolves without any lockfile being demanded or produced.
        val normalResult = runner(":foo:dependencies", "--configuration-cache").build()
        assertTrue(normalResult.output.contains("BUILD SUCCESSFUL"), normalResult.output)
        assertFalse(projectDir.resolve("modules/foo/gradle.lockfile").exists(), "no lockfile without the toggle")
    }

    private fun runner(vararg args: String): GradleRunner =
        frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--stacktrace")

    private fun writeConsumerProject(dependencyLocking: Boolean) {
        val toggle = if (dependencyLocking) "dependencyLocking = true" else ""
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("org.framefork.build")
            }

            framefork {
                minJavaVersion = 17
                jdkVersion = 21
                $toggle
            }

            rootProject.name = "consumer"
            """.trimIndent(),
        )
        write("build.gradle.kts", "")

        write(
            "modules/foo/build.gradle.kts",
            """
            plugins {
                id("org.framefork.build.library-internal")
            }

            dependencies {
                implementation("org.jspecify:jspecify:1.0.0")
            }
            """.trimIndent(),
        )
        write("modules/foo/src/main/java/foo/Foo.java", "package foo;\n\npublic final class Foo {\n}\n")
    }

    private fun write(relativePath: String, content: String) {
        val file = projectDir.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
