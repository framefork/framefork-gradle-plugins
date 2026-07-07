package org.framefork.build

import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Test as JupiterTest

class AutoServiceConventionsTest {

    @field:TempDir
    lateinit var projectDir: File

    @JupiterTest
    fun `wires the annotation compileOnly and the processor onto a java module`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply("java")
        project.plugins.apply(AutoServicePlugin::class.java)

        assertTrue(dependencyNotation(project, "compileOnly").contains("com.google.auto.service:auto-service-annotations:1.1.1"))
        assertTrue(dependencyNotation(project, "annotationProcessor").contains("com.google.auto.service:auto-service:1.1.1"))
    }

    @JupiterTest
    fun `fails with a clear message when applied to a module without a java plugin`() {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()

        val ex = assertThrows(Exception::class.java) {
            project.plugins.apply(AutoServicePlugin::class.java)
        }

        val messages = generateSequence<Throwable>(ex) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(messages.contains("needs a java plugin"), messages)
    }

    private fun dependencyNotation(project: Project, configuration: String): Set<String> =
        project.configurations.getByName(configuration).dependencies
            .map { d: Dependency -> "${d.group}:${d.name}:${d.version}" }
            .toSet()
}
