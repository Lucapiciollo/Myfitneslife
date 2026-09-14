# V1 Test Traceability

Status values are `PASS`, `STATIC REVIEW ONLY`, `NOT RUN`, and `FAIL`. `PASS` means an executable test or recorded device run exists for the specific behavior, not merely for a related contract.

| Feature | Unit | Integration | E2E | UI | Error | Persistence | Export |
|---|---|---|---|---|---|---|---|
| Profile and calculations | PASS | PASS | NOT RUN | STATIC REVIEW ONLY | PASS | PASS | PASS |
| BIA history | PASS | PASS | NOT RUN | NOT RUN | STATIC REVIEW ONLY | PASS | PASS |
| Body measurements | PASS | PASS | NOT RUN | NOT RUN | STATIC REVIEW ONLY | PASS | PASS |
| Graph ranges/trends | PASS | STATIC REVIEW ONLY | NOT RUN | NOT RUN | PASS | STATIC REVIEW ONLY | STATIC REVIEW ONLY |
| NORMAL/SPORT classifier | PASS | PASS | NOT RUN | NOT RUN | PASS | PASS | STATIC REVIEW ONLY |
| Workouts | PASS | PASS | NOT RUN | NOT RUN | STATIC REVIEW ONLY | PASS | PASS |
| Nutrition targets | PASS | STATIC REVIEW ONLY | NOT RUN | NOT RUN | PASS | PASS | PASS |
| Weekly diet structure | PASS | PASS | PASS (fake runtime) | NOT RUN | PASS | PASS | PASS |
| Whey/protein powder | PASS | PASS | NOT RUN | NOT RUN | PASS | PASS | PASS |
| Creatine | PASS | PASS | NOT RUN | NOT RUN | PASS | PASS | PASS |
| Hydration note | PASS | PASS | NOT RUN | NOT RUN | PASS | PASS | PASS |
| Meal swap | PASS | PASS | PASS (fake runtime) | NOT RUN | PASS | PASS | PASS |
| Chiedi all'IA | PASS | PASS (fake runtime) | PASS (fake runtime) | NOT RUN | PASS | NOT RUN | NOT RUN |
| Text cheat two-phase flow | PASS (contract) | PASS (fake runtime) | PASS (fake runtime) | NOT RUN | PASS | PASS (entity) | PASS |
| Photo cheat flow | PASS (processor) | PASS (temp lifecycle) | NOT RUN (OS camera/gallery) | NOT RUN | PASS (unreadable image) | PASS (no image persistence) | PASS (no image export) |
| Future-only adaptation | PASS (contract only) | PASS (fake runtime no-adaptation path) | PASS (fake runtime) | NOT RUN | PASS | PASS | PASS |
| Plan versioning | PASS | PASS | NOT RUN | NOT RUN | PASS | PASS | PASS |
| Shopping list | PASS | STATIC REVIEW ONLY | NOT RUN | NOT RUN | STATIC REVIEW ONLY | PASS | PASS |
| Notifications | PASS (scheduler rules) | PASS (fake alarm gateway) | NOT RUN (OS delivery) | NOT RUN | STATIC REVIEW ONLY | PASS (settings/request codes) | NOT RUN |
| Weekly review | PASS (contract) | PASS (fake runtime) | PASS (fake runtime) | NOT RUN | PASS | PASS | PASS |
| JSON/CSV/PDF export | STATIC REVIEW ONLY | PASS | NOT RUN | NOT RUN | STATIC REVIEW ONLY | PASS | PASS |
| Multi-profile isolation | PASS | PASS | PASS | NOT RUN | PASS | PASS | PASS |
| DB migrations | NOT RUN | PASS (`3 -> 4`) | NOT RUN | NOT RUN | STATIC REVIEW ONLY | PASS | STATIC REVIEW ONLY |
| Restart/persistence | STATIC REVIEW ONLY | PASS | NOT RUN | NOT RUN | NOT RUN | PASS | PASS |
| Bottom tab navigation | NOT RUN | NOT RUN | PASS (UIAutomator device E2E) | PASS (UIAutomator device E2E) | PASS (Back E2E) | STATIC REVIEW ONLY | NOT RUN |
| Performance/stress | PASS (device timings) | PASS (stress fixture) | NOT RUN | NOT RUN | STATIC REVIEW ONLY | PASS | PASS (export timings) |

## Known Traceability Holes

- No real Gemini/OpenAI provider run with a configured credential; service integration uses a provider-neutral fake runtime and canonical JSON contracts.
- No executable Activity/UI test suite; bottom tabs have manual device evidence only.
- No OS camera/gallery picker runtime test; label-photo processor and temp-file lifecycle are covered.
- No Android alarm delivery/notification tap test.
- No screenshot/golden/pixel comparison.
- No 1Y physical chart rendering test or landscape/small/large-screen visual test.
- No memory profile or repeated/warm-cache stress run; the required one-pass large dataset timing run is covered.
- Only migration `3 -> 4` has schema assets and executable coverage; older migrations are not claimed as covered.
