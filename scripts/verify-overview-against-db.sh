#!/usr/bin/env bash
# Validate the overview fixtures against a REAL, DATABASE-BACKED response.
#
# ⛔ WHY THIS EXISTS ON TOP OF THE CONTRACT SUITE. The committed fixtures are generated with the
# data layer MOCKED, which is deliberate — the contract is the handler's own mapping and mocked
# inputs make it deterministic. But a mock only ever returns what someone typed into it, so a
# column whose real values differ in shape or case from the seed data is invisible. That is
# exactly how `analysis` shipped as `String?` when the real column is a JSON object: every seed
# row had it null.
#
# So: mint a real bearer token, call both new routes against the real Postgres, and diff the KEY
# SETS and VALUE TYPES against the committed fixtures.
set -u

# The local server and a psql command for its database. Both can be overridden; the defaults
# are the maintainers' own setup, described in scripts/README.md.
BASE="${DISTRICT_BASE_URL:-http://127.0.0.1:3100}"
DEVICE_ID="t8-validate-$(date +%s)"
CONTRACTS="$(cd "$(dirname "$0")/.." && pwd)/contracts"

# shellcheck source=lib/district-auth.sh
. "$(dirname "$0")/lib/district-auth.sh"

echo "== warming routes (next dev compiles on first hit) =="
warm_routes "/api/district/workspace/list" \
            "/api/district/overview?workspaceId=${DISTRICT_WS:?set DISTRICT_WS to a workspace id in the local server database}"

# ── Mint a real token ────────────────────────────────────────────────────────
mint_access_token "$DEVICE_ID" "T8 Validate"

# ── Fetch both routes for real ───────────────────────────────────────────────
echo
echo "== GET /api/district/workspace/list =="
LIST_CODE=$(curl -sS -o /tmp/t8-list.json -w '%{http_code}' --max-time 30 \
  -H "Authorization: Bearer $ACCESS" "$BASE/api/district/workspace/list")
[ "$LIST_CODE" = "200" ] && pass "HTTP 200" || { fail "HTTP $LIST_CODE"; cat /tmp/t8-list.json; }

WS_ID=$(python3 -c '
import json
d=json.load(open("/tmp/t8-list.json"))
ws=d.get("workspaces") or []
print(ws[0]["id"] if ws else "")
' 2>/dev/null)
[ -n "$WS_ID" ] && pass "resolved an active workspace: $WS_ID" || fail "no active workspace returned"

echo
echo "== GET /api/district/overview?workspaceId=$WS_ID =="
OV_CODE=$(curl -sS -o /tmp/t8-overview.json -w '%{http_code}' --max-time 30 \
  -H "Authorization: Bearer $ACCESS" "$BASE/api/district/overview?workspaceId=$WS_ID")
[ "$OV_CODE" = "200" ] && pass "HTTP 200" || { fail "HTTP $OV_CODE"; cat /tmp/t8-overview.json; }

# ── Structural comparison against the committed fixtures ─────────────────────
echo
echo "== fixture vs real: keys and types =="
python3 - "$CONTRACTS" <<'PY'
import json, sys

contracts = sys.argv[1]
failures = 0

def typename(v):
    if v is None: return "null"
    if isinstance(v, bool): return "bool"
    if isinstance(v, int): return "int"
    if isinstance(v, float): return "float"
    if isinstance(v, str): return "str"
    if isinstance(v, list): return "list"
    if isinstance(v, dict): return "dict"
    return type(v).__name__

def compare(label, fixture, real, path=""):
    """Keys and types only. Values legitimately differ between seeded and real data."""
    global failures
    fk, rk = set(fixture), set(real)
    missing = fk - rk       # fixture has it, real does not -> fixture may be inventing a field
    extra = rk - fk         # real has it, fixture does not -> UNMODELLED, the dangerous direction
    if missing:
        print(f"  \033[33mNOTE\033[0m {label}{path}: in fixture but absent from real: {sorted(missing)}")
    if extra:
        print(f"  \033[31mFAIL\033[0m {label}{path}: REAL RESPONSE HAS UNMODELLED KEYS: {sorted(extra)}")
        failures += 1
    for k in sorted(fk & rk):
        ft, rt = typename(fixture[k]), typename(real[k])
        # null on either side is uninformative: the fixture pins a nullable field that happens to
        # be populated in real data, or vice versa. Only a genuine type disagreement matters.
        if ft != rt and "null" not in (ft, rt):
            print(f"  \033[31mFAIL\033[0m {label}{path}.{k}: fixture={ft} real={rt}")
            failures += 1
        elif isinstance(fixture[k], dict) and isinstance(real[k], dict):
            compare(label, fixture[k], real[k], f"{path}.{k}")

# ── workspace list ──
fx = json.load(open(f"{contracts}/district-workspace-list.json"))
rl = json.load(open("/tmp/t8-list.json"))
compare("workspace-list", fx, rl)
print(f"  workspace-list: fixture {len(fx)} keys, real {len(rl)} keys")

if fx["workspaces"] and rl.get("workspaces"):
    compare("workspace-list.workspaces[0]", fx["workspaces"][0], rl["workspaces"][0])
    # ⚠️ THE POINT OF RUNNING AGAINST REAL DATA. subscriptionTier comes straight off the column
    # with no normalisation, so its CASE is whatever was written. Report it rather than assume.
    tiers = [w.get("subscriptionTier") for w in rl["workspaces"]]
    print(f"  real subscriptionTier values: {tiers}")
    for t in tiers:
        if isinstance(t, str) and t != t.lower():
            print(f"  \033[33mNOTE\033[0m subscriptionTier '{t}' is NOT lowercase in the real database")
    roles = sorted({w.get("role") for w in rl["workspaces"]})
    print(f"  real role values: {roles}")
    for r in roles:
        if r not in ("agency", "client", "viewer"):
            print(f"  \033[31mFAIL\033[0m unmodelled role in real data: {r!r}")
            failures += 1
    regions = sorted({w.get("region") for w in rl["workspaces"]})
    print(f"  real region values: {regions}")

# ── overview ──
fx = json.load(open(f"{contracts}/district-overview.json"))
ro = json.load(open("/tmp/t8-overview.json"))
compare("overview", fx, ro)
print(f"  overview: fixture {len(fx)} keys, real {len(ro)} keys")

print(f"  real metrics: {ro.get('metrics')}")
print(f"  real avgDurationLabel: {ro.get('avgDurationLabel')!r}")
print(f"  real role: {ro.get('role')!r}")
print(f"  real recentCalls count: {len(ro.get('recentCalls') or [])}")

if fx["recentCalls"] and ro.get("recentCalls"):
    compare("overview.recentCalls[0]", fx["recentCalls"][0], ro["recentCalls"][0])
    # Every real row, not just the first: nullability varies row to row and the first row is the
    # least likely to be the awkward one.
    fkeys = set(fx["recentCalls"][0])
    for i, call in enumerate(ro["recentCalls"]):
        extra = set(call) - fkeys
        if extra:
            print(f"  \033[31mFAIL\033[0m overview.recentCalls[{i}] unmodelled keys: {sorted(extra)}")
            failures += 1
        a = call.get("analysis")
        if a is not None and not isinstance(a, dict):
            print(f"  \033[31mFAIL\033[0m recentCalls[{i}].analysis is {typename(a)}, expected dict")
            failures += 1
elif not ro.get("recentCalls"):
    print("  \033[33mNOTE\033[0m no real call rows, so recentCalls row shape was NOT exercised")

print()
print("  UNMODELLED-KEY / TYPE FAILURES:", failures)
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
