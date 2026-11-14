# Provider Groq (groq.com) - SPERIMENTALE, SPENTO

Stato: **sperimentale, spento di default** (Lotto 1: solo backend/runtime). Nessuna UI, nessuna chiamata reale verificata, nessuna chiave usata.
Groq e' il servizio di inferenza groq.com su modelli open: **non** e' xAI/Grok.
`AiSettingsStore.groqEnabled` vale `false`. `AiRuntimeConfig.selectedProvider()` restituisce `GROQ` solo se `groqEnabled` **e** la scelta esplicita (`selectedProvider`) e' `GROQ` **e** la chiave c'e'. La UI di scelta/chiave/flag e' il Lotto 2.

## Endpoint e protocollo (doc consultata il 14/11/2026)
- `POST https://api.groq.com/openai/v1/chat/completions` (compatibile OpenAI), header `Authorization: Bearer <chiave>` (le chiavi Groq iniziano con `gsk_`).
- Body: `model`, `messages` (`system` + `user`; con foto `content = [{type:"text"},{type:"image_url", image_url:{url:"data:<mime>;base64,..."}}]`), `response_format = {type:"json_schema", json_schema:{name, strict:true, schema}}`, `max_completion_tokens` (opzionale; include i token di reasoning), `reasoning_effort` (solo gpt-oss: `low`).
- Streaming e tool use **non** sono supportati con gli structured outputs: non vengono inviati.
- Risposta: `choices[0].message.content`, `choices[0].finish_reason`, `usage.prompt_tokens/completion_tokens/total_tokens` (+ `prompt_tokens_details.cached_tokens` sui modelli con prompt caching). `total = prompt + completion`: il reasoning e' dentro `completion_tokens`.
- Errori (stessa mappatura degli altri provider, `HttpJsonClient` + `AiFailure`): `finish_reason=length` -> Truncated, `content_filter` e `message.refusal` -> Refused, contenuto vuoto/null/assente -> EmptyOutput, `finish_reason` sconosciuto (es. `tool_calls`), oggetto `error` o body non JSON -> errore di protocollo; 401/403 -> Auth, 429 -> RateLimited/Quota, 5xx -> ProviderUnavailable, 400 `json_validate_failed` ("Generated JSON does not match the expected schema", solo in best-effort) -> errore di protocollo/validazione. Il testo del provider (incluso `failed_generation`) non entra mai in un'eccezione.

## Modelli (https://console.groq.com/docs/models, 14/11/2026)
Selezionabili: solo modelli con `strict:true` documentato. Prezzi USD per 1M token.

| Modello | Stato | Input | Immagini | Input / Output (cached) | Contesto / max output |
|---|---|---|---|---|---|
| `openai/gpt-oss-20b` (**default**) | production | testo | NO | 0.075 / 0.30 (0.037) | 131.072 / 65.536 |
| `openai/gpt-oss-120b` | production | testo | NO | 0.15 / 0.60 (0.075) | 131.072 / 65.536 |
| `qwen/qwen3.8-27b` | **preview** | testo + immagini (max 3, 20 MB) | **SI** | 0.80 / 4.00 (nessuna cache documentata: cached = input) | 131.072 / 16.384 |

Non selezionati: `meta-models/muse-spark-open` (strict ma solo Enterprise/"Contact Sales", anteprima limitata), `openai/gpt-oss-safeguard-20b` (solo best-effort), modelli Llama (solo JSON object mode). Il prompt caching (sconto 50% sugli input in cache, nessun costo extra) e' documentato solo per i gpt-oss.
Nota doc: nella tabella di confronto "Strict vs best-effort" compare "GPT-OSS 20B, 120B", ma l'elenco ufficiale dei modelli strict include anche `qwen/qwen3.8-27b` e `meta-models/muse-spark-open`: si e' seguito l'elenco.
`reasoning_effort`: gpt-oss `low|medium|high` (si invia `low`); qwen3.8-27b di default e' `none` (nulla da inviare).

## Immagini (import foto BIA, foto pasto)
Solo `qwen/qwen3.8-27b` accetta immagini. I gpt-oss (default incluso) sono **solo testo**: `GroqProvider.buildRequestBody` rifiuta in locale (nessuna rete, nessun costo) una richiesta con foto su un modello senza immagini, con `AiTransportException.Http(GROQ, 400, MODEL_UNAVAILABLE)` -> `AiFailure.ModelUnavailable`. `AiModelConfig.groqSupportsImages(model)` espone la capacita'.
Nel repository **non esiste** un meccanismo di capacita' per provider (`AiProviderAccess` controlla solo la configurazione): non ne e' stato inventato uno. I punti che inviano foto (`BiaImportService`, `CheatAdjustmentService`) oggi inviano al provider selezionato: con Groq+gpt-oss falliranno con "modello non accessibile". Il Lotto 2 deve nascondere/disabilitare le foto (o indicare il cambio modello) quando `selectedProvider == GROQ && !groqSupportsImages(modello)`.

## Limiti dello schema strict (doc "Structured outputs > Schema Requirements")
- Obbligatori: **tutte** le property in `required`; `additionalProperties:false` su ogni oggetto; i campi opzionali si esprimono con unione `["tipo","null"]`/`anyOf` con `null`.
- Supportati: primitivi, object, array, enum, `anyOf`, `$defs`/`$ref` (anche ricorsivi). Non e' elencata alcuna keyword di vincolo (`pattern`, `format`, `min*`/`max*`, `uniqueItems`...), ne' un limite di profondita'/dimensione: **la doc non dichiara limiti numerici** e nemmeno che schemi grandi non reggano. Non si puo' quindi affermare che il piano settimanale "non regga": va provato con chiave reale (Lotto 2).
- `GroqSchemaAdapter` (funzione pura in `GroqProvider.kt`, lavora sulla **copia** inviata): chiude ogni oggetto (`required` completo + `additionalProperties:false`), rende nullable (`anyOf[.., {type:null}]`) le property che erano opzionali, converte `const` in `enum` e `oneOf` in `anyOf`, rimuove per prudenza le keyword non documentate per strict (`$schema`, `deprecated`, `uniqueItems`, `pattern`, `format`, `min/maxLength`, `minimum/maximum/exclusiveMin/Max/multipleOf`, `min/maxItems`, `min/maxProperties`, `contains/minContains/maxContains`). `description` e i valori di `enum` restano. Il contratto canonico e la validazione locale (`CanonicalJsonSchemaValidator`, business validator, `appValidation`) restano invariati e restano l'autorita'.
- Tutti i contratti oggi registrati in `AiContractRegistry` sono la busta compatta `{data: string}` (`AiCompactEnvelope`), gia' strict-compatibile: l'adattatore non la modifica (testato). Per eventuali contratti nativi con campi opzionali (`weekly-meal-plan`, `plan-adjustment`) un campo opzionale potrebbe tornare `null` e il validatore canonico lo rifiuterebbe: da gestire se/quando quei contratti saranno attivati (riservati al contratto v2).

## Credenziali
`AiCredentialProvider.GROQ("groq")` usa lo stesso `SecureAiCredentialStore` (AES-GCM, Android Keystore) degli altri provider: solo ciphertext + IV, mai chiave in chiaro, log, eccezioni, BuildConfig, intent o backup.

## Selezione provider
`AiSettingsStore.groqEnabled` (default false), `selectedGroqModel` (default `openai/gpt-oss-20b`), `selectedProvider` accetta `GROQ` (migrazione in lettura invariata: senza scelta esplicita vale il booleano storico `useGemini`; scegliere GROQ non lo tocca). `AiRuntimeConfig(groqEnabled, groqSelected, groqConfigured)`: `GROQ` solo se enabled && selected && chiave presente; Groq scelto ma spento -> si comporta come il provider storico; abilitato+scelto senza chiave -> `NOT_CONFIGURED` (mai passaggio silenzioso ad altro provider). Se Grok e Groq risultassero entrambi attivi vince Grok (la scelta esplicita e' comunque un solo valore). `AiProviderStatus.from` e' coerente; `selectableProviders(grokEnabled, groqEnabled)` mostra Groq solo se acceso.

## Prezzi e costi
`GroqPricingStore` (prefs `groq_pricing`, prezzi manuali come Grok/OpenAI), `GroqCostCalculator` (cached = sottoinsieme dell'input; output fatturato come riportato, reasoning gia' incluso in `completion_tokens` e solo informativo), `GroqUsageTracker` (tabella Room esistente `ai_usage_records`, `provider = 'GROQ'`: nessuna migrazione). Prezzi sopra, consultati il 14/11/2026; `pricingEffectiveDate = 2026-11-14`.

## Cosa resta (Lotto 2 e oltre)
UI di selezione provider/inserimento chiave (`gsk_...`)/flag `groqEnabled`/selezione modello/binder costi; disabilitare le foto con modelli senza immagini; agenti/prompt con parita' JSON (`PROVIDER_CONTRACT.md`, `JSON_FORMAT_PARITY.md`); prove reali con chiave dell'utente (verifica strict su busta compatta, schemi grandi, eventuali 400 sulle keyword rimosse/mantenute, tetto `max_completion_tokens` con reasoning).
