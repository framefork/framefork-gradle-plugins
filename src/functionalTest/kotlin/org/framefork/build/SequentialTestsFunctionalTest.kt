package org.framefork.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SequentialTestsFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    @Test
    fun `sequentialTests makes two modules' test JVMs mutually exclusive`() {
        writeConsumer(sequentialTests = true)
        writeCanaryTest("alpha")
        writeCanaryTest("beta")

        // Both test tasks are eligible to run concurrently (org.gradle.parallel, workers.max=4). Each test grabs a single
        // shared canary file (File.createNewFile is atomic), sleeps, then deletes it; if two test JVMs ever overlapped the
        // second createNewFile would return false and the assertion would fail. A green build therefore proves non-overlap.
        val result = runner(":alpha:test", ":beta:test").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":alpha:test")?.outcome, result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":beta:test")?.outcome, result.output)
    }

    @Test
    fun `the opt-out default (no knob) registers the one-permit test-serializer shared service`() {
        writeConsumer(sequentialTests = null)

        val result = runner(":printSharedServices").build()

        assertTrue(result.output.lineSequence().any { it.startsWith("SHARED_SERVICES=") && it.contains("frameforkTestSerializer") }, result.output)
    }

    @Test
    fun `sequentialTests = false opts out and registers no test-serializer service`() {
        writeConsumer(sequentialTests = false)

        val result = runner(":printSharedServices").build()

        val line = result.output.lineSequence().first { it.startsWith("SHARED_SERVICES=") }
        assertFalse(line.contains("frameforkTestSerializer"), line)
    }

    @Test
    fun `a sequential-tests consumer build is configuration-cache clean across store and reuse`() {
        writeConsumer(sequentialTests = true)
        writeCanaryTest("alpha")
        writeCanaryTest("beta")

        val args = arrayOf(":alpha:test", ":beta:test", "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace")

        val first = frameforkRunner().withProjectDir(projectDir).withPluginClasspath().withArguments(*args).build()
        assertTrue(first.output.contains("Configuration cache entry stored"), first.output)

        val second = frameforkRunner().withProjectDir(projectDir).withPluginClasspath().withArguments(*args).build()
        assertTrue(second.output.contains("Configuration cache entry reused"), second.output)
    }

    private fun runner(vararg args: String): GradleRunner =
        frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--configuration-cache", "--stacktrace")

    private fun writeConsumer(sequentialTests: Boolean?) {
        val toggle = when (sequentialTests) {
            null -> "" // omit the knob entirely, exercising the opt-out default
            else -> "sequentialTests = $sequentialTests"
        }
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

        // Parallel execution with several worker slots is what a serialized Test task must hold back against; without it
        // the mutual-exclusion constraint would be untested (nothing else could ever run alongside a test).
        write(
            "gradle.properties",
            """
            org.gradle.parallel=true
            org.gradle.workers.max=4
            """.trimIndent(),
        )

        // Snapshot the registered shared-service names at configuration time into a task-local String (a plain scalar —
        // a script-level val would drag the script object reference into the config cache and fail the store). This gives
        // the off/on assertions an observable signal; beforeProject runs before this script, so a service the init action
        // registers is already visible here.
        write(
            "build.gradle.kts",
            """
            tasks.register("printSharedServices") {
                val sharedServices = "SHARED_SERVICES=" + gradle.sharedServices.registrations.names.sorted().joinToString(",")
                doLast {
                    println(sharedServices)
                }
            }
            """.trimIndent(),
        )

        for (module in listOf("alpha", "beta")) {
            write(
                "modules/$module/build.gradle.kts",
                """
                plugins {
                    id("org.framefork.build.library-internal")
                }

                tasks.withType<Test>().configureEach {
                    systemProperty("framefork.mutexCanary", File(rootDir, "build/test-mutex/canary.lock").absolutePath)
                }
                """.trimIndent(),
            )
        }
    }

    private fun writeCanaryTest(module: String) {
        write(
            "modules/$module/src/test/java/$module/MutexCanaryTest.java",
            """
            package $module;

            import static org.junit.jupiter.api.Assertions.assertTrue;

            import java.io.File;
            import org.junit.jupiter.api.Test;

            final class MutexCanaryTest {

                @Test
                void onlyOneTestJvmRunsAtATime() throws Exception {
                    File canary = new File(System.getProperty("framefork.mutexCanary"));
                    canary.getParentFile().mkdirs();
                    // createNewFile is atomic: it returns false if the file already exists, i.e. another test JVM holds it.
                    assertTrue(canary.createNewFile(), "another test JVM held the canary — test JVMs overlapped: " + canary);
                    try {
                        Thread.sleep(2000);
                    } finally {
                        assertTrue(canary.delete(), "canary vanished unexpectedly: " + canary);
                    }
                }
            }
            """.trimIndent(),
        )
    }

    private fun write(relativePath: String, content: String) {
        val file = projectDir.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
