#!/usr/bin/env bash
# sp-fuseki packaging smoke test.
#
# Asserts OUR packaging contract — not Jena's correctness (that's Apache's job).
#   1. runs non-root (uid 1000)
#   2. boots; /$/ping healthcheck endpoint answers
#   3. zero-config default dataset: POST turtle -> query it back
#   4. config-respecting: a mounted config.ttl is honoured (its datasets appear;
#      the generated default does not)
#
# Restart-persistence (TDB2) is intentionally NOT tested here: v0.1 is in-memory
# only. It lands with the TDB2 work in v0.2 and tests volume/permission wiring.
#
# Usage: IMAGE=sp-fuseki:dev test/smoke.sh
set -euo pipefail

IMAGE="${IMAGE:-sp-fuseki:dev}"
PORT="${PORT:-13030}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CIDS=()

cleanup() { for c in "${CIDS[@]:-}"; do docker rm -f "$c" >/dev/null 2>&1 || true; done; }
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

wait_ping() {
  local base="$1" i
  for i in $(seq 1 60); do
    if curl -fsS "${base}/\$/ping" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  fail "server did not answer /\$/ping at ${base} within 60s"
}

echo "== sp-fuseki smoke: ${IMAGE} =="

# 1. non-root
echo "[1] runs non-root"
uid="$(docker run --rm --entrypoint id "$IMAGE" -u)"
[ "$uid" = "1000" ] || fail "expected uid 1000, got '$uid'"
pass "uid=$uid"

# 2 + 3. zero-config default dataset, boot, roundtrip
echo "[2] zero-config boot + ping"
cid="$(docker run -d -p "${PORT}:3030" "$IMAGE")"; CIDS+=("$cid")
base="http://localhost:${PORT}"
wait_ping "$base"
pass "/\$/ping answered"

echo "[3] POST turtle -> query back (default /ds)"
curl -fsS -X POST -H 'Content-Type: text/turtle' \
  --data-binary "@${HERE}/sample.ttl" "${base}/ds/data?default" >/dev/null \
  || fail "POST turtle to /ds/data failed"
ask="$(curl -fsS -G "${base}/ds/sparql" \
  --data-urlencode 'query=ASK { <http://example.org/s> <http://example.org/p> <http://example.org/o> }' \
  -H 'Accept: application/sparql-results+json')"
echo "$ask" | grep -q '"boolean"[[:space:]]*:[[:space:]]*true' \
  || fail "ASK did not return true; got: $ask"
pass "round-trip confirmed"

docker rm -f "$cid" >/dev/null; CIDS=()

# 4. config-respecting
echo "[4] mounted config.ttl is honoured"
cid="$(docker run -d -p "${PORT}:3030" \
  -v "$(cd "$HERE/.." && pwd)/examples/config.ttl:/fuseki/config.ttl:ro" \
  "$IMAGE")"; CIDS+=("$cid")
wait_ping "$base"
curl -fsS -G "${base}/training/sparql" --data-urlencode 'query=ASK {}' >/dev/null \
  || fail "mounted dataset /training not served"
pass "/training present (mounted config used)"
code="$(curl -s -o /dev/null -w '%{http_code}' -G "${base}/ds/sparql" --data-urlencode 'query=ASK {}')"
[ "$code" = "404" ] || fail "expected generated default /ds to be absent (404), got $code"
pass "generated default /ds absent — config respected, not merged"

echo "== smoke PASSED =="
