package org.framefork.build

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.api.initialization.resolve.RepositoriesMode

/**
 * The `org.framefork.build` settings entrypoint every consumer applies (with a version) in `settings.gradle.kts`.
 *
 * It centralizes repository management, discovers `modules/` and `testing/` subprojects, and propagates the
 * resolved `framefork { }` knobs to every project.
 */
abstract class FrameforkSettingsPlugin : Plugin<Settings> {

    override fun apply(settings: Settings) {
        val extension = settings.extensions.create("framefork", FrameforkExtension::class.java)

        settings.dependencyResolutionManagement.apply {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories.apply {
                mavenCentral()
                gradlePluginPortal()
            }
        }

        // settingsEvaluated runs after the whole settings script (incl. the consumer's framefork {} block),
        // so the knob values read here are the resolved ones, not the defaults captured at apply() time.
        settings.gradle.settingsEvaluated {
            discoverSubprojects(settings)

            settings.gradle.lifecycle.beforeProject(
                FrameforkProjectInitAction(
                    minJavaVersion = extension.minJavaVersion.get(),
                    jdkVersion = extension.jdkVersion.get(),
                    testsJdkVersion = extension.testsJdkVersion.orNull,
                    jspecifyMode = extension.jspecifyMode.get(),
                    dependencyLocking = extension.dependencyLocking.get(),
                ),
            )
        }
    }

    /** Scans `modules/` and `testing/` for immediate subdirs holding a `build.gradle.kts` and wires them in. */
    private fun discoverSubprojects(settings: Settings) {
        for (groupDir in listOf("modules", "testing")) {
            val group = settings.rootDir.resolve(groupDir)
            if (!group.isDirectory) {
                continue
            }
            val moduleDirs = group.listFiles { file -> file.isDirectory && file.resolve("build.gradle.kts").isFile }
                ?.sortedBy { it.name }
                ?: continue
            for (moduleDir in moduleDirs) {
                val path = ":${moduleDir.name}"
                settings.include(path)
                settings.project(path).projectDir = moduleDir
            }
        }
    }
}
