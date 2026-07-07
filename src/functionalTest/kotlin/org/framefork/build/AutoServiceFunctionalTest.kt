package org.framefork.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarFile

class AutoServiceFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    @Test
    fun `auto-service generates the service file into the jar under the full strictness stack`() {
        writeConsumerProject()
        writeService()

        val result = runner(":foo:jar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:jar")?.outcome, result.output)

        val jar = projectDir.resolve("modules/foo/build/libs").listFiles { f -> f.name.endsWith(".jar") }?.singleOrNull()
        assertNotNull(jar, "expected exactly one built jar under modules/foo/build/libs, got ${projectDir.resolve("modules/foo/build/libs").listFiles()?.toList()}")
        JarFile(jar).use { jf ->
            // The @AutoService(Greeter.class) annotation must have produced a service registration for the interface FQN,
            // listing the annotated implementation FQN — the whole point of the processor being wired.
            val entry = jf.getJarEntry("META-INF/services/foo.Greeter")
            assertNotNull(entry, "expected META-INF/services/foo.Greeter in the jar; entries=${jf.entries().toList().map { it.name }}")
            val content = jf.getInputStream(entry).bufferedReader().readText()
            assertTrue(content.contains("foo.LoudGreeter"), "service file should register the implementation FQN, was: $content")
        }
    }

    @Test
    fun `a consumer build with auto-service is configuration-cache clean across store and reuse`() {
        writeConsumerProject()
        writeService()

        val first = runner(":foo:jar").build()
        assertTrue(first.output.contains("Configuration cache entry stored"), first.output)
        assertTrue(!first.output.contains("Configuration cache disabled"), first.output)
        assertTrue(!first.output.contains("notCompatibleWithConfigurationCache"), first.output)

        val second = runner(":foo:jar").build()
        assertTrue(second.output.contains("Configuration cache entry reused"), second.output)
        assertTrue(!second.output.contains("Configuration cache disabled"), second.output)
        assertTrue(!second.output.contains("notCompatibleWithConfigurationCache"), second.output)
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
                id("org.framefork.build.auto-service")
            }
            """.trimIndent(),
        )
    }

    private fun writeService() {
        write(
            "modules/foo/src/main/java/foo/Greeter.java",
            """
            package foo;

            public interface Greeter {

                String greet();
            }
            """.trimIndent(),
        )
        // @AutoService(Greeter.class) drives the processor; the class is null-clean so it also survives NullAway + -Werror,
        // proving the auto-service processor coexists with the Error Prone processor in the same javac round.
        write(
            "modules/foo/src/main/java/foo/LoudGreeter.java",
            """
            package foo;

            import com.google.auto.service.AutoService;

            @AutoService(Greeter.class)
            public final class LoudGreeter implements Greeter {

                @Override
                public String greet() {
                    return "HELLO";
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
