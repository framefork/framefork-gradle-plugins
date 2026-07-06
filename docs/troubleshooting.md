# Troubleshooting

Real failures you may hit adopting `org.framefork.build`, and why. Most are the strict-by-default conventions doing their job — the fix is usually a small, correct change, not relaxing the plugin.

## `unknown enum constant When.MAYBE` / `TypeUseLocation.…`

A dependency's public API carries **jsr305** (`javax.annotation.Nullable` = `@Nonnull(When.MAYBE)`) or **Checker Framework** annotations, and NullAway's `acknowledgeRestrictiveAnnotations` forces javac to resolve those enums — which `-Werror` then fails on if the annotation library isn't on the compile classpath.

The plugin already provides `checker-qual` and `jsr305` as `compileOnly` (they cover the vast majority of annotated JVM libraries — Guava, Spring, Micrometer, Hibernate/hypersistence). If a **different** annotation library trips it, add that one `compileOnly` in the affected module. If it recurs widely, it's a candidate for bundling in the plugin.

## `framefork: minJavaVersion (N) must be <= jdkVersion (M)` (or `… <= testsJdkVersion (M)`)

A JDK-knob misconfiguration, caught up front by the plugin. The compile-once-test-many model requires:
- `minJavaVersion ≤ jdkVersion` — you can't emit `--release 25` bytecode from a JDK 21 toolchain (raw javac would say `error: release version N not supported`).
- `minJavaVersion ≤ testsJdkVersion` — bytecode compiled for a newer release can't run on an older test JVM (it would fail at test runtime with `UnsupportedClassVersionError`).

The plugin validates the **resolved** values, so `-Pjdk.version` / `-Ptests.jdk.version` overrides are checked too. Fix the `framefork { }` block or the override in your CI matrix so the pair named in the message holds. Defaults (17 / 21) are always safe; only overrides bite.

## javadoc fails with `self-closing element not allowed` (or other doclint errors)

`library-published` builds a `-javadoc.jar`, and javadoc runs strict doclint over your sources. Malformed HTML in a doc comment (e.g. `<p/>`) fails the `javadoc` task. Fix the HTML in the offending source. (Whether the plugin should relax doclint by default is an open question — see `docs/design-decisions.md` and the project TODOs.)

## New Error Prone findings after adopting (`PatternMatchingInstanceof`, `EffectivelyPrivate`, `VarWithPrimitive`, …)

The plugin ships **Error Prone 2.50**, which is stricter than older versions your repo may have used. Under `-Werror` these on-by-default checks fail the build — they're genuine improvements (pattern-matching `instanceof`, dropping meaningless `public`, etc.). Fix the code; don't disable the check. (The plugin deliberately does **not** turn on the opt-in style checks fleet-wide.)

## A real nullness bug — `[NullAway] … @Nullable … where @NonNull is required`

Working as intended: NullAway runs in JSpecify mode (`onlyNullMarked` + `jspecifyMode`) as an **error** on `compileJava`. Add `@org.jspecify.annotations.Nullable` where a value genuinely can be null, or refactor. Suppress (`@SuppressWarnings("NullAway")`) only for a real false positive, with a comment.

## `@NullMarked` / package-info

The plugin **generates** a JSpecify `@NullMarked` `package-info.java` for every package of the `main` source set — you don't write them. A hand-written `package-info.java` is respected and never overwritten. Only `main` is marked (NullAway is off on test / test-fixtures).

## Kotlin sources aren't being compiled

`org.jetbrains.kotlin.jvm` is applied **only when a module has `.kt`/`.kts` sources under `src/`**. A pure-Java module intentionally gets no Kotlin plugin. Companion Kotlin plugins are on the classpath, so a Kotlin module can apply them **version-less**: `plugins { id("org.framefork.build.library-published"); kotlin("plugin.serialization") }`.

## Dependency locking

Locking is opt-in: set `framefork { dependencyLocking = true }`. Then generate the lockfiles once and commit them:

```bash
./gradlew resolveAndLockAll --write-locks --no-configuration-cache
```

`--no-configuration-cache` is required — `resolveAndLockAll` is a maintenance task that resolves configurations at execution time and is intentionally not CC-compatible. It does **not** taint your normal build's config cache. Re-run it (and commit the diff) whenever dependencies change; a CI job that regenerates and `git diff --exit-code`s the lockfiles keeps them honest.

## The plugin won't resolve at all

- Ensure `mavenCentral()` is in `pluginManagement { repositories { … } }` in `settings.gradle.kts` (the plugin and its markers are on Maven Central).
- The consumer must be on **Gradle 9.0+** (a Kotlin-DSL plugin built on Gradle 9 emits metadata only Gradle ≥ 8.11 can read; 9.0 is the supported floor).
- The `library-*` plugins are applied **without** a version — adding one errors, because they come in on the classpath via the settings plugin.
