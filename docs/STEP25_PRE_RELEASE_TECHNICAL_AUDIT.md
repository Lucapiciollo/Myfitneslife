# Step 25 — Pre-release technical audit

## Scope
Static technical audit before the first full build/device verification. This step does **not** claim that the application compiles or runs: GitHub Actions are intentionally not triggered because of the current budget/quota constraint.

## Fixes applied

### Room migration contract
`MIGRATION_1_2` adds `createdAtEpochMillis DEFAULT 0` and the new `profileId DEFAULT 1` columns. Room entities now declare the same SQL defaults through `@ColumnInfo(defaultValue=...)`, avoiding an entity/migrated-schema default-value mismatch during Room migration validation.

No destructive migration fallback is enabled.

### Android manifest / receivers
The reschedule receiver remains available for system broadcasts (`BOOT_COMPLETED`, package replace, time and timezone changes) but is now `exported=false`. This avoids exposing the receiver to arbitrary external applications while retaining the system-broadcast use case.

### Workout calendar consistency
Creating an entry from the workout weekly calendar now passes the selected calendar day into `NewWorkoutActivity` instead of silently defaulting to today.

The Material date picker is interpreted as a UTC calendar date (the convention used by `MaterialDatePicker`) and only converted to the device timezone when the final workout timestamp is created. This avoids day shifts around timezone offsets.

## Static checks reviewed
- Java/Kotlin target: Java 17 / JVM 17.
- compileSdk / targetSdk: 36; minSdk: 26.
- Room: 2.7.2, schema export enabled.
- No `fallbackToDestructiveMigration`.
- CI still only runs for pull requests or explicit `workflow_dispatch`; pushes to `develop` do not consume build budget.
- Application class is declared and schedules notification refresh without requiring an Activity.
- Background reminder receivers are registered in the manifest.
- `POST_NOTIFICATIONS` and `RECEIVE_BOOT_COMPLETED` are declared.
- `FileProvider` is non-exported and export files stay under app cache.
- Secure AI key stores are not part of profile export.

## Items that require executable verification
These cannot be truthfully marked as passed until a build/emulator/device run is performed:

1. `:app:assembleDebug` and unit-test compilation.
2. Android instrumented tests.
3. Room schema generation for v2.
4. Real v1 -> v2 migration against a genuine v1 database artifact. The repository currently does not contain the exported v1 Room schema JSON, so `MigrationTestHelper` cannot yet reproduce a canonical v1 database from schema history.
5. Fresh install and upgrade install.
6. Notification delivery with app process dead, Doze, reboot, time/timezone change and Android 13+ permission states.
7. Keystore save/read/delete on physical Android.
8. OpenAI/Gemini real network calls and structured-output compatibility.
9. File sharing to external apps for JSON/ZIP/PDF.
10. Pixel-level UI comparison with the approved screen references.

## Release gate
Do not merge to release/master or describe the app as release-ready until the executable verification above has been completed. The next step should be the first full local/CI build and test pass once build capacity is available, followed by targeted fixes from actual compiler/runtime output.
