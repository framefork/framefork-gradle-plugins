package org.framefork.build

import org.gradle.testkit.runner.GradleRunner

/**
 * Entry point every functional test uses to build its [GradleRunner], in place of [GradleRunner.create].
 *
 * When the `framefork.testedGradleVersion` system property is non-empty (surfaced by the `functionalTest` task from
 * `-PtestedGradleVersion`), the runner is pinned to that Gradle version via [GradleRunner.withGradleVersion], so the
 * whole functional suite can be replayed against the consumer baseline (`-PtestedGradleVersion=9.0`). When the property
 * is unset the runner uses TestKit's embedded/current Gradle, preserving each test's default behavior.
 *
 * Callers keep chaining `withProjectDir` / `withPluginClasspath` / `withArguments` themselves — this factory only owns
 * the version selection, so tests that deliberately skip `withPluginClasspath()` (marker-resolution) stay unaffected.
 */
internal fun frameforkRunner(): GradleRunner {
    val runner = GradleRunner.create()
    val testedGradleVersion = System.getProperty("framefork.testedGradleVersion").orEmpty()
    if (testedGradleVersion.isNotEmpty()) {
        runner.withGradleVersion(testedGradleVersion)
    }
    return runner
}
