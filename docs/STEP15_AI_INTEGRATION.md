# Step 15 — Integrazione IA reale

Stato: IMPLEMENTATO LATO CODICE — build/device/network test finale pending.

## Pipeline
`App -> AiRuntimeService -> AiProvider -> provider API -> canonical JSON -> CanonicalJsonSchemaValidator -> businessValidator -> caller`

## Provider
- OpenAI: Responses API, Structured Outputs con JSON Schema, `store=false`.
- Gemini: Gemini Developer API REST diretta con output JSON strutturato e lo stesso schema canonico.
- Nessuna trasformazione semantica provider-specific dell'output.

## Credenziali
- OpenAI e Gemini sono BYOK. Le chiavi sono cifrate AES-GCM con chiave AES non esportabile in Android Keystore.
- Le chiavi vengono lette just-in-time dentro il provider e non vengono mantenute come proprietà del provider.
- Le chiavi non vengono loggate, esportate o incluse nel body di errori applicativi.
- La preferenza provider è separata dalle credenziali e contiene solo un booleano non segreto.

## Validazione
- JSON non valido o non conforme allo schema viene rifiutato come `INVALID_SCHEMA`.
- Retry schema massimo configurabile (default 1).
- Dopo la validazione schema viene sempre invocato il business validator dell'app.
- Il business validator è autorevole; eventuali campi `agentValidation` sono solo informativi.
- Nessuna correzione o normalizzazione silenziosa di un output non valido.

## QA residuo Step 23
- build debug/release;
- unit test;
- chiamata reale OpenAI con chiave BYOK;
- chiamata reale Gemini Developer API con key BYOK;
- gestione 401/403/429/5xx e timeout su device;
- verifica rete assente e rotazione processo/activity.
