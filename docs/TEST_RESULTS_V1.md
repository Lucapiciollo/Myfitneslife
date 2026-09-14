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
| `:app:connectedDebugAndroidTest` | PASS | 37 tests, 0 failures, 0 skipped on `SM-A546B - 16` |
| Manual bottom-tab smoke | PASS | Root reuse, active-tab no-op, rapid taps, Back to launcher, no crash/ANR |

## Added Test Count

- 18 new executable test methods in this work:
  - 8 JVM tests: six-month fixture/regression and progress-series edge cases.
  - 10 Android tests: historical Room persistence/reopen, export formats, multi-profile isolation, and deterministic AI workflows.
- Existing executable coverage remains active; totals after the run are 44 JVM tests and 37 Android tests.

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
- Label-photo processing covers valid JPEG conversion to in-memory payload, unreadable image rejection, temporary camera-file deletion and no external-file deletion.
- UIAutomator E2E covers seeded launch, bottom-tab root switching, active-tab no-op and Back returning to launcher.
- UIAutomator E2E reaches export from Altro, verifies all export format rows and confirms JSON export file creation from the real screen.
- Notification receiver tests verify meal/review channels and meal notification creation when Android notification permission allows it.
- Stress dataset executed twice on device: 5 profiles, 240 BIA, 240 body measurements, 1000 workouts, 520 plan roots, 936 plan versions and 200 cheats. Run 1 timings: BIA 34 ms, body 10 ms, workouts 16 ms, plan roots 11 ms, snapshot 32 ms, shopping 46 ms, JSON 14832 ms, CSV ZIP 15488 ms, profile PDF 8391 ms, weekly PDF 327 ms. Run 2: BIA 36 ms, body 22 ms, workouts 21 ms, plan roots 19 ms, snapshot 14 ms, shopping 40 ms, JSON 21783 ms, CSV ZIP 20348 ms, profile PDF 4811 ms, weekly PDF 162 ms.
- Bottom-tab manual smoke flow, including non-cyclic Back behavior.

## FAIL

- No remaining failure in the final executed run.

## Bug Corrected

`CheatAdjustmentService` previously rebuilt adapted `DayDraft` objects without carrying `supplements` and `hydrationNote`. The service now preserves both fields and includes supplement values in rebuilt daily totals. The service boundary is now testable through `AiRuntimeGateway`; real provider transport remains untested.

`ProfileExportService` also now preserves `supplements` and `hydrationNote` in the weekly-PDF snapshot path and includes them in canonical JSON day exports.

## STATIC REVIEW ONLY

- Real Gemini/OpenAI HTTP behavior, authentication, timeout, network loss, and provider parity.
- Real AlarmManager delivery, reboot rescheduling, Doze/OEM timing and notification tap routing remain untested; receiver/channel construction and permission-aware behavior are covered.
- Full Activity forms, OS camera/gallery picker flow, notification tap routing, share chooser, and visual/pixel fidelity.
- 1Y chart rendering, landscape, small/large screens, and screenshot/golden comparison.
- Memory profiling; post-run `dumpsys meminfo` was unavailable because connected tests stop/uninstall the app process at completion.

## NOT RUN

- Real provider calls with Gemini or OpenAI credentials.
- Camera/gallery OS picker delivery and blurred-label capture remain untested; processor and temporary-file lifecycle are covered.
- Reboot rescheduling, Doze/OEM delivery, notification tap routing, permission denial behavior on a controlled permission state, and stale notification delivery tests.
- Full UI E2E for profile/BIA/measurements/workouts/diet/cheat/review/export screens.
- v1 -> current and v2 -> current migrations; schema assets for those versions are not present.

## Residual Risks

- Service integrations use `AiRuntimeGateway`; repositories and Android system services remain concrete in several flows.
- Nested plan/version/meal reads now require the owning `profileId` at the DAO/repository boundary.
- Notification timing remains subject to Android alarm and OEM scheduling behavior.
- No pixel-level comparison has been executed.
- V1 is not certified as fully complete; the traceability holes above remain open.
