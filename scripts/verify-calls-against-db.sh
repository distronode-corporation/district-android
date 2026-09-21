#!/usr/bin/env bash
# Validate the calls surfaces against a REAL, DATABASE-BACKED server.
#
# ⛔ THE HEADLINE CHECK IS THE DUPLICATE-ROW WINDOW. CallsPagingSource deduplicates because offset
# paging over a live `createdAt desc` feed can serve the boundary row twice, and a duplicate key
# crashes a LazyColumn. That reasoning was derived from reading the handler; this proves it against
# the real endpoint by INSERTING A CALL BETWEEN TWO PAGE FETCHES and showing the same id come back.
# If it does not reproduce, the deduplication is unnecessary complexity and should be reconsidered.
set -u

BASE="http://127.0.0.1:3100"
REDIRECT="districtai://auth"
DEVICE_ID="t9-validate-$(date +%s)"
CONTRACTS="$(cd "$(dirname "$0")/.." && pwd)/contracts"
WS="${DISTRICT_WS:?set DISTRICT_WS to a workspace id in the local dev_hub}"
PG="docker exec -i distronode-test-pg psql -U postgres -d dev_hub -qtAX"

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
note() { printf '  \033[33mNOTE\033[0m %s\n' "$1"; }
FAILURES=0

cleanup() {
  $PG -c "DELETE FROM \"Call\" WHERE id LIKE 't9seed-%';" >/dev/null 2>&1
  printf '  cleaned up seeded rows\n'
}
trap cleanup EXIT

# ── Seed more rows than one page ─────────────────────────────────────────────
echo "== seeding 60 calls =="
$PG -c "DELETE FROM \"Call\" WHERE id LIKE 't9seed-%';" >/dev/null
$PG <<SQL >/dev/null
INSERT INTO "Call" (id, "workspaceId", "from", "callerName", direction, status, duration,
                    summary, transcript, "followUpSent", analysis, "createdAt")
SELECT
  't9seed-' || lpad(i::text, 3, '0'),
  '$WS',
  '+141655501' || lpad(i::text, 2, '0'),
  'Seed Caller ' || i,
  CASE WHEN i % 3 = 0 THEN 'outbound' ELSE 'inbound' END,
  CASE WHEN i % 7 = 0 THEN 'no-answer' ELSE 'completed' END,
  i * 3,
  'Seeded call ' || i,
  CASE WHEN i % 2 = 0 THEN 'Agent: seeded transcript for call ' || i ELSE NULL END,
  false,
  CASE WHEN i % 5 = 0
       THEN ('{"keyPoints":["point ' || i || '"],"objections":[],"topics":["seed"],"actionItems":[],"followUpSuggested":false}')::jsonb
       ELSE NULL END,
  now() - (i || ' minutes')::interval
FROM generate_series(1, 60) AS i;
SQL
SEEDED=$($PG -c "SELECT count(*) FROM \"Call\" WHERE id LIKE 't9seed-%';")
[ "$SEEDED" = "60" ] && pass "seeded 60 rows" || fail "seeded $SEEDED rows, expected 60"

# ── Warm and authenticate ────────────────────────────────────────────────────
echo
echo "== warming routes =="
for p in "/auth/native?code_challenge=x&state=y&redirect_uri=z" \
         "/api/district/calls?workspaceId=$WS&limit=1" \
         "/api/district/calls/t9seed-001?workspaceId=$WS" \
         "/api/district/calls/t9seed-001/transcript?workspaceId=$WS" \
         "/api/district/calls/t9seed-001/recording?workspaceId=$WS"; do
  curl -sS -o /dev/null --max-time 120 -H 'Cookie: distronode_session=local-dev-mock' "$BASE$p" 2>/dev/null
done
curl -sS -o /dev/null --max-time 120 -X POST "$BASE/api/auth/native/token" \
  -H 'Content-Type: application/json' -d '{}' 2>/dev/null
echo "  done"

VERIFIER=$(head -c 32 /dev/urandom | basenc --base64url | tr -d '=')
CHALLENGE=$(printf '%s' "$VERIFIER" | openssl dgst -sha256 -binary | basenc --base64url | tr -d '=')
STATE=$(head -c 16 /dev/urandom | basenc --base64url | tr -d '=')
AUTH_HTML=$(curl -sS --max-time 30 -H 'Cookie: distronode_session=local-dev-mock' \
  "$BASE/auth/native?code_challenge=$CHALLENGE&state=$STATE&redirect_uri=$(printf '%s' "$REDIRECT" | sed 's|:|%3A|g; s|/|%2F|g')")
CODE=$(printf '%s' "$AUTH_HTML" | grep -oE 'districtai://auth\?code=[A-Za-z0-9_-]+' | head -1 | sed 's/.*code=//')
[ -n "$CODE" ] || { fail "no authorization code"; exit 1; }
ACCESS=$(curl -sS --max-time 30 -X POST "$BASE/api/auth/native/token" -H 'Content-Type: application/json' \
  -d "{\"code\":\"$CODE\",\"codeVerifier\":\"$VERIFIER\",\"redirectUri\":\"$REDIRECT\",\"deviceId\":\"$DEVICE_ID\",\"deviceName\":\"T9\",\"platform\":\"android\"}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)
[ -n "$ACCESS" ] || { fail "no access token"; exit 1; }
pass "minted a bearer token"

AUTH=(-H "Authorization: Bearer $ACCESS")
feed() { curl -sS --max-time 30 "${AUTH[@]}" "$BASE/api/district/calls?workspaceId=$WS&limit=$1&offset=$2"; }

# ── Paging ───────────────────────────────────────────────────────────────────
echo
echo "== paging =="
feed 25 0 > /tmp/t9-p1.json
feed 25 25 > /tmp/t9-p2.json
feed 25 50 > /tmp/t9-p3.json

python3 - <<'PY'
import json, sys
failures = 0
p1 = json.load(open('/tmp/t9-p1.json'))
p2 = json.load(open('/tmp/t9-p2.json'))
p3 = json.load(open('/tmp/t9-p3.json'))

def check(ok, msg):
    global failures
    print(("  \033[32mPASS\033[0m " if ok else "  \033[31mFAIL\033[0m ") + msg)
    if not ok: failures += 1

check(isinstance(p1, list), "the feed is a BARE ARRAY, not an envelope")
check(len(p1) == 25, f"page 1 is full ({len(p1)} rows)")
check(len(p2) == 25, f"page 2 is full ({len(p2)} rows)")
# 60 seeded rows + whatever else exists; page 3 should be short, which is the ONLY end-of-list signal.
check(len(p3) < 25, f"page 3 is SHORT ({len(p3)} rows) — the only end-of-list signal available")

ids1, ids2, ids3 = [c['id'] for c in p1], [c['id'] for c in p2], [c['id'] for c in p3]
check(not (set(ids1) & set(ids2)), "pages 1 and 2 do not overlap on a quiet feed")
allids = ids1 + ids2 + ids3
check(len(allids) == len(set(allids)), "no duplicate ids across pages on a quiet feed")

# Newest-first ordering, which is what makes offset paging unstable under insertion.
created = [c['createdAt'] for c in p1]
check(created == sorted(created, reverse=True), "ordering is newest-first")

print(f"  page1 first={ids1[0]} last={ids1[-1]}")
print(f"  page2 first={ids2[0]} last={ids2[-1]}")
sys.exit(1 if failures else 0)
PY
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

# ── The duplicate-row window, reproduced for real ────────────────────────────
echo
echo "== the duplicate-row window (insert a call between two page fetches) =="
feed 25 0 > /tmp/t9-before.json
# A brand-new call sorts to the TOP, shifting every window down by one.
$PG -c "INSERT INTO \"Call\" (id, \"workspaceId\", \"from\", \"callerName\", direction, status, duration, \"followUpSent\", \"createdAt\") VALUES ('t9seed-999', '$WS', '+14165550199', 'Interrupting Caller', 'inbound', 'completed', 5, false, now());" >/dev/null
feed 25 25 > /tmp/t9-after.json

python3 - <<'PY'
import json, sys
before = [c['id'] for c in json.load(open('/tmp/t9-before.json'))]
after = [c['id'] for c in json.load(open('/tmp/t9-after.json'))]
overlap = set(before) & set(after)

if overlap:
    print(f"  \033[32mPASS\033[0m REPRODUCED: {len(overlap)} id(s) served on BOTH pages: {sorted(overlap)}")
    print("         ⛔ Without deduplication this is a duplicate key in LazyColumn, i.e. a CRASH")
    print("            triggered by a call arriving while the user scrolls. The dedupe is justified.")
    sys.exit(0)
print("  \033[31mFAIL\033[0m did NOT reproduce — re-examine whether the dedupe is needed")
sys.exit(1)
PY
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

# ── Detail, transcript, recording ────────────────────────────────────────────
echo
echo "== per-call endpoints =="
DETAIL_CODE=$(curl -sS -o /tmp/t9-detail.json -w '%{http_code}' --max-time 30 "${AUTH[@]}" \
  "$BASE/api/district/calls/t9seed-002?workspaceId=$WS")
[ "$DETAIL_CODE" = "200" ] && pass "detail HTTP 200" || fail "detail HTTP $DETAIL_CODE"

TR_CODE=$(curl -sS -o /tmp/t9-transcript.json -w '%{http_code}' --max-time 30 "${AUTH[@]}" \
  "$BASE/api/district/calls/t9seed-002/transcript?workspaceId=$WS")
[ "$TR_CODE" = "200" ] && pass "transcript HTTP 200" || fail "transcript HTTP $TR_CODE"

# Odd-numbered rows were seeded with NULL transcript -> must be "" not null.
TR_EMPTY=$(curl -sS --max-time 30 "${AUTH[@]}" "$BASE/api/district/calls/t9seed-001/transcript?workspaceId=$WS")

# ⚠️ -o /dev/null and NO -L: the recording route REDIRECTS, and following it is exactly what the
# client must not do. 404 is correct here since seeded rows have no recording.
REC_CODE=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 "${AUTH[@]}" \
  "$BASE/api/district/calls/t9seed-002/recording?workspaceId=$WS")
case "$REC_CODE" in
  404) pass "recording answers 404 for a call with no recording (an ordinary state)" ;;
  302) pass "recording answers 302 with a Location (not followed)" ;;
  *) fail "recording HTTP $REC_CODE — expected 302 or 404" ;;
esac

MISSING_CODE=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 "${AUTH[@]}" \
  "$BASE/api/district/calls/no-such-call?workspaceId=$WS")
[ "$MISSING_CODE" = "404" ] && pass "an unknown call id is 404" || fail "unknown id gave HTTP $MISSING_CODE"

# ── Detail vs feed row, and fixture key/type parity ──────────────────────────
echo
echo "== detail vs feed, and fixture parity =="
python3 - "$CONTRACTS" "$TR_EMPTY" <<'PY'
import json, sys
contracts, tr_empty_raw = sys.argv[1], sys.argv[2]
failures = 0

def check(ok, msg):
    global failures
    print(("  \033[32mPASS\033[0m " if ok else "  \033[31mFAIL\033[0m ") + msg)
    if not ok: failures += 1

detail = json.load(open('/tmp/t9-detail.json'))
feed = json.load(open('/tmp/t9-p1.json'))
row = next((c for c in feed if c['id'] == 't9seed-002'), None)

check(detail.get('success') is True, "detail is enveloped as {success, call}")
check(row is not None, "the same call appears in the feed")
if row:
    # ⛔ THE INVARIANT THAT LETS ONE KOTLIN DTO SERVE BOTH SURFACES.
    check(detail['call'] == row, "detail.call is IDENTICAL to the feed row")

def typename(v):
    if v is None: return "null"
    for t, n in ((bool,"bool"),(int,"int"),(float,"float"),(str,"str"),(list,"list"),(dict,"dict")):
        if isinstance(v, t): return n
    return type(v).__name__

# Real rows against the committed fixture: keys and types only.
fx = json.load(open(f"{contracts}/district-calls.json"))[0]
extra_all = set()
for c in feed:
    extra_all |= (set(c) - set(fx))
check(not extra_all, f"no unmodelled keys in any real feed row (found {sorted(extra_all)})")

mismatches = []
for c in feed:
    for k in set(c) & set(fx):
        ft, rt = typename(fx[k]), typename(c[k])
        if ft != rt and "null" not in (ft, rt):
            mismatches.append(f"{c['id']}.{k}: fixture={ft} real={rt}")
check(not mismatches, f"no type mismatches against the fixture ({mismatches[:3]})")

# analysis must be a dict wherever present: a String? DTO would throw on any real row carrying one.
bad = [c['id'] for c in feed if c.get('analysis') is not None and not isinstance(c['analysis'], dict)]
check(not bad, f"analysis is an object wherever present ({bad[:3]})")
populated = sum(1 for c in feed if isinstance(c.get('analysis'), dict))
print(f"  rows with a populated analysis object: {populated}")

# Transcript envelope, and the "" (not null) rule for a call with none.
tr = json.load(open('/tmp/t9-transcript.json'))
fx_tr = json.load(open(f"{contracts}/district-call-transcript.json"))
check(set(tr) == set(fx_tr), f"transcript keys match the fixture ({sorted(tr)})")
check(isinstance(tr['transcript'], str) and tr['transcript'] != "", "a populated transcript is a non-empty string")

tr_empty = json.loads(tr_empty_raw)
check(tr_empty.get('transcript') == "", "a call with NO transcript sends \"\" — not null")
check(tr_empty.get('transcript') is not None, "and definitively not null, so isEmpty is the right test")

fx_detail = json.load(open(f"{contracts}/district-call-detail.json"))
check(set(detail) == set(fx_detail), f"detail keys match the fixture ({sorted(detail)})")

print()
print("  FAILURES:", failures)
sys.exit(1 if failures else 0)
PY
PY_STATUS=$?

echo
if [ "$FAILURES" -eq 0 ] && [ "$PY_STATUS" -eq 0 ]; then
  printf '\033[32mALL CHECKS PASSED\033[0m\n'
else
  printf '\033[31mFAILURES: shell=%s python=%s\033[0m\n' "$FAILURES" "$PY_STATUS"
fi
exit $((FAILURES + PY_STATUS))
