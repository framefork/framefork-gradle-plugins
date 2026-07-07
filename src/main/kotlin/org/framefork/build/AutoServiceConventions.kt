package org.framefork.build

import org.gradle.api.Project

/**
 * Wires Google [auto-service](https://github.com/google/auto/tree/main/service) into a single module: the compile-only
 * `@AutoService` annotation plus the annotation processor that turns it into `META-INF/services` registration files.
 *
 * This is a **per-module feature**, not part of the base library stack: only some modules ship `@AutoService`-annotated
 * classes (e.g. a Hibernate `TypeContributor`), so paying for the processor everywhere would be wrong. A capability that
 * varies per module is a separate opt-in plugin id (`org.framefork.build.auto-service`) applied alongside a `library-*`
 * plugin — deliberately not a `framefork {}` knob, which is a build-wide switch.
 *
 * Both dependencies attach to configurations (`compileOnly`, `annotationProcessor`) that only exist once a `java` plugin
 * is applied; the `library-*` convention plugins apply `java-library`, which is the intended companion. Applied to a
 * module with no `java` plugin there is nothing to attach to — a consumer error we fail loudly on rather than silently
 * no-op'ing. This makes the plugin apply-order-sensitive: list the `library-*` plugin first in the `plugins {}` block.
 *
 * The annotation processor coexists with the Error Prone processor the strictness stack registers — javac runs all
 * processors in one round — so no special ordering is needed.
 */
internal fun Project.configureAutoService() {
    require(pluginManager.hasPlugin("java")) {
        "org.framefork.build.auto-service needs a java plugin on the module — apply it alongside (and after) " +
            "org.framefork.build.library-published / library-internal, which provide the compileOnly and " +
            "annotationProcessor configurations it wires into."
    }

    dependencies.apply {
        add("compileOnly", "com.google.auto.service:auto-service-annotations:${AutoServiceVersions.AUTO_SERVICE}")
        add("annotationProcessor", "com.google.auto.service:auto-service:${AutoServiceVersions.AUTO_SERVICE}")
    }
}

/**
 * The auto-service coordinate version injected into each consuming module. Consumer-injected versions live in Kotlin
 * `const`s rather than the suite's version catalog (see docs/design-decisions.md §4); the annotation and the processor
 * ship in lockstep, so a single version covers both `auto-service-annotations` and `auto-service`.
 */
internal object AutoServiceVersions {
    const val AUTO_SERVICE: String = "1.1.1"
}
