package org.framefork.build

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Test as JupiterTest

class SequentialTestsConventionsTest {

    @field:TempDir
    lateinit var projectDir: File

    private lateinit var project: Project

    @JupiterTest
    fun `enabled toggle registers the one-permit test-serializer shared service`() {
        runInitAction(sequentialTests = true)

        val registration = project.gradle.sharedServices.registrations.findByName(TEST_SERIALIZER_SERVICE)
        assertNotNull(registration, "$TEST_SERIALIZER_SERVICE registered when sequentialTests is on")
        assertNotNull(registration!!.maxParallelUsages.orNull, "the serializer must cap parallel usages")
    }

    @JupiterTest
    fun `disabled toggle registers no shared service`() {
        runInitAction(sequentialTests = false)

        assertNull(project.gradle.sharedServices.registrations.findByName(TEST_SERIALIZER_SERVICE), "no serializer when sequentialTests is off")
    }

    private fun runInitAction(sequentialTests: Boolean) {
        project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply("java")
        FrameforkProjectInitAction(
            minJavaVersion = 17,
            jdkVersion = 21,
            testsJdkVersion = null,
            jspecifyMode = true,
            dependencyLocking = false,
            sequentialTests = sequentialTests,
        ).execute(project)
    }
}
