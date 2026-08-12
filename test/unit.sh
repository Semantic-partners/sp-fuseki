#!/usr/bin/env bash
# sp-fuseki unit tests — the renderer contract, no Docker required.
#
# These are fast (sub-second) and run before the image build in CI, so a broken
# rendering rule fails in seconds rather than after a multi-arch build.
#
# Usage: bash test/unit.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

command -v bb >/dev/null 2>&1 || {
  echo "FAIL: babashka (bb) not on PATH — install it, or run the tests in the image:" >&2
  echo "  docker run --rm -v \"$ROOT:/w\" -w /w --entrypoint bash sp-fuseki:dev test/unit.sh" >&2
  exit 1
}

echo "== sp-fuseki unit tests =="
cd "$ROOT"
bb --classpath "entrypoint:test" \
   -e '(require (quote render-test))
       (let [{:keys [fail error]} (clojure.test/run-tests (quote render-test))]
         (System/exit (if (pos? (+ fail error)) 1 0)))'
