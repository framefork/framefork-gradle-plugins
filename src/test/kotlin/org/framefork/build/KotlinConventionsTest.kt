package org.framefork.build

import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Test as JupiterTest

class KotlinConventionsTest {

    @field:TempDir
    lateinit var projectDir: File

    @JupiterTest
    fun `applying library conventions to a Kotlin module applies the Kotlin plugin`() {
        write("src/main/kotlin/demo/Demo.kt", "package demo\n\npublic class Demo\n")
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        seedFrameforkProjectExtension(project)

        project.plugins.apply(LibraryPublishedPlugin::class.java)

        assertTrue(project.plugins.hasPlugin("org.jetbrains.kotlin.jvm"), "Kotlin plugin must be applied when .kt sources exist")
        assertTrue(project.tasks.findByName("compileKotlin") != null, "compileKotlin task must exist for a Kotlin module")
    }

    @JupiterTest
    fun `applying library conventions to a pure-Java module leaves Kotlin unapplied`() {
        write("src/main/java/demo/Demo.java", "package demo;\n\npublic final class Demo {\n}\n")
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        seedFrameforkProjectExtension(project)

        project.plugins.apply(LibraryPublishedPlugin::class.java)

        assertFalse(project.plugins.hasPlugin("org.jetbrains.kotlin.jvm"), "Kotlin plugin must not be applied to a pure-Java module")
        assertFalse(project.tasks.findByName("compileKotlin") != null, "no compileKotlin task on a pure-Java module")
    }

    private fun seedFrameforkProjectExtension(project: org.gradle.api.Project) {
        val ext = project.extensions.create("frameforkProject", FrameforkProjectExtension::class.java)
        ext.minJavaVersion.set(17)
        ext.jdkVersion.set(21)
        ext.jspecifyMode.set(true)
    }

    private fun write(relativePath: String, content: String) {
        val file = projectDir.resolve(relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}
