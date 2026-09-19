# MyFitAI V1 Test Plan

## Scope

This plan defines the executable and manual certification layers for the V1 Android app. It uses the deterministic `SixMonthHistoryFixture` and keeps provider, Android-system, UI, visual, and performance evidence separate.

The plan does not consider V1 certified only because the app starts. Certification requires the historical path to remain coherent across profile, measurements, calculations, training mode, nutrition plans, deviations, versioning, shopping, review, export, persistence, and profile isolation.

## Deterministic Controls

- Fixture seed: `20260914`.
- Reference date: `2026-09-14`.
- Fixture range: approximately six months, from `2026-03-14` through `2026-09-14`.
- Fixture timezone: `UTC`.
- Production time source: `SystemTimeProvider`.
- Test time source: `TimeProvider` implementations with fixed epoch milliseconds.
- Real API credentials are never part of a fixture, test output, or evidence.

## Test Layers

| Layer | Location | Purpose | Current status |
|---|---|---|---|
| Unit | `android/app/src/test` | Pure engines, contracts, fixture history, trend filtering, provider selection | Executed: 44 |
| Room integration | `android/app/src/androidTest` | Persistence, versioning, profile isolation, migration, device QA | Executed: 41 on SM-A546B / Android 16 |
| Export integration | `android/app/src/androidTest/e2e` | JSON, ZIP, profile PDF, weekly PDF on Android | Executed |
| Provider integration | Runtime Gemini/OpenAI | Real direct transports, BYOK credentials, timeout, parity | Not run: user credentials unavailable |
| UI/E2E | Device/manual | Activities, visual fidelity, bottom tabs, forms | Partial/manual |
| System integration | Android alarms/camera/share | Notifications, camera/gallery, chooser | Not run or partial |
| Performance | Device/profiling | Large dataset timings and memory | Not run |

## Commands

Run from `android/` with the installed Gradle distribution:

```text
gradle :app:assembleDebug
gradle :app:testDebugUnitTest
gradle :app:connectedDebugAndroidTest
```

No GitHub Actions workflow is started by this plan.

## Executable Suite

### Unit regression

- `SixMonthHistoryFixtureTest`: deterministic six-month history, NORMAL/SPORT phases, 26 weeks, plans, supplements, hydration, cheats, versions, and local calculations.
- `ProgressSeriesEngineTest`: empty, single point, missing/non-finite values, duplicate dates, and 1M/3M/6M/1Y ranges.
- Existing calculation, nutrition contract, AI execution, shopping, personalization, and adaptation contract tests remain active.

### Instrumented integration

- `SixMonthHistoryExportTest`: Room historical insertion, persistent close/reopen, JSON/CSV ZIP/profile PDF/weekly PDF generation, and multi-profile export isolation.
- Existing Room persistence tests remain active.
- Existing migration `3 -> 4` test remains active.

## Acceptance Rules

- `PASS`: the test was executed and the expected behavior was observed.
- `FAIL`: the test was executed and a reproducible mismatch occurred.
- `STATIC REVIEW ONLY`: implementation was inspected but no executable test proves the behavior.
- `NOT RUN`: required runtime capability, provider, fixture, or device flow was unavailable.
- Real Gemini/OpenAI calls are never replaced by fake-provider tests in the results.
- Gemini BYOK requires a personal Gemini API key entered only through Settings. Firebase project/App Check configuration is not required and Firebase AI Logic is not used.
- Screenshot/golden tests are not considered executed unless an image comparison was actually run.
