# Design decisions

Why this suite is built the way it is. Records the reasoning behind the non-obvious choices so a future contributor doesn't have to re-derive (or accidentally undo) them.

## 1. Purpose

Every Framefork library repo used to copy-paste the same `framefork.java` / `framefork.java-public` conventions into its own `buildSrc`/`build-logic`, plus root/settings boilerplate — and the copies drifted (different Error Prone / NullAway / Gradle / Java versions). Goal: **one published plugin** every repo pulls in, so consumers declare no tool versions and no `buildSrc`; uniform build/check/test/publish at one strictness level, with a small parametrization surface propagated to every module.

## 2. Distribution — Maven Central via JReleaser (not the Plugin Portal, not GitHub Packages)

- **Not the Gradle Plugin Portal**: its policy rejects company-specific plugins ("too specific to a particular use case or company will be rejected"). `org.framefork.build.*` is exactly that.
- **Not GitHub Packages**: its Maven registry requires a personal access token to *read* — even for public packages — which is friction against the "consumers declare nothing" goal.
- **Maven Central**: no content/editorial policy, no consumer auth (anonymous public read), and `org.framefork` is already a verified namespace publishing there via the org's JReleaser/Sonatype pipeline.
- **Mechanism**: `java-gradle-plugin` + `maven-publish` generate the `pluginMaven` publication plus one `*PluginMarkerMaven` marker per registered id. POM metadata is applied to **all** publications via `publications.withType<MavenPublication>().configureEach { pom { … } }` — Central/pomchecker requires it on the markers too (they're `packaging=pom`, exempt only from the sources/javadoc-jar rule). Everything stages to `build/staging-deploy`; JReleaser signs (in-memory GPG) and deploys, then cuts the GitHub release. `com.gradle.plugin-publish` was dropped (Portal-specific; the markers come from `java-gradle-plugin`).
- Consumers resolve with `pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }`.

## 3. Structure — a settings plugin plus version-less convention plugins

Exactly **three public plugin IDs** — the rule is "an ID exists only if a consumer applies it":
- `org.framefork.build` — the settings plugin, applied **with a version** in `settings.gradle.kts`.
- `org.framefork.build.library-published` — a published library module (**version-less**).
- `org.framefork.build.library-internal` — a non-published `testing/` module (version-less; identical to `library-published` **minus publishing**).

**The classpath-injection crux (why version-less works):** when the consumer applies `org.framefork.build` with a version, its implementation JAR lands on the *settings buildscript classpath*, which is the **parent classloader of every project build script** (same mechanism as `buildSrc`). So the `library-*` plugins are already on the classpath and apply without a version — specifying one *errors*. The non-negotiable prerequisite: **one published JAR** containing the settings plugin and both convention plugins as a single Gradle project.

**Rejected**: a tiered `.java` / `.errorprone` / `.tests` / `.publish` + `.module.*` scheme (Hiero-style) — over-engineered for a library-only suite. All build/check/test/publish logic lives in private `internal fun Project.…Conventions()` helper functions the two library plugins compose, **not** as separately-applyable plugins.

**All binary Kotlin** (no precompiled `*.gradle.kts`): helpers apply external plugins via `pluginManager.apply(id)` (versions pinned once in `build.gradle.kts` deps) and configure them with real DSL types via `extensions.configure<…>`. Precompiled scripts' only advantage (the `plugins{}`-block accessor sugar) is unneeded, and staying all-binary keeps everything unit-testable with `ProjectBuilder`.

## 4. Parametrization surface & propagation

The `framefork { }` extension on **Settings** is the single parametrization surface. Propagation is CC-safe:
1. The settings plugin registers the extension, then in `settingsEvaluated {}` (after the consumer's `framefork {}` block has run) reads the resolved values.
2. It registers `gradle.lifecycle.beforeProject(IsolatedAction)` capturing **only plain scalars** (never `Settings`/extension objects).
3. The `IsolatedAction` creates a per-project `frameforkProject` extension the convention plugins read.

Deliberately **not** `gradle.beforeProject{}` (deprecated / CC-violation), `rootProject{}`, or `allprojects`/`subprojects`. The per-project resolved values are **locked** (`disallowChanges`) — the settings `framefork {}` block is the only override surface; per-module divergence is not supported.

## 5. Three-knob JDK model

- **`minJavaVersion`** (default 17) → `options.release` on **all** `JavaCompile` (main + test) + Kotlin `jvmTarget`. The bytecode target — the minimum Java a consumer needs. `options.release` (not `source`/`targetCompatibility`) for real ct.sym API-surface enforcement.
- **`jdkVersion`** (default 21) → the compile **toolchain**, overridable via `-Pjdk.version`. Must be ≥ 21 because **Error Prone 2.50 requires JDK 21+ to run** — so compilation is always on a modern JDK, emitting older `--release` bytecode. (This is why "compile on the matrix JDK, including 17" is wrong.)
- **`testsJdkVersion`** → the test-runtime launcher (`javaToolchains.launcherFor`), overridable via `-Ptests.jdk.version`, defaulting to the resolved `jdkVersion`.

**Multi-JDK testing is a CI-matrix concern, not the plugin's.** The plugin exposes a single scalar test JDK; CI varies it per cell (`-Ptests.jdk.version=NN`) while the compile toolchain stays modern — compile-once, test-many. `--release` cross-compilation is safe (Hibernate itself builds on JDK 25 and emits Java 17); multi-JDK *runtime* testing exists to catch ByteBuddy/Hibernate/Mockito JDK-sensitivity that `--release` can't.

_(Override property names are dotted — `jdk.version` / `tests.jdk.version` — matching Gradle's own `org.gradle.*` convention. The `framefork {}` extension properties stay camelCase.)_

## 6. Strictness stack

- Versions: Error Prone core **2.50.0**, NullAway **0.13.7**, JSpecify **1.0.0**; `net.ltgt.errorprone` **5.1.0**, `net.ltgt.nullaway` **3.1.0**. The errorprone plugin auto-injects every JDK-16+ flag (`--add-exports/--add-opens`, `-XDcompilePolicy=simple`, `--should-stop=ifError=FLOW`, `-XDaddTypeAnnotationsToSymbol`) — no hand-written `jvmArgs`.
- **JSpecify-native NullAway**: `onlyNullMarked = true` + `jspecifyMode` (a `framefork {}` toggle, default on) + `.error()` on `compileJava`, disabled on test/test-fixtures compilation.
- **Own `@NullMarked` package-info generator**, not the third-party `strict-null-check` plugin: strict-null-check 3.5.0's generation task holds a `Project` field → it **breaks the configuration cache for consumers**. Ours (`GenerateNullMarkedPackageInfoTask`) is `@CacheableTask` with `@InputFiles sourceRoots → @OutputDirectory`, an injected `FileSystemOperations`, no `Project`; it skips packages that already ship a hand-written `package-info.java`; `main` source set only (NullAway is disabled elsewhere).
- **`checker-qual` + `jsr305` as `compileOnly`**: dependencies whose bytecode carries these annotations (hypersistence-utils → Checker Framework; Micrometer/Guava/Spring → jsr305) force javac to resolve their enums under `acknowledgeRestrictiveAnnotations`; without the annotation lib on the classpath, javac emits `unknown enum constant …` warnings that `-Werror` turns into failures. checker-qual + jsr305 cover the common set. They map to `optional` in the published POM — harmless.
- **Standard Error Prone posture** — `allDisabledChecksAsWarnings` was dropped: combined with blanket `-Werror` it made every opt-in EP style check fatal, so each EP version bump would break the whole fleet on new stylistic checks. Now only EP's on-by-default checks + NullAway run; the `disable(...)` list was pruned to on-by-default checks we actually opt out of (kept `AddNullMarkedToClass`).

## 7. Conditional Kotlin support

The Kotlin Gradle plugins (`kotlin-gradle-plugin` + `kotlin-serialization`, Kotlin **2.2.21**) are `implementation` deps of the suite, so a consumer module can apply companion plugins like `kotlin("plugin.serialization")` / `kapt` **version-less**. `org.jetbrains.kotlin.jvm` is applied **only when a module has `.kt`/`.kts` sources** under `src/` — detected via a CC-tracked `ValueSource`, so pure-Java modules stay Kotlin-free (no `compileKotlin`, no stdlib). When applied: `jvmTarget = minJavaVersion`, `explicitApi()`, configured with real KGP types (no reflection). No embedded-Kotlin conflict — Gradle 9.6.1 embeds Kotlin 2.3.21 and the added 2.2.21 plugin coexists cleanly.

## 8. Dependency locking (opt-in)

`framefork { dependencyLocking }` (default **off** — some repos want lockfiles, some don't). When on: `dependencyLocking { lockAllConfigurations() }` in `LockMode.DEFAULT` on every module, plus a per-project `resolveAndLockAll` task (the typed-ids pattern: `notCompatibleWithConfigurationCache`, requires `--write-locks`, resolves all resolvable configurations — a root `dependencies --write-locks` re-locks nothing in a multi-module build). Normal builds stay CC-clean; only the maintenance task is CC-incompatible.

## 9. Configuration-cache safety (a hard constraint)

Everything is CC-safe from day one (Gradle 9 prefers CC; the old convention repos disabled it). No `afterEvaluate` except one sanctioned exception in `configureStagingPublishing` (snapshots `compileOnly` deps into a `Serializable` list so the `withXml` action captures no project state); no `allprojects`/`subprojects`; `IsolatedAction` captures only plain data; the package-info generator captures no `Project`; filesystem probes go through a `ValueSource`.

## 10. Consumer Gradle baseline — 9.0+

A Kotlin-DSL plugin built on Gradle 9.x emits Kotlin metadata v2, consumable only by Gradle ≥ 8.11. The baseline is set to **9.0** (a clean break: CC-preferred, Kotlin 2.2). The suite itself builds on Gradle **9.6.1**. CI validates against both current Gradle and the 9.0 baseline on JDK 21 and 25 — the `Gradle 9.0 × JDK 25` cell is excluded because Gradle 9.0 predates and can't run on JDK 25.

## 11. Testing

GradleTestKit functional tests (`java-gradle-plugin` + `gradlePlugin.testSourceSets(functionalTest)`). The settings plugin is functional-test-only (Gradle has no `SettingsBuilder`). Cross-Gradle-version replay via `-PtestedGradleVersion`; CC store→reuse asserted with `--configuration-cache-problems=fail`. The classpath-injection crux is proven by `PluginResolutionFunctionalTest`, which publishes the suite to a hermetic `build/` Maven repo and resolves a version-less sub-plugin from the real marker artifacts — deliberately **without** `withPluginClasspath()`.

## 12. Root housekeeping absorbed onto the settings→project rails

Every consumer's root `build.gradle.kts` used to copy-paste the same housekeeping — `version` plumbing, an `allDependencies` report task, and the wrapper distribution type. `FrameforkProjectInitAction` (which already visits every project, including the root, to register `cleanAllPublications`) absorbs it:

- **`version` on every project, not via `allprojects`.** Consumers did `allprojects { version = rootProject.version }`, but `allprojects{}` reaches across projects and is Isolated-Projects-hostile. Instead each project reads the global `version` Gradle property itself (it lives in the root `gradle.properties` and Gradle exposes it to every project), so it's a purely local read — no cross-project access. Trimmed, and left at Gradle's `unspecified` default when the property is absent (never crash a repo that declares no version).
- **`group` stays consumer-side — deliberately not propagated.** The root build script runs *after* `beforeProject`, so a root-declared `group` is invisible to the init action; and `group` legitimately diverges per module (e.g. a per-module override in a snapshots/testing module). So the plugin propagates `version` but never touches `group` — the consumer's root `group = "…"` (plus any per-module override) remains the single source.
- **`allDependencies` registered per project, no `evaluationDependsOnChildren()`.** The old task used `evaluationDependsOnChildren()` + the `project-report` plugin to render subprojects. The clean equivalent is a per-project `DependencyReportTask` writing `build/reports/dependencies.txt` — each renders only its own project's dependencies (the task default), so there are no cross-project reads.
- **Wrapper `distributionType = ALL` is root-only**, wired in the `project === rootProject` branch alongside `cleanAllPublications` (the wrapper task exists only on the root).

## 13. Sequential tests (opt-in) — a one-permit shared service, not a `mustRunAfter` chain

`framefork { sequentialTests }` (default **off**). When on, every `Test` task across the whole consumer build runs mutually exclusively — at most one test JVM at a time — while everything else stays parallel. The mechanism is a no-op shared `BuildService` (`TestSerializerService`) registered with `maxParallelUsages = 1` and declared as `usesService(...)` on every `Test` task: a one-permit semaphore the Gradle scheduler enforces against whatever actually runs.

- **Why not `mustRunAfter`**: it only orders tasks that are both in the task graph, so running a subset (`:a:test` alone) or having up-to-date tasks silently breaks the chain. A shared service is mutual exclusion enforced by the scheduler regardless of which tasks run, and it's the official CC-safe API for shared-resource constraints.
- **The contract is mutual exclusion, not ordering.** There is no deterministic cross-module run order — a test must not assume it runs before or after any other module's tests, only that two test JVMs never overlap.
- **Wired on the settings→project rails, not in a library convention helper.** The wiring lives in `FrameforkProjectInitAction` (the `beforeProject` `IsolatedAction`) because it may touch only `gradle.sharedServices` + *this* project's own tasks — Isolated-Projects-legal — and `registerIfAbsent` is idempotent by name across every project's visit. Registration happens only when the knob is on, so the everyday default-off build registers nothing.

## Reference implementations mirrored

Hiero `org.hiero.gradle.*` (settings-plugin + classpath injection + module tiers), Palantir Baseline (`javaVersions` DSL split), vanniktech `maven-publish` (publishing DSL idioms), GradleX build-parameters / reproducible-builds, and jjohannes/idiomatic-gradle.
