package org.framefork.build

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class JavadocConventionsFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    @Test
    fun `undocumented public API builds javadoc without no-comment warnings, and the JDK 25 javadoc jar bundles no fonts`() {
        writeConsumerProject(jdkVersion = 25, source = UNDOCUMENTED_API)

        val result = runner(":foo:javadocJar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:javadoc")?.outcome, result.output)
        assertFalse(result.output.contains("warning: no comment"), result.output)
        val options = javadocOptions()
        assertTrue(options.contains("-Xdoclint:all,-missing"), options)
        assertTrue(options.contains("--no-fonts"), options)
        // Gradle's own default (JavadocOutputLevel.QUIET), which is why the suite does not add it.
        assertTrue(options.contains("-quiet"), options)

        val entries = javadocJarEntries()
        assertTrue(entries.contains("foo/Foo.html"), "javadoc was generated: $entries")
        assertTrue(entries.none { it.contains("fonts/") || it.endsWith(".woff") || it.endsWith(".woff2") }, "no bundled fonts: $entries")
    }

    @Test
    fun `a module can opt back into the missing-comment checks with plain javadoc options`() {
        // Control for the test above: the same sources do warn once the module swaps the suite's doclint option for the
        // full one — the per-module opt-out docs/troubleshooting.md documents, verbatim.
        writeConsumerProject(
            jdkVersion = 25,
            source = UNDOCUMENTED_API,
            extraBuildScript = """
                tasks.javadoc {
                    (options as CoreJavadocOptions).addBooleanOption("Xdoclint:all,-missing", false)
                    (options as CoreJavadocOptions).addBooleanOption("Xdoclint:all", true)
                }
            """.trimIndent(),
        )

        val result = runner(":foo:javadoc").build()

        assertTrue(result.output.contains("warning: no comment"), result.output)
    }

    @Test
    fun `javadoc on a JDK without --no-fonts still builds and is not passed the flag`() {
        writeConsumerProject(jdkVersion = 21, source = UNDOCUMENTED_API)

        val result = runner(":foo:javadocJar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:javadoc")?.outcome, result.output)
        assertFalse(result.output.contains("warning: no comment"), result.output)
        val options = javadocOptions()
        assertTrue(options.contains("-Xdoclint:all,-missing"), options)
        assertFalse(options.contains("--no-fonts"), options)
        assertTrue(javadocJarEntries().contains("foo/Foo.html"), "javadoc was generated")
    }

    @Test
    fun `malformed HTML in a doc comment still fails javadoc`() {
        writeConsumerProject(
            jdkVersion = 25,
            source = """
                package foo;

                /** Foo.<p/>Second paragraph. */
                public final class Foo {
                }
            """.trimIndent(),
        )

        val result = runner(":foo:javadoc").buildAndFail()

        assertTrue(result.output.contains("error: self-closing element not allowed"), result.output)
    }

    @Test
    fun `an unknown @param fails the build already at compileJava, through Error Prone`() {
        writeConsumerProject(
            jdkVersion = 25,
            source = """
                package foo;

                /** Foo. */
                public final class Foo {

                    /**
                     * Greets.
                     *
                     * @param nope not a parameter
                     * @return a greeting
                     */
                    public static String greet() {
                        return "hi";
                    }
                }
            """.trimIndent(),
        )

        val result = runner(":foo:javadoc").buildAndFail()

        // Error Prone's InvalidParam under -Werror rejects it before javadoc runs, so doclint never gets to see it.
        assertEquals(TaskOutcome.FAILED, result.task(":foo:compileJava")?.outcome, result.output)
        assertTrue(result.output.contains("[InvalidParam] Parameter name `nope` is unknown"), result.output)
    }

    @Test
    fun `a broken link still fails javadoc`() {
        writeConsumerProject(
            jdkVersion = 25,
            source = """
                package foo;

                /** Foo, see {@link Nope}. */
                public final class Foo {
                }
            """.trimIndent(),
        )

        val result = runner(":foo:javadoc").buildAndFail()

        assertTrue(result.output.contains("error: reference not found"), result.output)
    }

    @Test
    fun `library-internal modules get the same javadoc defaults`() {
        writeConsumerProject(jdkVersion = 25, source = UNDOCUMENTED_API, plugin = "org.framefork.build.library-internal")

        val result: BuildResult = runner(":foo:javadoc").build()

        assertFalse(result.output.contains("warning: no comment"), result.output)
        val options = javadocOptions()
        assertTrue(options.contains("-Xdoclint:all,-missing"), options)
        assertTrue(options.contains("--no-fonts"), options)
    }

    private fun javadocOptions(): String = projectDir.resolve("modules/foo/build/tmp/javadoc/javadoc.options").readText()

    private fun javadocJarEntries(): List<String> =
        ZipFile(projectDir.resolve("modules/foo/build/libs/foo-1.2.3-javadoc.jar")).use { zip -> zip.entries().toList().map { it.name } }

    private fun runner(vararg args: String): GradleRunner =
        frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace")

    private fun writeConsumerProject(
        jdkVersion: Int,
        source: String,
        plugin: String = "org.framefork.build.library-published",
        extraBuildScript: String = "",
    ) {
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("org.framefork.build")
            }

            framefork {
                minJavaVersion = 17
                jdkVersion = $jdkVersion
            }

            rootProject.name = "consumer"
            """.trimIndent(),
        )
        write("build.gradle.kts", "")
        write(
            "modules/foo/build.gradle.kts",
            """
            |plugins {
            |    id("$plugin")
            |}
            |
            |group = "org.framefork"
            |version = "1.2.3"
            |
            |$extraBuildScript
            """.trimMargin(),
        )
        write("modules/foo/src/main/java/foo/Foo.java", source + "\n")
    }

    private fun write(relativePath: String, content: String) {
        val file = projectDir.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private companion object {
        /** Public class, constructor and method with no doc comments at all — each one a `no comment` warning under javadoc's default doclint. */
        val UNDOCUMENTED_API = """
            package foo;

            public final class Foo {

                public Foo() {
                }

                public String greet(String name) {
                    return "hi " + name;
                }
            }
        """.trimIndent()
    }
}
