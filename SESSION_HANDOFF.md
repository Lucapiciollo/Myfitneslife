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
- No `FATAL EXCEPTION` or `AndroidRuntime` app crash was observed in the captured logcat
- Screenshot artifacts captured locally: `qa-bia-fixed-final.png`, `qa-body-measures-device.png` when available in the workspace

The direct `adb shell am start` check for the internal activities was rejected because those activities are intentionally not exported. This is expected and not an app defect. Instrumentation reaches them correctly.

Not verified in the latest delta:

- Full connected test suite after the latest BIA layout delta
- Runtime functional editing/saving/import flows for BIA and body measurements

## Current UI Audit Findings

`BaseShellActivity` already normalizes most regular screen headers to 56dp and applies `bg_screen_header`. BIA now has a visible title, aligned segment spacing, side-by-side date/time inputs, a readable first tab, a one-line date, and a vertical conditions checklist. The following work remains:

- Align form card spacing, section hierarchy, empty/loading/error states, help affordances, and history cards
- Verify that BIA history and body-measurement history expose equivalent visual affordances without changing behavior
- Review `NewBodyMeasurementActivity` and the BIA form for the same header, input, card, and primary-action hierarchy; the new-measurement form now has device smoke/screenshot coverage
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
- `ProfileEditActivity`
- `MealDetailActivity`
- `ShoppingListActivity`
- `WeeklyReviewActivity`
- complete end-to-end visual/functional QA across profile, diet, cheat, review, export, and photo flows

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
