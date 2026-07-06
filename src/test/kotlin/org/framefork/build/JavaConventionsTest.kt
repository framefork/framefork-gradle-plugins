package org.framefork.build

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.io.TempDir
import java.io.File
import org.junit.jupiter.api.Test as JupiterTest

class JavaConventionsTest {

    @field:TempDir
    lateinit var projectDir: File

    private lateinit var project: Project

    @JupiterTest
    fun `resolves the three knobs from the extension`() {
        project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        seedFrameforkProjectExtension(minJavaVersion = 17, jdkVersion = 21, testsJdkVersion = null)

        project.plugins.apply(LibraryPublishedPlugin::class.java)

        assertEquals(21, compileToolchainVersion(), "compile toolchain follows jdkVersion")
        assertEquals(17, releaseOf("compileJava"), "main --release follows minJavaVersion")
        assertEquals(17, releaseOf("compileTestJava"), "test --release follows minJavaVersion too")
        assertEquals(21, testLauncherVersion(), "test launcher defaults to the resolved compile toolchain")
    }

    @JupiterTest
    fun `-P properties override the extension knobs`() {
        projectDir.resolve("gradle.properties").writeText("jdk.version=25\ntests.jdk.version=25\n")
        project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        seedFrameforkProjectExtension(minJavaVersion = 17, jdkVersion = 21, testsJdkVersion = null)

        project.plugins.apply(LibraryPublishedPlugin::class.java)

        assertEquals(25, compileToolchainVersion(), "-Pjdk.version overrides the jdkVersion knob")
        assertEquals(17, releaseOf("compileJava"), "--release stays on minJavaVersion regardless of -P overrides")
        assertEquals(25, testLauncherVersion(), "-Ptests.jdk.version overrides the test launcher")
    }

    @JupiterTest
    fun `explicit testsJdkVersion knob drives the test launcher`() {
        project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        seedFrameforkProjectExtension(minJavaVersion = 17, jdkVersion = 21, testsJdkVersion = 25)

        project.plugins.apply(LibraryInternalPlugin::class.java)

        assertEquals(21, compileToolchainVersion())
        assertEquals(25, testLauncherVersion(), "test launcher follows the explicit testsJdkVersion knob")
    }

    private fun seedFrameforkProjectExtension(minJavaVersion: Int, jdkVersion: Int, testsJdkVersion: Int?) {
        val ext = project.extensions.create("frameforkProject", FrameforkProjectExtension::class.java)
        ext.minJavaVersion.set(minJavaVersion)
        ext.jdkVersion.set(jdkVersion)
        if (testsJdkVersion != null) {
            ext.testsJdkVersion.set(testsJdkVersion)
        }
        ext.jspecifyMode.set(true)
    }

    private fun compileToolchainVersion(): Int =
        project.extensions.getByType(JavaPluginExtension::class.java)
            .toolchain.languageVersion.get().asInt()

    private fun releaseOf(taskName: String): Int =
        (project.tasks.getByName(taskName) as JavaCompile).options.release.get()

    private fun testLauncherVersion(): Int =
        (project.tasks.getByName("test") as Test).javaLauncher.get().metadata.languageVersion.asInt()
}
