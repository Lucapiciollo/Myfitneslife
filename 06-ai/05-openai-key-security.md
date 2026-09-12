# OpenAI BYOK Key Security — IMPERATIVE

La chiave API OpenAI inserita dall'utente è un segreto.

Obblighi:
- Android Keystore come root of trust.
- Chiave AES non esportabile.
- Cifratura AES-256-GCM del valore OpenAI.
- Persistenza soltanto di ciphertext, IV e metadata necessari.
- Mai salvare raw key in Room, normali SharedPreferences, file, log, crash report, analytics, telemetria, export, backup, repository, BuildConfig, Intent o navigation arguments.
- `android:allowBackup=false` non deve essere indebolito.
- UI credenziali protetta con `FLAG_SECURE`.
- Supportare replace e delete della chiave.
- Svuotare il campo UI dopo il salvataggio.
- Nessun agente o sviluppatore può derogare a questa regola.
