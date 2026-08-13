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
#   9. fuseki.edn renders to TTL — mem/tdb2/reasoner datasets all served, the
#      effective TTL is written, and the reasoner actually infers
#  10. a mounted config.ttl beats a mounted fuseki.edn, and says so in the log
#  11. a broken fuseki.edn is FATAL at boot with an actionable message
#  12. settings the EDN carries are HONOURED, not just validated (:ui), and the
#      resolved value is logged with its source
#  13. an explicit env var beats the EDN
#  14. FUSEKI_AUTH=basic: data needs credentials, and the HEALTHCHECK still passes
#  15. FUSEKI_ADMIN_PASSWORD_FILE works and trims the trailing newline
#  16. a missing secret file is FATAL, not a silent empty password
#  17. fuseki.edn can carry :user and a #env secret; the source is logged, the
#      value is not
#  18. a mounted shiro.ini is honoured untouched, not merged with generated auth
#
# 9-11 cover the EDN path; the renderer's own rules are unit-tested in
# test/render_test.clj (bash test/unit.sh), which is the faster feedback loop.
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
TMP="$(mktemp -d)"
CIDS=()

cleanup() {
  for c in "${CIDS[@]:-}"; do docker rm -f "$c" >/dev/null 2>&1 || true; done
  docker volume rm -f "$VOL" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

# Idempotent: sections that use `docker run --rm` leave $cid already reaped, and a
# spurious "No such container" in a passing run is noise in a suite whose whole
# point is being readable.
drop() { for c in "${CIDS[@]:-}"; do docker rm -f "$c" >/dev/null 2>&1 || true; done; CIDS=(); }

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

drop

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

drop

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

drop

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

drop

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

drop

# 9. fuseki.edn as a config source, and its precedence against a mounted TTL.
echo "[9] fuseki.edn renders to TTL"
cid="$(docker run -d -p "${PORT}:3030" \
  -v "$(cd "$HERE/.." && pwd)/examples/fuseki.edn:/fuseki/fuseki.edn:ro" \
  -v "${VOL}:/fuseki/databases" \
  "$IMAGE")"; CIDS+=("$cid")
wait_ping "$base"
for ds in training kb training-inferred; do
  curl -fsS -G "${base}/${ds}/sparql" --data-urlencode 'query=ASK {}' >/dev/null \
    || fail "dataset /${ds} from fuseki.edn not served"
done
pass "all three datasets served (mem, tdb2, reasoner)"
# The rendered file is a documented artefact, so assert it exists and is labelled.
docker exec "$cid" sh -c 'grep -q "GENERATED from" /fuseki/run/config.effective.ttl' \
  || fail "effective config not written/labelled as generated"
docker exec "$cid" sh -c 'grep -q "tdb2:location \"/fuseki/databases/kb\"" /fuseki/run/config.effective.ttl' \
  || fail "tdb2 location not rendered under the writable mount"
pass "effective TTL written, tdb2 location under /fuseki/databases"
# The reasoner dataset must actually infer, or ':reasoner' is decoration.
curl -fsS -X POST -H 'Content-Type: text/turtle' \
  --data-binary '@'"${HERE}/inference.ttl" "${base}/training-inferred/data?default" >/dev/null \
  || fail "POST to reasoner-backed dataset failed"
ask="$(curl -fsS -G "${base}/training-inferred/sparql" \
  --data-urlencode 'query=ASK { <http://example.org/socrates> a <http://example.org/Mortal> }' \
  -H 'Accept: application/sparql-results+json')"
echo "$ask" | grep -q '"boolean"[[:space:]]*:[[:space:]]*true' \
  || fail "RDFS inference did not entail the supertype; got: $ask"
pass "RDFS reasoner entails rdfs:subClassOf (inference is real)"

drop

echo "[10] a mounted config.ttl beats a mounted fuseki.edn, loudly"
cid="$(docker run -d -p "${PORT}:3030" \
  -v "$(cd "$HERE/.." && pwd)/examples/config.ttl:/fuseki/config.ttl:ro" \
  -v "$(cd "$HERE/.." && pwd)/examples/fuseki.edn:/fuseki/fuseki.edn:ro" \
  "$IMAGE")"; CIDS+=("$cid")
wait_ping "$base"
curl -fsS -G "${base}/training/sparql" --data-urlencode 'query=ASK {}' >/dev/null \
  || fail "mounted config.ttl dataset /training not served"
# /kb exists only in the EDN. If it answers, the EDN was rendered and the TTL lost.
code="$(curl -s -o /dev/null -w '%{http_code}' -G "${base}/kb/sparql" --data-urlencode 'query=ASK {}')"
[ "$code" = "404" ] || fail "EDN appears to have been used despite a mounted config.ttl (/kb -> $code)"
pass "TTL won; EDN-only dataset /kb absent"
# Capture first: `docker logs | grep -q` would SIGPIPE docker and trip pipefail
# even on a successful match.
logs="$(docker logs "$cid" 2>&1 || true)"
echo "$logs" | grep -q "IGNORED" \
  || fail "ignoring the EDN was not logged — conflicting config must never be silent"
pass "the ignored EDN was logged"

echo "[11] a broken fuseki.edn fails loudly at boot"
drop
printf '{:datasets [{:name "bad/name" :storage :mem :endpoints #{:query}}]}\n' > "${TMP}/bad.edn"
out="$(docker run --rm -v "${TMP}/bad.edn:/fuseki/fuseki.edn:ro" "$IMAGE" 2>&1 || true)"
echo "$out" | grep -q "FATAL" || fail "invalid EDN did not produce a FATAL message; got: $out"
echo "$out" | grep -q "URL path segment" \
  || fail "error message did not explain the problem; got: $out"
pass "invalid EDN -> FATAL with an actionable message, no half-configured boot"

drop

# 12. Settings the EDN carries are HONOURED, not merely validated.
#
# This exists because `:ui {:enabled false}` was type-checked by the renderer and
# then ignored: you got a UI anyway. A key that validates and does nothing is the
# same "config that lies" this image refuses elsewhere — and it's the very line
# that made us believe the image shipped without a UI in the first place.
echo "[12] fuseki.edn :ui {:enabled false} actually disables the UI"
cat > "${TMP}/ui-off.edn" <<'EOF'
{:ui {:enabled false}
 :datasets [{:name "ds" :storage :mem :endpoints #{:query :gsp-rw}}]}
EOF
cid="$(docker run -d -p "${PORT}:3030" -v "${TMP}/ui-off.edn:/fuseki/fuseki.edn:ro" "$IMAGE")"; CIDS+=("$cid")
wait_ping "$base"
code="$(curl -s -o /dev/null -w '%{http_code}' "$base/")"
[ "$code" != "200" ] || fail "EDN :ui {:enabled false} was ignored — UI still served at / (200)"
pass "/ not served (got $code)"
curl -fsS -G "${base}/ds/sparql" --data-urlencode 'query=ASK {}' >/dev/null \
  || fail "data endpoint broke with the UI disabled from EDN"
pass "data endpoints unaffected"
logs="$(docker logs "$cid" 2>&1 || true)"
echo "$logs" | grep -qE "ui: off \(from fuseki.edn" \
  || fail "the resolved setting and its source were not logged; got: $(echo "$logs" | grep -i 'ui:' || true)"
pass "resolved value logged with its source"

drop

echo "[13] an explicit FUSEKI_UI beats the EDN"
cid="$(docker run -d -p "${PORT}:3030" -e FUSEKI_UI=on \
  -v "${TMP}/ui-off.edn:/fuseki/fuseki.edn:ro" "$IMAGE")"; CIDS+=("$cid")
wait_ping "$base"
root="$(curl -fsS "$base/")" || fail "FUSEKI_UI=on did not override the EDN's :ui false"
echo "$root" | grep -q 'Apache Jena Fuseki UI' || fail "GET / was not the UI shell"
pass "env overrides the file, as documented"

drop

# 14-17. CREDENTIALS. Previously untested end to end, while the README documents
# five separate auth surfaces — and both CVEs found in Jena 6.1.0 were in the auth
# path (shiro-core, jetty-security). "Verified by hand once" is not coverage.
echo "[14] FUSEKI_AUTH=basic — data requires credentials, healthcheck still works"
cid="$(docker run -d -p "${PORT}:3030" -e FUSEKI_AUTH=basic -e FUSEKI_ADMIN_PASSWORD=s3cret "$IMAGE")"; CIDS+=("$cid")
wait_ping "$base"   # /$/ping is deliberately anon, or the healthcheck can never pass
code="$(curl -s -o /dev/null -w '%{http_code}' -G "${base}/ds/sparql" --data-urlencode 'query=ASK {}')"
[ "$code" = "401" ] || fail "basic auth: unauthenticated query should be 401, got $code"
pass "unauthenticated query -> 401"
curl -fsS -u admin:s3cret -X POST -H 'Content-Type: text/turtle' \
  --data-binary "@${HERE}/sample.ttl" "${base}/ds/data?default" >/dev/null \
  || fail "basic auth: authenticated POST failed"
ask="$(curl -fsS -u admin:s3cret -G "${base}/ds/sparql" \
  --data-urlencode 'query=ASK { <http://example.org/s> <http://example.org/p> <http://example.org/o> }' \
  -H 'Accept: application/sparql-results+json')"
echo "$ask" | grep -q '"boolean"[[:space:]]*:[[:space:]]*true' \
  || fail "basic auth: authenticated round-trip failed; got: $ask"
pass "authenticated round-trip confirmed"
# This is the image's own HEALTHCHECK command. Gating /$/ping made every
# basic-auth container report unhealthy; nothing caught it until it was fixed.
docker exec "$cid" sh -c 'curl -fsS "http://localhost:3030/$/ping" >/dev/null' \
  || fail "the HEALTHCHECK command fails under basic auth — containers will go unhealthy"
pass "HEALTHCHECK command still succeeds under basic auth"

drop

echo "[15] FUSEKI_ADMIN_PASSWORD_FILE — the documented-preferred secret path"
printf 'filesecret\n' > "${TMP}/pw"   # trailing newline on purpose: secrets arrive this way
cid="$(docker run -d -p "${PORT}:3030" -e FUSEKI_AUTH=basic \
  -e FUSEKI_ADMIN_PASSWORD_FILE=/run/secrets/pw \
  -v "${TMP}/pw:/run/secrets/pw:ro" "$IMAGE")"; CIDS+=("$cid")
wait_ping "$base"
curl -fsS -u admin:filesecret -G "${base}/ds/sparql" --data-urlencode 'query=ASK {}' >/dev/null \
  || fail "password-from-file did not authenticate (newline not trimmed?)"
pass "secret read from file, newline trimmed"
# The positive case above is the real proof of trimming (an untrimmed stored
# secret would have rejected it). This is the paired negative, and it has to build
# the header by hand: `$(printf '\n')` in a -u argument loses the newline to
# command substitution, so the first version of this test silently sent the valid
# credential and "passed" for the wrong reason.
untrimmed="$(printf 'admin:filesecret\n' | base64 | tr -d '\n')"
code="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Basic ${untrimmed}" \
  -G "${base}/ds/sparql" --data-urlencode 'query=ASK {}')"
[ "$code" = "401" ] || fail "a secret with the trailing newline also authenticated (got $code)"
pass "the untrimmed form is rejected"

drop

echo "[16] a missing password file is FATAL, not a silent empty password"
out="$(docker run --rm -e FUSEKI_AUTH=basic -e FUSEKI_ADMIN_PASSWORD_FILE=/run/secrets/nope "$IMAGE" 2>&1 || true)"
echo "$out" | grep -q "FATAL" || fail "missing secret file did not produce FATAL; got: $out"
echo "$out" | grep -q "not found" || fail "the message did not say the file was missing; got: $out"
pass "missing secret file -> FATAL naming the path"

echo "[17] fuseki.edn can carry the credential, via #env"
cat > "${TMP}/auth.edn" <<'EOF'
{:auth {:mode :basic :user "carol" :password #env "SP_SECRET"}
 :datasets [{:name "ds" :storage :mem :endpoints #{:query :gsp-rw}}]}
EOF
cid="$(docker run -d -p "${PORT}:3030" -e SP_SECRET=fromenvtag \
  -v "${TMP}/auth.edn:/fuseki/fuseki.edn:ro" "$IMAGE")"; CIDS+=("$cid")
wait_ping "$base"
curl -fsS -u carol:fromenvtag -G "${base}/ds/sparql" --data-urlencode 'query=ASK {}' >/dev/null \
  || fail "EDN :auth {:user :password #env} did not authenticate"
pass "EDN-supplied user + #env secret authenticate"
code="$(curl -s -o /dev/null -w '%{http_code}' -u admin:fromenvtag -G "${base}/ds/sparql" --data-urlencode 'query=ASK {}')"
[ "$code" = "401" ] || fail "the EDN's :user was ignored (admin worked), got $code"
pass "EDN :user honoured, not the default"
logs="$(docker logs "$cid" 2>&1 || true)"
echo "$logs" | grep -q "secret from fuseki.edn" || fail "the secret's source was not logged"
# NOT `grep -qv`: that succeeds if ANY line lacks the secret, which is vacuously
# true and would pass while leaking it. This fails if it appears anywhere.
if echo "$logs" | grep -q "fromenvtag"; then fail "THE SECRET VALUE WAS LOGGED"; fi
pass "source logged, value not"

drop

echo "[18] a mounted shiro.ini is honoured untouched"
cat > "${TMP}/shiro.ini" <<'EOF'
[main]
ssl.enabled = false
[users]
dave = mountedpw
[roles]
[urls]
/$/ping = anon
/** = authcBasic
EOF
cid="$(docker run -d -p "${PORT}:3030" -e FUSEKI_AUTH=basic -e FUSEKI_ADMIN_PASSWORD=ignored \
  -v "${TMP}/shiro.ini:/fuseki/shiro.ini:ro" "$IMAGE")"; CIDS+=("$cid")
wait_ping "$base"
curl -fsS -u dave:mountedpw -G "${base}/ds/sparql" --data-urlencode 'query=ASK {}' >/dev/null \
  || fail "the mounted shiro.ini was not used"
pass "the mounted file's user authenticates"
code="$(curl -s -o /dev/null -w '%{http_code}' -u admin:ignored -G "${base}/ds/sparql" --data-urlencode 'query=ASK {}')"
[ "$code" = "401" ] || fail "generated credentials also worked — the mount was merged, not honoured (got $code)"
pass "generated credentials absent — honoured untouched, not merged"

echo "== smoke PASSED =="
