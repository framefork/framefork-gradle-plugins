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
    // testsJdkVersion = 21 // test-runtime JDK; defaults to the resolved jdkVersion; override with -PtestsJdkVersion
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
| `testsJdkVersion` | = resolved `jdkVersion` | The JDK the tests execute on. Overridable with `-PtestsJdkVersion=NN`. |
| `jspecifyMode` | `true` | Whether NullAway runs in JSpecify generics mode. |

Compilation always runs on a modern `jdkVersion` (so current Error Prone works) and emits `--release minJavaVersion` bytecode, so the same artifact is portable across JDKs. Testing across multiple JDKs is a CI-matrix concern — set `-PtestsJdkVersion` per matrix cell; the plugin models a single scalar, not a list.

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

## Releasing

Releases go to **Maven Central** via [JReleaser](https://jreleaser.org/), driven by the `jreleaser-release` GitHub Actions workflow. You trigger the workflow and then write the changelog; everything else runs in CI. Each step below says exactly what to run and how to confirm it worked, so either a person or an automated agent can follow it without prior context.

### Before releasing

- **Release only a commit that has already passed CI.** A released version is immutable on Maven Central, so never release an unverified or a red commit. The commit being released is the current `HEAD` of `main`; it must be pushed and the `Run Checks via Gradle` workflow must be **green for that exact SHA**:

  ```bash
  # the SHA that will be released
  git rev-parse origin/main

  # the latest CI run on main — its headSha must equal the SHA above, and conclusion must be "success"
  gh run list --repo framefork/framefork-gradle-plugins --workflow gradle-build.yml \
    --branch main --limit 1 --json headSha,status,conclusion
  ```

  If the SHAs don't match, or `status` isn't `completed`, or `conclusion` isn't `success`, **do not release** — push/fix and wait for a green run first.

- The `JRELEASER_*` secrets (GPG key + passphrase, Maven Central token, GitHub token) are configured as **framefork organization** secrets, visible to public repositories — nothing to set per release.

### 1. Trigger the release workflow

Choose the release version and the next development version, then dispatch the workflow:

```bash
gh workflow run jreleaser-release.yml \
  --repo framefork/framefork-gradle-plugins \
  -f version=0.2.0 \
  -f nextVersion=0.3.0
```

- `version` — the version to release. No `-SNAPSHOT` (Maven Central rejects snapshots, and released versions cannot be overwritten).
- `nextVersion` — the next development version. After a successful release the workflow bumps `gradle.properties` to `<nextVersion>-SNAPSHOT` and pushes that commit to `main`.

The workflow builds and stages the artifacts, then JReleaser signs them, deploys the plugin and its marker artifacts to Maven Central, creates a `v<version>` GitHub release, and pushes the version bump.

### 2. Watch it finish

```bash
# find the run that was just started, then watch it to completion
gh run list --repo framefork/framefork-gradle-plugins --workflow jreleaser-release.yml --limit 1
gh run watch <run-id> --repo framefork/framefork-gradle-plugins --exit-status
```

Then confirm the artifacts:

```bash
# the plugin marker POM is live on Central (200 = published; 404 right after release is normal, Central sync lags — retry shortly)
curl -s -o /dev/null -w "%{http_code}\n" \
  "https://repo1.maven.org/maven2/org/framefork/build/org.framefork.build.gradle.plugin/0.2.0/org.framefork.build.gradle.plugin-0.2.0.pom"

# the GitHub release exists
gh release view v0.2.0 --repo framefork/framefork-gradle-plugins
```

### 3. Write the changelog

JReleaser fills the GitHub release body with raw commit subjects — replace it with human-readable release notes. Write the notes to a file, then update the release:

```bash
gh release edit v0.2.0 --repo framefork/framefork-gradle-plugins --notes-file RELEASE_NOTES.md
```

Good notes describe **what changed for a consumer** — new or changed `framefork { }` knobs, new conventions or behavior, and anything that requires action to upgrade — not the internal commit list. See the [`v0.1.0` notes](https://github.com/framefork/framefork-gradle-plugins/releases/tag/v0.1.0) for the shape.

## License

Released under the [Apache License 2.0](LICENSE).
