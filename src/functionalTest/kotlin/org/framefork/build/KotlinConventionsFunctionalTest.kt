package org.framefork.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

class KotlinConventionsFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    @Test
    fun `a module with Kotlin sources compiles, targets minJavaVersion, and ships Kotlin classes in its jar`() {
        writeConsumerSettings()
        write(
            "modules/kt/build.gradle.kts",
            """
            plugins {
                id("org.framefork.build.library-published")
            }

            group = "org.framefork"
            version = "1.0.0"
            """.trimIndent(),
        )
        write(
            "modules/kt/src/main/kotlin/kt/Greeter.kt",
            """
            package kt

            public class Greeter {
                public fun greeting(): String = "hello"
            }
            """.trimIndent(),
        )

        val result = runner(":kt:compileKotlin", ":kt:jar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":kt:compileKotlin")?.outcome, result.output)

        // minJavaVersion is 17 while the compile toolchain is 21: bytecode major 61 (== Java 17) proves jvmTarget followed
        // minJavaVersion, not the toolchain (which would be major 65).
        val kotlinClass = projectDir.resolve("modules/kt/build/classes/kotlin/main/kt/Greeter.class")
        assertTrue(kotlinClass.isFile, "expected a compiled Kotlin class at $kotlinClass")
        assertEquals(61, classFileMajorVersion(kotlinClass), "compiled Kotlin bytecode must target minJavaVersion (17 => major 61)")

        val jar = projectDir.resolve("modules/kt/build/libs/kt-1.0.0.jar")
        assertTrue(jar.isFile, "expected a jar at $jar")
        ZipFile(jar).use { zip ->
            assertTrue(zip.getEntry("kt/Greeter.class") != null, "the Kotlin class must be packaged into the jar")
        }
    }

    @Test
    fun `a version-less kotlin serialization companion resolves from the suite classpath and compiles a @Serializable class`() {
        writeConsumerSettings()
        // No Kotlin version anywhere: kotlin("plugin.serialization") must resolve its marker from the suite's classpath,
        // exactly the hard-fail the typed-ids pilot hit. Point.serializer() only resolves if the serialization compiler
        // plugin actually ran, so a green compile is proof the version-less companion was wired in.
        write(
            "modules/srz/build.gradle.kts",
            """
            plugins {
                id("org.framefork.build.library-published")
                kotlin("plugin.serialization")
            }

            group = "org.framefork"
            version = "1.0.0"

            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            }
            """.trimIndent(),
        )
        write(
            "modules/srz/src/main/kotlin/srz/Point.kt",
            """
            package srz

            import kotlinx.serialization.KSerializer
            import kotlinx.serialization.Serializable

            @Serializable
            public class Point(
                public val x: Int,
                public val y: Int,
            )

            public fun pointSerializer(): KSerializer<Point> = Point.serializer()
            """.trimIndent(),
        )

        val result = runner(":srz:compileKotlin").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":srz:compileKotlin")?.outcome, result.output)
    }

    @Test
    fun `a Java-only module never applies Kotlin - no compileKotlin task exists`() {
        writeConsumerSettings()
        write(
            "modules/onlyjava/build.gradle.kts",
            """
            plugins {
                id("org.framefork.build.library-published")
            }

            group = "org.framefork"
            version = "1.0.0"

            tasks.register("assertNoKotlin") {
                val hasCompileKotlin = tasks.findByName("compileKotlin") != null
                doLast {
                    if (hasCompileKotlin) {
                        throw GradleException("compileKotlin exists on a pure-Java module; the Kotlin plugin was applied unexpectedly")
                    }
                    println("PROBE noKotlin=true")
                }
            }
            """.trimIndent(),
        )
        write("modules/onlyjava/src/main/java/onlyjava/Foo.java", "package onlyjava;\n\npublic final class Foo {\n}\n")

        val result = runner(":onlyjava:assertNoKotlin", ":onlyjava:compileJava").build()

        assertTrue(result.output.contains("PROBE noKotlin=true"), result.output)
        assertEquals(TaskOutcome.SUCCESS, result.task(":onlyjava:compileJava")?.outcome, result.output)
    }

    private fun classFileMajorVersion(classFile: File): Int =
        DataInputStream(classFile.inputStream().buffered()).use { input ->
            input.readInt() // 0xCAFEBABE magic
            input.readUnsignedShort() // minor version
            input.readUnsignedShort() // major version
        }

    @Test
    fun `a Kotlin consumer build is configuration-cache clean across store and reuse`() {
        writeConsumerSettings()
        write(
            "modules/kt/build.gradle.kts",
            """
            plugins {
                id("org.framefork.build.library-published")
            }

            group = "org.framefork"
            version = "1.0.0"
            """.trimIndent(),
        )
        write(
            "modules/kt/src/main/kotlin/kt/Greeter.kt",
            """
            package kt

            public class Greeter {
                public fun greeting(): String = "hello"
            }
            """.trimIndent(),
        )

        // The Kotlin-source detection runs through a ValueSource, so the filesystem probe is a tracked config-cache input;
        // a clean store then reuse proves that path introduces no untracked config-time read.
        val first = runner(":kt:compileKotlin").build()
        assertTrue(first.output.contains("Configuration cache entry stored"), first.output)
        assertTrue(!first.output.contains("Configuration cache disabled"), first.output)

        val second = runner(":kt:compileKotlin").build()
        assertTrue(second.output.contains("Configuration cache entry reused"), second.output)
        assertTrue(!second.output.contains("Configuration cache disabled"), second.output)
    }

    private fun runner(vararg args: String): GradleRunner =
        frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace")

    private fun writeConsumerSettings() {
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
    }

    private fun write(relativePath: String, content: String) {
        val file = projectDir.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
