package org.framefork.build

import org.gradle.api.IsolatedAction
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.diagnostics.DependencyReportTask
import org.gradle.api.tasks.wrapper.Wrapper
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

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

        // Version plumbing, absorbing the `allprojects { version = rootProject.version }` boilerplate consumers used to
        // copy-paste. allprojects{} reaches across projects (Isolated-Projects-hostile), so instead every project reads
        // the global `version` Gradle property itself: it lives in the root `gradle.properties` and Gradle exposes it to
        // every project, so this stays a purely local read with no cross-project access. Absent property ⇒ leave
        // `project.version` at Gradle's "unspecified" default rather than crash a repo that doesn't declare a version.
        //
        // `group` is deliberately NOT propagated here. Consumers set it in the root build script, which runs *after*
        // beforeProject, so a root-declared group is invisible to this action; group also legitimately diverges per
        // module (e.g. a per-module `group` override in a testing/snapshots module), so it stays entirely consumer-side.
        project.providers.gradleProperty("version").orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { resolvedVersion ->
            project.version = resolvedVersion
        }

        // Per-project dependency-tree report, replacing the `allprojects { register<DependencyReportTask>("allDependencies") }`
        // boilerplate. Registered per project — no `evaluationDependsOnChildren()` — so each task renders only its own
        // project's dependencies (the DependencyReportTask default), keeping it free of cross-project reads.
        project.tasks.register<DependencyReportTask>(ALL_DEPENDENCIES_TASK) {
            setOutputFile(project.layout.buildDirectory.file("reports/dependencies.txt").get().asFile)
        }

        // The staging-wipe task belongs on the root project: every published module's staging-repository write task
        // depends on it (by path) so publishing starts from an empty `build/staging-deploy` regardless of which modules
        // take part. It is registered here, as beforeProject visits the root, so it lives on the CC-safe settings→project
        // rails — rather than being lazily created from whichever published subproject's apply() reaches the root first,
        // which Isolated Projects forbids as cross-project task-container mutation.
        if (project === project.rootProject) {
            project.tasks.register<Delete>(CLEAN_ALL_PUBLICATIONS_TASK) {
                outputs.upToDateWhen { false }
                delete(project.layout.buildDirectory.dir("staging-deploy"))
            }

            // Ship the full Gradle distribution (binaries + sources) in the generated wrapper so IDEs resolve Gradle
            // API sources. Root-only, since the wrapper task exists only on the root project.
            project.tasks.withType<Wrapper>().configureEach {
                distributionType = Wrapper.DistributionType.ALL
            }
        }
    }
}

/** Root-level task that wipes the shared `build/staging-deploy` staging repo; see [FrameforkProjectInitAction]. */
internal const val CLEAN_ALL_PUBLICATIONS_TASK = "cleanAllPublications"

/** Per-project dependency-tree report task writing to `build/reports/dependencies.txt`; see [FrameforkProjectInitAction]. */
internal const val ALL_DEPENDENCIES_TASK = "allDependencies"
