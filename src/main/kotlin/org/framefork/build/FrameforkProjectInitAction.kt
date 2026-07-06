package org.framefork.build

import org.gradle.api.IsolatedAction
import org.gradle.api.Project

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
) : IsolatedAction<Project> {

    override fun execute(project: Project) {
        val extension = project.extensions.create("frameforkProject", FrameforkProjectExtension::class.java)
        extension.minJavaVersion.set(minJavaVersion)
        extension.jdkVersion.set(jdkVersion)
        if (testsJdkVersion != null) {
            extension.testsJdkVersion.set(testsJdkVersion)
        }
        extension.jspecifyMode.set(jspecifyMode)
    }
}
