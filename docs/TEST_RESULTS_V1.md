# V1 Test Results

## Run Metadata

- Branch: `develop`.
- Remote state: `origin/develop` fetched; local branch is ahead by 9 commits and behind by 0.
- Reference device: `SM-A546B`, Android 16.
- Reference date: `2026-09-14`.
- No GitHub Actions or CI workflow was started.
- OpenAI/Gemini credentials were not used.

## Executed Results

| Command | Result | Evidence |
|---|---|---|
| `:app:assembleDebug` | PASS | Debug APK assembled locally |
| `:app:testDebugUnitTest` | PASS | 44 tests, 0 failures, 0 skipped |
| `:app:connectedDebugAndroidTest` | PASS | 16 tests, 0 failures, 0 skipped on `SM-A546B - 16` |
| Manual bottom-tab smoke | PASS | Root reuse, active-tab no-op, rapid taps, Back to launcher, no crash/ANR |

## Added Test Count

- 13 new executable test methods in this work:
  - 8 JVM tests: six-month fixture/regression and progress-series edge cases.
  - 5 Android tests: historical Room persistence/reopen, export formats, and multi-profile isolation.
- Existing executable coverage remains active; totals after the run are 44 JVM tests and 16 Android tests.

## PASS

- Deterministic six-month data generation and repeatability.
- BIA/body-measurement trend checkpoints and local calculation regression.
- NORMAL versus SPORT historical phases.
- 26-week structured plan fixture validation.
- Supplement metadata, whey accounting, creatine SPORT-only rule, and hydration representation.
- Graph-series empty/single/missing/non-finite/duplicate/range behavior.
- Room persistence, immutable versions, profile-scoped histories, and migration `3 -> 4`.
- JSON completeness for profile history, plans, versions, supplements, hydration, and reviews.
- CSV ZIP entry structure.
- Profile PDF and weekly plan PDF file generation on Android.
- Multi-profile export isolation for populated, normal-empty, and new-empty profiles.
- Bottom-tab manual smoke flow, including non-cyclic Back behavior.

## FAIL

- No remaining failure in the final executed run.

## Bug Corrected

`CheatAdjustmentService` previously rebuilt adapted `DayDraft` objects without carrying `supplements` and `hydrationNote`. The service now preserves both fields and includes supplement values in rebuilt daily totals. This was identified during static service review; a full provider-backed service integration flow remains pending because `AiRuntimeService` is concrete and no credentialed provider test was run.

`ProfileExportService` also now preserves `supplements` and `hydrationNote` in the weekly-PDF snapshot path and includes them in canonical JSON day exports.

## STATIC REVIEW ONLY

- Provider-specific Gemini/OpenAI HTTP behavior, authentication, timeout, network loss, and JSON parity.
- Full service orchestration for plan generation, meal swap, advice, cheat analysis/adaptation, and weekly review with a fake runtime at the service boundary.
- Notification scheduler internals were inspected and time-injected, but AlarmManager delivery/cancellation was not executed as a deterministic fake-system test.
- Full Activity forms, camera/gallery flow, notification tap routing, share chooser, and visual/pixel fidelity.
- 1Y chart rendering, landscape, small/large screens, and screenshot/golden comparison.
- Large stress dataset timings and memory profile.

## NOT RUN

- Real provider calls with Gemini or OpenAI credentials.
- Camera, gallery, blurred-label image, and temporary-file lifecycle runtime tests.
- Notification delivery, reboot rescheduling, permission denial, snooze, and stale notification tests.
- Full UI E2E for profile/BIA/measurements/workouts/diet/cheat/review/export screens.
- v1 -> current and v2 -> current migrations; schema assets for those versions are not present.
- 5-profile/2-year/1000-workout/104-week/300-version/200-cheat performance run.

## Residual Risks

- Several service classes still depend on concrete `AiRuntimeService`, repositories, and Android system services, limiting JVM service integration coverage.
- Nested meal reads are ID-based rather than profile-aware at the DAO boundary.
- Notification timing remains subject to Android alarm and OEM scheduling behavior.
- No pixel-level comparison has been executed.
- V1 is not certified as fully complete; the traceability holes above remain open.
