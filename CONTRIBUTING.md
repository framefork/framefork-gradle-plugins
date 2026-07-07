# Contributing

Thanks for looking under the hood. This suite is a set of Gradle convention plugins; the **why** behind its shape lives in [`docs/design-decisions.md`](docs/design-decisions.md) — read that first if you're changing anything structural.

## Requirements

- JDK **21+** to build. The functional-test matrix also exercises JDK **25**, so having both installed (e.g. via SDKMAN) lets you reproduce CI locally; the suite does **not** auto-download toolchains.
- Use the Gradle wrapper (`./gradlew`, pinned to 9.6.x). Don't rely on a system Gradle.

## Build & test

```bash
./gradlew build                              # compile + unit (ProjectBuilder) + functional (GradleTestKit) tests
./gradlew functionalTest -PtestedGradleVersion=9.0   # replay the functional suite against the consumer baseline
./gradlew publishToMavenLocal                # install locally to try against a consumer repo (add mavenLocal() to its pluginManagement)
```

## Codebase map

Everything is **binary Kotlin** under `src/main/kotlin/org/framefork/build/` — there are no precompiled `*.gradle.kts` script plugins.

| File | Role |
| --- | --- |
| `FrameforkSettingsPlugin.kt` | The `org.framefork.build` settings plugin: injects repos, discovers `modules/` + `testing/` subprojects, registers the `framefork {}` extension, and propagates resolved knobs to every project via `gradle.lifecycle.beforeProject(IsolatedAction)`. |
| `FrameforkExtension` / `FrameforkProjectExtension` / `FrameforkProjectInitAction` | The settings-level knobs, their per-project mirror, and the CC-safe (scalar-only) propagation action. |
| `LibraryPublishedPlugin` / `LibraryInternalPlugin` | The two version-less convention plugins a consumer applies per module. |
| `AutoServicePlugin` / `AutoServiceConventions.kt` | The version-less per-module feature plugin (`auto-service`) and its `configureAutoService()` helper: wires Google auto-service's annotation + processor onto a `library-*` module. |
| `LibraryConventions.kt` | `configureLibraryBaseConventions()` — the shared composition; `library-internal` == base, `library-published` == base + publishing. |
| `JavaConventions` · `StaticAnalysisConventions` · `NullMarkedPackageInfo` · `TestConventions` · `KotlinConventions` · `PublishConventions` · `DependencyLockingConventions` | The private `internal fun Project.…()` helpers each library plugin composes. |
| `Accessors.kt` | `frameforkProjectExtension()` — the one guarded accessor for the per-project extension. |
| `build.gradle.kts` | Registers the four plugin IDs, pins the external plugin versions (errorprone/nullaway/test-logger/kotlin), and configures publishing (POM on all publications incl. markers). |

## Invariants a change must preserve

1. **Configuration-cache-safe.** No `afterEvaluate` (the single exception in `configureStagingPublishing` is commented as such), no `allprojects`/`subprojects`, `IsolatedAction`s capture only plain data, filesystem reads go through a `ValueSource`, and task classes capture no `Project`. The functional tests run inner builds with `--configuration-cache-problems=fail` — keep them green.
2. **All-binary Kotlin.** Don't introduce precompiled script plugins.
3. **A plugin ID exists only if a consumer applies it.** The registered IDs are the settings plugin, the two `library-*` convention plugins, and the `auto-service` per-module feature plugin; all other logic is `internal` helper functions, not applyable plugins.
4. **Versions have one home.** Tool versions the plugin *injects into consumers* are `const` objects (e.g. `StaticAnalysisVersions`); the *plugin's own* external-plugin versions live in `build.gradle.kts` / `gradle/libs.versions.toml`.
5. **`library-internal` == `library-published` minus publishing, by construction** — both compose `configureLibraryBaseConventions()`; don't hand-repeat the sequence.
6. Read the per-project extension only through `frameforkProjectExtension()` (keeps the missing-settings-plugin error message in one place).

## Adding a convention

1. Write `internal fun Project.configureXConventions()`, reading knobs via `frameforkProjectExtension()`.
2. Call it from `configureLibraryBaseConventions()` (or from `LibraryPublishedPlugin` only, if it's publish-specific).
3. Add a `ProjectBuilder` unit test (fast, asserts config) **and** a `GradleTestKit` functional test (real build). Keep both CC-safe.
4. If it applies an external Gradle plugin, pin its version in `build.gradle.kts` deps and apply it by id via `pluginManager` — never inline a version in the helper.

## Testing model

- **Unit** (`src/test`, `ProjectBuilder`): fast assertions on registration/config. The settings plugin can't be unit-tested (Gradle has no `SettingsBuilder`).
- **Functional** (`src/functionalTest`, `GradleTestKit`): real consumer builds. `PluginResolutionFunctionalTest` is the important one — it resolves a version-less sub-plugin from *real published marker artifacts* (no `withPluginClasspath()`), proving the classpath-injection mechanism.
- Use `frameforkRunner()` (not `GradleRunner.create()`) so `-PtestedGradleVersion` can replay a test against the 9.0 baseline.

## Style & commits

- Follow `.editorconfig` (4-space, LF, final newline, ~240-col lines).
- Conventional-commit subjects (`feat:` / `fix:` / `refactor:` / `build:` / `ci:` / `docs:`), atomic commits, signed.

## Releasing

See the [**Releasing**](README.md#releasing) section of the README — releases go to Maven Central via the `jreleaser-release` workflow, and must only ever be triggered on a commit that already passed CI.
