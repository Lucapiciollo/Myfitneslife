# Provider Grok (xAI) — SPERIMENTALE, SPENTO

Stato: **sperimentale, spento di default** (Lotto 1, solo backend). Nessuna UI, nessun selettore provider, nessuna chiamata reale verificata.
`AiRuntimeConfig.selectedProvider()` non restituisce mai `GROK`; `AiSettingsStore.grokEnabled` vale `false`. Il collegamento a UI e selettore è il Lotto 2.

## Endpoint e protocollo (doc consultata il 13/11/2026)
- `POST https://api.x.ai/v1/responses` (Responses API compatibile OpenAI), header `Authorization: Bearer <chiave>`.
- Body: `model`, `instructions`, `input` (stringa oppure `[{role:"user", content:[{type:"input_text"},{type:"input_image", image_url:"data:<mime>;base64,..."}]}]`), `store:false`, `max_output_tokens` (opzionale), `text.format = {type:"json_schema", name, strict:true, schema}`.
- Immagini: jpg/jpeg/png, max 20 MiB; con immagini la doc raccomanda di NON salvare la cronologia lato server → `store:false` sempre.
- `max_output_tokens` limita solo l'output visibile, non i token di reasoning.
- Risposta: `status` (`completed`/`incomplete`/`in_progress`), `output[]` con item `reasoning` (senza `content`) e `message` con parti `output_text`; `usage.input_tokens/output_tokens/total_tokens`, `input_tokens_details.cached_tokens`, `output_tokens_details.reasoning_tokens`. Nell'esempio della doc `total = input + output + reasoning`: il reasoning è **in aggiunta** a `output_tokens` (da tenere presente per i prezzi nel Lotto 2). Il parser accetta anche i nomi stile chat (`prompt_tokens`/`completion_tokens`).
- Errori: stessa mappatura degli altri provider (`HttpJsonClient` + `AiFailure`): `incomplete`/`max_output_tokens` → Truncated, `content_filter` e parte `refusal` → Refused, output vuoto → EmptyOutput, 401/403 → Auth, 429 → RateLimited/Quota, 5xx → ProviderUnavailable. Il testo del provider non entra mai nelle eccezioni.

## Modelli (https://docs.x.ai/developers/models, 13/11/2026)
Selezionabili (solo modelli con "Structured outputs: Yes" e "text, image → text"):

| Modello | Contesto | Input / Output per 1M token (<200k prompt) | Reasoning (default) |
|---|---|---|---|
| `grok-4.3` (**default**, alias `grok-4.3-latest`) | 1M | $1.25 / $2.50 (cached $0.20) | none/low/medium/high/xhigh (low) |
| `grok-4.7` | 500k | $2.00 / $6.00 (cached $0.50) | low/medium/high/xhigh (high) |

Sopra 200k token di prompt la tariffa raddoppia. Il reasoning effort non viene impostato (default del modello): da valutare nel Lotto 2 per i costi di `grok-4.7` (default `high`).

## Limiti dello schema (doc "Structured outputs")
xAI risponde 400 a: `enum`/`anyOf` vuoti, proprietà con schema `true`/`false`, `maxContains`/`minContains`, `items` come array (usare `prefixItems`). `$ref`/`$defs` solo non circolari; `additionalProperties` è `false` di default. Vincoli garantiti solo entro soglie (`minLength/maxLength` 2048, `minItems/maxItems` 256, `minProperties/maxProperties` 64); `not`, `if/then/else`, `allOf` multiplo e `format` non elencati sono best-effort.

Gli schemi di `06-ai/schemas/` usano solo keyword accettate, tranne `$schema`, `deprecated` e `uniqueItems` (non elencate dalla doc). `GrokSchemaAdapter` (funzione pura in `GrokProvider.kt`) lavora su una **copia** inviata a xAI: rimuove le keyword rifiutate/non documentate, converte `items` array in `prefixItems`, elimina `enum`/`anyOf`/`oneOf` vuoti e gestisce proprietà booleane. Il contratto canonico e la validazione locale (`CanonicalJsonSchemaValidator`, business validator, `appValidation`) restano invariati e restano l'autorità.

## Credenziali
`AiCredentialProvider.GROK("grok")` usa lo stesso `SecureAiCredentialStore` (AES-GCM, Android Keystore) di Gemini/OpenAI: solo ciphertext + IV, mai chiave in chiaro, log, eccezioni, BuildConfig o backup.

## Cosa resta (Lotto 2)
UI di selezione/inserimento chiave e flag `grokEnabled`, selettore provider a tre vie, usage tracker e prezzi, agenti/prompt Grok con parità JSON (`PROVIDER_CONTRACT.md`, `JSON_FORMAT_PARITY.md`), prove reali con chiave dell'utente.
