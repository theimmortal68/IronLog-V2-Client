# IronLog V2 — Android Thin-Client (v0.1 Smoke-Test) Design

**Date:** 2026-06-24
**Repo:** `~/projects/IronLog-V2-Client` (this repo)
**Server:** `~/projects/IronLog-V2` (FastAPI/SQLModel) — separate repo
**Status:** approved design; awaiting implementation plan
**Scope:** v0.1 only — a smoke-test thin client that exercises every endpoint the server currently exposes

---

## 1. Purpose

The IronLog-V2 server is a small FastAPI service that holds the adaptive strength-training engine (deterministic loading math, autoregulation, an LLM-driven session generator that is specified but not yet built). Its README states *"a thin client logs workouts; this server holds the brain"*, but no client has been written.

This document specifies the **v0.1 Android thin-client**: a small Compose app that runs on the user's phone, talks to the server over the home LAN, and exercises every endpoint the server currently exposes (read-mostly, plus the one autoregulate POST). It is a **smoke-test** client — it proves the V2 architecture works end-to-end and gives a hands-on UI to interact with the seeded library. It is **not** a workout logger; logging is blocked on server-side endpoints that do not exist yet (see §10).

The app must install alongside the existing IronLog v1 app (`com.jauschua.ironlog`) without collision.

---

## 2. Constraints

- **Server endpoints available** (v0.1 is upper-bounded by this list):
  - `GET /movements` → `List[MovementDto]`
  - `GET /movements/{id}` → `MovementDto`
  - `GET /phase-policy/{phase}` → `PhasePolicyDto`
  - `GET /bands/usable` → `List[BandPairDto]`
  - `POST /autoregulate/next-set` → `NextSetResponse`
- No authentication on the server (LAN-trusted).
- No session-write endpoints, no sync, no generation endpoints.
- Cleartext HTTP only (server is plain `uvicorn`; HTTPS would require a reverse proxy not yet deployed).
- Phone form factor, home WiFi, single user.
- Must install alongside `com.jauschua.ironlog` → distinct `applicationId`.

---

## 3. Architecture

Single-module Android app following the same conventions as `~/projects/IronLog` and `~/projects/IronLogJr`.

### 3.1 Stack

| Layer | Choice |
|---|---|
| UI | Jetpack Compose, Material3 |
| Navigation | Navigation Compose (single Activity, multiple destinations) |
| State | `ViewModel` + `StateFlow<UiState<T>>`, collected via `collectAsStateWithLifecycle()` |
| DI | Manual — one `AppContainer` object held on `Application`; `viewModelFactory{}` helper for per-screen ViewModels. **No Hilt.** |
| HTTP | Ktor 3.x client with the **OkHttp engine** (not CIO) |
| JSON | kotlinx-serialization |
| Persistence | None for v0.1 (no caches, no DB). DataStore deferred. |

### 3.2 Build targets

| Setting | Value | Reason |
|---|---|---|
| `applicationId` | `com.jauschua.ironlogv2` | Mirrors `com.jauschua.ironlog` / `com.jauschua.ironlogjr` family; distinct from v1 so both install side-by-side |
| `namespace` | `com.jauschua.ironlogv2` | Matches `applicationId` |
| `compileSdk` | 35 | Matches IronLog/IronLogJr |
| `minSdk` | 26 | Matches IronLog/IronLogJr |
| `targetSdk` | 35 | Matches IronLog/IronLogJr |
| App label | `"IronLog V2"` | Distinct on launcher |
| AGP | 8.7.3 | Matches IronLog |
| Kotlin | 2.1.0 | Matches IronLog |
| Compose BOM | 2024.12.01 | Matches IronLog |

### 3.3 Module layout

```
IronLog-V2-Client/
├── app/
│   ├── build.gradle.kts                              # AGP 8.7.3, Kotlin 2.1.0, kotlin-serialization plugin
│   └── src/main/
│       ├── AndroidManifest.xml                       # INTERNET permission, networkSecurityConfig
│       ├── res/
│       │   ├── values/strings.xml                    # app_name = "IronLog V2"
│       │   ├── xml/network_security_config.xml       # cleartext whitelisted for myflix.media + 192.168.1.7
│       │   └── (theme/icons)
│       └── java/com/jauschua/ironlogv2/
│           ├── IronLogV2Application.kt               # holds AppContainer
│           ├── di/AppContainer.kt                    # builds http client + repos
│           ├── data/api/ApiClient.kt                 # Ktor + OkHttp engine config
│           ├── data/api/dto/Models.kt                # @Serializable DTOs + enums
│           ├── data/api/ApiError.kt                  # error sealed interface
│           ├── data/repo/LibraryRepo.kt              # movements / movement(id) / phasePolicy / usableBands
│           ├── data/repo/AutoregRepo.kt              # nextSet(...)
│           ├── ui/MainActivity.kt                    # NavHost + bottom navigation scaffold
│           ├── ui/UiState.kt                         # shared sealed interface
│           ├── ui/theme/                             # Material3 theme (default scheme; matching IronLog later if desired)
│           └── ui/screens/
│               ├── movements/MovementsListScreen.kt   + MovementsListViewModel.kt
│               ├── movement_detail/MovementDetailScreen.kt + ViewModel
│               ├── bands/BandsScreen.kt               + ViewModel
│               └── autoregulate/AutoregulateScreen.kt + ViewModel
├── gradle/libs.versions.toml                         # mirrors IronLog catalog + ktor/kotlinx-serialization
├── settings.gradle.kts
├── build.gradle.kts
├── .gitignore
├── README.md
└── docs/superpowers/specs/2026-06-24-android-thinclient-design.md   # this file
```

---

## 4. Screens & navigation

Single Activity hosting a `NavHost` whose root is a Material3 `Scaffold` with a `NavigationBar` (3 tabs). Detail screens are nested under their owning tab.

```
NavHost (start = "movements")
├── tab "Movements"    → MovementsListScreen
│      tap row         → MovementDetailScreen(id)
│      detail button   → switches to Autoregulate tab pre-filled
├── tab "Bands"        → BandsScreen
└── tab "Autoregulate" → AutoregulateScreen
```

### 4.1 MovementsListScreen
- On enter: ViewModel calls `libraryRepo.movements()` once.
- Renders `LazyColumn` of cards: name (big), `region · lift_category · primary?`, `floor / cap` if set.
- Pull-to-refresh (`PullToRefreshBox`) triggers a reload.
- TopAppBar: title `"Library"`, trailing health dot (green if last call succeeded, red if last call failed).
- Tap row → navigate `"movement/{id}"`.

### 4.2 MovementDetailScreen
- Loads `getMovement(id)` on enter.
- Renders Movement fields: name, base_name, region, lift_category, is_primary, progression_mode, scheme, increment_ladder, min_step, load_floor, cap, equipment_tags, family/anchor/derived_from_id/start_ratio, notes.
- Bottom action: **"Try autoregulate"** → switches to Autoregulate tab with `movement_id` and (`current_load` = movement.load_floor or 100) pre-filled.

### 4.3 BandsScreen
- `usableBands()` GET, list rows: `#label · bottom lb / peak lb · calibration_status` with a small badge for `MODELED` vs `MEASURED`.
- The server's `/bands/usable` filters `usable=true`, so #5 Purple is excluded.

### 4.4 AutoregulateScreen
A form, not a list:
- Movement picker (dropdown sourced from cached `/movements` — cache held in ViewModel for the screen's lifetime; first opening fetches if empty).
- `current_load`: numeric `OutlinedTextField`, decimal keyboard.
- Tap: three Material3 `SegmentedButton`s — `TOO_EASY · ON_TARGET · TOO_HARD`.
- `tier`: integer (0..2), small `+ / −` stepper with the current value in the middle.
- "Suggest next load" button → POST → result card shows `suggested_load` and a comparison delta (`+5.0 lb`, `−10.0 lb`, or `unchanged`).
- Re-submittable; the form retains its values after each submission.

### 4.5 Shared UI state pattern

One tiny sealed interface used by every screen:

```kotlin
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val msg: String) : UiState<Nothing>
}
```

ViewModels expose `StateFlow<UiState<...>>`. Screens render via `when`. Errors get a Material3 snackbar with **Retry** that re-invokes the load.

---

## 5. Networking

### 5.1 Ktor + OkHttp engine

One shared `HttpClient` held by `AppContainer`:

```kotlin
val http = HttpClient(OkHttp) {
    expectSuccess = true                              // throws on 4xx/5xx
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        })
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        socketTimeoutMillis  = 10_000
        requestTimeoutMillis = 10_000
    }
    install(Logging) {
        logger = Logger.ANDROID
        level = if (BuildConfig.DEBUG) LogLevel.HEADERS else LogLevel.NONE
    }
    defaultRequest {
        url(BuildConfig.SERVER_BASE_URL)              // http://myflix.media:8000
    }
}
```

### 5.2 BuildConfig URL

`app/build.gradle.kts`:

```kotlin
defaultConfig {
    // ...
    buildConfigField("String", "SERVER_BASE_URL", "\"http://myflix.media:8000\"")
}
buildFeatures { buildConfig = true; compose = true }
```

Single value for v0.1. A `release` flavor pointing at a TLS-fronted URL is a v0.2 concern.

### 5.3 Cleartext HTTP

A network security config that whitelists only the dev host (tighter than blanket `usesCleartextTraffic=true`):

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">myflix.media</domain>
        <domain includeSubdomains="false">192.168.1.7</domain>
    </domain-config>
</network-security-config>
```

`AndroidManifest.xml`: `<application android:networkSecurityConfig="@xml/network_security_config" ...>` and `<uses-permission android:name="android.permission.INTERNET" />`.

---

## 6. Data shapes (DTOs)

All DTOs use **snake_case** field names so the FastAPI/SQLModel JSON deserializes without `@SerialName` annotations. `ignoreUnknownKeys = true` covers fields the server adds later.

```kotlin
// dto/Models.kt
@Serializable enum class Region { UPPER, LOWER, CORE, NONE }
@Serializable enum class LiftCategory { BENCH, BACK_SQUAT, FRONT_SQUAT, OHP, RDL, DEADLIFT, ROW, HIP_THRUST, REV_HYPER, CG_PRESS, NONE }
@Serializable enum class FeedbackTap { TOO_EASY, ON_TARGET, TOO_HARD }
@Serializable enum class BandCalStatus { MODELED, MEASURED }
@Serializable enum class ProgressionMode { LADDER, COMPOSITE, ASSISTED, PROTOCOL, CONDITIONING, NONE }
@Serializable enum class Scheme { STRAIGHT, DOUBLE_PROGRESSION, TOPSET_BACKOFF, UNDULATION, WAVE, REP_RATIO }
@Serializable enum class Objective { MAINTAIN, PROGRESS, MEASURE }
@Serializable enum class Phase { CALIBRATION, CUT, STAB, REBUILD }

@Serializable data class MovementDto(
    val id: Int,
    val name: String,
    val base_name: String,
    val region: Region = Region.NONE,
    val lift_category: LiftCategory = LiftCategory.NONE,
    val is_primary: Boolean = false,
    val is_tracked: Boolean = true,
    val progression_mode: ProgressionMode = ProgressionMode.NONE,
    val scheme: Scheme = Scheme.STRAIGHT,
    val increment_ladder: List<Double> = emptyList(),
    val min_step: Double? = null,
    val load_floor: Double? = null,
    val cap: Double? = null,
    val rpe_capped: Boolean = false,
    val rpe_cap_exempt: Boolean = false,
    val equipment_tags: List<String> = emptyList(),
    val family: String? = null,
    val is_family_anchor: Boolean = false,
    val derived_from_id: Int? = null,
    val start_ratio: Double? = null,
    val notes: String? = null,
)

@Serializable data class BandPairDto(
    val id: Int,
    val label: String,
    val bottom_lb: Double,
    val peak_lb: Double,
    val calibration_status: BandCalStatus = BandCalStatus.MODELED,
    val usable: Boolean = true,
)

@Serializable data class PhasePolicyDto(
    val id: Int,
    val phase: Phase,
    val default_objective: Objective,
    val rpe_band_low: Double,
    val rpe_band_high: Double,
    val hard_cap: Double,
    val top_set_rpe: Double,
    val progression_attempted: Boolean,
    val volume_posture: String,
)

@Serializable data class NextSetRequest(
    val movement_id: Int,
    val current_load: Double,
    val tap: FeedbackTap,
    val tier: Int = 0,
)

@Serializable data class NextSetResponse(val suggested_load: Double)
```

---

## 7. Error handling

Repositories wrap calls and translate Ktor exceptions into a typed error:

```kotlin
sealed interface ApiError {
    data class Network(val cause: Throwable) : ApiError      // IOException, no connection
    data class Server(val status: Int, val body: String?) : ApiError  // 5xx
    data class Client(val status: Int, val body: String?) : ApiError  // 4xx
    data class Timeout(val cause: Throwable) : ApiError
    data class Parse(val cause: Throwable) : ApiError
}
```

Repos return `Result<T>` (Kotlin stdlib). ViewModels map `Result.failure` → `UiState.Error(humanMessage)`. Screen renders a snackbar with Retry. No silent failures.

Threading: all network calls are `suspend` functions launched in `viewModelScope`; Ktor's OkHttp engine handles its own thread pools.

---

## 8. Testing

Proportional to v0.1 scope:

- **Unit tests** with Ktor's `MockEngine`:
  - `LibraryRepoTest` — `movements()` deserializes a sample JSON array; `movements()` surfaces a network failure as `Result.failure<ApiError.Network>`; `getMovement(id)` deserializes a sample MovementDto including `equipment_tags` and `increment_ladder` arrays; `usableBands()` deserializes the band table.
  - `AutoregRepoTest` — `nextSet(...)` POSTs the expected JSON body and deserializes the response.
  - ~6 tests total.
- **No instrumented (device) tests** for v0.1 — manual `adb install` smoke test is the verification.
- **No Compose UI tests** for v0.1 — `composeRule` can be added later when the screen count grows.

---

## 9. Build & install

### 9.1 Build
```
cd ~/projects/IronLog-V2-Client
./gradlew :app:assembleDebug
```
Produces `app/build/outputs/apk/debug/app-debug.apk`.

### 9.2 Install (alongside existing IronLog)

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Different `applicationId` than v1 → both apps coexist with their own data, signing key, and launcher icon. Verifiable: `adb shell pm list packages | grep ironlog` shows both `com.jauschua.ironlog` and `com.jauschua.ironlogv2`.

### 9.3 Smoke-test protocol (v0.1 "done" criteria)
1. Server: `pytest -q` on `~/projects/IronLog-V2` green (baseline unchanged).
2. Server reachable from LAN: `uvicorn ironlog.api.app:app --host 0.0.0.0 --port 8000` running on `myflix.media:8000`. (Deploy is a separate step — see §10.)
3. Phone on home WiFi: app launches → bottom navigation visible.
4. Movements tab: all 5 seeded movements render, no error snackbar.
5. Tap a movement → detail renders all fields without crash.
6. Bands tab: 5 usable bands visible (#5 Purple correctly absent).
7. Autoregulate tab: pick a movement, `current_load=100`, tap `ON_TARGET`, submit → response card shows a `suggested_load`.
8. Stop server: snackbar with Retry on next refresh; restart server + Retry recovers without app restart.

---

## 10. Out of scope (explicit YAGNI)

These are deliberately deferred. Listed here so they don't sneak in.

- **Login / auth** — server has none.
- **Local DB / offline mode** — no session-write endpoints exist yet.
- **Workout logging UI** — same blocker; the entire `Session/PlannedSet/SetLog` capture layer needs server endpoints first.
- **Session generation UI** — server stub raises `NotImplementedError` (`ironlog/engine/generation.py`).
- **Validator UI** — the deterministic validator exists in the server repo as untracked work but isn't wired into any API yet.
- **Build flavors (debug/release/local)** — single debug build for v0.1; release flavor with a TLS URL is a v0.2 concern.
- **Crash reporting, analytics, ProGuard rules** — premature for a LAN-only smoke test.
- **DataStore-backed settings screen** — earlier brainstorming option that was rejected in favor of hardcoded BuildConfig URL.

---

## 11. Server-side prerequisite (separate work)

Not part of this client scaffold, but the test run can't succeed without it:

- Get `uvicorn` running on `myflix.media` listening on `0.0.0.0:8000`, reachable from the LAN.
- **Quick path** (for the first test run): SSH to myflix, run uvicorn in a tmux session. Throwaway.
- **Proper path** (sometime after the smoke test works): a systemd user service or a podman container mirroring how the existing MCP fleet is deployed on myflix (see project-ops MEMORY.md "Media Server MCP Fleet"). Out of scope for *this* design doc — to be addressed as its own piece of work right after the client scaffold lands.

---

## 12. Registration

After scaffold + first successful build:
- Add the row to `~/project-ops/CLAUDE.md` **Projects Managed** table:
  `| IronLog V2 Client | ~/projects/IronLog-V2-Client | Kotlin/Compose | ~/projects/IronLog-V2-Client/gradlew :app:assembleDebug |`
- Add the row to the **Build Verification** table with the deploy command above.

---

## 13. Approvals

| Step | Status | Date |
|---|---|---|
| Stack & module layout (§3) | approved | 2026-06-24 |
| Screens & navigation (§4) | approved | 2026-06-24 |
| Networking & DTOs (§5–§7) | approved | 2026-06-24 |
| Build, install, testing (§8–§9) | approved | 2026-06-24 |
| Spec written | this commit | 2026-06-24 |
| User review of spec | pending | — |
| Implementation plan (`writing-plans` skill) | not yet started | — |
