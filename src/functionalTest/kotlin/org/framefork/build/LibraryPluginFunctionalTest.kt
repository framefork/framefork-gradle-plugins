package org.framefork.build

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LibraryPluginFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    @Test
    fun `library-published applies the three-knob java conventions on the real settings stack`() {
        writeConsumerProject()

        val result = runner(":foo:frameforkProbe", ":foo:compileJava").build()

        // compileJava reaching SUCCESS/UP-TO-DATE proves --release 17 bytecode compiled on the JDK 21 toolchain.
        assertTrue(result.output.contains("PROBE release=17"), result.output)
        assertTrue(result.output.contains("PROBE compileJdk=21"), result.output)
        assertTrue(result.output.contains("PROBE testJdk=21"), result.output)
    }

    @Test
    fun `-PtestsJdkVersion switches only the test-runtime launcher`() {
        writeConsumerProject()

        val result = runner(":foo:frameforkProbe", "-PtestsJdkVersion=25").build()

        assertTrue(result.output.contains("PROBE release=17"), result.output)
        assertTrue(result.output.contains("PROBE compileJdk=21"), result.output)
        assertTrue(result.output.contains("PROBE testJdk=25"), result.output)
    }

    private fun runner(vararg args: String): GradleRunner =
        GradleRunner.create()
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

        // A task-based probe reads the resolved task configuration directly, so assertions don't scrape build logs.
        // The providers are captured as lambda locals to keep the doLast action configuration-cache-safe.
        write(
            "modules/foo/build.gradle.kts",
            """
            plugins {
                id("org.framefork.build.library-published")
            }

            tasks.register("frameforkProbe") {
                val compileJava = tasks.named<JavaCompile>("compileJava").get()
                val release = compileJava.options.release.get()
                val compileJdk = compileJava.javaCompiler.get().metadata.languageVersion.asInt()
                val testJdk = tasks.named<Test>("test").get().javaLauncher.get().metadata.languageVersion.asInt()
                doLast {
                    println("PROBE release=" + release)
                    println("PROBE compileJdk=" + compileJdk)
                    println("PROBE testJdk=" + testJdk)
                }
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
