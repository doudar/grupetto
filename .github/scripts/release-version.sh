#!/usr/bin/env bash
set -euo pipefail

# Release tags can be on bot commits outside this checkout's ancestry.
# Ignore unrelated tags and sort numerically rather than using git describe.
RELEASE_TAGS=$(git tag --list | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V || true)
LATEST_TAG=$(printf '%s\n' "$RELEASE_TAGS" | tail -1)
LATEST_TAG=${LATEST_TAG:-v0.0.0}
IFS=. read -r MAJOR MINOR PATCH <<< "${LATEST_TAG#v}"
NEW_TAG="v${MAJOR}.${MINOR}.$((10#$PATCH + 1))"

VERSION_CODE=$(awk '/^[[:space:]]*versionCode [0-9]+/ {print $2}' app/build.gradle)
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]]
MAX_VERSION_CODE=$((10#$VERSION_CODE))
# A previous run may have published successfully but failed to push its
# version commit because another merge advanced the branch in the meantime.
while IFS= read -r tag; do
  [ -n "$tag" ] || continue
  TAG_VERSION_CODE=$(git show "${tag}:app/build.gradle" | awk '/^[[:space:]]*versionCode [0-9]+/ {print $2}')
  [[ "$TAG_VERSION_CODE" =~ ^[0-9]+$ ]]
  if (( 10#$TAG_VERSION_CODE > MAX_VERSION_CODE )); then
    MAX_VERSION_CODE=$((10#$TAG_VERSION_CODE))
  fi
done <<< "$RELEASE_TAGS"
NEW_VERSION_CODE=$((MAX_VERSION_CODE + 1))

echo "New tag will be: $NEW_TAG (after $LATEST_TAG)"
echo "New version code will be: $NEW_VERSION_CODE"
{
  echo "VERSION_CODE=$VERSION_CODE"
  echo "NEW_VERSION_CODE=$NEW_VERSION_CODE"
  echo "TAG_NAME=$NEW_TAG"
  echo "PREVIOUS_TAG=$LATEST_TAG"
  echo "SOURCE_SHA=$(git rev-parse HEAD)"
} >> "$GITHUB_OUTPUT"
