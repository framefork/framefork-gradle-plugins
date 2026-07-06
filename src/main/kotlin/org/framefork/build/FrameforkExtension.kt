package org.framefork.build

import org.gradle.api.provider.Property

/**
 * Consumer-facing `framefork { }` block on the Settings script.
 *
 * The values here are the single parametrization surface for the whole suite; they are resolved
 * once (after the settings script has run) and propagated to every project via [FrameforkProjectExtension].
 */
abstract class FrameforkExtension {

    /** Lowest Java version the produced bytecode must stay portable to; drives `--release` and Kotlin `jvmTarget`. */
    abstract val minJavaVersion: Property<Int>

    /** JDK the compilation toolchain runs on. Must be >= 21 because Error Prone requires it to run. */
    abstract val jdkVersion: Property<Int>

    /** JDK the tests are executed on. Unset means "use the resolved [jdkVersion]" — resolved in a later step. */
    abstract val testsJdkVersion: Property<Int>

    /** Whether NullAway runs in JSpecify mode. */
    abstract val jspecifyMode: Property<Boolean>

    init {
        minJavaVersion.convention(17)
        jdkVersion.convention(21)
        jspecifyMode.convention(true)
    }
}
