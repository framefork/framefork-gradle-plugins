package org.framefork.build

import net.ltgt.gradle.nullaway.NullAwayExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Test as JupiterTest

class StaticAnalysisTest {

    @field:TempDir
    lateinit var projectDir: File

    private lateinit var project: Project

    @JupiterTest
    fun `applies the analyzer plugins and wires the consumer dependencies`() {
        applyPlugin(jspecifyMode = true)

        assertTrue(project.plugins.hasPlugin("net.ltgt.errorprone"), "errorprone plugin applied")
        assertTrue(project.plugins.hasPlugin("net.ltgt.nullaway"), "nullaway plugin applied")
        assertTrue(project.tasks.findByName("generateNullMarkedPackageInfo") != null, "package-info generator registered")

        assertTrue(dependencyNotation("errorprone").contains("com.google.errorprone:error_prone_core:2.50.0"))
        assertTrue(dependencyNotation("errorprone").contains("com.uber.nullaway:nullaway:0.13.7"))
        assertTrue(dependencyNotation("api").contains("org.jspecify:jspecify:1.0.0"))
    }

    @JupiterTest
    fun `nullaway runs in onlyNullMarked and follows the jspecifyMode knob`() {
        applyPlugin(jspecifyMode = false)

        val nullaway = project.extensions.getByType(NullAwayExtension::class.java)
        assertEquals(true, nullaway.onlyNullMarked.get(), "onlyNullMarked is always on")
        assertEquals(false, nullaway.jspecifyMode.get(), "jspecifyMode follows the framefork {} knob")
    }

    private fun applyPlugin(jspecifyMode: Boolean) {
        project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val ext = project.extensions.create("frameforkProject", FrameforkProjectExtension::class.java)
        ext.minJavaVersion.set(17)
        ext.jdkVersion.set(21)
        ext.jspecifyMode.set(jspecifyMode)
        project.plugins.apply(LibraryPublishedPlugin::class.java)
    }

    private fun dependencyNotation(configuration: String): Set<String> =
        project.configurations.getByName(configuration).dependencies
            .map { d: Dependency -> "${d.group}:${d.name}:${d.version}" }
            .toSet()
}
