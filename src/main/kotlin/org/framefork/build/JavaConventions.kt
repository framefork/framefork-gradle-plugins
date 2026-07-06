package org.framefork.build

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/**
 * The three-knob compile/target/test model that is the heart of the suite. Applied by both `library-*` plugins.
 *
 * The knobs come from the per-project [FrameforkProjectExtension] (populated by the settings plugin before this
 * project's build script runs), each resolvable to a modern JDK independently:
 *  - **compile toolchain** = `jdkVersion` (overridable via `-Pjdk.version`) — what the compiler *runs on*;
 *  - **bytecode target** = `minJavaVersion` — `--release` on every `JavaCompile`, so output stays portable to every test JDK;
 *  - **test-runtime launcher** = `testsJdkVersion` (overridable via `-Ptests.jdk.version`, defaulting to the resolved compile toolchain) — compile-once-test-many.
 *
 * Everything is wired through lazy Providers (never `.get()` at configuration time) so it stays configuration-cache-safe.
 */
internal fun Project.configureJavaConventions() {
    val ext = frameforkProjectExtension()

    pluginManager.apply("java-library")

    val minJavaVersion = ext.minJavaVersion

    // Resolved compile toolchain: -Pjdk.version wins over the framefork { jdkVersion } knob.
    // The `zip` validates `minJavaVersion <= jdkVersion` as the provider resolves — a `.get()`-free guard that stays
    // lazy and config-cache-safe, and catches the resolved pair (after `-P` overrides), so a bad combination fails
    // with a knob-naming message instead of the raw javac `error: release version N not supported`.
    val resolvedJdkVersion: Provider<Int> = providers.gradleProperty("jdk.version").map(String::toInt).orElse(ext.jdkVersion)
        .zip(minJavaVersion) { jdk, min ->
            require(min <= jdk) { "framefork: minJavaVersion ($min) must be <= jdkVersion ($jdk)" }
            jdk
        }
    // Resolved test-runtime JDK: -Ptests.jdk.version, else the framefork { testsJdkVersion } knob, else the resolved compile toolchain.
    // Same guard for `minJavaVersion <= testsJdkVersion`: bytecode compiled with `--release minJavaVersion` cannot launch
    // on an older test JDK, which would otherwise surface as an `UnsupportedClassVersionError` only once tests run.
    val resolvedTestsJdkVersion: Provider<Int> = providers.gradleProperty("tests.jdk.version").map(String::toInt)
        .orElse(ext.testsJdkVersion)
        .orElse(resolvedJdkVersion)
        .zip(minJavaVersion) { tests, min ->
            require(min <= tests) { "framefork: minJavaVersion ($min) must be <= testsJdkVersion ($tests)" }
            tests
        }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(resolvedJdkVersion.map { JavaLanguageVersion.of(it) })
    }

    val javaToolchains = extensions.getByType<JavaToolchainService>()

    tasks.withType<JavaCompile>().configureEach {
        // Bytecode target is minJavaVersion via --release on main AND test, independent of the compile toolchain.
        options.release.set(ext.minJavaVersion)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf(
                "-parameters",
                "-Xlint:all,-fallthrough,-processing,-serial,-classfile,-path,-this-escape",
                "-Werror",
            ),
        )
    }

    tasks.withType<Test>().configureEach {
        javaLauncher.set(
            javaToolchains.launcherFor {
                languageVersion.set(resolvedTestsJdkVersion.map { JavaLanguageVersion.of(it) })
            },
        )
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

}
