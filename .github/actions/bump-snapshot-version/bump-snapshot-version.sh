#!/usr/bin/env bash
#
# Sets version=<NEXT_VERSION>-SNAPSHOT in gradle.properties after a release and pushes the commit to BRANCH.
#
# Every attempt starts over from the current remote branch, so a commit pushed to BRANCH while the release ran does not fail the bump: the push is
# rejected, the script sleeps, fetches the branch again and re-applies the change on top of it.
#
# The script only moves the version from <RELEASED_VERSION>-SNAPSHOT to <NEXT_VERSION>-SNAPSHOT. If the branch already holds <NEXT_VERSION>-SNAPSHOT
# (the release job was re-run), it does nothing. Any other version means someone changed it by hand, and the script fails instead of overwriting it.
#
# Inputs (environment variables):
#   RELEASED_VERSION  the version that was just released, without -SNAPSHOT
#   NEXT_VERSION      the next development version, without -SNAPSHOT
#   BRANCH            the branch to push the bump to
#   ATTEMPTS          how many times to try the push, default 5
#   BUMP_SNAPSHOT_RETRY_DELAY_SECONDS  attempt N sleeps N times this before the next attempt, default 5 (the tests set 0)
#
# Run it from the root of a clone whose "origin" remote can be pushed to with the credentials git already has.

set -euo pipefail

properties_file='gradle.properties'

fail() {
    echo "::error::$*" >&2
    exit 1
}

require_version() {
    local name="$1" value="$2"
    [[ -n "$value" ]] || fail "${name} is empty"
    # %q keeps a rejected value on one line, so it cannot start a workflow command of its own in the log.
    [[ "$value" =~ ^[0-9A-Za-z.+-]+$ ]] || fail "${name} $(printf '%q' "$value") contains characters outside [0-9A-Za-z.+-]"
    [[ "$value" != *-SNAPSHOT ]] || fail "${name} '${value}' must not end with -SNAPSHOT, the script adds it"
}

released_version="${RELEASED_VERSION-}"
next_version="${NEXT_VERSION-}"
branch="${BRANCH-}"
attempts="${ATTEMPTS:-5}"
retry_delay="${BUMP_SNAPSHOT_RETRY_DELAY_SECONDS:-5}"

require_version 'released-version' "$released_version"
require_version 'next-version' "$next_version"
[[ "$next_version" != "$released_version" ]] || fail "next-version must differ from released-version '${released_version}'"
[[ -n "$branch" ]] || fail 'branch is empty'
git check-ref-format --branch "$branch" > /dev/null 2>&1 || fail "branch $(printf '%q' "$branch") is not a valid branch name"
[[ "$attempts" =~ ^[1-9][0-9]*$ ]] || fail "attempts $(printf '%q' "$attempts") is not a positive integer"
[[ "$retry_delay" =~ ^[0-9]+$ ]] || fail "BUMP_SNAPSHOT_RETRY_DELAY_SECONDS $(printf '%q' "$retry_delay") is not a non-negative integer"

released_snapshot="${released_version}-SNAPSHOT"
next_snapshot="${next_version}-SNAPSHOT"
remote_ref="refs/remotes/origin/${branch}"

git config user.name 'Framefork BOT'
git config user.email 'bot@framefork.org'

# Prints the value of the single "version=" line, or fails when there is none or more than one.
read_version() {
    local lines
    lines="$(grep -E '^version=' "$properties_file" || true)"
    [[ -n "$lines" ]] || fail "${properties_file} on ${branch} has no 'version=' line"
    [[ "$lines" != *$'\n'* ]] || fail "${properties_file} on ${branch} has more than one 'version=' line"
    printf '%s\n' "${lines#version=}"
}

for ((attempt = 1; attempt <= attempts; attempt++)); do
    echo "Attempt ${attempt}/${attempts}: bumping ${properties_file} on ${branch} from ${released_snapshot} to ${next_snapshot}"

    git fetch --no-tags origin "+refs/heads/${branch}:${remote_ref}"
    git reset --hard "$remote_ref"

    current_version="$(read_version)"
    if [[ "$current_version" == "$next_snapshot" ]]; then
        echo "${branch} already has version=${next_snapshot}, nothing to do"
        exit 0
    fi
    if [[ "$current_version" != "$released_snapshot" ]]; then
        fail "${properties_file} on ${branch} has version=${current_version}, expected ${released_snapshot} (or ${next_snapshot} if already bumped); not overwriting it, check the branch by hand"
    fi

    # The versions are validated to [0-9A-Za-z.+-], none of which is special in a sed replacement.
    sed -i "s/^version=.*\$/version=${next_snapshot}/" "$properties_file"
    [[ "$(read_version)" == "$next_snapshot" ]] || fail "rewriting the version line in ${properties_file} failed"

    git commit --quiet -m "chore: bump version to ${next_snapshot}" -- "$properties_file"

    if git push origin "HEAD:refs/heads/${branch}"; then
        echo "Pushed version=${next_snapshot} to ${branch}"
        exit 0
    fi

    echo "::warning::Push to ${branch} was rejected on attempt ${attempt}/${attempts}, the branch probably moved during the release"
    if ((attempt < attempts)); then
        sleep "$((attempt * retry_delay))"
    fi
done

fail "could not push version=${next_snapshot} to ${branch} after ${attempts} attempts; bump ${properties_file} by hand"
