package org.framefork.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StaticAnalysisFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    @Test
    fun `a null-clean NullMarked source compiles`() {
        writeConsumerProject()
        write(
            "modules/foo/src/main/java/foo/Greeter.java",
            """
            package foo;

            public final class Greeter {

                private Greeter() {
                }

                public static String greeting() {
                    return "hello";
                }
            }
            """.trimIndent(),
        )

        val result = runner(":foo:compileJava").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:compileJava")?.outcome, result.output)
        // Our generator emits the @NullMarked package-info that onlyNullMarked needs to actually analyse the package.
        val generated = projectDir.resolve("modules/foo/build/generated/sources/framefork-nullmarked/java/main/foo/package-info.java")
        assertTrue(generated.isFile, "expected a generated package-info at $generated")
        assertTrue(generated.readText().contains("@NullMarked"), generated.readText())
        assertTrue(generated.readText().contains("package foo;"), generated.readText())
    }

    @Test
    fun `a hand-written package-info is not overwritten and does not cause a duplicate`() {
        writeConsumerProject()
        write(
            "modules/foo/src/main/java/foo/Greeter.java",
            """
            package foo;

            public final class Greeter {

                private Greeter() {
                }

                public static String greeting() {
                    return "hello";
                }
            }
            """.trimIndent(),
        )
        // A hand-authored package-info in the same package: the generator must skip it so javac never sees two of them.
        write(
            "modules/foo/src/main/java/foo/package-info.java",
            """
            @NullMarked
            package foo;

            import org.jspecify.annotations.NullMarked;
            """.trimIndent(),
        )

        val result = runner(":foo:compileJava").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:compileJava")?.outcome, result.output)
        val generated = projectDir.resolve("modules/foo/build/generated/sources/framefork-nullmarked/java/main/foo/package-info.java")
        assertTrue(!generated.exists(), "generator must not emit a package-info for a package that already has a hand-written one")
    }

    @Test
    fun `a source root added later in the module build script is covered by @NullMarked generation`() {
        writeConsumerProject()
        // A consumer grafts an extra source root in its own build script — which runs after the plugin's apply() — so a
        // snapshot taken during apply() would miss it. The generator must derive its roots lazily and still cover it.
        write(
            "modules/foo/build.gradle.kts",
            """
            plugins {
                id("org.framefork.build.library-published")
            }

            sourceSets.main {
                java.srcDir("src/generated-api/java")
            }
            """.trimIndent(),
        )
        write(
            "modules/foo/src/generated-api/java/api/Api.java",
            """
            package api;

            public final class Api {

                private Api() {
                }

                public static String name() {
                    return "api";
                }
            }
            """.trimIndent(),
        )

        val result = runner(":foo:compileJava").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:compileJava")?.outcome, result.output)
        val generated = projectDir.resolve("modules/foo/build/generated/sources/framefork-nullmarked/java/main/api/package-info.java")
        assertTrue(generated.isFile, "expected a generated package-info for the late-added source root at $generated")
        assertTrue(generated.readText().contains("@NullMarked"), generated.readText())
        assertTrue(generated.readText().contains("package api;"), generated.readText())
    }

    @Test
    fun `a consumer build is configuration-cache clean across store and reuse`() {
        writeConsumerProject()
        write(
            "modules/foo/src/main/java/foo/Greeter.java",
            """
            package foo;

            public final class Greeter {

                private Greeter() {
                }

                public static String greeting() {
                    return "hello";
                }
            }
            """.trimIndent(),
        )

        val first = runner(":foo:compileJava").build()
        assertTrue(first.output.contains("Configuration cache entry stored"), first.output)
        assertTrue(!first.output.contains("Configuration cache disabled"), first.output)
        assertTrue(!first.output.contains("notCompatibleWithConfigurationCache"), first.output)

        val second = runner(":foo:compileJava").build()
        assertTrue(second.output.contains("Configuration cache entry reused"), second.output)
        assertTrue(!second.output.contains("Configuration cache disabled"), second.output)
        assertTrue(!second.output.contains("notCompatibleWithConfigurationCache"), second.output)
    }

    @Test
    fun `checker-qual is on the compile classpath so Checker-Framework annotations do not break -Werror`() {
        writeConsumerProject()
        // A dependency's bytecode carrying Checker-Framework annotations (referencing TypeUseLocation) makes javac warn
        // `unknown enum constant TypeUseLocation.*` when checker-qual is absent, which -Werror turns into a failure.
        // Referencing a checker-qual symbol directly from source is the minimal, network-free proxy: it only resolves
        // (and only compiles under -Werror) when the convention has put checker-qual on the compile classpath.
        write(
            "modules/foo/src/main/java/foo/UsesChecker.java",
            """
            package foo;

            import org.checkerframework.checker.nullness.qual.NonNull;

            public final class UsesChecker {

                private UsesChecker() {
                }

                public static @NonNull String value() {
                    return "hello";
                }
            }
            """.trimIndent(),
        )

        val result = runner(":foo:compileJava").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:compileJava")?.outcome, result.output)
    }

    @Test
    fun `jsr305 is on the compile classpath so jsr305 annotations do not break -Werror`() {
        writeConsumerProject()
        // A dependency's bytecode carrying jsr305 annotations (e.g. `@Nonnull(When.MAYBE)`) makes javac warn
        // `unknown enum constant When.MAYBE` when jsr305 is absent, which -Werror turns into a failure.
        // Referencing a jsr305 symbol directly from source is the minimal, network-free proxy: it only resolves
        // (and only compiles under -Werror) when the convention has put jsr305 on the compile classpath.
        // @Nonnull (not @CheckForNull) is used here because it is consistent with the @NullMarked default and
        // does not produce a NullAway finding that would mask the classpath-presence signal.
        write(
            "modules/foo/src/main/java/foo/UsesJsr305.java",
            """
            package foo;

            import javax.annotation.Nonnull;

            public final class UsesJsr305 {

                private UsesJsr305() {
                }

                public static @Nonnull String value() {
                    return "hello";
                }
            }
            """.trimIndent(),
        )

        val result = runner(":foo:compileJava").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:compileJava")?.outcome, result.output)
    }

    @Test
    fun `a real nullness violation fails compileJava with a NullAway error`() {
        writeConsumerProject()
        write(
            "modules/foo/src/main/java/foo/Broken.java",
            """
            package foo;

            public final class Broken {

                private Broken() {
                }

                public static String mustNotBeNull() {
                    return null;
                }
            }
            """.trimIndent(),
        )

        val result = runner(":foo:compileJava").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":foo:compileJava")?.outcome, result.output)
        assertTrue(result.output.contains("NullAway"), result.output)
    }

    @Test
    fun `jspecifyMode on catches a generics-only nullness violation`() {
        writeConsumerProject()
        write("modules/foo/src/main/java/foo/Generics.java", genericsViolationSource())

        val result = runner(":foo:compileJava").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":foo:compileJava")?.outcome, result.output)
        assertTrue(result.output.contains("NullAway"), result.output)
    }

    @Test
    fun `jspecifyMode off no longer fails on a generics-only nullness violation`() {
        writeConsumerProject(frameforkConfig = "minJavaVersion = 17\n    jdkVersion = 21\n    jspecifyMode = false")
        write("modules/foo/src/main/java/foo/Generics.java", genericsViolationSource())

        val result = runner(":foo:compileJava").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:compileJava")?.outcome, result.output)
    }

    // A @Nullable type argument flowing into a slot expecting a non-null type argument: only NullAway's JSpecify
    // generics mode (jspecifyMode) reasons about type-argument nullness, so this fails only when jspecifyMode is on.
    private fun genericsViolationSource(): String =
        """
        package foo;

        import org.jspecify.annotations.Nullable;

        public final class Generics {

            private Generics() {
            }

            interface Box<T> {
                T get();
            }

            static String unwrap(Box<String> box) {
                return box.get();
            }

            static void leak(Box<@Nullable String> box) {
                unwrap(box);
            }
        }
        """.trimIndent()

    private fun runner(vararg args: String): GradleRunner =
        frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--configuration-cache", "--stacktrace")

    private fun writeConsumerProject(frameforkConfig: String = "minJavaVersion = 17\n    jdkVersion = 21") {
        write(
            "settings.gradle.kts",
            """
            plugins {
                id("org.framefork.build")
            }

            framefork {
                $frameforkConfig
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
