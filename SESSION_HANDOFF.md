# MyFitAI - Session Handoff

This file records the verified working state so another OpenCode session or another PC can continue without relying on conversation memory.

## Read First

1. `AGENTS.md`
2. `DEVELOPMENT_PLAN.md`
3. This file

The authoritative implementation plan remains `DEVELOPMENT_PLAN.md`. This handoff adds operational continuity and does not replace it.

## Snapshot

- Date: 2026-09-22
- App: Android Views/XML, not Jetpack Compose
- Canonical UI mock: `assets/MOCK_APPROVATO_MYFITAI_V2_COMPLETO.png`
- Visual theme: warm near-white background, white surfaces, MyFitAI green, charcoal text
- Current UI work: second pass on operational measurement screens
- Current focus: `BiaActivity`, `BodyMeasuresActivity`, and their form/history flows
- AI, Room, ViewModels, navigation, and persistence contracts must remain unchanged during UI work

## Verified Work Completed

The following UI pilots are already implemented and committed in the current history:

- Shared shell/header, card, bottom navigation, and visual token cleanup
- `MeasurementsActivity` hub with shared BIA/body-measurement action cards
- Food plan historical read-only state and empty-state separation
- Progress hierarchy with evolution before indicators and conditional AI/review cards
- Home dashboard compact metrics panel
- Route cleanup that removed redundant Profile entries and preserved historical plan navigation

The latest UI delta covered by this handoff is:

- `MetricCardView.setCompactStyle()` now uses dp padding and smaller compact typography
- `activity_bia.xml` now has a 56dp shared-style header with the title `Bioimpedenziometria`
- `activity_bia.xml` segmented control has the same top spacing used by the body-measurement screen
- `activity_body_measures.xml` segmented control has aligned top spacing
- BIA date and time inputs are now side by side, matching the approved BIA composition
- BIA measurement conditions are now a single vertical checklist instead of two split rows
- BIA segmented control now gives `Nuova misurazione` enough width to remain readable on the physical Samsung
- BIA date input is constrained to one line so a full date such as `22/09/2026` is not clipped

Files in the latest delta:

- `android/app/src/main/java/com/myfitai/app/ui/widgets/MetricCardView.kt`
- `android/app/src/main/res/layout/activity_bia.xml`
- `android/app/src/main/res/layout/activity_body_measures.xml`
- `android/app/src/main/java/com/myfitai/app/ui/widgets/BiaSegmentView.kt`

## Verification Completed

Using Gradle 8.14.3 and device `RZCX924RQMV`:

- `:app:assembleDebug`: PASS
- `:app:testDebugUnitTest`: PASS
- `:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.myfitai.app.e2e.ActivitySmokeTest`: PASS
- `ActivitySmokeTest` reached `BiaActivity` and `BodyMeasuresActivity` without lifecycle/content-view crashes
- `git diff --check`: PASS
- Debug APK installation on the device: PASS

The latest BIA layout delta was additionally checked with:

- `:app:assembleDebug`: PASS
- `:app:testDebugUnitTest`: PASS
- `git diff --check`: PASS

The connected test was attempted again after the BIA layout change, but ADB reported no connected devices. The latest BIA change is therefore not yet certified by device smoke or screenshot comparison.

Physical-device QA was subsequently completed on `SM-A546B - 16` / `RZCX924RQMV` after installing the latest debug APK and seeding the six-month QA dataset through the debug seeder:

- `QaSeederDeviceTest`: PASS
- Real navigation reached `MeasurementsActivity`, `BiaActivity`, and `BodyMeasuresActivity`
- BIA screenshot verified the full `Nuova misurazione` tab label and one-line `22/09/2026` date
- BIA data/time remained side by side and the four measurement conditions remained a vertical checklist
- Body-measurement header, segments, front/back diagram, current values, and bottom navigation were visible
- `NewBodyMeasurementDeviceTest` passed on the physical Samsung: the form opened through instrumentation, date/peso/`Circonferenze` were present, the scroll reached `Arti`, and `Salva misurazione` was present
- The form test captured top/bottom screenshots in the app QA artifacts directory without saving data
- `MeasurementHistoryDeviceTest` passed on the physical Samsung with the six-month QA fixture
- BIA history and body-measurement history both exposed populated cards, `historyList`, and the shared edit/delete instruction; screenshots were captured in the QA artifacts directory
- BIA history cards were visually consolidated with body-measurement history cards: same 16/12dp padding, 8dp card gap, 15sp date, 12sp summary, and consistent secondary rows
- `MeasurementHistoryDeviceTest` passed again after the history-card visual consolidation
- No `FATAL EXCEPTION` or `AndroidRuntime` app crash was observed in the captured logcat
- Screenshot artifacts captured locally: `qa-bia-fixed-final.png`, `qa-body-measures-device.png` when available in the workspace

The direct `adb shell am start` check for the internal activities was rejected because those activities are intentionally not exported. This is expected and not an app defect. Instrumentation reaches them correctly.

Not verified in the latest delta:

- Full connected test suite after the latest BIA layout delta
- Runtime functional editing/saving/import flows for BIA and body measurements

## Current UI Audit Findings

`BaseShellActivity` already normalizes most regular screen headers to 56dp and applies `bg_screen_header`. BIA now has a visible title, aligned segment spacing, side-by-side date/time inputs, a readable first tab, a one-line date, and a vertical conditions checklist. The following work remains:

- Align form card spacing, section hierarchy, empty/loading/error states, help affordances, and history cards
- Review remaining empty-state and summary-copy differences; populated history card spacing and hierarchy are now consolidated and device-tested
- Review `NewBodyMeasurementActivity` and the BIA form for the same header, input, card, and primary-action hierarchy; the new-measurement form now has device smoke/screenshot coverage
- `SettingsSecurityUiTest` passed `2/2` on the physical Samsung without reading or changing credentials
- `SettingsDeviceVisualTest` passed on the physical Samsung: `FLAG_SECURE`, visible general/data/AI cards, provider status, and provider switch were verified; no Settings redesign was required from this QA pass
- `ProfileEditDeviceTest` passed on the physical Samsung without saving changes: title, personal-data fields, height/weight fields, dietary preferences, and the primary save action were verified; top/bottom screenshots were captured in QA artifacts
- `FoodReviewScreensDeviceTest` passed on the physical Samsung with the QA fixture: meal detail tabs/actions, shopping-list week/filter/status/export controls, and weekly-review metrics/tracking/generation controls were verified without provider calls or destructive changes
- Consolidated connected UI regression pass on `SM-A546B - 16`: `ActivitySmokeTest`, `SettingsSecurityUiTest` (`2/2`), `ProfileEditDeviceTest`, `MeasurementHistoryDeviceTest`, and `FoodReviewScreensDeviceTest` all passed
- Export QA completed on `SM-A546B - 16`: `ExportFormatsE2ETest` (`2/2`) verified CSV ZIP, PDF profile, and weekly-plan PDF; `ExportNavigationUiTest` (`2/2`) verified export navigation and JSON generation
- Notification QA passed on `SM-A546B - 16`: `NotificationSchedulerTest` (`4/4`) verified future meal/review scheduling, cancellation/reschedule, and snooze behavior; `ReminderReceiverTest` (`2/2`) verified notification channels and receiver output
- Photo-flow QA passed on `SM-A546B - 16`: `LabelImageFlowTest` (`3/3`) verified invalid-image rejection, JPEG conversion, resize and cleanup; `CheatEntryE2ETest` (`2/2`) verified the label-photo dialog is reachable without opening the camera
- Full `connectedDebugAndroidTest` on `SM-A546B - 16` executed `62` tests: `60 PASS`, `2 FAIL` only in `BottomNavigationUiTest` due to the known legacy `LocalActivityManager`/nested DecorView accessibility issue (`UiAutomator` timeout and `StackOverflowError`); no failures occurred in the newly covered UI, export, notification, or photo flows
- Full `connectedDebugAndroidTest` rerun after the BottomNavigation test seam: `62/62 PASS` on `SM-A546B - 16`
- A subsequent full suite exposed a real persisted-database compatibility issue: a version-10 database from an intermediate build lacked the optional extended BIA columns while the current DAO expected them. Added idempotent `MIGRATION_10_11`, installed the debug APK in place without clearing data, passed `DatabaseMigrationTest` `6/6`, and reran the full connected suite at `63/63 PASS`
- Provider-neutral AI verification passed: `:app:testDebugUnitTest` and `AiWorkflowIntegrationTest` (`6/6`) passed with fake runtime provider; no API keys were accessed or logged
- Keep all user-facing strings in resources when touching additional screens

## Next Target

1. Run `git fetch --all --prune` and confirm the working tree is clean except intentionally untracked local artifacts.
2. Read `DEVELOPMENT_PLAN.md`, then inspect the BIA/body-measurement mock regions and current layouts.
3. Finish the UI second pass for:
   - `android/app/src/main/res/layout/activity_bia.xml`
   - `android/app/src/main/res/layout/activity_body_measures.xml`
   - `android/app/src/main/res/layout/activity_new_body_measurement.xml`
   - the corresponding Activity/widget code only where presentation changes are required
4. Preserve all existing IDs, routes, ViewModels, Room calls, AI job scheduling, import preview, edit/delete behavior, and history semantics.
5. Build, run relevant unit/instrumentation tests, install on a connected device, and capture screenshots before declaring the pass complete.
6. Update `DEVELOPMENT_PLAN.md` only for behavior or QA that is genuinely verified.

After the measurement pass, the remaining UI review queue is:

- `SettingsActivity`
- `ProfileEditActivity` now has device smoke/screenshot coverage; visual redesign remains optional unless a concrete mismatch is found
- `MealDetailActivity`, `ShoppingListActivity`, and `WeeklyReviewActivity` now have combined device coverage; no production UI change was required in this pass
- `MealDetailActivity`
- `ShoppingListActivity`
- `WeeklyReviewActivity`
- complete end-to-end visual/functional QA across profile, diet, cheat, review, export, and photo flows
- Next QA focus after the UI coverage pass: export flows, local notifications, photo import/camera paths, and the full connected suite
- Export is no longer pending in `DEVELOPMENT_PLAN.md`; next QA focus is local notifications, photo import/camera paths, and the full connected suite
- Deterministic notification scheduling/receiver coverage is now verified; remaining notification risk is real background delivery/permission behavior, alongside photo import/camera paths and the full connected suite
- Photo conversion/lifecycle and sgarro photo-dialog coverage are verified; real Photo Picker/camera capture remains explicitly unverified
- BottomNavigation residual resolved in test infrastructure only: `TabHostActivity.currentTabActivity()` exposes the embedded root to instrumentation assertions; production navigation behavior was not changed
- Gemini HTTP runtime is now verified on the physical device: manual BYOK configuration was already present, `myfitai_provider_verification` returned structured JSON with `finishReason=STOP`, weekly-plan generation used `myfitai_weekly_nutrition_pipe_v1` in JSON-only mode, usage metadata was received, the initial invalid output was rejected locally, the retry passed validation, and the plan was persisted and visible in the weekly UI. OpenAI HTTP runtime and real provider parity are intentionally deferred for a later phase; never automate, read, log, or commit credentials
- Secure manual-runtime path is confirmed: enter a key only in `SettingsActivity`, use `Verifica e salva`, run the target provider flow on the device, then use `Sostituisci`/`Elimina`; keys are read just-in-time from `SecureAiCredentialStore` and must never enter chat, logs, tests, exports, or navigation arguments
- Physical-device prerequisite verified: the debug QA seeder created an active local profile on `SM-A546B`, `SplashActivity` reopened `TabHostActivity`, and the `Altro` tab reached `Impostazioni` with the protected provider fields available for manual key entry; no key was inserted or read by automation
- Current device result: Gemini is active as `Gemini BYOK`, the weekly plan for `21–27 Settembre 2026` is visible with generated version date `22/09/2026`, and the persisted meal cards are rendered. No API key value was inspected or logged

## Release Readiness Review

- `:app:assembleRelease`: PASS with Gradle `8.14.3`; release lint passed.
- Release signing: BLOCKED for distribution. `:app:signingReport` reports `Config: null` for the release variant, so the generated release APK is not signed with a distribution keystore. Configure signing only through a protected local/CI keystore before publishing; never commit keystore files or passwords.
- Manifest security: `android:allowBackup="false"`, `android:fullBackupContent="false"`, non-exported production provider/receivers, and debug-only QA activities are present.
- Credential security: Keystore/AES-GCM storage, hidden saved-key UI, `FLAG_SECURE`, sanitized provider telemetry and no plaintext credential path were confirmed by source review and existing device tests.
- Current verified device baseline: `63/63` connected tests on `SM-A546B - 16`, migration `10->11` covered by `DatabaseMigrationTest` `6/6`, Gemini real weekly-plan runtime verified, OpenAI intentionally deferred.
- Remaining release QA gaps: real Photo Picker/camera capture, OS-level notification delivery/tap, rotation/landscape/font-scale visual coverage, 1Y chart rendering, post-run memory profiling, and real OpenAI provider parity.

## Next UI Reorganization

The remaining graphic pass will use the Home as a reference for hierarchy and spacing while preserving the approved screen-specific compositions and all existing behavior.

Priority order:

1. `PhysicalEvolutionActivity`: completed the first pilot. Removed the duplicated screen title, aligned the header and card hierarchy with Home, kept metric/range/chart IDs unchanged, separated indicator rows with dividers, and preserved dynamic AI cards and all existing state handling. `ProgressRangeDeviceTest` passed on `SM-A546B - 16` with `1M/3M/6M/1Y` and metric switching.
2. `ProfileActivity` and `HistoryActivity`: completed the second pilot. Profile now uses the standard icon header and Home spacing, while History uses the standard header, separated segment control, surfaced empty state, and shared primary export button style. Existing IDs, profile/photo actions, history categories and navigation were preserved. Targeted device run passed `3/3` (`ActivitySmokeTest`, `MeasurementHistoryDeviceTest`, `ProfileEditDeviceTest`).
3. `WorkoutsActivity` and `NewWorkoutActivity`: align week navigation, summary card, workout rows and primary action.
4. `ExportActivity` and `NotificationsActivity`: align action-card hierarchy and states without flattening the notification-specific visual composition.
5. Remaining nutrition/detail screens: review `FoodPlanActivity`, `MealDetailActivity`, `AdjustedPlanActivity`, `NutritionAdviceActivity` and `NutritionPathActivity` for the same token/header/state consistency.

The remaining UI reorganization groups are now implemented on the current working tree:

- Workouts/NewWorkout: standard shell gutter, 56dp header, shared title/button styles and tighter card hierarchy.
- Export: standard shell gutter/header and normalized action-card spacing.
- Nutrition operational screens: Advice, adjusted plan, meal alternatives, nutrition settings, FoodPlan, ShoppingList, CheatEntry and WeeklyReview now use the shared shell spacing/header/button treatment where their specific compositions allow it.
- Meal detail and notification reminder retain their dedicated hero/full-screen compositions intentionally; only shared behavior remains unchanged.
- Verification after the full UI pass: `:app:assembleDebug`, `:app:testDebugUnitTest`, targeted device flows `9/9 PASS`, and full `connectedDebugAndroidTest` `63/63 PASS` on `SM-A546B - 16`.
- Final global UI pass: remaining base, measurement, AI, nutrition, review, export, workout, profile and history layouts were normalized to the shared MyFitAI visual system. `MealDetailActivity` keeps its meal hero and `NotificationsActivity` keeps its full-screen reminder composition, with shared surfaces/CTA semantics refined. Final build/test verification remains green: debug/release assemble, unit tests and `63/63` connected tests.
- Visual system refinement: shared card elevation reduced to tonal/border-led surfaces, primary/outlined buttons standardized with stable minimum heights, bottom navigation spacing increased, and `BaseShellActivity` now applies a short 220ms fade/translate entrance to screen content. No animation was added to business operations or AI jobs.
- Visual hierarchy correction after direct device review: green is now reserved for primary actions, selected controls and status accents; secondary headers use the Home navy with light text; cards and positive/AI panels use white, warm-neutral or very-light semantic surfaces. This addresses the previous issue where screens still read as green cards instead of following the Home composition.
- Profile visual correction: `ProfileActivity` and `ProfileEditActivity` now force white title/back contrast on the navy Home-style header; `SettingRowView` uses neutral icon tiles, semibold labels and muted chevrons instead of flat green-accented rows. The current debug APK is installed in place without clearing data; bootstrap remains open until profile data is entered manually.
- ProfileEdit compact pass: added compact form card/input styles and reduced vertical density to match Home; debug APK installed in place without clearing data. A final connected suite attempt was interrupted when `SM-A546B` disconnected during `SixMonthHistoryExportTest` after 53/63 tests; the export failure is inconclusive and not attributed to UI changes. Device must reconnect before rerunning the final suite.
- Global compact pattern pass: compact input/dropdown defaults (44dp, 14sp, reduced horizontal padding), compact section content, 16sp section headers, tighter setting/metric rows and neutral white form cards are now shared across the app. Debug APK installed in place and left open on `ProfileEditActivity`; no data was cleared. Final full-suite rerun is intentionally deferred until visual approval of this pass.
- List pattern pass: `SettingRowView` now supports a compact secondary description, neutral icon tile, semibold title and muted chevron. Profile and Settings/Gestione dati rows were populated with the shared title + short-description structure matching the first Home action rows; mock data and debug install were preserved.
- Global white-card pass: all shared soft card surfaces used by Alimentazione, Progresso, Review, Piano adattato, storico, workout and measurement summaries now resolve to white Home-style cards with neutral borders. The three main tabs also use the compact shared card-content token; green remains reserved for actions, selection and semantic indicators.
- The final debug APK was installed in place on `SM-A546B`; complete device suite passed after the global visual changes. Advanced landscape, expanded-window and font-scale visual comparison remain unverified; release signing remains a separate distribution blocker.

For each group: preserve IDs/routes/ViewModels/Room/AI jobs, compare against the approved mock on device, run the relevant instrumentation tests, then update this handoff only with verified results.

## Git Continuity

- Target branch: `develop`
- Working branch used for the UI pass: `opencode/nutrition-path-integrated`
- The remote `origin/develop` was at `c911f23` before this handoff commit.
- The local `develop` branch was stale and diverged from the remote by one local commit and 292 remote commits. It must be synchronized deliberately, not merged blindly.
- The local stale line should be preserved as `backup/develop-before-ui-sync` before moving local `develop` to the current UI line.
- Do not commit `.playwright-mcp/` or `assets/ui-concepts/`; they are local/untracked artifacts already present in the workspace.

## Useful Commands

From `android/`:

```powershell
& 'C:\Users\LucaPiciollo\.gradle\wrapper\dists\gradle-8.14.3-all\10utluxaxniiv4wxiphsi49nj\gradle-8.14.3\bin\gradle.bat' :app:assembleDebug
& 'C:\Users\LucaPiciollo\.gradle\wrapper\dists\gradle-8.14.3-all\10utluxaxniiv4wxiphsi49nj\gradle-8.14.3\bin\gradle.bat' :app:testDebugUnitTest
& 'C:\Users\LucaPiciollo\.gradle\wrapper\dists\gradle-8.14.3-all\10utluxaxniiv4wxiphsi49nj\gradle-8.14.3\bin\gradle.bat' :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.myfitai.app.e2e.ActivitySmokeTest'
```

ADB path on the known Windows machine:

```powershell
& 'C:\Users\LucaPiciollo\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices
```

## Do Not Change

- Do not migrate the app globally to Compose.
- Do not expose internal activities just to launch them with ADB.
- Do not weaken `android:allowBackup="false"`.
- Do not access, log, commit, or reproduce AI API keys.
- Do not mark visual work complete without device comparison.
- Do not use Gradle 9.6.0 for this continuation; use Gradle 8.14.3.
