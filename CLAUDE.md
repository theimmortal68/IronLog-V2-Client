# CLAUDE.md — context for Claude Code (IronLog V2 Android client)

You are working in the **client** repo. The server is a separate repo (`IronLog-V2`,
FastAPI/SQLModel) that holds the engine. This app is a thin client that talks to it over
the home LAN. Read this, then `docs/superpowers/specs/2026-06-24-android-thinclient-design.md`
for the full design.

## What this is

v0.1 is a **smoke-test** client: a small Jetpack Compose app that exercises every endpoint
the server currently exposes, to prove the V2 architecture works end-to-end on the phone.
It is **not** a workout logger — logging, session generation, and the validator UI are all
blocked on server endpoints that don't exist yet (see the design doc §10). Don't build them.

Installs alongside the v1 app (`com.jauschua.ironlog`); this app's id is
`com.jauschua.ironlogv2`.

## The server is the source of truth for data shapes

The DTOs in `data/api/dto/Models.kt` mirror the server's JSON. **When a DTO and the server
disagree, the server wins** — its models live in the server repo at `ironlog/models/`
(`library.py`, `enums.py`). Rules:

- DTO field names are **snake_case** to match FastAPI/SQLModel output (no `@SerialName`).
- `ignoreUnknownKeys = true` tolerates server fields the client doesn't model — but any
  field a screen *renders* must be in the DTO. Keep `MovementDto` tracking the server's
  `Movement`, especially the signature flags: `objective_override`, `band_eligible`,
  `assist_subtype`/`assist_unit`, `status`.
- The four endpoints consumed: `GET /movements`, `GET /movements/{id}`, `GET /bands/usable`,
  `POST /autoregulate/next-set`. (`/phase-policy/{phase}` exists but no v0.1 screen uses it.)

## Invariants — do not violate

1. **Don't break the contract silently.** If the server changes a shape, update the DTO; if
   you need a shape the server doesn't expose, that's a server change, not a client hack.
2. **Autoregulate is LADDER-only.** The picker must filter to `progression_mode == LADDER`.
   Composite (Hip Thrust) and assisted (Pull-up) movements don't use the ladder loop and
   return meaningless suggestions — never offer them in the autoregulate picker.
3. **No silent failures.** Repos return `Result<T>`; map failures to `UiState.Error` with a
   retryable snackbar. Network/parse/timeout errors are typed (`ApiError`).
4. **Scope discipline.** No login, no local DB, no logging UI, no generation UI — they're
   deferred because the server can't back them yet. See design doc §10 before adding anything.

## Stack / conventions

- Jetpack Compose + Material3, Navigation Compose, single Activity.
- State: `ViewModel` + `StateFlow<UiState<T>>`, `collectAsStateWithLifecycle()`.
- DI: **manual** (`AppContainer` on the Application). No Hilt.
- HTTP: Ktor 3.x with the **OkHttp engine**; JSON via kotlinx-serialization.
- No persistence in v0.1 (no Room, no DataStore).
- `UiState<T>` sealed interface (Loading/Success/Error) is shared by every screen.

## Build gotchas

- **Kotlin 2.1.0 requires the Compose compiler plugin** (`org.jetbrains.kotlin.plugin.compose`)
  in the version catalog and applied in `app/build.gradle.kts`. The old
  `composeOptions { kotlinCompilerExtensionVersion }` is gone; without the plugin the build fails.
- `applicationId` / `namespace` = `com.jauschua.ironlogv2` (side-by-side with v1).
- compileSdk/targetSdk 35, minSdk 26, AGP 8.7.3, Compose BOM 2024.12.01 — match the IronLog family.
- Cleartext HTTP is whitelisted only for the server host in `res/xml/network_security_config.xml`.

## Commands

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm list packages | grep ironlog   # expect both com.jauschua.ironlog and ...ironlogv2
```
The server must be reachable first: on its host run
`uvicorn ironlog.api.app:app --host 0.0.0.0 --port 8000` (NOT the localhost default).

## Tests

~6 repo unit tests with Ktor `MockEngine` (deserialization + a forced network failure +
the autoregulate POST body). No instrumented or Compose UI tests in v0.1.

## Next

Scaffold per the design doc (module layout in §3.3), wire the four endpoints, run the
smoke-test protocol (§9.3). When the server gains session-write endpoints, the logging UI
becomes the v0.2 conversation — not before.
