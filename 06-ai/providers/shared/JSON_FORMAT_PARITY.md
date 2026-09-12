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
