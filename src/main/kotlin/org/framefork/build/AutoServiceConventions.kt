package org.framefork.build

import org.gradle.api.Project

/**
 * Wires Google [auto-service](https://github.com/google/auto/tree/main/service) into a single module: the compile-only
 * `@AutoService` annotation plus the processing backends that turn it into `META-INF/services` registration files.
 *
 * This is a **per-module feature**, not part of the base library stack: only some modules ship `@AutoService`-annotated
 * classes (e.g. a Hibernate `TypeContributor`), so paying for the processor everywhere would be wrong. A capability that
 * varies per module is a separate opt-in plugin id (`org.framefork.build.auto-service`) applied alongside a `library-*`
 * plugin — deliberately not a `framefork {}` knob, which is a build-wide switch.
 *
 * The base annotation + `annotationProcessor` attach to configurations that only exist once a `java` plugin is applied;
 * the `library-*` convention plugins apply `java-library`, which is the intended companion. Applied to a module with no
 * `java` plugin there is nothing to attach to — a consumer error we fail loudly on rather than silently no-op'ing. This
 * makes the plugin apply-order-sensitive: list the `library-*` plugin first in the `plugins {}` block.
 *
 * ## Kotlin sources need a Kotlin-native backend
 *
 * javac's `annotationProcessor` only ever sees **Java** sources, so `@AutoService` on a Kotlin class generates nothing
 * through it — it fails open (a silent no-op). Kotlin annotation processing runs through either KSP or kapt, and this
 * plugin wires whichever backend the module opted into, reactively (via `pluginManager.withPlugin`) so the order the
 * consumer lists the plugins in doesn't matter:
 *  - **KSP** (`com.google.devtools.ksp`, preferred): adds `dev.zacsweers.autoservice:auto-service-ksp` — the third-party
 *    KSP2-compatible processor. Note it does **not** merge hand-written `META-INF/services` files (see [AutoServiceVersions]).
 *  - **kapt** (`org.jetbrains.kotlin.kapt`, fallback): adds Google's own `auto-service` processor on the `kapt`
 *    configuration. kapt suppresses javac's `annotationProcessor` for the module by default, so the Java and Kotlin
 *    sources are processed once, by kapt — never set `keepJavacAnnotationProcessors = true`, which re-runs the processor
 *    over the Java sources too and produces duplicate service entries.
 *
 * The plugin does **not** auto-apply a backend: it can't know a KSP plugin version compatible with the consumer's Kotlin,
 * and silently applying maintenance-mode kapt would be the wrong default (see docs/design-decisions.md §14). If a module
 * carries Kotlin sources but wires neither backend, this plugin warns — `@AutoService` on those Kotlin classes would
 * otherwise register nothing with no error.
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

    pluginManager.withPlugin("com.google.devtools.ksp") {
        dependencies.add("ksp", "dev.zacsweers.autoservice:auto-service-ksp:${AutoServiceVersions.KSP_PROCESSOR}")
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.kapt") {
        dependencies.add("kapt", "com.google.auto.service:auto-service:${AutoServiceVersions.AUTO_SERVICE}")
    }

    // A Kotlin module that wired no processing backend registers nothing for its Kotlin @AutoService classes and gives no
    // error — warn so the silent gap is diagnosable. The check must be deferred: KSP/kapt may be applied *after* this
    // plugin in the consumer's plugins block, so an eager read would false-warn on a module that does wire a backend.
    // afterEvaluate is the one sanctioned exception (see PublishConventions) — the moment the plugins block is fully
    // evaluated — and stays configuration-cache-safe: it only reads plugin state and logs, capturing no task/service state.
    afterEvaluate {
        val hasKotlin = pluginManager.hasPlugin("org.jetbrains.kotlin.jvm")
        val hasBackend = pluginManager.hasPlugin("com.google.devtools.ksp") || pluginManager.hasPlugin("org.jetbrains.kotlin.kapt")
        if (hasKotlin && !hasBackend) {
            logger.warn(
                "org.framefork.build.auto-service: '$path' has Kotlin sources but no Kotlin annotation-processing backend " +
                    "(KSP or kapt) is applied, so @AutoService on Kotlin classes generates no META-INF/services entries — " +
                    "javac's annotationProcessor only processes Java sources. Add a backend alongside auto-service: KSP " +
                    "(preferred) — id(\"com.google.devtools.ksp\") version \"<version matching your Kotlin>\" — or, as a " +
                    "fallback, kapt — kotlin(\"kapt\"). See docs/troubleshooting.md.",
            )
        }
    }
}

/**
 * The auto-service coordinate versions injected into each consuming module. Consumer-injected versions live in Kotlin
 * `const`s rather than the suite's version catalog (see docs/design-decisions.md §4).
 */
internal object AutoServiceVersions {
    /** Google auto-service — the annotation and both the javac and kapt processors ship in lockstep, so one version covers all. */
    const val AUTO_SERVICE: String = "1.1.1"

    /**
     * `dev.zacsweers.autoservice:auto-service-ksp` — the third-party KSP2-compatible auto-service processor. Google's own
     * auto-service ships only a javac/kapt processor; the upstream project declined to support KSP, so KSP modules use
     * this reimplementation. Caveat vs. the javac processor: it does **not** merge pre-existing hand-written
     * `META-INF/services` files, it only emits entries for the `@AutoService`-annotated classes it processes.
     */
    const val KSP_PROCESSOR: String = "1.2.0"
}
