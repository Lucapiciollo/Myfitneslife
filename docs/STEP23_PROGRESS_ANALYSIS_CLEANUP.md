# Step 23 — Progress / Analysis cleanup

## Goal
Remove residual demo values from the Progress and Analysis screens and render only data that exists for the active profile.

## Physical evolution
- Source: Room BIA history for the active profile.
- Metrics: weight, body-fat percentage, muscle mass and body water.
- Ranges: 1M, 3M, 6M, 1Y, anchored to the latest real measurement.
- Current value and delta are computed from available values only; missing fields are never converted to zero.
- Chart and date labels are built from the filtered real series.
- The old fake body-photo comparison is hidden because the app does not yet persist a dated body-photo history.

## Analysis
- Source: `PersonalResponseService`.
- Ranges: 7, 30 and 90 days.
- Displays real workout/rest/deviation counts, available body deltas and evidence-backed patterns.
- Does not invent adherence.
- Text explicitly treats correlations as observations, never causality or diagnosis.
- Weekly Review remains the explicit AI review path.

## Final QA
Build/runtime verification is intentionally deferred to the final control because GitHub Actions budget is currently unavailable.
