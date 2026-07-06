package org.framefork.build

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The `org.framefork.build.library-published` entrypoint applied (version-less) to a published library module.
 *
 * At this step it is identical to [LibraryInternalPlugin]; the publishing divergence arrives in a later step.
 */
abstract class LibraryPublishedPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.configureJavaConventions()
        project.configureStaticAnalysis()
    }
}
