package org.framefork.build

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The `org.framefork.build.library-internal` entrypoint applied (version-less) to a non-published (`testing/`) module.
 *
 * Identical to [LibraryPublishedPlugin] minus publishing (the publishing helper is added to library-published in a later step).
 */
abstract class LibraryInternalPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.configureJavaConventions()
        project.configureStaticAnalysis()
    }
}
