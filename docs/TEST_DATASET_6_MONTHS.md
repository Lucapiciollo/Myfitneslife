# SixMonthHistoryFixture

## Identity

- Name: `SixMonthHistoryFixture`.
- Seed: `20260914`.
- Reference date: `2026-09-14`.
- Timezone: `UTC`.
- Source: `android/app/src/testFixturesShared/java/com/myfitai/app/fixtures/SixMonthHistoryFixture.kt`.

The fixture is generated in memory from formulas and a fixed oscillation sequence. It has no random source, provider call, current-clock read, API key, or network dependency.

## Main Profile

| Field | Value |
|---|---|
| Name | `Test Sport` |
| Biological sex | `Maschio` |
| Birth date | `09/08/1983` |
| Height | `186 cm` |
| Initial weight | `94 kg` |
| Current weight | `89 kg` |
| Goal | `Ricomposizione` |
| Activity | `Moderatamente attivo` |
| Wake / sleep | `07:00` / `23:30` |

## Generated History

| Dataset | Quantity | Shape |
|---|---:|---|
| BIA | 13 | Approximately twice per month, weight/body fat/muscle/water and conditions |
| Body measurements | 13 | Approximately twice per month, waist and all major circumference fields |
| Workouts | More than 70 | Early rest/sedentary phase, later four non-rest workouts per week |
| Weekly plans | 26 | One plan root per week, seven days per version |
| Plan versions | More than 26 | `AI_GENERATION`, `AI_MEAL_SWAP`, and `CHEAT_ADAPTATION` reasons |
| Cheats | 18 | Gelato, pizza, hamburger, dessert, aperitivo, packaged snack and ambiguous quantities |
| Weekly reviews | 26 | One deterministic review row per historical week |

## Expected Trends

- Weight moves from approximately `94 kg` to `89 kg` with small oscillations.
- Body fat moves from approximately `22%` to `18%`.
- Waist moves from approximately `96 cm` to `88.5 cm`.
- Muscle mass moves from approximately `68 kg` to `69.7 kg`.
- Early weeks classify as `NORMAL` with sedentary activity and no training sessions.
- Later weeks classify as `SPORT` with moderate activity and four non-rest workouts.
- Whey is available as an optional counted supplement throughout the plans.
- Creatine exists only in the later SPORT phase and has zero kcal/macros.
- Hydration notes are separate from meals and are present on deterministic days.

## Intentional Edge Coverage

- Non-perfect historical values with repeated oscillation patterns.
- Missing BIA BMR values, while local BMR is calculated by the app.
- Duplicate-date and missing/non-finite chart values are tested separately by `ProgressSeriesEngineTest`.
- Multiple immutable versions for selected weeks.
- Profile B has no historical rows and profile C is empty, both exercised in export isolation instrumentation.
