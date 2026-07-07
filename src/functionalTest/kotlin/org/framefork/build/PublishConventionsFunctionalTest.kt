package org.framefork.build

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PublishConventionsFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    @Test
    fun `library-published emits jar, sources, javadoc and a full POM into the root staging repo`() {
        writeConsumerProject()

        runner(":foo:publish").build()

        val artifactDir = projectDir.resolve("build/staging-deploy/org/framefork/foo/1.2.3")
        assertTrue(artifactDir.resolve("foo-1.2.3.jar").isFile, "main jar published: ${artifactDir.list()?.toList()}")
        assertTrue(artifactDir.resolve("foo-1.2.3-sources.jar").isFile, "sources jar published")
        assertTrue(artifactDir.resolve("foo-1.2.3-javadoc.jar").isFile, "javadoc jar published")

        val pom = artifactDir.resolve("foo-1.2.3.pom")
        assertTrue(pom.isFile, "POM published")
        val pomText = pom.readText()
        assertTrue(pomText.contains("<artifactId>foo</artifactId>"), pomText)
        assertTrue(pomText.contains("<groupId>org.framefork</groupId>"), pomText)
        assertTrue(pomText.contains("<version>1.2.3</version>"), pomText)
        assertTrue(pomText.contains("Apache-2.0"), pomText)
        assertTrue(pomText.contains("<id>fprochazka</id>"), pomText)
        assertTrue(pomText.contains("<description>The Foo module</description>"), pomText)
        assertTrue(pomText.contains("https://github.com/framefork/consumer"), pomText)
        // compileOnly is rewritten to an optional compile-scoped dependency in the POM.
        assertTrue(pomText.contains("<artifactId>jsr305</artifactId>"), pomText)
        assertTrue(pomText.contains("<optional>true</optional>"), pomText)
    }

    @Test
    fun `publish wipes the staging repo before any module publishes`() {
        writeConsumerProject()

        val stagingDir = projectDir.resolve("build/staging-deploy")
        val stale = stagingDir.resolve("org/framefork/foo/9.9.9/foo-9.9.9.jar")
        stale.parentFile.mkdirs()
        stale.writeText("stale")

        val result = runner(":foo:publish").build()

        // cleanAllPublications lives on the root project and runs as a dependency of the module's publish.
        assertEquals(TaskOutcome.SUCCESS, result.task(":cleanAllPublications")?.outcome, result.output)
        assertFalse(stale.isFile, "stale staging artifact was wiped before publishing")
        assertTrue(projectDir.resolve("build/staging-deploy/org/framefork/foo/1.2.3/foo-1.2.3.jar").isFile, "fresh artifact published")
    }

    @Test
    fun `parallel publishToMavenLocal plus publish in one invocation wipes staging once without racing the writes`() {
        val moduleNames = (1..8).map { "mod$it" }
        writeMultiModuleConsumerProject(moduleNames)

        // parallel execution is the trigger: the staging-wipe must be ordered before the per-module staging *write*
        // tasks, not merely before the `publish` lifecycle aggregate, or the wipe races the writes into staging-deploy.
        write(
            "gradle.properties",
            """
            org.gradle.parallel=true
            org.gradle.workers.max=8
            systemProp.maven.repo.local=${projectDir.resolve("local-m2").absolutePath}
            """.trimIndent(),
        )

        // seed a stale artifact so the wipe has something to delete (SUCCESS, not UP-TO-DATE) — proving the wipe both
        // ran and, on the fixed wiring, completed before any staging write races into the same dir.
        val stale = projectDir.resolve("build/staging-deploy/org/framefork/mod1/9.9.9/mod1-9.9.9.jar")
        stale.parentFile.mkdirs()
        stale.writeText("stale")

        val result = runner("publishToMavenLocal", "publish").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":cleanAllPublications")?.outcome, result.output)
        assertFalse(stale.isFile, "stale staging artifact was wiped before publishing")
        for (module in moduleNames) {
            val jar = projectDir.resolve("build/staging-deploy/org/framefork/$module/1.2.3/$module-1.2.3.jar")
            assertTrue(jar.isFile, "staged jar for $module present: ${jar.parentFile.list()?.toList()}\n${result.output}")
            // the module's staging write task must be ordered after the shared wipe
            assertEquals(
                TaskOutcome.SUCCESS,
                result.task(":$module:publishMavenStagingPublicationToStagingRepository")?.outcome,
                result.output,
            )
        }
    }

    @Test
    fun `library-internal has no publish task at all`() {
        writeConsumerProject()

        val result = runner(":bar:publish").buildAndFail()

        assertTrue(
            result.output.contains("Task 'publish' not found in project ':bar'") ||
                result.output.contains("Cannot locate tasks that match ':bar:publish'"),
            result.output,
        )
    }

    private fun runner(vararg args: String): GradleRunner =
        frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace")

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

            group = "org.framefork"
            version = "1.2.3"
            description = "The Foo module"

            dependencies {
                compileOnly("com.google.code.findbugs:jsr305:3.0.2")
            }
            """.trimIndent(),
        )
        write("modules/foo/src/main/java/foo/Foo.java", "package foo;\n\npublic final class Foo {\n}\n")

        write(
            "testing/bar/build.gradle.kts",
            """
            plugins {
                id("org.framefork.build.library-internal")
            }

            group = "org.framefork"
            version = "1.2.3"
            """.trimIndent(),
        )
        write("testing/bar/src/main/java/bar/Bar.java", "package bar;\n\npublic final class Bar {\n}\n")
    }

    private fun writeMultiModuleConsumerProject(moduleNames: List<String>) {
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

        for (module in moduleNames) {
            write(
                "modules/$module/build.gradle.kts",
                """
                plugins {
                    id("org.framefork.build.library-published")
                }

                group = "org.framefork"
                version = "1.2.3"
                description = "The $module module"
                """.trimIndent(),
            )
            write("modules/$module/src/main/java/$module/Api.java", "package $module;\n\npublic final class Api {\n}\n")
        }
    }

    private fun write(relativePath: String, content: String) {
        val file = projectDir.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
