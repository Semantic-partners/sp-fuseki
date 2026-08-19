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
# Four suites: the fuseki.edn renderer contract, the CI workflow itself
# (generated from ci/sp_fuseki/workflows.clj precisely so it can be tested), and
# image/Dockerfile's build-time guarantees — the checksum verification a running
# container cannot reveal, because a corrupted artifact that still unpacks boots
# fine and passes every behavioural test. And the pre-start hook's decisions —
# which files run, in what order, and which refuse the boot outright.
bb --classpath "entrypoint:ci:test" \
   -e '(require (quote render-test) (quote workflows-test) (quote dockerfile-test)
                (quote prestart-test))
       (let [{:keys [fail error]} (clojure.test/run-tests (quote render-test)
                                                          (quote workflows-test)
                                                          (quote dockerfile-test)
                                                          (quote prestart-test))]
         (System/exit (if (pos? (+ fail error)) 1 0)))'
