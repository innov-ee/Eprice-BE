#!/usr/bin/env bash
set -euo pipefail

# Figures out which commit and tag to user for release
# Note either of them may or may not have been provided depending on where workflow was started
# Writes them to $GITHUB_OUTPUT

mode=${1:?Expected release or rollback}
version_pattern='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'

if [[ "$mode" == rollback || "${GITHUB_EVENT_NAME:-}" == push ]]; then
  tag=${RELEASE_TAG:-${GITHUB_REF_NAME:-}}
  [[ "$tag" =~ $version_pattern ]] || { echo 'Expected a vMAJOR.MINOR.PATCH tag' >&2; exit 1; }
  sha=$(git rev-parse --verify "refs/tags/$tag^{commit}")
else
  [[ "$mode" == release ]] || { echo 'Unknown release mode' >&2; exit 1; }
  requested=${RELEASE_COMMIT:-${GITHUB_SHA:?Missing commit}}
  [[ "$requested" =~ ^[0-9a-fA-F]{40}$ ]] || { echo 'Commit must be a full SHA' >&2; exit 1; }
  sha=$(git rev-parse --verify "$requested^{commit}")
  latest=v0.0.0
  while IFS= read -r candidate; do
    if [[ "$candidate" =~ $version_pattern ]] && git merge-base --is-ancestor "$candidate^{commit}" origin/main; then
      latest=$candidate
      break
    fi
  done < <(git tag --list 'v*' --sort=-version:refname)
  IFS=. read -r major minor patch <<< "${latest#v}"
  case "${BUMP:-minor}" in
    major) major=$((major + 1)); minor=0; patch=0 ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    patch) patch=$((patch + 1)) ;;
    *) echo 'Expected major, minor or patch' >&2; exit 1 ;;
  esac
  tag="v$major.$minor.$patch"
  if git show-ref --verify --quiet "refs/tags/$tag"; then
    echo "Version already exists: $tag" >&2
    exit 1
  fi
fi

git merge-base --is-ancestor "$sha" origin/main || { echo 'Selected commit is outside main' >&2; exit 1; }
printf 'sha=%s\ntag=%s\n' "$sha" "$tag" >> "${GITHUB_OUTPUT:?Missing output file}"
printf 'Selected %s at %s\n' "$tag" "$sha"
