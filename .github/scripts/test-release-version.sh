#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
fixture=$(mktemp -d)
cd "$fixture"
git init -q
git config user.name 'Release test'
git config user.email 'release-test@example.com'
mkdir app
printf 'versionCode 49\nversionName "v0.0.47"\n' > app/build.gradle
git add app/build.gradle
git commit -qm 'Initial version'
base=$(git rev-parse HEAD)

check_version() {
  export GITHUB_OUTPUT="$fixture/outputs"
  : > "$GITHUB_OUTPUT"
  bash "$script_dir/release-version.sh"
  grep -qx "TAG_NAME=$1" "$GITHUB_OUTPUT"
  grep -qx "NEW_VERSION_CODE=$2" "$GITHUB_OUTPUT"
  grep -qx "SOURCE_SHA=$(git rev-parse HEAD)" "$GITHUB_OUTPUT"
}

# Empty repositories and unrelated tags do not break allocation.
check_version v0.0.1 50
git tag stackoverflow-val-bug
check_version v0.0.1 50
git tag v0.0.47

# Reproduce the failed run: the published bot commit is not an ancestor.
printf 'versionCode 50\nversionName "v0.0.48"\n' > app/build.gradle
git commit -qam 'Published version'
git tag v0.0.48
git checkout -q "$base"
test "$(git describe --tags --abbrev=0 --match 'v*')" = v0.0.47
check_version v0.0.49 51

# Subsequent queued runs see the new tag even from the same old checkout.
printf 'versionCode 51\nversionName "v0.0.49"\n' > app/build.gradle
git commit -qam 'Next published version'
git tag v0.0.49
git checkout -q "$base"
check_version v0.0.50 52

# Numerical sorting ignores prereleases and does not sort 9 above 49.
git tag v0.0.9
git tag v9.0.0-rc1
check_version v0.0.50 52

# Honor a branch's explicitly increased Android code as well.
printf 'versionCode 100\nversionName "v0.0.47"\n' > app/build.gradle
check_version v0.0.50 101
echo 'All six release version regression cases passed.'
