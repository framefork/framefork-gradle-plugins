package org.framefork.build

import org.gradle.api.Project

/**
 * The convention stack shared by every library module, applied by both `library-*` plugins in this exact order.
 *
 * [LibraryInternalPlugin] is exactly this stack; [LibraryPublishedPlugin] is this stack plus staging publishing — so the
 * "internal == published minus publishing" invariant holds by construction rather than by two hand-kept call sequences.
 */
internal fun Project.configureLibraryBaseConventions() {
    configureJavaConventions()
    configureKotlinConventions()
    configureStaticAnalysis()
    configureTestConventions()
    configureDependencyLocking()
}
