# V9 — Secure OpenAI credential storage

- Added the shared `SecureAiCredentialStore` based on Android Keystore + AES-256-GCM for Gemini and OpenAI BYOK.
- Removed raw OpenAI key from `AiRuntimeConfig`; runtime config now stores only whether a key is configured.
- Added secure Save/Remove actions to Settings.
- Credential input is cleared immediately after secure save.
- Added `FLAG_SECURE` on Settings while credentials are editable.
- Disabled Android application backups (`allowBackup=false`, `fullBackupContent=false`).
- Added imperative security documentation and agent handoff rule.
- API key remains excluded from exports, logs, telemetry and persistence outside the secure store.
