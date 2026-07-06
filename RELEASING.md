# Releasing

Releases go to **Maven Central** via [JReleaser](https://jreleaser.org/), driven by the `jreleaser-release` GitHub Actions workflow. You trigger the workflow and then write the changelog; everything else runs in CI. Each step below says exactly what to run and how to confirm it worked, so either a person or an automated agent can follow it without prior context.

## Before releasing

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

## 1. Trigger the release workflow

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

## 2. Watch it finish

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

## 3. Write the changelog

JReleaser fills the GitHub release body with raw commit subjects — replace it with human-readable release notes. Write the notes to a file, then update the release:

```bash
gh release edit v0.2.0 --repo framefork/framefork-gradle-plugins --notes-file RELEASE_NOTES.md
```

Good notes describe **what changed for a consumer** — new or changed `framefork { }` knobs, new conventions or behavior, and anything that requires action to upgrade — not the internal commit list. See the [`v0.1.0` notes](https://github.com/framefork/framefork-gradle-plugins/releases/tag/v0.1.0) for the shape.
