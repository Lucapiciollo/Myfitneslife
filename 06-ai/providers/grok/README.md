# Provider Grok (xAI) — SPERIMENTALE, SPENTO

Stato: **sperimentale, spento di default** (Lotto 1 backend + Lotto 2a runtime/dati). Nessuna UI, nessuna chiamata reale verificata.
`AiSettingsStore.grokEnabled` vale `false`. `AiRuntimeConfig.selectedProvider()` restituisce `GROK` solo se `grokEnabled` **e** la scelta esplicita e' `GROK` **e** la chiave c'e' (vedi "Selezione provider"); la UI di scelta/chiave/flag e' il Lotto 2b.

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

Sopra 200k token di prompt la tariffa raddoppia. Il reasoning effort e' impostato a `low` (vedi "Reasoning effort").

## Limiti dello schema (doc "Structured outputs")
xAI risponde 400 a: `enum`/`anyOf` vuoti, proprietà con schema `true`/`false`, `maxContains`/`minContains`, `items` come array (usare `prefixItems`). `$ref`/`$defs` solo non circolari; `additionalProperties` è `false` di default. Vincoli garantiti solo entro soglie (`minLength/maxLength` 2048, `minItems/maxItems` 256, `minProperties/maxProperties` 64); `not`, `if/then/else`, `allOf` multiplo e `format` non elencati sono best-effort.

Gli schemi di `06-ai/schemas/` usano solo keyword accettate, tranne `$schema`, `deprecated` e `uniqueItems` (non elencate dalla doc). `GrokSchemaAdapter` (funzione pura in `GrokProvider.kt`) lavora su una **copia** inviata a xAI: rimuove le keyword rifiutate/non documentate, converte `items` array in `prefixItems`, elimina `enum`/`anyOf`/`oneOf` vuoti e gestisce proprietà booleane. Il contratto canonico e la validazione locale (`CanonicalJsonSchemaValidator`, business validator, `appValidation`) restano invariati e restano l'autorità.

## Credenziali
`AiCredentialProvider.GROK("grok")` usa lo stesso `SecureAiCredentialStore` (AES-GCM, Android Keystore) di Gemini/OpenAI: solo ciphertext + IV, mai chiave in chiaro, log, eccezioni, BuildConfig o backup.

## Selezione provider (Lotto 2a)
- `AiSettingsStore.selectedProvider` (`GEMINI`/`OPENAI`/`GROK`, chiave `selected_provider`). Migrazione in lettura: se non scritta, deriva dal booleano storico `useGemini` (true -> GEMINI, false -> OPENAI): le installazioni esistenti continuano a usare cio' che usano oggi, nessun default cambiato.
- Scegliere GEMINI/OPENAI aggiorna anche `useGemini`; scegliere GROK **non** lo tocca (resta il "provider precedente" di fallback). Una scrittura legacy di `useGemini` cancella la scelta esplicita (compatibilita' con la UI attuale).
- `AiRuntimeConfig(grokEnabled, grokSelected, grokConfigured)`: `GROK` solo se enabled && selected && chiave presente. Scelta Grok ma `grokEnabled=false` -> si comporta come il provider storico (`useGemini`). Grok abilitato e scelto ma senza chiave -> `NOT_CONFIGURED` (mai passaggio silenzioso ad altro provider). `AiProviderStatus` e' coerente (selected=GROK, alternativa = provider storico con chiave).

## Prezzi e costi (Lotto 2a)
`GrokPricingStore` (prefs `grok_pricing`, prezzi manuali come OpenAI), `GrokCostCalculator`, `GrokUsageTracker` (tabella Room esistente `ai_usage_records`, campo testuale `provider = 'GROK'`: nessuna migrazione).
- Listino sotto 200k token di prompt per 1M token (docs.x.ai 13/11/2026): grok-4.3 input 1.25 / cached 0.20 / output 2.50; grok-4.7 input 2.00 / cached 0.50 / output 6.00.
- Con prompt >= 200k token la doc dice che **tutti** i token della richiesta sono fatturati alla tariffa piu' alta (input, cached e output x2): il calcolatore applica x2 a tutte le tariffe (anche se manuali, che sono le tariffe base) e il record riporta `pricingSource = ...+LONG_CONTEXT_X2`. Nel record si salvano le tariffe base.
- Cached = sottoinsieme di input. Reasoning fatturato come output ma tenuto separato (`thoughtsTokens`). xAI lo riporta in aggiunta a `output_tokens`: si somma al billable output, tranne se `total_tokens == input + output` con reasoning > 0 (gia' incluso: non si fattura due volte).

## Reasoning effort (Lotto 2a)
Doc (models/grok-4.3, models/grok-4.7, model-capabilities/text/reasoning): Responses API `reasoning: {"effort": ...}`; grok-4.7 `low|medium|high|xhigh` (default high), grok-4.3 anche `none` (default low). Il reasoning non si puo' disattivare su 4.7. `GrokProvider.buildRequestBody(model, request, reasoningEffort = "low")` invia `reasoning.effort = "low"` (`null` lo omette; valore non documentato -> `IllegalArgumentException`). Nota: `max_output_tokens` non limita i token di reasoning (fatturati come output).

## Cosa resta (Lotto 2b e oltre)
UI di selezione provider/inserimento chiave/flag `grokEnabled` (onboarding, SettingsActivity, AiSettingsActivity, binder costi), agenti/prompt Grok con parita' JSON (`PROVIDER_CONTRACT.md`, `JSON_FORMAT_PARITY.md`), prove reali con chiave dell'utente.
