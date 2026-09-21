# Architecture

A short map of how the modules fit together. The code comments carry the reasoning for
individual decisions; this is the picture they sit in.

## Modules

```
app ──────────► core-data ──► core-network ──► core-auth
 │                               │
 ├──► core-media                 └───────────► core-model
 ├──► core-designsystem
 └──► (also core-network, core-auth and core-model directly)
```

`core-model`, `core-auth`, `core-media` and `core-designsystem` depend on no other project
module.

- **core-model** holds the request and response types (kotlinx.serialization) and nothing
  that does I/O. Its tests decode the fixtures in `contracts/` strictly, which is what keeps these
  types honest against the real server.
- **core-auth** owns the session: tokens encrypted with an Android Keystore key
  (`KeystoreTokenStore`), the PKCE sign-in flow, and `TokenRefreshCoordinator`, which
  serialises refreshes so a refresh token is never presented twice.
- **core-network** is a small typed client over OkHttp (no Retrofit; its build file says why).
  Endpoints are declared on interfaces, one per feature area (`DistrictApi` composes the core
  ones; desk, support, persona, call handling and others sit beside it), each with an `Http*`
  implementation. Every call returns an `ApiResult`, which folds the API's three error envelopes,
  network and decode failures, rate limiting and "signed out" into one sealed type the UI can
  branch on.
- **core-data** puts repositories over those interfaces, including paging sources, and holds
  the active-workspace selection.
- **core-media** wraps the LiveKit SDK behind the `CallEngine` interface. The SDK is an
  `implementation` dependency here, so no other module can name a LiveKit type; `app` sees the
  interface, its data types and one composable (`VideoTile`).
- **core-designsystem** is the bottom of the UI stack: colour, type (the bundled Geist
  typeface), shape and primitive composables. It depends on no other project module, so a
  primitive can never start taking a DTO.
- **app** is everything with an Android lifecycle.

## Inside `app`

- **Wiring.** `AppContainer` builds the object graph by hand: one `OkHttpClient`, one
  `TokenRefreshCoordinator`, and the repositories over them. There is no DI framework; the
  constructors are injection-shaped, so adding one later is mechanical.
- **Screens.** `ui/<feature>/` holds a Compose screen and its ViewModel per feature (calls,
  inbox, contacts, rooms, dialer, settings and so on). `DistrictNavHost` is the
  navigation graph. ViewModels talk to repositories, never to the HTTP client directly.
- **Calls.** `telecom/` registers a self-managed `PhoneAccount` and implements the
  `ConnectionService`, so the system knows a call is in progress; `call/` handles incoming calls
  and the foreground service that keeps an answered call alive with the screen off. Media goes
  through `CallEngine`.
- **Push.** `push/` initialises Firebase from constants rather than a `google-services.json`,
  receives FCM messages whose payload carries ids only, and turns them into notifications and
  deep links.
- **Sign-in and app links.** `auth/` launches the server's login page in a Custom Tab and
  handles the `districtai://auth` return; `applinks/` resolves verified `https` links into
  in-app destinations.
- **Crash reporting.** `DistrictSentry` arms Sentry only when the build carries a DSN.

## Tests

Everything CI runs is a JVM test: plain JUnit for logic, MockWebServer for HTTP mapping,
Robolectric with the Compose test rule for screens. There are no instrumented tests; the
Maestro flow in `.maestro/` is the device-level smoke check, run by hand.

Three gates are worth knowing before changing things:

- **Contract fixtures** (`contracts/`, `ContractManifest`): the server's real responses,
  decoded with unknown keys rejected.
- **Endpoint parity** (`EndpointParityTest`, `parity/`): this client's endpoints against the
  iOS client's, with the differences written down in `EndpointParity.kt`.
- **Coverage floors** (`build.gradle.kts`): aggregate line and branch coverage across every
  module, which may only go up.

## Build

Convention plugins in `build-logic/` (`district.android.application`, `.library`, `.compose`,
`.base`) carry the shared Android, Kotlin, lint and Kover configuration, so a module's own
build file holds only what is specific to it. Versions come from `gradle/libs.versions.toml`,
and every configuration is dependency-locked.
