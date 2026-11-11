#!/usr/bin/env bash
#
# Tests bump-snapshot-version.sh against throwaway git repositories: a bare "origin" plus clones, all under a temp directory.
# Run it from anywhere: .github/actions/bump-snapshot-version/test.sh

set -euo pipefail

script="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/bump-snapshot-version.sh"

work_root="$(mktemp -d)"
trap 'rm -rf "$work_root"' EXIT

# Keep the user's and the system's git config (signing, hooks, aliases) out of the test repositories.
export GIT_CONFIG_NOSYSTEM=1
export GIT_CONFIG_GLOBAL="${work_root}/gitconfig"
git config --global user.name 'Test User'
git config --global user.email 'test@example.com'
git config --global init.defaultBranch main
git config --global advice.detachedHead false
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE

export BUMP_SNAPSHOT_RETRY_DELAY_SECONDS=0

failures=0
current_test=''
case_dir=''

check() {
    local description="$1"
    shift
    if "$@"; then
        echo "  ok: ${description}"
    else
        echo "  FAILED: ${description}"
        failures=$((failures + 1))
    fi
}

properties_with_version() {
    cat <<EOF
# version=0.0.1 in a comment is not the version line
group=org.framefork
version=$1
version.catalog=1
kotlinVersion=2.0.0
EOF
}

# Creates a bare origin with one commit on main holding gradle.properties at the given version, a "work" clone the script runs in, and an "other"
# clone that plays the colleague pushing to main during the release.
setup_case() {
    local version="$1"
    case_dir="${work_root}/${current_test}"
    mkdir -p "$case_dir"
    git init --quiet --bare "${case_dir}/origin.git"
    git clone --quiet "${case_dir}/origin.git" "${case_dir}/seed" 2> /dev/null
    properties_with_version "$version" > "${case_dir}/seed/gradle.properties"
    git -C "${case_dir}/seed" add gradle.properties
    git -C "${case_dir}/seed" commit --quiet -m 'initial'
    git -C "${case_dir}/seed" push --quiet origin HEAD:main
    git clone --quiet "${case_dir}/origin.git" "${case_dir}/work"
    git clone --quiet "${case_dir}/origin.git" "${case_dir}/other"
}

# Installs a pre-push hook in the work clone that pushes a competing commit from the other clone before the script's push reaches origin, which makes
# that push a non-fast-forward. With "once" it happens on the first push only, with "always" on every push.
install_competing_push_hook() {
    local mode="$1"
    cat > "${case_dir}/work/.git/hooks/pre-push" <<EOF
#!/usr/bin/env bash
set -euo pipefail
cat > /dev/null
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_PREFIX
marker='${case_dir}/competing-pushes'
if [[ '${mode}' == once && -e "\$marker" ]]; then
    exit 0
fi
echo x >> "\$marker"
cd '${case_dir}/other'
git pull --quiet --ff-only origin main
echo "competing change \$(wc -l < "\$marker")" >> README.md
git add README.md
git commit --quiet -m "competing commit \$(wc -l < "\$marker")"
git push --quiet origin HEAD:main
EOF
    chmod +x "${case_dir}/work/.git/hooks/pre-push"
}

# Runs the script in the work clone; sets bump_status and leaves the output in ${case_dir}/output.
run_bump() {
    local released="$1" next="$2" branch="${3-main}" attempts="${4-}"
    bump_status=0
    (
        cd "${case_dir}/work"
        RELEASED_VERSION="$released" NEXT_VERSION="$next" BRANCH="$branch" ATTEMPTS="$attempts" bash "$script"
    ) > "${case_dir}/output" 2>&1 || bump_status=$?
    sed 's/^/    | /' "${case_dir}/output"
}

origin_git() {
    git --git-dir "${case_dir}/origin.git" "$@"
}

origin_head() {
    origin_git rev-parse main
}

origin_properties_are() {
    [[ "$(origin_git show main:gradle.properties)" == "$(properties_with_version "$1")" ]]
}

output_contains() {
    grep -qF -- "$1" "${case_dir}/output"
}

not_output_line_starting_with() {
    ! grep -q "^$1" "${case_dir}/output"
}

succeeded() {
    [[ "$bump_status" == 0 ]]
}

failed() {
    [[ "$bump_status" != 0 ]]
}

equals() {
    [[ "$1" == "$2" ]]
}

test_bumps_and_pushes() {
    setup_case '0.5.0-SNAPSHOT'
    local before
    before="$(origin_head)"
    run_bump 0.5.0 0.6.0
    check 'exits 0' succeeded
    check 'origin has the next snapshot version and every other line unchanged' origin_properties_are '0.6.0-SNAPSHOT'
    check 'bump commit message' equals "$(origin_git log -1 --format=%s main)" 'chore: bump version to 0.6.0-SNAPSHOT'
    check 'bump commit author is the bot' equals "$(origin_git log -1 --format='%an <%ae>' main)" 'Framefork BOT <bot@framefork.org>'
    check 'bump commit sits directly on the previous head' equals "$(origin_git rev-parse main~1)" "$before"
    check 'bump commit touches only gradle.properties' equals "$(origin_git diff --name-only main~1 main)" 'gradle.properties'
}

test_retries_after_a_concurrent_push() {
    setup_case '0.5.0-SNAPSHOT'
    install_competing_push_hook once
    run_bump 0.5.0 0.6.0
    check 'exits 0' succeeded
    check 'the first push was rejected' output_contains 'rejected on attempt 1/5'
    check 'origin has the next snapshot version' origin_properties_are '0.6.0-SNAPSHOT'
    check 'origin head is the bump commit' equals "$(origin_git log -1 --format=%s main)" 'chore: bump version to 0.6.0-SNAPSHOT'
    check 'the competing commit is preserved right below it' equals "$(origin_git log -1 --format=%s main~1)" 'competing commit 1'
    check 'the competing change is in the tree' equals "$(origin_git show main:README.md)" 'competing change 1'
    check 'exactly one bump commit' equals "$(origin_git log --format=%s main | grep -c 'chore: bump version')" 1
}

test_rerun_is_idempotent() {
    setup_case '0.5.0-SNAPSHOT'
    run_bump 0.5.0 0.6.0
    check 'first run exits 0' succeeded
    local after_first
    after_first="$(origin_head)"
    run_bump 0.5.0 0.6.0
    check 'second run exits 0' succeeded
    check 'second run says there is nothing to do' output_contains 'already has version=0.6.0-SNAPSHOT'
    check 'second run does not move origin' equals "$(origin_head)" "$after_first"
    check 'exactly one bump commit' equals "$(origin_git log --format=%s main | grep -c 'chore: bump version')" 1
}

test_unexpected_version_fails_without_touching_origin() {
    setup_case '0.5.1-SNAPSHOT'
    local before
    before="$(origin_head)"
    run_bump 0.5.0 0.6.0
    check 'exits non-zero' failed
    check 'error names the found and the expected version' output_contains 'has version=0.5.1-SNAPSHOT, expected 0.5.0-SNAPSHOT'
    check 'origin is unchanged' equals "$(origin_head)" "$before"
}

test_duplicate_version_line_fails() {
    setup_case '0.5.0-SNAPSHOT'
    (cd "${case_dir}/other" && echo 'version=0.5.0-SNAPSHOT' >> gradle.properties && git commit --quiet -am 'duplicate' && git push --quiet origin HEAD:main)
    local before
    before="$(origin_head)"
    run_bump 0.5.0 0.6.0
    check 'exits non-zero' failed
    check 'error says why' output_contains "more than one 'version=' line"
    check 'origin is unchanged' equals "$(origin_head)" "$before"
}

test_gives_up_after_the_last_attempt() {
    setup_case '0.5.0-SNAPSHOT'
    install_competing_push_hook always
    run_bump 0.5.0 0.6.0 main 3
    check 'exits non-zero' failed
    check 'error says it gave up' output_contains 'after 3 attempts'
    check 'every attempt was rejected' equals "$(grep -c 'rejected on attempt' "${case_dir}/output")" 3
    check 'origin still has the released snapshot version' origin_properties_are '0.5.0-SNAPSHOT'
    check 'origin has no bump commit' equals "$(origin_git log --format=%s main | grep -c 'chore: bump version' || true)" 0
    check 'the competing commits are all there' equals "$(origin_git log -1 --format=%s main)" 'competing commit 3'
}

test_rejects_invalid_input() {
    setup_case '0.5.0-SNAPSHOT'
    local before
    before="$(origin_head)"
    # released|next|branch|attempts; the $(...) is meant literally, it must reach the script unexpanded
    # shellcheck disable=SC2016
    local -a invalid_inputs=(
        '|0.6.0|main|'
        '0.5.0||main|'
        '0.5.0|0.6.0/evil|main|'
        '0.5.0|0.6.0 evil|main|'
        '0.5.0|0.6.0$(touch pwned)|main|'
        '0.5.0|0.6.0&|main|'
        '0.5.0|0.6.0-SNAPSHOT|main|'
        '0.5.0|0.5.0|main|'
        '0.5.0|0.6.0||'
        '0.5.0|0.6.0|bad..branch|'
        '0.5.0|0.6.0|main|0'
        '0.5.0|0.6.0|main|abc'
    )
    local input released next branch attempts
    for input in "${invalid_inputs[@]}"; do
        IFS='|' read -r released next branch attempts <<< "$input"
        echo "  input: released='${released}' next='${next}' branch='${branch}' attempts='${attempts}'"
        run_bump "$released" "$next" "$branch" "$attempts"
        check 'exits non-zero' failed
        check 'origin is unchanged' equals "$(origin_head)" "$before"
    done
    check 'nothing was executed from an input' test ! -e "${case_dir}/work/pwned"

    echo "  input: next-version with a newline followed by a workflow command"
    run_bump 0.5.0 $'0.6.0\n::warning::injected' main
    check 'exits non-zero' failed
    check 'the rejected value cannot start a workflow command line' not_output_line_starting_with '::warning::injected'
    check 'origin is unchanged' equals "$(origin_head)" "$before"
}

for current_test in \
    test_bumps_and_pushes \
    test_retries_after_a_concurrent_push \
    test_rerun_is_idempotent \
    test_unexpected_version_fails_without_touching_origin \
    test_duplicate_version_line_fails \
    test_gives_up_after_the_last_attempt \
    test_rejects_invalid_input; do
    echo "${current_test}"
    "$current_test"
done

if ((failures > 0)); then
    echo "${failures} check(s) FAILED"
    exit 1
fi
echo 'All checks passed'
