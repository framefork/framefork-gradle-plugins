package org.framefork.build

import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
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

    @JupiterTest
    fun `minJavaVersion 8 maps to the Kotlin JVM_1_8 target, not the raw "8" string fromTarget rejects`() {
        assertEquals(JvmTarget.JVM_1_8, kotlinJvmTargetFor(8))
    }

    @JupiterTest
    fun `in-range minJavaVersion maps to the matching Kotlin JVM target`() {
        assertEquals(JvmTarget.JVM_17, kotlinJvmTargetFor(17))
        assertEquals(JvmTarget.JVM_24, kotlinJvmTargetFor(24))
    }

    @JupiterTest
    fun `a minJavaVersion Kotlin cannot emit fails with a message naming the supported range`() {
        for (unsupported in intArrayOf(7, 25)) {
            val error = assertThrows(IllegalArgumentException::class.java) { kotlinJvmTargetFor(unsupported) }
            assertTrue(error.message!!.contains("minJavaVersion 8..24"), error.message)
        }
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
