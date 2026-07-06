package org.framefork.build

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Test as JupiterTest

class TestConventionsTest {

    @field:TempDir
    lateinit var projectDir: File

    private lateinit var project: Project

    @JupiterTest
    fun `applies test-logger and wires the JUnit 5 consumer dependencies`() {
        applyPlugin()

        assertTrue(project.plugins.hasPlugin("com.adarshr.test-logger"), "test-logger plugin applied")

        assertTrue(
            dependencyNotation("testImplementation").contains("org.junit:junit-bom:${TestConventionsVersions.JUNIT_BOM}"),
            "JUnit BOM platform pinned from the catalog version",
        )
        assertTrue(dependencyNotation("testImplementation").any { it.startsWith("org.junit.jupiter:junit-jupiter:") }, "junit-jupiter added")
        assertTrue(dependencyNotation("testRuntimeOnly").any { it.startsWith("org.junit.platform:junit-platform-launcher:") }, "platform launcher added")
    }

    private fun applyPlugin() {
        project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val ext = project.extensions.create("frameforkProject", FrameforkProjectExtension::class.java)
        ext.minJavaVersion.set(17)
        ext.jdkVersion.set(21)
        ext.jspecifyMode.set(true)
        project.plugins.apply(LibraryPublishedPlugin::class.java)
    }

    private fun dependencyNotation(configuration: String): Set<String> =
        project.configurations.getByName(configuration).dependencies
            .map { d: Dependency -> "${d.group}:${d.name}:${d.version}" }
            .toSet()
}
