# JSON Format Parity Contract — V8

## Regola vincolante
Gemini e OpenAI DEVONO restituire lo stesso formato JSON per la stessa operazione.
Il provider può cambiare il contenuto semantico (es. alimenti scelti), ma NON può cambiare:
- nomi dei campi;
- casing dei campi;
- struttura/nesting;
- tipi;
- enum;
- campi obbligatori;
- formato data/ora;
- unità ammesse;
- nomi delle operazioni;
- nomi degli stati.

## Fonte unica di verità
Gli schema in `06-ai/schemas/` sono il contratto canonico. I prompt provider-specifici NON definiscono formati alternativi.

Operazioni e schema:
- `GENERATE_WEEKLY_PLAN` -> `weekly-meal-plan.schema.json`
- `ADJUST_AFTER_DEVIATION` -> `plan-adjustment.schema.json`
- `REVIEW_PLAN` -> `plan-review.schema.json`
- `ANALYZE_PROGRESS` -> `progress-review.schema.json`

## Divieti provider
Entrambi i provider NON DEVONO:
- rinominare `proteinG` in `proteinGrams`;
- rinominare `carbsG` in `carbsGrams`;
- rinominare `fatG` in `fatGrams`;
- rinominare `title` in `dishName`;
- usare campi non presenti nello schema;
- omettere campi required;
- aggiungere markdown o testo esterno al JSON;
- usare valori enum non previsti.

## Validazione runtime
Ogni risposta segue:
`raw response -> JSON parse -> JSON Schema validation -> normalization forbidden -> business validation`

IMPORTANTE: il runtime NON deve "aggiustare" chiavi diverse provenienti dai provider. Se un provider restituisce un formato differente, la risposta è INVALID_SCHEMA e va ritentata con lo stesso schema canonico.

## Versionamento
Ogni payload deve essere compatibile con `schemaVersion: "1.0"` quando il relativo schema lo prevede. Le future modifiche incompatibili richiedono incremento di versione per entrambi i provider contemporaneamente.

## Parita errori e fixture
Stessa situazione => stessa categoria `AiFailure` per Gemini e OpenAI. La categoria e decisa dal codice comune
(`AiFailure.classify`, `AiFailure.fromFinishReason`, `AiTransportFailureClassifier`), mai dal testo del provider:

| Situazione | Gemini | OpenAI | Categoria (code) |
|---|---|---|---|
| stop normale | `finishReason=STOP` | `status=completed` | nessun errore |
| risposta troncata | `MAX_TOKENS` | `incomplete` / `max_output_tokens` | `OUTPUT_TRUNCATED` |
| rifiuto / sicurezza | `SAFETY`, `promptFeedback.blockReason` | parte `refusal`, `content_filter` | `REFUSED` |
| nessun testo | parti vuote o solo `thought` | `output_text` vuoto o solo reasoning | `EMPTY_OUTPUT` |
| corpo HTTP non JSON / stato sconosciuto | | | `PROVIDER_PROTOCOL` |
| testo del modello non JSON | | | `MALFORMED_JSON` |
| JSON non conforme allo schema | | | `INVALID_SCHEMA` (solo percorsi e categorie) |
| regola di business violata | | | `BUSINESS_VALIDATION_FAILED` (`ruleCode` stabile) |
| HTTP 429 quota/billing | | | `QUOTA_EXHAUSTED` |
| HTTP 429 rate limit | | | `RATE_LIMITED` |
| HTTP 5xx | | | `PROVIDER_UNAVAILABLE` |
| HTTP 401/403 | | | `AUTH` |

Fixture golden condivise: `android/app/src/test/resources/ai_golden_fixtures.json` contiene, per i contratti registrati in
`AiContractRegistry` (NA1, BA2, BIA1, WR1, MFP1 minimo, MA1), risposte VALIDE e NON VALIDE (enum fuori elenco, tipo
sbagliato, campo o record mancante, JSON troncato, vuoto, refusal). Ogni risposta passa da
`AiExecutionService.executeValidated` (schema v2 + parse + regole di business) e deve chiudersi con la categoria attesa
(`AiContractGoldenFixturesTest`). Un nuovo contratto nel registro richiede nuove fixture nello stesso file.
La parita tra provider e verificata senza rete da `AiProviderErrorParityTest` (parser puri
`GeminiByokProvider.parseGenerateContent` e `OpenAiProvider.parseResponse`). Se un provider cambia una categoria, il test fallisce.