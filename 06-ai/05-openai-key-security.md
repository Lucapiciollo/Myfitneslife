# AI provider API key security — IMPERATIVE

This requirement is non-negotiable for MyFitAI.

## Storage
- Never persist Gemini or OpenAI API keys in plaintext.
- Android Keystore is the root of trust.
- Use a non-exportable AES-256 key generated in `AndroidKeyStore`.
- Persist only AES-GCM ciphertext and its IV in app-private storage.
- Never store the API key in Room, DataStore/plain preferences, ordinary SharedPreferences, files, resources, BuildConfig, source code, Gradle properties, or repository secrets intended for the APK.

## Runtime
- Load the API key only just-in-time for an OpenAI request.
- Do not place the key in `AiRuntimeConfig`, navigation arguments, intents, bundles, models, analytics events, exception messages or debug UI.
- Do not cache it longer than needed by the transport call.
- Do not print/log request headers containing credentials.

## Backup / screen / telemetry
- Android application backups MUST be disabled for V1 (`allowBackup=false`).
- Key material/ciphertext must never be exported by MyFitAI data export.
- Credential-editing UI uses `FLAG_SECURE` to prevent screenshots/screen recording.
- Never send API keys to analytics, crash reporting, diagnostics or remote logging.

## Lifecycle
- User can replace the key by securely saving a new value.
- User can remove the saved credential from Settings.
- If ciphertext cannot be decrypted, treat the provider as not configured; never expose crypto details or the credential.

## Current implementation
`android/app/src/main/java/com/myfitai/app/security/SecureAiCredentialStore.kt`
implements AES/GCM using a non-exportable Android Keystore AES key for both providers.

Any agent or developer changing credential handling MUST preserve these guarantees.
