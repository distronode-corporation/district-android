# district-android verification scripts

Checks that the ordinary Gradle gate cannot make.

⛔ **These live in the repo because they find real defects.** A throwaway script has to be re-derived
from scratch for each new screen and the reasoning it encodes is never reviewable. Everything here is
reusable when the app gains a new screen.

## `verify-release-minification.sh`

Builds the R8'd release artifact and asserts every **referenced** `@Serializable` model kept its
serializer. It also checks that the Geist licence text survived resource shrinking, because the
font's licence requires it to ship with the font files.

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

## `l10n-contracts.py`

Generates `l10n/api_errors.toml` and `l10n/wire_labels.toml`, with their Kotlin lookup tables under
`app/src/main/kotlin/.../l10n/`, from the website's contract files in `contracts/i18n/`. Run it after
those files change, then `tools/l10n/l10n.py gen` for each catalog (CONTRIBUTING.md, "Strings").
`--check` is what CI runs.

An entry whose English is unchanged keeps the `fr`, `state` and other translation fields the committed
catalog has, so French written by `tools/l10n/l10n.py import` survives a regeneration; a new entry, or one
whose English changed, takes the contract's French as a draft. Tests: `python3 -m unittest discover -s
scripts -p 'test_*.py'` (CI runs them).

⚠️ A wire label's English is what this app has always shown (the raw value, or upper-cased for a
region), not the portal's label: W1 changes no English. The portal's English is in each entry's note.

## `french-guard.sh`

`absent` fails when any of the app's own strings, plurals or arrays has a value in any French
configuration (`fr`, `fr-rCA`, `fr-rFR`, `b+fr+CA`), or the per-app language list
(`xml/_generated_res_locale_config`) names French. `present` fails unless every translatable app key
has an `fr-rCA` value, no app key has another French one, and the list names `fr-CA`. release.yml
picks the mode from `DISTRICT_FRENCH`, the variable that also decides whether `release-build.sh`
passes `-Pdistrict.l10n.fr=true`; the French CI job runs `present` on its debug APK.

⛔ **The app's own keys, not "any French configuration".** AndroidX ships French strings in every
build, so the configuration is always there. The app's keys are the ones the catalogs generate into
`app/src/main/res/values` (the l10n.py `@generated` header). A key that resource shrinking removed
from the table is skipped; `string/app_name` must be there.

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
responses against the committed contract fixtures: keys and types, every row.

⛔ **The committed fixtures are generated with the data layer MOCKED, which cannot catch a column whose
real values differ in shape from the seed data.** That is the entire reason these exist. The kinds of
defect they catch:

| Defect | Why the mocked fixtures miss it |
|---|---|
| `analysis` is a JSON **object**, not a string | Every seed row had it `null`, so the fixture never exercised the field. The DTO was `String?` and would have thrown on any real call carrying an analysis blob. |
| `subscriptionTier` is **not lowercase** (`VoicePro`) | A doc can claim lowercase. `== "voicepro"` would silently never match. |
| Offset paging **repeats a row** when one is inserted mid-scroll | Reproduced by fetching page 1, inserting a call, fetching page 2. A duplicate key crashes a `LazyColumn`, so this is a crash on the most ordinary event on that screen. |
| A missing `createdAt` tie-break **loses rows** | Without `id desc` in the contacts ordering, **2 of 40** rows become unreachable and 2 duplicate. Bulk imports write hundreds of rows sharing one timestamp. |

Run one after regenerating any fixture, and write a new one for each new screen. They need a
local copy of the District AI server with a test database, and the server is not public, so these
three are maintainer tools; CI runs only `verify-release-minification.sh`.

The warm-up, the PKCE sign-in and the PASS/FAIL/NOTE helpers live once, in `lib/district-auth.sh`,
which all three source. A change to the native sign-in flow is one edit there.

### What they need

- **A local copy of the District AI server** from its (private) repository, run in development mode
  with its local mock session turned on, so that a sign-in resolves to a real user in the test
  database. Its Redis must be running too. The scripts reach it at `http://127.0.0.1:3100`; set
  `DISTRICT_BASE_URL` to use another address.
- **Its test database, reachable with `psql`.** The calls and contacts scripts seed rows with it.
  The default command is the maintainers' own local container; set `DISTRICT_PSQL` to any `psql`
  invocation that reaches the database, with `-qtAX` so the output is bare values.
- **`DISTRICT_WS`**: the id of a workspace in that database.

```bash
DISTRICT_WS=<workspace id> DISTRICT_PSQL='psql -h 127.0.0.1 -U postgres -d <database> -qtAX' \
  scripts/verify-calls-against-db.sh
```

The mock session is off in production by construction. The native sign-in route resolves the user
from the database by email, so a mock session maps to a real user and the full PKCE exchange works
locally.

⚠️ **Every script warms the routes first, and that is not optional.** The server in development mode
compiles a route on its first hit (several seconds for the native sign-in route) while the
authorization code's TTL is 120s, so an unwarmed run spends the budget on compilation and fails
with an opaque `refused (unknown)` that reads like a PKCE bug.

⚠️ **Capture the token-exchange response before parsing it.** Piping `curl` straight into a JSON
extractor turns any failure into a bare "no access token" with no clue why.

⚠️ Seeded rows use a fixed `id` prefix and are removed by an `EXIT` trap, so an interrupted run
still cleans up.
