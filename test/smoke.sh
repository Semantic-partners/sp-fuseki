#!/usr/bin/env bash
# sp-fuseki packaging smoke test — bootstrap only.
#
# The tests live in test/smoke.clj (babashka). This wrapper exists so the entry
# point stays exactly what it always was:
#
#   IMAGE=sp-fuseki:dev bash test/smoke.sh
#
# ...which means CI, the README and the pre-commit hook did not have to change.
#
# Why the tests are no longer bash: four defects in the previous 531-line version,
# none of them logic errors — `docker logs | grep -q` SIGPIPEing docker so pipefail
# failed a SUCCESSFUL assertion; `$(printf '\n')` losing its newline so a negative
# test sent the valid credential and passed for the wrong reason; `grep -qv` passing
# vacuously while a secret could leak; and teardown printing "No such container" on
# green runs.
#
# Finding bb: PATH first, otherwise copied out of $IMAGE. That image already
# contains a checksum-verified bb for exactly this architecture, so there is no new
# download and nothing new to trust — and GitHub release downloads have been the
# flakiest dependency in this project by a distance.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
IMAGE="${IMAGE:-sp-fuseki:dev}"

cd "$ROOT"

# A runner *service* often has a narrower PATH than a login shell, so bb can be
# installed and still invisible to CI. Look where it actually gets installed before
# concluding it's missing.
BB=""
if command -v bb >/dev/null 2>&1; then
  BB=bb
else
  for candidate in "$HOME/.local/bin/bb" /opt/homebrew/bin/bb /usr/local/bin/bb; do
    if [ -x "$candidate" ]; then BB="$candidate"; break; fi
  done
fi

if [ -n "$BB" ]; then
  :
elif [ "$(uname -s)" = "Linux" ]; then
  # The image's bb is a Linux binary for this architecture, already
  # checksum-verified at build time — so on a Linux host it is free to reuse and
  # adds no download. This is the path GitHub's hosted runners take.
  echo "bb not on PATH — extracting the verified one from ${IMAGE}"
  TMPBIN="$(mktemp -d)"
  trap 'rm -rf "$TMPBIN"' EXIT
  cref="$(docker create "$IMAGE")"
  docker cp "${cref}:/usr/local/bin/bb" "$TMPBIN/bb" >/dev/null
  docker rm -f "$cref" >/dev/null
  chmod +x "$TMPBIN/bb"
  BB="$TMPBIN/bb"
else
  # Deliberately NOT extracting on macOS: the image's bb is an ELF binary and
  # would fail with "cannot execute binary file" — which is exactly what the first
  # version of this wrapper did on a Mac. The self-hosted arm64 runner is macOS, so
  # bb is a prerequisite there alongside Docker.
  echo "FAIL: bb is required on $(uname -s) and is not on PATH." >&2
  echo "  brew install borkdude/brew/babashka" >&2
  echo "  (the copy inside ${IMAGE} is a Linux binary and cannot run here)" >&2
  exit 1
fi

exec env IMAGE="$IMAGE" PORT="${PORT:-13030}" "$BB" test/smoke.clj
