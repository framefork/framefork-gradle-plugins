package org.framefork.build

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The `org.framefork.build.library-internal` entrypoint applied (version-less) to a non-published (`testing/`) module.
 *
 * Identical to [LibraryPublishedPlugin] minus publishing: internal/testing modules must never publish.
 */
abstract class LibraryInternalPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.configureJavaConventions()
        project.configureKotlinConventions()
        project.configureStaticAnalysis()
        project.configureTestConventions()
    }
}
