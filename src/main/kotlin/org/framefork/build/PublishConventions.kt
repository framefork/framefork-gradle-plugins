package org.framefork.build

import groovy.util.Node
import groovy.util.NodeList
import org.gradle.api.Project
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Delete
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import java.io.Serializable

/**
 * Maven-publish staging conventions for **published** library modules only (applied by [LibraryPublishedPlugin], never
 * by [LibraryInternalPlugin] — internal/testing modules must never publish).
 *
 * The publication is emitted to a **local** staging repository under the root project's `build/staging-deploy`; the
 * actual promotion to a remote (Maven Central / Portal) is a per-repo JReleaser concern that stays outside the suite.
 *
 * POM fields are derived per-project: the GitHub coordinates come from `github.com/framefork/<rootProject.name>` (every
 * framefork library repo's name is its GitHub slug), the artifact name from `group:name`, and the description from the
 * module's own `description` (with a fallback so a bare module still produces a valid POM).
 */
internal fun Project.configureStagingPublishing() {
    pluginManager.apply("maven-publish")

    extensions.configure<JavaPluginExtension> {
        withJavadocJar()
        withSourcesJar()
    }

    // Test-fixture variants must never leak into the published component (they carry test-only API/runtime deps).
    pluginManager.withPlugin("java-test-fixtures") {
        val javaComponent = components.getByName("java") as AdhocComponentWithVariants
        javaComponent.withVariantsFromConfiguration(configurations.getByName("testFixturesApiElements")) { skip() }
        javaComponent.withVariantsFromConfiguration(configurations.getByName("testFixturesRuntimeElements")) { skip() }
    }

    val repoName = rootProject.name
    val projectUrl = "https://github.com/framefork/$repoName"

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenStaging") {
                from(components.getByName("java"))

                pom {
                    url.set(projectUrl)
                    inceptionYear.set("2024")
                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://spdx.org/licenses/Apache-2.0.html")
                        }
                    }
                    organization {
                        name.set("Framefork")
                        url.set("https://github.com/framefork")
                    }
                    developers {
                        developer {
                            id.set("fprochazka")
                            name.set("Filip Procházka")
                            email.set("mr@fprochazka.cz")
                            url.set("https://filip-prochazka.com/")
                        }
                    }
                    scm {
                        connection.set("scm:git:$projectUrl.git")
                        developerConnection.set("scm:git:ssh://github.com/framefork/$repoName.git")
                        url.set(projectUrl)
                    }
                    issueManagement {
                        system.set("GitHub")
                        url.set("$projectUrl/issues")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "staging"
                url = uri(rootProject.layout.buildDirectory.dir("staging-deploy"))
            }
        }
    }

    // `group`/`description` and the module's `compileOnly` dependencies are all set by the consumer's build script,
    // which runs *after* this plugin's apply(). afterEvaluate is the moment they are known; the values are snapshotted
    // into plain, serializable data so the withXml action captures no project state and stays configuration-cache-safe.
    afterEvaluate {
        val coordinates = "${project.group}:${project.name}"
        val optionalDependencies = configurations.getByName("compileOnly").dependencies
            .map { OptionalDependency(it.group, it.name, it.version) }

        extensions.configure<PublishingExtension> {
            val publication = publications.getByName("mavenStaging") as MavenPublication
            publication.pom.name.set(coordinates)
            publication.pom.description.set(project.description ?: coordinates)
            publication.pom.withXml {
                if (optionalDependencies.isEmpty()) {
                    return@withXml
                }
                val root = asNode()
                val dependenciesNode = (root.get("dependencies") as NodeList).firstOrNull() as? Node
                    ?: root.appendNode("dependencies")
                // framefork libraries treat compileOnly as "optional at runtime": consumers opt in by adding the dep
                // themselves. Maven has no compileOnly, so it is expressed as an optional compile-scoped dependency.
                optionalDependencies.forEach { dependency ->
                    dependenciesNode.appendNode("dependency").apply {
                        appendNode("groupId", dependency.groupId)
                        appendNode("artifactId", dependency.artifactId)
                        appendNode("version", dependency.version)
                        appendNode("scope", "compile")
                        appendNode("optional", "true")
                    }
                }
            }
        }
    }

    // A single wipe of the staging dir, registered once on the root project and depended on by every module's `publish`,
    // so a `publish` run always starts from an empty staging repo regardless of which modules take part.
    if (rootProject.tasks.findByName(CLEAN_ALL_PUBLICATIONS) == null) {
        rootProject.tasks.register<Delete>(CLEAN_ALL_PUBLICATIONS) {
            outputs.upToDateWhen { false }
            delete(rootProject.layout.buildDirectory.dir("staging-deploy"))
        }
    }
    tasks.named("publish") {
        dependsOn(rootProject.tasks.named(CLEAN_ALL_PUBLICATIONS))
    }
}

private const val CLEAN_ALL_PUBLICATIONS = "cleanAllPublications"

/** Serializable snapshot of a `compileOnly` dependency's coordinates, safe to capture in a config-cached withXml action. */
private data class OptionalDependency(
    val groupId: String?,
    val artifactId: String,
    val version: String?,
) : Serializable
