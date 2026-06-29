# Task 6 Report: Wizard DTOs + `WizardRepo`

**Status:** DONE
**Branch:** `feat/wizard`

## What was built

### DTOs — `app/src/main/java/com/jauschua/ironlogv2/data/api/dto/WizardModels.kt`
Six `@Serializable` snake_case data classes, verified field-for-field against the server source of truth `IronLog-V2/ironlog/api/schemas_wizard.py`:

| DTO | Fields (name : type) | Matches server |
|-----|----------------------|----------------|
| `WizardMovement` | `movement_id: Int`, `movement_name: String`, `load_field: String`, `trust: String`, `prefill_value: Double? = null`, `unit_hint: String? = null` | ✅ |
| `WizardStateResponse` | `program_id: Int`, `program_name: String`, `needs_attention_count: Int`, `ready_to_start: Boolean`, `movements: List<WizardMovement>` | ✅ (`ready_to_start` confirmed on state response) |
| `WizardResolution` | `movement_id: Int`, `value: Double` | ✅ |
| `WizardResolveRequest` | `resolutions: List<WizardResolution>` | ✅ |
| `WizardResolveResponse` | `resolved: Int`, `needs_attention_count: Int`, `ready_to_start: Boolean` | ✅ |
| `StartProgramResponse` | `program_id: Int`, `started: Boolean`, `active: Boolean` | ✅ |

Type mapping: Python `Optional[float] = None` → Kotlin `Double? = null`; `int`/`str`/`bool`/`List` → `Int`/`String`/`Boolean`/`List`. No name/type/nullability drift.

### Repository — `app/src/main/java/com/jauschua/ironlogv2/data/repo/WizardRepo.kt`
`class WizardRepo(private val apiClient: ApiClient)`, using the `runCatchingApi { apiClient.http.get/post(...).body() }` pattern from `CaptureRepo`/`AutoregRepo`.

**Program-scoped paths — verified against `IronLog-V2/ironlog/api/app.py` (NOT the spec shorthand):**
- `GET /programs/{program_id}/wizard-state` (app.py:405) → `state(programId): Result<WizardStateResponse>`
- `POST /programs/{program_id}/wizard-resolve` (app.py:459, body `WizardResolveRequest`) → `resolve(programId, resolutions): Result<WizardResolveResponse>`
- `POST /programs/{program_id}/start` (app.py:510) → `start(programId): Result<StartProgramResponse>`

### Test — `app/src/test/java/com/jauschua/ironlogv2/data/repo/WizardRepoTest.kt`
Ktor `MockEngine`, mirroring `AutoregRepoTest`/`CaptureRepoTest`. Three tests:
1. `state_getsProgramScopedPathAndParsesTrustPrefillCount` — asserts `GET /programs/7/wizard-state`; parses `trust` (`STALE`/`UNKNOWN`), `prefill_value` (135.0 and null for UNKNOWN), `unit_hint`, `needs_attention_count`, `ready_to_start`, movement list.
2. `resolve_postsSnakeCaseBodyToProgramScopedPathAndParsesResponse` — asserts `POST /programs/7/wizard-resolve`; verifies the serialized request body is snake_case: `{"resolutions":[{"movement_id":11,"value":140.0},{"movement_id":22,"value":1.0}]}`; parses the response.
3. `start_postsProgramScopedPathAndParsesResponse` — asserts `POST /programs/7/start`; parses `started`/`active`.

## TDD flow
DTOs (mirroring schemas_wizard.py) → `WizardRepoTest` (red: WizardRepo absent) → `WizardRepo` (green).

## Gradle tails
```
./gradlew testDebugUnitTest --tests "*WizardRepoTest*"  → BUILD SUCCESSFUL in 17s
./gradlew testDebugUnitTest                             → BUILD SUCCESSFUL
```
**Test count: 23 total** (20 baseline + 3 new WizardRepoTest). 0 failures, 0 errors.

## Commit
`feat(wizard): contract DTOs + WizardRepo (state/resolve/start, program-scoped paths)`
Hash: recorded at commit time below.
