package org.framefork.build

import org.gradle.api.provider.Property

/**
 * Per-project mirror of the resolved [FrameforkExtension] knobs, created on every project by
 * [FrameforkProjectInitAction]. The later library convention plugins read their configuration from here
 * rather than reaching back to the Settings extension, keeping project configuration config-cache-safe.
 */
abstract class FrameforkProjectExtension {

    abstract val minJavaVersion: Property<Int>

    abstract val jdkVersion: Property<Int>

    /** Unset when the consumer left `testsJdkVersion` unset; a later step defaults it to the resolved [jdkVersion]. */
    abstract val testsJdkVersion: Property<Int>

    abstract val jspecifyMode: Property<Boolean>

    abstract val dependencyLocking: Property<Boolean>

    abstract val sequentialTests: Property<Boolean>
}
