package org.framefork.build

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The `org.framefork.build.library-published` entrypoint applied (version-less) to a published library module.
 *
 * Identical to [LibraryInternalPlugin] plus maven-publish staging conventions — publishing is what separates a
 * published module from an internal/testing one.
 */
abstract class LibraryPublishedPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.configureJavaConventions()
        project.configureStaticAnalysis()
        project.configureTestConventions()
        project.configureStagingPublishing()
    }
}
