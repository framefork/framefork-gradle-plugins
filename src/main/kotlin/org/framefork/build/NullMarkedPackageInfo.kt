package org.framefork.build

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.io.File
import javax.inject.Inject

/**
 * Emits a JSpecify `@NullMarked` `package-info.java` into a generated source dir for every package of the target source
 * set that does not already ship a hand-written one. NullAway runs in `onlyNullMarked` mode, so these generated
 * package-infos are what actually make the source set nullness-checked, with new packages covered automatically.
 *
 * The task deliberately captures neither a `Project` reference nor any live model object at execution time — its whole
 * contract is (source roots -> output dir) plus the injected [FileSystemOperations] — so it is configuration-cache-safe,
 * unlike the third-party generator it replaces.
 */
@CacheableTask
abstract class GenerateNullMarkedPackageInfoTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceRoots: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun generate() {
        val outputDir = outputDirectory.get().asFile

        // Wipe stale output so a package-info dropped for a package that has since gained a hand-written one (or lost its
        // last .java file) does not linger and cause a duplicate-class or empty-package compile error on the next run.
        fileSystemOperations.delete { delete(outputDir) }

        val roots = sourceRoots.files.filter { it.isDirectory }

        // A package already carrying a hand-written package-info.java must be skipped: a second, generated one is a
        // duplicate-class compile error.
        val packagesWithHandWrittenInfo = mutableSetOf<String>()
        val packagesWithSource = mutableSetOf<String>()

        for (root in roots) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .forEach { javaFile ->
                    val packagePath = javaFile.parentFile.relativeTo(root).path
                    if (packagePath.isEmpty()) {
                        // A .java file in the default package cannot be given a package-info.java; skip.
                        return@forEach
                    }
                    val packageName = packagePath.replace(File.separatorChar, '.')
                    if (javaFile.name == "package-info.java") {
                        packagesWithHandWrittenInfo.add(packageName)
                    } else {
                        packagesWithSource.add(packageName)
                    }
                }
        }

        for (packageName in packagesWithSource - packagesWithHandWrittenInfo) {
            val target = File(outputDir, packageName.replace('.', File.separatorChar) + File.separator + "package-info.java")
            target.parentFile.mkdirs()
            target.writeText(
                """
                @NullMarked
                package $packageName;

                import org.jspecify.annotations.NullMarked;
                """.trimIndent() + "\n",
            )
        }
    }
}

/**
 * Registers [GenerateNullMarkedPackageInfoTask] for the `main` source set and grafts its output onto `main`'s Java
 * sources, so `compileJava` both depends on it and sees the generated package-infos.
 *
 * Scope is `main` only, matching where NullAway is active (test / test-fixture compilations are `disable()`d, so they
 * need no `@NullMarked` marking).
 */
internal fun Project.configureNullMarkedPackageInfoGeneration() {
    val sourceSets = extensions.getByType<SourceSetContainer>()
    val main = sourceSets.getByName("main")

    val outputDir = layout.buildDirectory.dir("generated/sources/framefork-nullmarked/java/main")

    val generateTask = tasks.register<GenerateNullMarkedPackageInfoTask>("generateNullMarkedPackageInfo") {
        description = "Generates JSpecify @NullMarked package-info.java files for every package of the main source set."
        // Derive the roots lazily so a source dir a consumer adds later in its build script (module scripts run after
        // apply()) is still covered — an eager srcDirs snapshot taken here would miss it, silently letting its packages
        // escape @NullMarked and NullAway's onlyNullMarked check. Reading `main.java.srcDirs` through a plain provider
        // (not the live `sourceDirectories` FileCollection) resolves the roots lazily while carrying no build
        // dependency, so the generator's own output dir — grafted onto `main` below — does not create a self-referential
        // circular task dependency. That output dir is filtered out by value so it is never treated as an input root.
        val ownOutputDir = outputDir.get().asFile
        sourceRoots.from(providers.provider { main.java.srcDirs.filter { it != ownOutputDir } })
        outputDirectory.convention(outputDir)
    }

    // Using the task provider registers compileJava's dependency on it and adds the output dir as a generated Java root.
    main.java.srcDir(generateTask)
}
