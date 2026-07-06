package org.framefork.build

import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Test as JupiterTest

class PublishConventionsTest {

    @field:TempDir
    lateinit var projectDir: File

    @JupiterTest
    fun `library-published registers the mavenStaging publication`() {
        val project = seededProject()

        project.plugins.apply(LibraryPublishedPlugin::class.java)

        val publishing = project.extensions.findByType(PublishingExtension::class.java)
        assertNotNull(publishing, "maven-publish applied for a published library")
        assertNotNull(publishing!!.publications.findByName("mavenStaging"), "mavenStaging publication registered")
    }

    @JupiterTest
    fun `library-internal registers no publication`() {
        val project = seededProject()

        project.plugins.apply(LibraryInternalPlugin::class.java)

        assertNull(
            project.extensions.findByType(PublishingExtension::class.java),
            "internal modules must not apply maven-publish nor register any publication",
        )
    }

    private fun seededProject(): Project {
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        val ext = project.extensions.create("frameforkProject", FrameforkProjectExtension::class.java)
        ext.minJavaVersion.set(17)
        ext.jdkVersion.set(21)
        ext.jspecifyMode.set(true)
        return project
    }
}
