package org.framefork.build

import net.ltgt.gradle.errorprone.errorprone
import net.ltgt.gradle.nullaway.NullAwayExtension
import net.ltgt.gradle.nullaway.nullaway
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType

/**
 * Uniform nullness strictness for every library module: Error Prone + NullAway driven by JSpecify `@NullMarked`.
 *
 * The stack is deliberately JSpecify-native rather than the older `annotatedPackages` approach:
 *  - [configureNullMarkedPackageInfoGeneration] generates a `@NullMarked` `package-info.java` for **every** package
 *    of the `main` source set, so no hand-written package-info is needed and new packages are covered automatically;
 *  - NullAway runs in `onlyNullMarked` mode, meaning it only analyses code that is actually `@NullMarked` — so the
 *    generated package-infos are what make `main` checked, while any un-annotated package stays unchecked.
 *
 * Severity is asymmetric on purpose: NullAway is an **error** on the main `compileJava` (a real nullness bug fails the
 * build) but **off** on every other compilation. Test / test-fixture / other source sets are not `@NullMarked`
 * (the generator only covers `main`), and `configureJavaConventions()` runs all compilation under `-Werror`, so
 * leaving NullAway active there would turn incidental findings into build failures on code we intentionally don't mark.
 *
 * All configuration is lazy (Providers, `configureEach`, per-task extensions), so it stays configuration-cache-safe.
 */
internal fun Project.configureStaticAnalysis() {
    val ext = frameforkProjectExtension()

    pluginManager.apply("net.ltgt.errorprone")
    pluginManager.apply("net.ltgt.nullaway")

    configureNullMarkedPackageInfoGeneration()

    dependencies.apply {
        add("errorprone", "com.google.errorprone:error_prone_core:${StaticAnalysisVersions.ERROR_PRONE_CORE}")
        add("errorprone", "com.uber.nullaway:nullaway:${StaticAnalysisVersions.NULLAWAY}")
        // JSpecify is API-visible: consumers of the library see the `@NullMarked`/`@Nullable` contract on the public surface.
        add("api", "org.jspecify:jspecify:${StaticAnalysisVersions.JSPECIFY}")
        // Checker-qual completes the nullness stack at compile time only: dependencies whose bytecode carries
        // Checker-Framework annotations (e.g. hypersistence-utils references `TypeUseLocation`) make javac emit
        // `unknown enum constant TypeUseLocation.*` warnings when checker-qual is absent, which `-Werror` turns into
        // build failures. It is compile-only because consumers never need it at runtime.
        add("compileOnly", "org.checkerframework:checker-qual:${StaticAnalysisVersions.CHECKER_QUAL}")
        // jsr305 is compile-only for the same reason as checker-qual: dependencies whose public API carries jsr305
        // annotations (e.g. `@Nonnull(When.MAYBE)` used by Guava, Micrometer, Spring) force javac to resolve the
        // `javax.annotation.meta.When` enum; without jsr305 on the classpath, javac emits `unknown enum constant
        // When.MAYBE` warnings that `-Werror` turns into build failures.
        add("compileOnly", "com.google.code.findbugs:jsr305:${StaticAnalysisVersions.JSR305}")
    }

    // Project-wide NullAway conventions inherited by every per-task NullAwayOptions.
    extensions.configure<NullAwayExtension>("nullaway") {
        onlyNullMarked.set(true)
        jspecifyMode.set(ext.jspecifyMode)
    }

    tasks.withType<JavaCompile>().configureEach {
        // The main source set's compile task is the only @NullMarked-covered one, so it is the only place NullAway errors.
        val isMainCompile = name == "compileJava"

        options.errorprone {
            disableWarningsInGeneratedCode.set(true)
            // The generated @NullMarked package-infos (and any other generated sources) carry no code to null-check.
            excludedPaths.set(".*/build/generated/.*")
            // Report every default-check finding (instead of stopping at the first) and downgrade it to a warning;
            // -Werror (set in configureJavaConventions) then turns the whole batch back into a build failure. Only
            // Error Prone's on-by-default checks run here: enabling the opt-in catalogue fleet-wide (the former
            // allDisabledChecksAsWarnings) made every new stylistic check a hard failure on each Error Prone bump.
            allErrorsAsWarnings.set(true)
            // The disabled-check list is curated once but Error Prone's catalogue drifts across releases (checks get
            // renamed/removed); tolerate names this Error Prone version no longer knows instead of failing the compile.
            ignoreUnknownCheckNames.set(true)

            // Only Error Prone's on-by-default checks run (opt-in checks stay off), so this list only names the
            // on-by-default checks we deliberately opt out of: stylistic/opinionated ones we don't want failing the
            // build under -Werror, plus the class-level nullness suggestion that fights our package @NullMarked.
            disable(
                // Nullness is declared once at package level by the generated package-info, so the
                // class-level suggestion is actively wrong for this convention.
                "AddNullMarkedToClass",
                "DistinctVarargsChecker",
                "InlineMeSuggester",
                "InvalidBlockTag",
                "MissingSummary",
                "OptionalOfRedundantMethod",
                "SameNameButDifferent",
                "StatementSwitchToExpressionSwitch",
                "StringSplitter",
                "TraditionalSwitchExpression",
                "UnnecessaryStringBuilder",
            )

            nullaway {
                if (isMainCompile) {
                    error()
                } else {
                    disable()
                }
                treatGeneratedAsUnannotated.set(true)
                acknowledgeRestrictiveAnnotations.set(true)
                handleTestAssertionLibraries.set(true)
                checkContracts.set(true)
                customInitializerAnnotations.addAll(
                    "org.junit.jupiter.api.BeforeAll",
                    "org.junit.jupiter.api.BeforeEach",
                )
            }
        }
    }
}

/**
 * Coordinates for the analyzer artifacts the helper injects into each *consumer* project (Error Prone core, NullAway,
 * JSpecify, checker-qual, jsr305). They are the single source of truth for those runtime-added dependencies; the Gradle *plugin* artifact
 * versions live in the suite's `gradle/libs.versions.toml` (they are build-time dependencies of the suite itself).
 */
internal object StaticAnalysisVersions {
    const val ERROR_PRONE_CORE = "2.50.0"
    const val NULLAWAY = "0.13.7"
    const val JSPECIFY = "1.0.0"
    const val CHECKER_QUAL = "3.48.2"
    const val JSR305 = "3.0.2"
}
