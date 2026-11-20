#!/usr/bin/env bash
# Open, or keep, the PR that moves one ARG pin in image/Dockerfile, plus any
# change the caller already made under image/ (the KEYS file for a KEYS bump).
# Called by .github/workflows/upstream-check.yml from a checkout of main.
#
#   ci/bump-pr.sh ARG FROM TO PREFIX TITLE BODY_FILE [ISSUE_TITLE_REGEX]
#
# One branch per target version, PREFIX + TO (or $BUMP_KEY) with `+` spelled `-`, so a rerun
# finds the PR it opened yesterday instead of opening another. Open bump PRs on
# the same PREFIX for an older version are closed as superseded, and so are open
# issues whose title matches ISSUE_TITLE_REGEX (a jq regex; the issues this job
# used to open in place of a PR).
#
# A CLOSED, unmerged PR for the same version is a person's decision: it is left
# closed and nothing new is opened for that version.
set -euo pipefail

ARG="$1" FROM="$2" TO="$3" PREFIX="$4" TITLE="$5" BODY="$6" ISSUES="${7:-}"
REPO="${GITHUB_REPOSITORY:?}"
# BUMP_KEY names the target when it is more than TO: the Temurin bump also moves
# the arm64 override, and a different override is a different PR.
KEY="${BUMP_KEY:-$TO}"
BRANCH="${PREFIX}${KEY//+/-}"

OPEN="$(gh pr list --repo "$REPO" --head "$BRANCH" --state open \
          --json number --jq '.[0].number // empty')"
REFUSED="$(gh pr list --repo "$REPO" --head "$BRANCH" --state closed \
             --json number,mergedAt --jq '[.[] | select(.mergedAt == null)][0].number // empty')"

if [ -n "$OPEN" ]; then
  NUM="$OPEN"
  echo "PR #$NUM already open for $ARG=$TO"
elif [ -n "$REFUSED" ]; then
  echo "PR #$REFUSED for $ARG=$TO was closed unmerged; leaving it closed"
  exit 0
else
  sed -i "s/^ARG ${ARG}=.*/ARG ${ARG}=${TO}/" image/Dockerfile
  grep -qx "ARG ${ARG}=${TO}" image/Dockerfile \
    || { echo "could not set ARG ${ARG}=${TO} in image/Dockerfile" >&2; exit 1; }
  git switch -q -c "$BRANCH"
  # image/, not just the Dockerfile: a KEYS bump changes image/jena-KEYS too.
  git add image/
  git -c user.name='github-actions[bot]' \
      -c user.email='41898282+github-actions[bot]@users.noreply.github.com' \
      commit -q -m "$TITLE"
  # --force: a branch left from a PR that was deleted, or from a run that died
  # before `pr create`, holds nothing anyone reviewed.
  git push -q --force origin "HEAD:refs/heads/${BRANCH}"
  gh label create upstream --repo "$REPO" --color 0e8a16 \
    --description "New upstream release to pick up" >/dev/null 2>&1 || true
  # The label is best-effort: a missing label must not lose the PR.
  URL="$(gh pr create --repo "$REPO" --base main --head "$BRANCH" \
           --title "$TITLE" --body-file "$BODY" --label upstream \
         || gh pr create --repo "$REPO" --base main --head "$BRANCH" \
              --title "$TITLE" --body-file "$BODY")"
  NUM="${URL##*/}"
  echo "opened #$NUM: $ARG $FROM -> $TO"
fi

gh pr list --repo "$REPO" --state open --limit 100 --json number,headRefName \
  --jq ".[] | select(.headRefName | startswith(\"${PREFIX}\")) | select(.headRefName != \"${BRANCH}\") | .number" \
| while read -r n; do
    gh pr close "$n" --repo "$REPO" --delete-branch --comment "Superseded by #${NUM}."
    echo "closed #$n, superseded by #$NUM"
  done

if [ -n "$ISSUES" ]; then
  gh issue list --repo "$REPO" --state open --limit 100 --json number,title \
    --jq ".[] | select(.title | test(\"${ISSUES}\")) | .number" \
  | while read -r n; do
      gh issue close "$n" --repo "$REPO" --comment "Superseded by #${NUM}, which makes the bump."
      echo "closed issue #$n, superseded by #$NUM"
    done
fi

echo "pr=$NUM" >> "${GITHUB_OUTPUT:-/dev/null}"
