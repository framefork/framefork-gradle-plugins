package org.framefork.build

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * A configuration-cache-tracked filesystem probe: does the module carry any Kotlin source under `src/`? Modelling this
 * as a [ValueSource] (rather than a bare `File.exists()` at configuration time) makes the read a first-class Provider
 * input that Gradle records in the configuration cache, instead of an untracked config-time side effect.
 */
abstract class KotlinSourcesPresentValueSource : ValueSource<Boolean, KotlinSourcesPresentValueSource.Params> {

    interface Params : ValueSourceParameters {
        val sourceRoot: DirectoryProperty
    }

    override fun obtain(): Boolean {
        val root = parameters.sourceRoot.get().asFile
        if (!root.isDirectory) {
            return false
        }
        return root.walkTopDown().any { it.isFile && (it.extension == "kt" || it.extension == "kts") }
    }
}

/**
 * Conditional Kotlin support for a library module. Applied by both `library-*` plugins after the Java conventions.
 *
 * Kotlin is opt-in *by presence*: a module that ships no `.kt`/`.kts` source under `src/` stays pure Java — the Kotlin
 * Gradle plugin is never applied, no `compileKotlin` task exists, and no `kotlin-stdlib` reaches its classpath. Only when
 * the [KotlinSourcesPresentValueSource] probe reports Kotlin source do we apply `org.jetbrains.kotlin.jvm` and configure it:
 *  - **jvmTarget** follows the per-project [FrameforkProjectExtension.minJavaVersion], matching the `--release` bytecode
 *    target the Java conventions set — so a mixed Java+Kotlin module emits one uniform bytecode level;
 *  - **explicitApi()** is on: a published library must declare visibility and public-API return types explicitly.
 *
 * The `@NullMarked` `package-info` generation stays keyed off Java sources only (Kotlin is natively null-safe and needs no
 * JSpecify marking), so a mixed module still gets its Java packages marked while its Kotlin stays untouched.
 *
 * Putting the Kotlin Gradle plugin on the suite's classpath (an `implementation` dependency) is also what lets a consumer
 * module apply companion Kotlin plugins version-less — e.g. `kotlin("plugin.serialization")` with no version resolves the
 * marker from our classpath, exactly like the other convention sub-plugins.
 */
internal fun Project.configureKotlinConventions() {
    val ext = frameforkProjectExtension()

    val hasKotlinSources = providers.of(KotlinSourcesPresentValueSource::class.java) {
        parameters.sourceRoot.set(layout.projectDirectory.dir("src"))
    }

    if (!hasKotlinSources.get()) {
        return
    }

    pluginManager.apply("org.jetbrains.kotlin.jvm")

    extensions.configure<KotlinJvmProjectExtension> {
        explicitApi()
        compilerOptions {
            jvmTarget.set(ext.minJavaVersion.map { kotlinJvmTargetFor(it) })
        }
    }
}

/**
 * Maps a module's [FrameforkProjectExtension.minJavaVersion] to the Kotlin [JvmTarget] its `.kt` sources compile to.
 *
 * [JvmTarget.fromTarget] speaks Kotlin's own target strings, which don't line up with the plain integer the Java
 * conventions use: Java 8 is `"1.8"` there (the constant is `JVM_1_8`), and Kotlin 2.2.x defines targets only up to
 * `JVM_24`. Pure-Java modules accept the full `minJavaVersion` range, so this narrower bound is a Kotlin-only constraint
 * — a module carrying Kotlin source must pick a `minJavaVersion` Kotlin can actually emit, and the raw
 * `Unknown Kotlin JVM target` error `fromTarget` would otherwise throw names neither the range nor the cause.
 */
internal fun kotlinJvmTargetFor(minJavaVersion: Int): JvmTarget {
    require(minJavaVersion in 8..24) {
        "framefork: a module with Kotlin sources supports minJavaVersion 8..24 (the JVM targets Kotlin 2.2.x can emit); got $minJavaVersion"
    }
    return JvmTarget.fromTarget(if (minJavaVersion == 8) "1.8" else minJavaVersion.toString())
}
