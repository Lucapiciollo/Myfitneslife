# MyFitAI V1 Test Plan

## Execution Rules

- Branch: `develop`.
- Device reference: physical Android device or emulator with Android 26+.
- Do not store real API keys in test evidence, logs, screenshots, exports or test data.
- Use the profile below for deterministic manual tests unless a test explicitly requires a second profile.
- Record one row per test execution with date, device, build commit, result and notes.
- `PASS` means the observed behavior matches the expected result.
- `FAIL` means a reproducible mismatch or crash.
- `NOT RUN` means the test needs a provider, data fixture or device capability not currently available.

## Reference Data

Profile:

- Name: `Test Sport`
- Sex: `Maschio`
- Birth date: `09/08/1983`
- Height: `186 cm`
- Weight: `89 kg`
- Goal: `Ricomposizione`
- Activity: `Moderatamente attivo`
- Wake: `07:00`
- Sleep: `23:30`

BIA reference measurements, at least three dates:

- Weight: `89.0`, `88.6`, `88.2 kg`
- Body fat: `20.0`, `19.6`, `19.2 %`
- Muscle mass: `67.0`, `67.1`, `67.2 kg`
- Skeletal muscle: `34.0`, `34.1`, `34.2 kg`
- Body water: `56.0`, `56.2`, `56.4 %`

Body measurements, at least two dates:

- Chest: `104`, `103 cm`
- Waist: `88`, `86 cm`
- Abdomen: `92`, `90 cm`
- Shoulders: `49`, `49 cm`
- Glutes: `101`, `100 cm`
- Arms left/right: `35/35`, `35.2/35.1 cm`
- Thighs left/right: `60/60`, `60.2/60.1 cm`
- Calves left/right: `39/39`, `39.2/39.1 cm`

Workouts:

- Monday, weights, `18:30`.
- Wednesday, weights, `18:30`.
- Friday, weights, `18:30`.
- Saturday, weights, `11:00`.
- Rest day on the remaining days.

## Test Case Format

Each case contains ID, prerequisites, input, steps, expected result, severity and result.

## A. Installation And Lifecycle

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| A-001 | Clean app install | Install debug APK and launch | Splash routes to onboarding/bootstrap without crash | Blocker | NOT RUN | Requires clean install |
| A-002 | A-001 | Complete bootstrap with reference profile | Home opens with active profile | Blocker | PASS | Verified with `Debug` profile |
| A-003 | Existing profile | Kill and relaunch app | Active profile and Home remain available | High | PASS | Verified on physical device |
| A-004 | Existing profile | Send app background, restore | Same page and state remain available | High | PASS | Verified manually |
| A-005 | Existing profile | Rotate device during Home and ProfileEdit | Selected page and data remain valid | High | NOT RUN | Device rotation pending |

## B. Profile

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| B-001 | Bootstrap | Leave required fields empty and save | Inline validation prevents persistence | High | NOT RUN | |
| B-002 | Bootstrap | Save reference profile | Profile persists and appears in Home/Profile | Blocker | PASS | Profile fields verified |
| B-003 | Existing profile | Edit weight, goal and schedule, save, reopen | Updated values are shown | High | PASS | ProfileEdit reload verified |
| B-004 | Existing profile | Create second profile and switch via header | Data is profile-scoped and Home follows active profile | High | NOT RUN | |
| B-005 | Existing profiles | Switch profile repeatedly and rotate | No stale data from previous profile | High | NOT RUN | |

## C. BIA

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| C-001 | Reference profile | Insert three BIA rows | Rows persist in history | High | NOT RUN | |
| C-002 | BIA history | Open latest/history | Latest row and history order are correct | High | NOT RUN | |
| C-003 | Three BIA rows | Open evolution and change ranges | Trend/delta use real rows | High | NOT RUN | |
| C-004 | BIA form | Omit optional values | Partial row is accepted and missing values remain missing | Medium | NOT RUN | |
| C-005 | BIA form | Enter negative/extreme values | Validation rejects invalid values | High | NOT RUN | |
| C-006 | Two profiles | Insert BIA for each, switch profile | History is isolated | Blocker | PASS | Room profile isolation automated |

## D. Body Measurements

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| D-001 | Reference profile | Insert two complete measurement rows | Rows persist | High | NOT RUN | |
| D-002 | Two rows | Open trend and change metric/range | Correct series and delta are shown | High | NOT RUN | |
| D-003 | Measurement form | Save one field only | Partial measurement persists | Medium | NOT RUN | |
| D-004 | Measurement form | Save empty or invalid values | Save is blocked with validation | High | NOT RUN | |

## E. Local Calculation Engine

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| E-001 | Unit tests | Run `:app:testDebugUnitTest` | BMI, Katch-McArdle, TDEE and targets match assertions | Blocker | PASS | Automated |
| E-002 | Unit tests | Remove plausible body fat, retain sex/age/weight/height | Mifflin-St Jeor fallback is used | High | PASS | Automated |
| E-003 | Profile changes | Change goal/activity/weight | Local targets change deterministically | High | NOT RUN | |
| E-004 | Provider response | Return conflicting numeric values | AI cannot override local calculations | Blocker | PASS | Contract/validator tests |

## F. Workouts And Sport Classifier

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| F-001 | Reference profile | Save Monday/Wednesday/Friday/Saturday weights | Rows appear in weekly history | High | NOT RUN | |
| F-002 | Workout form | Save rest day | Rest row persists without workout duration requirement | High | NOT RUN | |
| F-003 | Workouts | Open Home/food generation context | Workout times are available as timing context | High | NOT RUN | |
| F-004 | Unit tests | Run classifier tests | Moderate activity plus two structured workouts is `SPORT`; sedentary is `NORMAL` | Blocker | PASS | Automated |

## G. AI Providers

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| G-001 | No key | Open AI feature | Clear `NOT_CONFIGURED` state, no crash | High | PASS | Verified on device |
| G-002 | Test Gemini key in Keystore | Select Gemini and call a feature | Request uses Gemini and returns validated JSON | Blocker | NOT RUN | Never record key |
| G-003 | Test OpenAI key in Keystore | Select OpenAI and call a feature | Request uses OpenAI and returns canonical JSON | Blocker | NOT RUN | Never record key |
| G-004 | Invalid key | Call provider | User-visible error, no plaintext secret in logs | Blocker | NOT RUN | |
| G-005 | Network disabled | Call provider | Timeout/network error is handled without crash | High | NOT RUN | |
| G-006 | Fake invalid JSON | Run provider test double | Schema retry/rejection works | High | PASS | Automated |
| G-007 | Business-invalid JSON | Run provider test double | Business validator rejects response | Blocker | PASS | Automated |

## H. Diet Generation

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| H-001 | Valid profile, provider | Generate current week | Exactly seven days are persisted as a new version | Blocker | NOT RUN | Provider required |
| H-002 | Generated plan | Inspect every day | Kcal/macros are within official `target -3% .. target` range and totals are coherent | Blocker | PASS | Automated contract coverage |
| H-003 | Generated plan | Inspect ingredients | Numeric quantity, unit and displayDose exist; condiments/beverages are explicit | High | NOT RUN | |
| H-004 | Generated plan | Inspect timing/seasonality | Timing follows context; seasonality does not override targets | High | NOT RUN | |
| H-005 | Generated plan | Inspect text | No invented allergy, intolerance, diagnosis or medical claim | Blocker | PASS | Validator/prompt rules |

## I. Sports Nutrition

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| I-001 | SPORT profile | Generate plan with protein powder | Whey kcal/macros are included in day totals | Blocker | PASS | Automated |
| I-002 | SPORT profile | Generate plan with creatine | Creatine is allowed and has zero kcal/macros | Blocker | PASS | Automated |
| I-003 | NORMAL profile | Return creatine | Business validator rejects it | Blocker | PASS | Automated |
| I-004 | NORMAL profile | Return whey useful for target | Whey is allowed and counted | High | PASS | Automated |

## J. Hydration

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| J-001 | Generated plan | Inspect day | `hydrationNote` is separate from meals | High | PASS | Contract/Room coverage |
| J-002 | BIA context | Generate plan | Hydration guidance is cautious and does not diagnose dehydration | Blocker | NOT RUN | Provider required |

## K. Meal Detail And L. Meal Swap

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| K-001 | Persisted plan | Open meal detail | Kcal, P/C/F, ingredients, displayDose, weightState and preparation are visible | High | NOT RUN | No plan on device |
| L-001 | Future meal and provider | Request alternatives | Exactly five alternatives with same type/time and exact kcal | Blocker | PASS | Contract coverage |
| L-002 | Future meal with supplements | Accept alternative | New immutable version; supplements and hydrationNote preserved | Blocker | PASS | Instrumented Room coverage |
| L-003 | Past meal | Request swap | Past meal is rejected | Blocker | NOT RUN | |
| L-004 | Stale generated alternatives | Change plan then apply | Stale plan is rejected | High | PASS | Service logic/contract |

## M. Nutrition Advice

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| M-001 | Provider | Ask `Ho fame, cosa mangio?` | Five ordered nutrition suggestions | High | NOT RUN | Provider required |
| M-002 | Provider | Ask `Mi va un gelato` | Gelato remains central; time affects portion/timing | High | NOT RUN | |
| M-003 | Provider | Ask `Cosa posso mangiare?` | Meal window strongly influences ranking | High | NOT RUN | |
| M-004 | Provider | Ask outside-domain question | Exact refusal: `Posso rispondere solo a richieste di consiglio alimentare e nutrizionale.` | Blocker | PASS | Automated contract coverage |
| M-005 | Valid advice | Accept suggestion | Existing registration/adaptation flow opens | High | NOT RUN | |

## N. Sgarro, O. Clarification And P. Label Photo

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| N-001 | Provider | Enter `Gelato coppetta media due gusti`, analyze | Preview with prudent kcal/macros; no persistence yet | High | NOT RUN | Provider required |
| O-001 | Understanding preview | Add `pistacchio e cioccolato, circa 180 g` and reevaluate | Preview is replaced with clarification-aware result | High | NOT RUN | |
| P-001 | Device camera/gallery | Attach label photo | Image is temporary, not persisted or exported | Blocker | NOT RUN | |
| Q-001 | Confirmed preview | Confirm | Sgarro persists with correct date/time | Blocker | NOT RUN | |

## R. Plan Adaptation And S. Large Cheat

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| R-001 | Active version, confirmed cheat | Adapt | Only future meals change; past meals remain immutable | Blocker | PASS | Contract coverage |
| R-002 | Active version, confirmed cheat | Inspect new version | No punitive compensation or zero meals | Blocker | PASS | Contract coverage |
| S-001 | Provider | Enter `pizza + fritti + dolce + birra` | No fasting or extreme restriction; safe no-adaptation is allowed | Blocker | PASS | Contract coverage |

## T. Versioning

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| T-001 | Generated plan | Inspect history | `AI_GENERATION` version is retained | Blocker | NOT RUN | |
| T-002 | Meal swap | Accept swap | `AI_MEAL_SWAP` version is appended; prior version unchanged | Blocker | PASS | Instrumented coverage |
| T-003 | Cheat adaptation | Confirm adaptation | `ADAPTATION` version is appended; prior version unchanged | Blocker | PASS | Contract coverage |

## U. Shopping, V. Notifications And W. Review

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| U-001 | Plan with ingredients | Open shopping list | Ingredients aggregate by item/unit/category from latest version | High | PASS | Unit tests |
| V-001 | Plan with timed meal | Schedule/refresh reminders | Next meal reminder is scheduled and refreshed after plan change | High | NOT RUN | |
| V-002 | Reminder | Tap notification | Correct meal detail or plan opens | High | NOT RUN | |
| W-001 | Week data | Generate review | Review uses recorded data and avoids invented causality | Blocker | PASS | Contract tests |

## X. Export And Y. Restart

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| X-001 | Profile/data | Export JSON | Valid JSON; no API key; only active profile data | Blocker | NOT RUN | |
| X-002 | Profile/data | Export CSV ZIP and PDF | Files are produced and share chooser opens | High | NOT RUN | |
| X-003 | Two profiles | Export active profile | Data from another profile is excluded | Blocker | NOT RUN | |
| Y-001 | Persisted data | Kill app and relaunch | Profile, measurements, workouts and plans remain | Blocker | PASS | Profile/device smoke |
| Y-002 | Persisted data | Reboot device/emulator | Same data remains after reboot | Blocker | NOT RUN | |

## Z. Error Handling

| ID | Prerequisites | Input / Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|---|
| Z-001 | Any AI feature | Provider crash/timeout | Error state, no crash, no secret leakage | Blocker | PASS | AI execution tests cover rejection |
| Z-002 | Any AI feature | Network loss | Retry/error state is visible | High | NOT RUN | |
| Z-003 | Nutrition validator | Invalid supplement/creatine NORMAL | Response rejected locally | Blocker | PASS | Automated |
| Z-004 | Nutrition validator | Kcal outside tolerance | Response rejected locally | Blocker | PASS | Automated |
| Z-005 | Any async action | Double tap/change profile/close app during operation | No duplicate version or corrupted state | Blocker | NOT RUN | |

## Bottom Tab Regression Suite

| ID | Steps | Expected Result | Severity | Result | Notes |
|---|---|---|---|---|---|
| TAB-001 | Home -> Food | Reuses Food root; no visible Activity animation/flicker | High | PASS | Physical device smoke test; root Activity reused and final four-tab task contained no duplicate root |
| TAB-002 | Food -> Progress | Progress root selected without duplicate stack | High | PASS | Physical device smoke test; root Activity reused and task contained one instance per visited root |
| TAB-003 | Tap active tab | No new Activity or refresh | High | PASS | Focused Activity and task unchanged |
| TAB-004 | Home -> Food -> Home | Original Home root reused | Blocker | PASS | Home Activity was reused; no new Home instance was created |
| TAB-005 | Back after tab changes | Does not cycle previous tabs | Blocker | PASS | Back returned to launcher, not previous tab |
| TAB-006 | Switch profile | Current root/tab remains coherent | High | NOT RUN | |
| TAB-007 | Rotate | Selected tab remains correct | High | NOT RUN | |
| TAB-008 | Background/foreground | No duplicate root Activity | High | NOT RUN | |
| TAB-009 | Rapid tab taps | No race or duplicate stack | Blocker | PASS | Physical device smoke test; final task size remained 4, one instance per root tab, with no crash or ANR |
| TAB-010 | Open meal detail from Food and back | Detail uses normal stack and returns to Food | High | NOT RUN | Requires plan; Food -> Cheat internal back was verified |

## Automated Commands

- `:app:assembleDebug`
- `:app:testDebugUnitTest`
- `:app:connectedDebugAndroidTest` for Room migration/persistence only when a device/emulator is available.

No GitHub Actions workflow is started automatically by this document.
