package org.framefork.build

import org.gradle.api.IsolatedAction
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.register

/**
 * Isolated per-project initializer registered via `gradle.lifecycle.beforeProject`.
 *
 * It deliberately captures only plain scalars (never the Settings or its extension objects), so it is safe
 * to serialize for the configuration cache and to re-run for every project. It creates [FrameforkProjectExtension]
 * and populates it with the values that were resolved after the consumer's `framefork { }` block ran.
 */
class FrameforkProjectInitAction(
    private val minJavaVersion: Int,
    private val jdkVersion: Int,
    private val testsJdkVersion: Int?,
    private val jspecifyMode: Boolean,
    private val dependencyLocking: Boolean,
) : IsolatedAction<Project> {

    override fun execute(project: Project) {
        val extension = project.extensions.create("frameforkProject", FrameforkProjectExtension::class.java)
        // Lock each knob right after seeding it: the settings-level `framefork {}` block is the single parametrization
        // surface, so a module's build script must not `frameforkProject.minJavaVersion.set(...)` and silently diverge.
        // disallowChanges() forbids later writes without forcing eager resolution (unlike finalizeValue), so an unset
        // testsJdkVersion still falls back through the `.orElse` chain in JavaConventions rather than being pinned here.
        extension.minJavaVersion.set(minJavaVersion)
        extension.minJavaVersion.disallowChanges()
        extension.jdkVersion.set(jdkVersion)
        extension.jdkVersion.disallowChanges()
        if (testsJdkVersion != null) {
            extension.testsJdkVersion.set(testsJdkVersion)
        }
        extension.testsJdkVersion.disallowChanges()
        extension.jspecifyMode.set(jspecifyMode)
        extension.jspecifyMode.disallowChanges()
        extension.dependencyLocking.set(dependencyLocking)
        extension.dependencyLocking.disallowChanges()

        // The staging-wipe task belongs on the root project: every published module's `publish` depends on it (by path)
        // so a `publish` run starts from an empty `build/staging-deploy` regardless of which modules take part. It is
        // registered here, as beforeProject visits the root, so it lives on the CC-safe settings→project rails — rather
        // than being lazily created from whichever published subproject's apply() reaches the root first, which Isolated
        // Projects forbids as cross-project task-container mutation.
        if (project === project.rootProject) {
            project.tasks.register<Delete>(CLEAN_ALL_PUBLICATIONS_TASK) {
                outputs.upToDateWhen { false }
                delete(project.layout.buildDirectory.dir("staging-deploy"))
            }
        }
    }
}

/** Root-level task that wipes the shared `build/staging-deploy` staging repo; see [FrameforkProjectInitAction]. */
internal const val CLEAN_ALL_PUBLICATIONS_TASK = "cleanAllPublications"
