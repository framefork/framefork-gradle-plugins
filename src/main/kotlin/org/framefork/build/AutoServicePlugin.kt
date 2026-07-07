package org.framefork.build

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * The `org.framefork.build.auto-service` entrypoint applied (version-less) to a module whose classes carry
 * `@AutoService`, alongside a `library-*` plugin.
 *
 * A version-less per-module feature plugin: it wires the Google auto-service annotation + processor and nothing else.
 * See [configureAutoService] for the apply-order requirement and why this is a plugin id rather than a `framefork {}` knob.
 */
abstract class AutoServicePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.configureAutoService()
    }
}
