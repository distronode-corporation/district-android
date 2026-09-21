# district-android verification scripts

Checks that the ordinary Gradle gate cannot make.

⛔ **These live in the repo because they find real defects.** A throwaway script has to be re-derived
from scratch for each new section and the reasoning it encodes is never reviewable. Everything here is
reusable for new dashboard sections.

## `verify-release-minification.sh`

Builds the R8'd release artifact and asserts every **referenced** `@Serializable` model kept its
serializer.

⛔ **`assembleDebug` proves nothing about this.** Debug builds do not minify, so a missing keep rule is
invisible locally and surfaces as a decode failure in a Play track. R8 can be enabled with
kotlinx.serialization in use and nothing will say so until `assembleRelease` is actually run.

⚠️ Two traps this script had to learn:

- **Read `mapping.txt`, not the dex.** R8 renames everything, so searching the dex for original class
  names reports zero matches even when all the serializers are present. The mapping file's left-hand
  column is the authoritative list of what survived.
- **A stripped serializer is only a bug if the DTO is reachable.** R8 removes unreachable code by
  design, so a DTO whose screen is not built yet is *correctly* absent. Failing on those would train
  everyone to ignore the script, so they are reported as notes. This is how the missing
  create-contact form was found: `CreateContactRequest` was referenced in source but reachable from
  nothing.

Static only. A full decode under R8 needs a signed-in device, and the login leg needs a browser the
emulator does not have.

## `smoke-emulator.sh`

Boots an emulator headless, installs the debug APK, runs the Maestro flow in `.maestro/smoke.yaml`,
and collects the JUnit report and screenshots under `build/smoke/`. Local only; CI does not run it.

```bash
scripts/smoke-emulator.sh --build --avd <your AVD>        # launch leg only
DISTRICT_SMOKE_EMAIL=... DISTRICT_SMOKE_PASSWORD=... \
  scripts/smoke-emulator.sh --build --avd <your AVD>      # the full authenticated tour
```

⛔ **It checks the APK's package against the flow's `appId` before booting anything** (when `aapt2` is in
the SDK's build tools; without it the check is skipped with a warning). The debug build
installs as `com.distronode.districtai.debug`; a flow naming another id launches nothing and fails as a
missing element, which reads like a broken screen.

⚠️ Without both credentials the flow runs its launch leg only and still passes, and the script says
which of the two it ran. A green run without credentials proves the app starts, not that the
authenticated screens work.

## `verify-{overview,calls,contacts}-against-db.sh`

Drive the real route handlers against a real PostgreSQL, with a real bearer token, and compare the
responses against the committed contract fixtures — keys and types, every row.

⛔ **The committed fixtures are generated with the data layer MOCKED, which cannot catch a column whose
real values differ in shape from the seed data.** That is the entire reason these exist. The kinds of
defect they catch:

| Defect | Why the mocked fixtures miss it |
|---|---|
| `analysis` is a JSON **object**, not a string | Every seed row had it `null`, so the fixture never exercised the field. The DTO was `String?` and would have thrown on any real call carrying an analysis blob. |
| `subscriptionTier` is **not lowercase** (`VoicePro`) | A doc can claim lowercase. `== "voicepro"` would silently never match. |
| Offset paging **repeats a row** when one is inserted mid-scroll | Reproduced by fetching page 1, inserting a call, fetching page 2. A duplicate key crashes a `LazyColumn`, so this is a crash on the most ordinary event on that screen. |
| A missing `createdAt` tie-break **loses rows** | Without `id desc` in the contacts ordering, **2 of 40** rows become unreachable and 2 duplicate. Bulk imports write hundreds of rows sharing one timestamp. |

Run one after regenerating any fixture, and write a new one for each new section. They need the
District server running locally, and the server is not public, so these three are maintainer
tools; CI runs only `verify-release-minification.sh`.

### What they need

```bash
# Postgres with the dev databases, and Redis
docker ps --format '{{.Names}}'      # expects distronode-test-pg and a redis container

# The District server, run from its own checkout with the local-dev mock session enabled
MOCK_SESSION=true REDIS_URL=redis://127.0.0.1:6379 PORT=3100 npm run dev
```

`MOCK_SESSION` is hard-false in production. `/auth/native` resolves the user from the database by
email, so a mock session maps to a real user and the full PKCE exchange works locally.

⚠️ **Every script warms the routes first, and that is not optional.** Next dev compiles a route on its
first hit (several seconds for `/auth/native`) while the authorization code's TTL is 120s, so an unwarmed
run spends the budget on compilation and fails with an opaque `refused (unknown)` that reads like a
PKCE bug.

⚠️ **Capture the token-exchange response before parsing it.** Piping `curl` straight into a JSON
extractor turns any failure into a bare "no access token" with no clue why.

⚠️ Seeded rows use an `id` prefix (`t9seed-`, `t10seed-`) and are removed by an `EXIT` trap, so an
interrupted run still cleans up.
