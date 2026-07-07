# framefork-gradle-plugins

Opinionated Gradle **convention plugins** for the [Framefork](https://github.com/framefork) family of JVM libraries. A single settings plugin that a consumer applies with a version, after which every module gets uniform build, static-analysis, test and publishing conventions — with no per-module tool versions or copy-pasted `buildSrc`.

## What it gives you

Apply one plugin and every library module gets, consistently:

- **Java toolchain model** — compile on a modern JDK, emit older-`--release` bytecode, run tests on a configurable JDK (compile-modern / target-old / test-anywhere).
- **Static-analysis strictness** — [Error Prone](https://errorprone.info/) + [NullAway](https://github.com/uber/NullAway) in JSpecify mode (`onlyNullMarked` + `jspecifyMode`), with `@NullMarked` `package-info` generation, over `-Werror`.
- **Tests** — JUnit 5 on the JUnit Platform + readable [test-logger](https://github.com/radarsh/gradle-test-logger-plugin) output, with stray JUnit 4 substituted away.
- **Publishing** — `maven-publish` staging with a full POM (for published library modules).
- **Kotlin** — applied automatically only to modules that actually contain Kotlin sources.

## Usage

In the consumer's `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.framefork.build") version "0.1.0"
}

framefork {
    minJavaVersion = 17    // --release bytecode target (the minimum Java a consumer needs)
    jdkVersion = 21        // compile/build toolchain (>= 21); override per-invocation with -Pjdk.version
    // testsJdkVersion = 21 // test-runtime JDK; defaults to the resolved jdkVersion; override with -Ptests.jdk.version
    // jspecifyMode = true  // NullAway JSpecify mode (default on)
}
```

The settings plugin discovers subprojects under `modules/` (published libraries) and `testing/` (internal test modules), so each module only declares which convention it wants — **version-less**, because the settings plugin puts them on the classpath:

```kotlin
// modules/foo/build.gradle.kts — a published library module
plugins {
    id("org.framefork.build.library-published")
}
```

```kotlin
// testing/bar/build.gradle.kts — an internal/test-only module (everything above, minus publishing)
plugins {
    id("org.framefork.build.library-internal")
}
```

That's the whole consumer surface. No error-prone/nullaway/jspecify versions, no `buildSrc`, no repeated toolchain wiring.

## The `framefork { }` knobs

| Knob | Default | Meaning |
| --- | --- | --- |
| `minJavaVersion` | `17` | `--release` bytecode target — the minimum Java a consumer of the library needs. |
| `jdkVersion` | `21` | The JDK the compiler runs on (must be ≥ 21 for Error Prone). Overridable with `-Pjdk.version=NN`. |
| `testsJdkVersion` | = resolved `jdkVersion` | The JDK the tests execute on. Overridable with `-Ptests.jdk.version=NN`. |
| `jspecifyMode` | `true` | Whether NullAway runs in JSpecify generics mode. |
| `sequentialTests` | `false` | Run at most one test JVM at a time across all modules — mutual exclusion, not ordering. |

Compilation always runs on a modern `jdkVersion` (so current Error Prone works) and emits `--release minJavaVersion` bytecode, so the same artifact is portable across JDKs. Testing across multiple JDKs is a CI-matrix concern — set `-Ptests.jdk.version` per matrix cell; the plugin models a single scalar, not a list.

## Requirements

- **Gradle 9.0+** on the consumer side.
- A JDK ≥ 21 available as a toolchain (plus whatever `testsJdkVersion` you target).

## Building this repo

```bash
./gradlew build                              # compile + unit + functional tests
./gradlew functionalTest -PtestedGradleVersion=9.0   # replay the functional suite against the consumer baseline
./gradlew publishToMavenLocal                # install locally to try against a consumer
```

To try an unreleased version against a real consumer without publishing, `publishToMavenLocal` here and add `mavenLocal()` to the consumer's `pluginManagement.repositories`.

## Documentation

- [Troubleshooting](docs/troubleshooting.md) — common adoption failures (annotation-library `-Werror` breaks, JDK-knob errors, doclint, Kotlin, dependency locking) and their fixes.
- [Design decisions](docs/design-decisions.md) — why the suite is built the way it is (distribution, structure, the JDK model, the strictness stack, config-cache safety).
- [Contributing](CONTRIBUTING.md) — building, testing, the codebase map, and the invariants a change must preserve.
- [Releasing](RELEASING.md) — the Maven Central release procedure: pre-flight checks, triggering the workflow, and writing the changelog.

## License

Released under the [Apache License 2.0](LICENSE).
