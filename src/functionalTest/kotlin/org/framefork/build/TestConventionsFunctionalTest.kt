package org.framefork.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TestConventionsFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    @Test
    fun `a JUnit 5 test runs on the JUnit Platform and succeeds`() {
        writeConsumerProject()
        write(
            "modules/foo/src/test/java/foo/GreeterTest.java",
            """
            package foo;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import org.junit.jupiter.api.Test;

            final class GreeterTest {

                @Test
                void greetingIsHello() {
                    assertEquals("hello", "hello");
                }
            }
            """.trimIndent(),
        )

        val result = runner(":foo:test").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:test")?.outcome, result.output)
        // The JUnit Platform ran the test only if it emitted a result for the method: proves useJUnitPlatform() took effect.
        val resultsXml = projectDir.resolve("modules/foo/build/test-results/test/TEST-foo.GreeterTest.xml")
        assertTrue(resultsXml.isFile, "expected JUnit Platform test-results XML at $resultsXml\n${result.output}")
        assertTrue(resultsXml.readText().contains("greetingIsHello"), resultsXml.readText())
    }

    @Test
    fun `a transitive junit4 is substituted with the quarkus mock on the test classpath`() {
        writeConsumerProject()
        // Declare JUnit 4 directly; the substitution must redirect it to the quarkus no-op regardless of how it arrives.
        write(
            "modules/foo/build.gradle.kts",
            """
            plugins {
                id("org.framefork.build.library-published")
            }

            dependencies {
                testImplementation("junit:junit:4.13.2")
            }

            val testRuntimeClasspath = configurations.named("testRuntimeClasspath")
            tasks.register("printTestRuntimeClasspath") {
                val files = testRuntimeClasspath.map { it.incoming.files }
                doLast {
                    files.get().forEach { println("CLASSPATH " + it.name) }
                }
            }
            """.trimIndent(),
        )

        val result = runner(":foo:printTestRuntimeClasspath").build()

        assertTrue(result.output.contains("CLASSPATH quarkus-junit4-mock-3.0.0.Final.jar"), result.output)
        assertTrue(!result.output.contains("CLASSPATH junit-4.13.2.jar"), result.output)
    }

    private fun runner(vararg args: String): GradleRunner =
        frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--configuration-cache", "--stacktrace")

    private fun writeConsumerProject() {
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("org.framefork.build")
            }

            framefork {
                minJavaVersion = 17
                jdkVersion = 21
            }

            rootProject.name = "consumer"
            """.trimIndent(),
        )
        write("build.gradle.kts", "")
        write(
            "modules/foo/build.gradle.kts",
            """
            plugins {
                id("org.framefork.build.library-published")
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
