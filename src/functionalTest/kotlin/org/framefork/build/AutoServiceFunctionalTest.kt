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
        writeSettings()
        write("build.gradle.kts", "")
        writeAutoServiceModule()
        writeJavaService()

        val result = runner(":foo:jar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:jar")?.outcome, result.output)
        assertServiceRegistration()

        // A pure-Java module wires no Kotlin backend but has no Kotlin sources either, so the Kotlin-backend warning must
        // stay silent — it fires only for modules that actually carry Kotlin.
        assertTrue(
            !result.output.contains("has Kotlin sources but no Kotlin annotation-processing backend"),
            "a pure-Java module must not emit the Kotlin-backend warning; output was: ${result.output}",
        )
    }

    @Test
    fun `a consumer build with auto-service is configuration-cache clean across store and reuse`() {
        writeSettings()
        write("build.gradle.kts", "")
        writeAutoServiceModule()
        writeJavaService()

        val first = runner(":foo:jar").build()
        assertTrue(first.output.contains("Configuration cache entry stored"), first.output)
        assertTrue(!first.output.contains("Configuration cache disabled"), first.output)
        assertTrue(!first.output.contains("notCompatibleWithConfigurationCache"), first.output)

        val second = runner(":foo:jar").build()
        assertTrue(second.output.contains("Configuration cache entry reused"), second.output)
        assertTrue(!second.output.contains("Configuration cache disabled"), second.output)
        assertTrue(!second.output.contains("notCompatibleWithConfigurationCache"), second.output)
    }

    @Test
    fun `with KSP applied, a Kotlin @AutoService class produces a service registration in the jar`() {
        writeMarkerResolutionKspConsumer()

        val result = markerResolutionRunner(":foo:jar", refreshDependencies = true).build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:jar")?.outcome, result.output)
        assertServiceRegistration()
    }

    @Test
    fun `a KSP consumer build is configuration-cache clean across store and reuse`() {
        writeMarkerResolutionKspConsumer()

        // The store run carries --refresh-dependencies (pulls the just-published SNAPSHOT into the TestKit dependency
        // cache); the reuse run drops it, because Gradle never *reuses* an entry within a --refresh-dependencies run —
        // the flag-less second run picks up the entry the first run stored.
        val first = markerResolutionRunner(":foo:jar", refreshDependencies = true).build()
        assertTrue(first.output.contains("Configuration cache entry stored"), first.output)
        assertTrue(!first.output.contains("Configuration cache disabled"), first.output)
        assertTrue(!first.output.contains("notCompatibleWithConfigurationCache"), first.output)

        val second = markerResolutionRunner(":foo:jar").build()
        assertTrue(second.output.contains("Configuration cache entry reused"), second.output)
        assertTrue(!second.output.contains("Configuration cache disabled"), second.output)
        assertTrue(!second.output.contains("notCompatibleWithConfigurationCache"), second.output)
    }

    @Test
    fun `with kapt applied, a Kotlin @AutoService class produces a service registration in the jar`() {
        writeSettings()
        write("build.gradle.kts", "")
        // kotlin("kapt") resolves version-less off the suite classpath (the kotlin-gradle-plugin backs it), unlike KSP.
        write(
            "modules/foo/build.gradle.kts",
            """
            plugins {
                id("org.framefork.build.library-published")
                kotlin("kapt")
                id("org.framefork.build.auto-service")
            }
            """.trimIndent(),
        )
        writeKotlinService()

        val result = runner(":foo:jar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:jar")?.outcome, result.output)
        assertServiceRegistration()
    }

    @Test
    fun `a Kotlin module with no processing backend builds but warns that @AutoService will not register`() {
        writeSettings()
        write("build.gradle.kts", "")
        writeAutoServiceModule()
        writeKotlinService()

        val result = runner(":foo:jar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":foo:jar")?.outcome, result.output)
        assertTrue(
            result.output.contains("has Kotlin sources but no Kotlin annotation-processing backend"),
            "expected the missing-Kotlin-backend warning; output was: ${result.output}",
        )
    }

    private fun assertServiceRegistration() {
        val jar = projectDir.resolve("modules/foo/build/libs").listFiles { f -> f.name.endsWith(".jar") }?.singleOrNull()
        assertNotNull(jar, "expected exactly one built jar under modules/foo/build/libs, got ${projectDir.resolve("modules/foo/build/libs").listFiles()?.toList()}")
        JarFile(jar).use { jf ->
            // The @AutoService(Greeter) annotation must have produced a service registration for the interface FQN,
            // listing the annotated implementation FQN — the whole point of a processing backend being wired.
            val entry = jf.getJarEntry("META-INF/services/foo.Greeter")
            assertNotNull(entry, "expected META-INF/services/foo.Greeter in the jar; entries=${jf.entries().toList().map { it.name }}")
            val content = jf.getInputStream(entry).bufferedReader().readText()
            assertTrue(content.contains("foo.LoudGreeter"), "service file should register the implementation FQN, was: $content")
        }
    }

    private fun runner(vararg args: String): GradleRunner =
        frameforkRunner()
            .withProjectDir(projectDir)
            .withPluginClasspath()
            .withArguments(*args, "--configuration-cache", "--stacktrace")

    /**
     * Runner for the KSP tests, which deliberately skip [GradleRunner.withPluginClasspath]: TestKit's injected plugin
     * classpath lives in a classloader Gradle's configuration-cache deserializer can't reconstruct from the scope of a
     * Portal-resolved plugin — KSP serializes state referencing KGP's `KotlinCompilation`, and the load-after-store phase
     * dies with `NoClassDefFoundError`. Resolving the suite from its published markers instead (the same hermetic repo
     * `PluginResolutionFunctionalTest` uses) puts the suite jar — with KGP as a real dependency — on the settings
     * buildscript classpath, the parent of every plugin classloader, exactly as a real consumer's build has it.
     *
     * [refreshDependencies] makes the run see the just-published SNAPSHOT rather than a TestKit-cached one; it also makes
     * Gradle refuse to reuse a CC entry for that run, so the store/reuse test primes with it once and then drops it.
     */
    private fun markerResolutionRunner(vararg args: String, refreshDependencies: Boolean = false): GradleRunner =
        frameforkRunner()
            .withProjectDir(projectDir)
            .withArguments(*args, *(if (refreshDependencies) arrayOf("--refresh-dependencies") else emptyArray()), "--configuration-cache", "--stacktrace")

    private fun writeMarkerResolutionKspConsumer() {
        val repoUri = File(requireNotNull(System.getProperty("framefork.localPluginRepo")) { "framefork.localPluginRepo must be set by the functionalTest task" }).toURI()
        val pluginVersion = requireNotNull(System.getProperty("framefork.pluginVersion")) { "framefork.pluginVersion must be set by the functionalTest task" }

        // KSP is not on the suite's classpath (unlike kotlin("jvm")/kotlin("kapt")), so a KSP consumer must declare a
        // version — pinned here to the KSP release matching the suite's bundled Kotlin 2.2.21.
        write(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    maven { url = uri("$repoUri") }
                    gradlePluginPortal()
                    mavenCentral()
                }
                plugins {
                    id("com.google.devtools.ksp") version "$KSP_PLUGIN_VERSION"
                }
            }

            plugins {
                id("org.framefork.build") version "$pluginVersion"
            }

            framefork {
                minJavaVersion = 17
                jdkVersion = 21
            }

            rootProject.name = "consumer"
            """.trimIndent(),
        )
        write("build.gradle.kts", "")
        // KSP deliberately listed *after* auto-service: the backend wiring reacts via withPlugin, so the consumer's
        // plugin order must not matter — only the library-* plugin has to come first (the eager java-plugin require).
        write(
            "modules/foo/build.gradle.kts",
            """
            plugins {
                id("org.framefork.build.library-published")
                id("org.framefork.build.auto-service")
                id("com.google.devtools.ksp")
            }
            """.trimIndent(),
        )
        writeKotlinService()
    }

    private fun writeSettings() {
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
    }

    private fun writeAutoServiceModule() {
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

    private fun writeJavaService() {
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

    private fun writeKotlinService() {
        // A Kotlin @AutoService class: the javac annotationProcessor never sees it, so a service registration in the jar
        // proves a Kotlin-native backend (KSP or kapt) did the processing. explicitApi() is on, hence the `public` markers.
        write(
            "modules/foo/src/main/kotlin/foo/Greeter.kt",
            """
            package foo

            public interface Greeter {

                public fun greet(): String
            }
            """.trimIndent(),
        )
        write(
            "modules/foo/src/main/kotlin/foo/LoudGreeter.kt",
            """
            package foo

            import com.google.auto.service.AutoService

            @AutoService(Greeter::class)
            public class LoudGreeter : Greeter {

                override fun greet(): String = "HELLO"
            }
            """.trimIndent(),
        )
    }

    private fun write(relativePath: String, content: String) {
        val file = projectDir.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private companion object {
        // The KSP release aligned to the suite's bundled Kotlin 2.2.21 (KSP versions are `<kotlin>-<ksp>`); latest for that line.
        const val KSP_PLUGIN_VERSION = "2.2.21-2.0.5"
    }
}
