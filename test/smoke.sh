#!/usr/bin/env bash
# sp-fuseki packaging smoke test.
#
# Asserts OUR packaging contract — not Jena's correctness (that's Apache's job).
#   1. runs non-root (uid 1000)
#   2. boots; /$/ping healthcheck endpoint answers
#   3. zero-config default dataset: POST turtle -> query it back
#   4. config-respecting: a mounted config.ttl is honoured (its datasets appear;
#      the generated default does not)
#   5. Fuseki's own UI is served (we ship it — this is the assertion that stops
#      "does it have a UI?" from ever being a docs question again)
#   6. anon mode fences the MUTATING admin API (you cannot delete datasets
#      without credentials)
#   7. FUSEKI_UI=off gives the headless server, data endpoints untouched
#   8. TDB2 on a named volume survives a restart, and the documented mount path
#      is writable as uid 1000 (the thing that silently breaks if the image
#      stops pre-creating /fuseki/databases)
#
# v0.1 datasets are in-memory by default; 8 asserts the *mount contract* for the
# persistent option, not TDB2 tuning/backup behaviour (that's the v0.2 work).
#
# Usage: IMAGE=sp-fuseki:dev test/smoke.sh
set -euo pipefail

IMAGE="${IMAGE:-sp-fuseki:dev}"
PORT="${PORT:-13030}"
HERE="$(cd "$(dirname "$0")" && pwd)"
VOL="sp-fuseki-smoke-$$"
CIDS=()

cleanup() {
  for c in "${CIDS[@]:-}"; do docker rm -f "$c" >/dev/null 2>&1 || true; done
  docker volume rm -f "$VOL" >/dev/null 2>&1 || true
}
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

docker rm -f "$cid" >/dev/null; CIDS=()

# 5 + 6. UI is served; anon mode fences the mutating admin API.
echo "[5] Fuseki's own UI is served"
cid="$(docker run -d -p "${PORT}:3030" "$IMAGE")"; CIDS+=("$cid")
wait_ping "$base"

root="$(curl -fsS "$base/")" || fail "GET / failed — no UI served"
echo "$root" | grep -q 'Apache Jena Fuseki UI' \
  || fail "GET / did not look like the Fuseki UI shell; got: $(echo "$root" | head -c 200)"
pass "/ serves the Fuseki UI shell"

# The shell is useless if its bundle 404s, so assert the asset it references.
asset="$(echo "$root" | grep -oE 'static/[A-Za-z0-9._-]+\.js' | head -1)"
[ -n "$asset" ] || fail "UI shell referenced no /static/*.js bundle"
code="$(curl -s -o /dev/null -w '%{http_code}' "${base}/${asset}")"
[ "$code" = "200" ] || fail "UI bundle ${asset} not served (got $code)"
pass "UI bundle ${asset} served"

curl -fsS "${base}/\$/server" | grep -q '"datasets"' \
  || fail "/\$/server did not report datasets — UI has no server info to show"
pass "/\$/server reports datasets"

echo "[6] anon mode fences the mutating admin API"
code="$(curl -s -o /dev/null -w '%{http_code}' -X POST "${base}/\$/datasets" \
  --data 'dbName=smoke-should-not-exist&dbType=mem')"
[ "$code" = "401" ] || fail "anon POST /\$/datasets should be 401, got $code (admin API is open!)"
pass "POST /\$/datasets -> 401"
code="$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "${base}/\$/datasets/ds")"
[ "$code" = "401" ] || fail "anon DELETE /\$/datasets/ds should be 401, got $code (your data is deletable!)"
pass "DELETE /\$/datasets/ds -> 401"
# Fencing admin must not have fenced the data endpoints.
curl -fsS -G "${base}/ds/sparql" --data-urlencode 'query=ASK {}' >/dev/null \
  || fail "data endpoint /ds/sparql broke while fencing admin"
pass "data endpoints still anonymous"

docker rm -f "$cid" >/dev/null; CIDS=()

# 7. headless variant — same image, runtime switch.
echo "[7] FUSEKI_UI=off serves no UI, data endpoints unaffected"
cid="$(docker run -d -p "${PORT}:3030" -e FUSEKI_UI=off "$IMAGE")"; CIDS+=("$cid")
wait_ping "$base"
code="$(curl -s -o /dev/null -w '%{http_code}' "$base/")"
[ "$code" != "200" ] || fail "FUSEKI_UI=off still served a UI at / (got 200)"
pass "/ not served (got $code)"
curl -fsS -X POST -H 'Content-Type: text/turtle' \
  --data-binary "@${HERE}/sample.ttl" "${base}/ds/data?default" >/dev/null \
  || fail "headless: POST turtle to /ds/data failed"
ask="$(curl -fsS -G "${base}/ds/sparql" \
  --data-urlencode 'query=ASK { <http://example.org/s> <http://example.org/p> <http://example.org/o> }' \
  -H 'Accept: application/sparql-results+json')"
echo "$ask" | grep -q '"boolean"[[:space:]]*:[[:space:]]*true' \
  || fail "headless: ASK did not return true; got: $ask"
pass "headless round-trip confirmed"

docker rm -f "$cid" >/dev/null; CIDS=()

# 8. TDB2 persistence on the documented mount path.
echo "[8] TDB2 on a volume at /fuseki/databases survives a restart"
cid="$(docker run -d -p "${PORT}:3030" \
  -v "${VOL}:/fuseki/databases" \
  -v "$(cd "$HERE/.." && pwd)/examples/config-tdb2.ttl:/fuseki/config.ttl:ro" \
  "$IMAGE")"; CIDS+=("$cid")
wait_ping "$base"
pass "booted with a volume mounted at /fuseki/databases"

# If the mount point were root-owned, TDB2 would have died before this point with
# a misleading "No such file or directory" — so a successful write is the assertion.
curl -fsS -X POST -H 'Content-Type: text/turtle' \
  --data-binary "@${HERE}/sample.ttl" "${base}/ds/data?default" >/dev/null \
  || fail "POST turtle to TDB2-backed /ds/data failed (mount not writable as uid 1000?)"
pass "wrote to the TDB2 dataset"

docker restart "$cid" >/dev/null
wait_ping "$base"
ask="$(curl -fsS -G "${base}/ds/sparql" \
  --data-urlencode 'query=ASK { <http://example.org/s> <http://example.org/p> <http://example.org/o> }' \
  -H 'Accept: application/sparql-results+json')"
echo "$ask" | grep -q '"boolean"[[:space:]]*:[[:space:]]*true' \
  || fail "triple did not survive restart — persistence broken; got: $ask"
pass "data survived a container restart"

echo "== smoke PASSED =="
