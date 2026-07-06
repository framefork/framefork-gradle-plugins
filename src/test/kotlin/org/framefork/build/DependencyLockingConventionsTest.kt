package org.framefork.build

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Test as JupiterTest

class DependencyLockingConventionsTest {

    @field:TempDir
    lateinit var projectDir: File

    private lateinit var project: Project

    @JupiterTest
    fun `enabled toggle registers the resolveAndLockAll task`() {
        applyPlugin(dependencyLocking = true)

        assertNotNull(project.tasks.findByName("resolveAndLockAll"), "resolveAndLockAll registered when locking is on")
    }

    @JupiterTest
    fun `disabled toggle registers no resolveAndLockAll task`() {
        applyPlugin(dependencyLocking = false)

        assertNull(project.tasks.findByName("resolveAndLockAll"), "no lock task when locking is off")
    }

    private fun applyPlugin(dependencyLocking: Boolean) {
        project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val ext = project.extensions.create("frameforkProject", FrameforkProjectExtension::class.java)
        ext.minJavaVersion.set(17)
        ext.jdkVersion.set(21)
        ext.jspecifyMode.set(true)
        ext.dependencyLocking.set(dependencyLocking)
        project.plugins.apply(LibraryInternalPlugin::class.java)
    }
}
