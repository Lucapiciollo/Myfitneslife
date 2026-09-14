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
| `:app:connectedDebugAndroidTest` | PASS | 26 tests, 0 failures, 0 skipped on `SM-A546B - 16` |
| Manual bottom-tab smoke | PASS | Root reuse, active-tab no-op, rapid taps, Back to launcher, no crash/ANR |

## Added Test Count

- 18 new executable test methods in this work:
  - 8 JVM tests: six-month fixture/regression and progress-series edge cases.
  - 10 Android tests: historical Room persistence/reopen, export formats, multi-profile isolation, and deterministic AI workflows.
- Existing executable coverage remains active; totals after the run are 44 JVM tests and 26 Android tests.

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
- Provider-neutral service integration for plan generation, meal swap, nutrition advice, cheat analyze/confirm, and weekly review.
- AI schema/business validation remains in the execution path; tests do not bypass canonical contracts.
- Nested plan/version/meal reads are profile-scoped; cross-profile access is rejected by the Android integration test.
- Deterministic notification scheduling covers lead time, future/past filtering, 60-day horizon, weekly review rollover, cancellation, profile switch and snooze clamping.
- Bottom-tab manual smoke flow, including non-cyclic Back behavior.

## FAIL

- No remaining failure in the final executed run.

## Bug Corrected

`CheatAdjustmentService` previously rebuilt adapted `DayDraft` objects without carrying `supplements` and `hydrationNote`. The service now preserves both fields and includes supplement values in rebuilt daily totals. The service boundary is now testable through `AiRuntimeGateway`; real provider transport remains untested.

`ProfileExportService` also now preserves `supplements` and `hydrationNote` in the weekly-PDF snapshot path and includes them in canonical JSON day exports.

## STATIC REVIEW ONLY

- Real Gemini/OpenAI HTTP behavior, authentication, timeout, network loss, and provider parity.
- Real AlarmManager delivery, notification permission, reboot rescheduling and notification tap delivery remain untested; scheduling logic is covered through a fake alarm gateway.
- Full Activity forms, camera/gallery flow, notification tap routing, share chooser, and visual/pixel fidelity.
- 1Y chart rendering, landscape, small/large screens, and screenshot/golden comparison.
- Large stress dataset timings and memory profile.

## NOT RUN

- Real provider calls with Gemini or OpenAI credentials.
- Camera, gallery, blurred-label image, and temporary-file lifecycle runtime tests.
- Notification delivery, reboot rescheduling, permission denial, and stale notification delivery tests.
- Full UI E2E for profile/BIA/measurements/workouts/diet/cheat/review/export screens.
- v1 -> current and v2 -> current migrations; schema assets for those versions are not present.
- 5-profile/2-year/1000-workout/104-week/300-version/200-cheat performance run.

## Residual Risks

- Service integrations use `AiRuntimeGateway`; repositories and Android system services remain concrete in several flows.
- Nested plan/version/meal reads now require the owning `profileId` at the DAO/repository boundary.
- Notification timing remains subject to Android alarm and OEM scheduling behavior.
- No pixel-level comparison has been executed.
- V1 is not certified as fully complete; the traceability holes above remain open.
- The profile-scoped nested-plan changes compile in the Android test source set, but the final connected instrumentation rerun was `NOT RUN` because no device was connected when the command was issued.
