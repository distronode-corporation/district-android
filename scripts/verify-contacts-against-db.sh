#!/usr/bin/env bash
# Validate the contacts surfaces against a REAL, DATABASE-BACKED server.
#
# ⛔ THE HEADLINE CHECK IS THE createdAt TIE-BREAK. `contacts/bulk-create` inserts an entire import in
# ONE statement, so many rows share a createdAt to the millisecond. Ordering on createdAt alone makes
# offset paging non-deterministic — rows get silently SKIPPED or REPEATED between pages, which reads to
# the user as "a contact I definitely saw has vanished". The route adds `id desc` as a second key; this
# proves it works by inserting 40 rows with an IDENTICAL timestamp and walking every page.
set -u

# The local server and a psql command for its database. Both can be overridden; the defaults
# are the maintainers' own setup, described in scripts/README.md.
BASE="${DISTRICT_BASE_URL:-http://127.0.0.1:3100}"
REDIRECT="districtai://auth"
DEVICE_ID="t10-validate-$(date +%s)"
CONTRACTS="$(cd "$(dirname "$0")/.." && pwd)/contracts"
WS="${DISTRICT_WS:?set DISTRICT_WS to a workspace id in the local server database}"
PG="${DISTRICT_PSQL:-docker exec -i distronode-test-pg psql -U postgres -d dev_hub -qtAX}"

pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
FAILURES=0

cleanup() {
  $PG -c "DELETE FROM \"Contact\" WHERE id LIKE 't10seed-%';" >/dev/null 2>&1
  printf '  cleaned up seeded contacts\n'
}
trap cleanup EXIT

echo "== seeding 40 contacts with an IDENTICAL createdAt =="
$PG -c "DELETE FROM \"Contact\" WHERE id LIKE 't10seed-%';" >/dev/null
$PG <<SQL >/dev/null
INSERT INTO "Contact" (id, "workspaceId", name, "phoneNumber", email, "socialHandles", company,
                       intelligence, "visualMemory", "latestContextSummary", "dgiStatus", "dgiError",
                       budget, timeline, website, "lastUpdated", "createdAt")
SELECT
  't10seed-' || lpad(i::text, 3, '0'),
  '$WS',
  'Seed Contact ' || i,
  -- Every third contact is EMAIL-ONLY: phoneNumber is nullable and Postgres treats NULLs as distinct
  -- in the unique index, so any number of phone-less contacts coexist. That is required, not tolerated.
  CASE WHEN i % 3 = 0 THEN NULL ELSE '+141655501' || lpad(i::text, 2, '0') END,
  't10seed' || i || '@seed.test',
  CASE WHEN i % 4 = 0 THEN '{"linkedin":"seed-' || i || '"}' ELSE NULL END::jsonb,
  CASE WHEN i % 5 = 0 THEN '{"name":"Seed Co ' || i || '","domain":"seed.test"}' ELSE NULL END::jsonb,
  CASE WHEN i % 6 = 0 THEN '{"summary":"seeded dossier ' || i || '"}' ELSE NULL END::jsonb,
  CASE WHEN i % 7 = 0 THEN '["https://img.seed.test/' || i || '.png"]' ELSE NULL END::jsonb,
  CASE WHEN i % 2 = 0 THEN 'context ' || i ELSE NULL END,
  -- NULL for most: clear-intel resets dgiStatus to NULL deliberately, and null must read as
  -- "no dossier" rather than as a queued job.
  CASE WHEN i % 8 = 0 THEN 'pending' WHEN i % 9 = 0 THEN 'complete' ELSE NULL END,
  NULL, NULL, NULL, NULL, NULL,
  -- ⛔ IDENTICAL for every row, which is what a bulk import produces.
  '2026-08-15 12:00:00'::timestamp
FROM generate_series(1, 40) AS i;
SQL
SEEDED=$($PG -c "SELECT count(*) FROM \"Contact\" WHERE id LIKE 't10seed-%';")
[ "$SEEDED" = "40" ] && pass "seeded 40 rows sharing one createdAt" || fail "seeded $SEEDED, expected 40"

echo
echo "== warming routes =="
for p in "/auth/native?code_challenge=x&state=y&redirect_uri=z" \
         "/api/district/contacts?workspaceId=$WS&limit=1" \
         "/api/district/contacts/get?workspaceId=$WS&contactId=t10seed-001"; do
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
EXCHANGE=$(curl -sS --max-time 30 -X POST "$BASE/api/auth/native/token" -H 'Content-Type: application/json' \
  -d "{\"code\":\"$CODE\",\"codeVerifier\":\"$VERIFIER\",\"redirectUri\":\"$REDIRECT\",\"deviceId\":\"$DEVICE_ID\",\"deviceName\":\"T10\",\"platform\":\"android\"}")
ACCESS=$(printf '%s' "$EXCHANGE" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)
if [ -z "$ACCESS" ]; then
  fail "no access token"
  echo "       server said: $EXCHANGE"
  exit 1
fi
pass "minted a bearer token"

AUTH=(-H "Authorization: Bearer $ACCESS")

echo
echo "== walking every page (10 at a time) =="
rm -f /tmp/t10-pages.json; : > /tmp/t10-pages.json
OFFSET=0
while [ "$OFFSET" -lt 200 ]; do
  curl -sS --max-time 30 "${AUTH[@]}" \
    "$BASE/api/district/contacts?workspaceId=$WS&limit=10&offset=$OFFSET" >> /tmp/t10-pages.json
  echo >> /tmp/t10-pages.json
  COUNT=$(tail -1 /tmp/t10-pages.json | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("contacts",[])))' 2>/dev/null || echo 0)
  [ "$COUNT" -lt 10 ] && break
  OFFSET=$((OFFSET + 10))
done
pass "walked pages up to offset $OFFSET"

# Clamp behaviour, exercised for real.
curl -sS --max-time 30 "${AUTH[@]}" "$BASE/api/district/contacts?workspaceId=$WS&limit=100000" > /tmp/t10-clamp.json
curl -sS --max-time 30 "${AUTH[@]}" "$BASE/api/district/contacts?workspaceId=$WS&limit=abc" > /tmp/t10-nan.json
curl -sS --max-time 30 "${AUTH[@]}" "$BASE/api/district/contacts?workspaceId=$WS&offset=-5" > /tmp/t10-negoff.json
curl -sS --max-time 30 "${AUTH[@]}" "$BASE/api/district/contacts/get?workspaceId=$WS&contactId=t10seed-002" > /tmp/t10-detail.json
MISSING=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 "${AUTH[@]}" \
  "$BASE/api/district/contacts/get?workspaceId=$WS&contactId=no-such-contact")
[ "$MISSING" = "404" ] && pass "an unknown contactId is 404" || fail "unknown contactId gave $MISSING"

echo
echo "== analysis =="
python3 - "$CONTRACTS" <<'PY'
import json, sys
contracts = sys.argv[1]
failures = 0

def check(ok, msg):
    global failures
    print(("  \033[32mPASS\033[0m " if ok else "  \033[31mFAIL\033[0m ") + msg)
    if not ok: failures += 1

pages = [json.loads(l) for l in open('/tmp/t10-pages.json') if l.strip()]
rows = [c for p in pages for c in p['contacts']]
seeded = [c for c in rows if c['id'].startswith('t10seed-')]

check(all(p.get('success') is True for p in pages), "every page answered success")
check(pages[0]['total'] >= 40, f"total is authoritative ({pages[0]['total']}) — the calls feed has none")
check(pages[0]['limit'] == 10, "the applied limit is echoed")

# ⛔ THE TIE-BREAK. 40 rows share one createdAt; without `id desc` paging would skip or repeat.
ids = [c['id'] for c in seeded]
check(len(ids) == len(set(ids)), f"no id appeared on two pages ({len(ids)} rows, {len(set(ids))} unique)")
check(len(set(ids)) == 40, f"ALL 40 seeded rows were reachable, none skipped (found {len(set(ids))})")

# Ordering must be total and stable: id desc within an identical createdAt.
seeded_order = [c['id'] for c in seeded]
check(seeded_order == sorted(seeded_order, reverse=True),
      "rows sharing a createdAt come back in a deterministic id desc order")

# Clamp, for real.
clamp = json.load(open('/tmp/t10-clamp.json'))
check(clamp['limit'] == 100, f"limit=100000 clamped to {clamp['limit']}")
check(len(clamp['contacts']) <= 100, "and no more than 100 rows came back")
nan = json.load(open('/tmp/t10-nan.json'))
check(nan['limit'] == 10, f"limit=abc replaced with the default ({nan['limit']}), not passed as NaN")
negoff = json.load(open('/tmp/t10-negoff.json'))
check(negoff['offset'] == 0, f"offset=-5 became {negoff['offset']}, which Prisma would have rejected")

# Email-first: a phone-less contact must exist and decode.
phoneless = [c for c in seeded if c['phoneNumber'] is None]
check(len(phoneless) > 0, f"{len(phoneless)} phone-less contacts coexist (email-first is real)")

# The raw Json columns, and dgiStatus null vs "pending".
def typename(v):
    if v is None: return "null"
    for t, n in ((bool,"bool"),(int,"int"),(float,"float"),(str,"str"),(list,"list"),(dict,"dict")):
        if isinstance(v, t): return n
    return type(v).__name__

objs = [c for c in seeded if isinstance(c.get('socialHandles'), dict)]
check(len(objs) > 0, f"socialHandles arrives as an OBJECT on {len(objs)} rows")
vis = [c for c in seeded if c.get('visualMemory') is not None]
check(all(isinstance(c['visualMemory'], list) for c in vis),
      f"visualMemory is an ARRAY on {len(vis)} rows — hence JsonElement, not a data class")
nulls = [c for c in seeded if c.get('dgiStatus') is None]
check(len(nulls) > 0, f"{len(nulls)} rows have a NULL dgiStatus — must read as 'no dossier', not 'pending'")

# Fixture parity: keys and types across EVERY real row.
fx = json.load(open(f"{contracts}/district-contacts.json"))
fx_row = fx['contacts'][0]
extra = set()
for c in rows: extra |= (set(c) - set(fx_row))
check(not extra, f"no unmodelled keys in any real row (found {sorted(extra)})")

mismatch = []
for c in rows:
    for k in set(c) & set(fx_row):
        ft, rt = typename(fx_row[k]), typename(c[k])
        if ft != rt and "null" not in (ft, rt):
            mismatch.append(f"{c['id']}.{k}: fixture={ft} real={rt}")
check(not mismatch, f"no type mismatches against the fixture ({mismatch[:3]})")

check(set(pages[0]) == set(fx), f"envelope keys match the fixture ({sorted(pages[0])})")

# Detail must be the SAME row shape as the list — that is why one DTO serves both.
detail = json.load(open('/tmp/t10-detail.json'))
listed = next((c for c in rows if c['id'] == 't10seed-002'), None)
check(detail.get('success') is True, "detail is enveloped as {success, contact}")
check(listed is not None and detail['contact'] == listed,
      "detail.contact is IDENTICAL to its list row")
fx_detail = json.load(open(f"{contracts}/district-contact-detail.json"))
check(set(detail) == set(fx_detail), f"detail envelope matches the fixture ({sorted(detail)})")

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
