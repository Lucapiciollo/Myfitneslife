# D1 - Prova di fattibilita: NutritionAdvice con JSON tipizzato nativo

Stato: **PoC non attivo** (flag `NutritionAdviceContractMode.DEFAULT = PIPE`). Nessuna rete, nessun dato reale, nessun
cambio di comportamento in produzione. Questo documento non segna nulla come completato in `DEVELOPMENT_PLAN.md`.
Decisioni di riferimento: `docs/PIANO_UNIFICATO_2026-11-12.md` (D3 cifre/riferimenti, D4 formato, B6 persistenza).

## 1. Che cosa e stato costruito

| Elemento | File |
|---|---|
| Schema JSON nativo, parser, business, prompt, richiesta, esecuzione `executeValidated`, flag `NutritionAdviceContractMode` | `android/app/src/main/java/com/myfitai/app/domain/food/NutritionAdviceJsonContract.kt` |
| Percorso JSON nel service (ramo `askTypedJson`, parametro `contractMode`, default PIPE) | `.../domain/food/NutritionAdviceService.kt` (+ `SYSTEM_PROMPT` reso `internal` per la misura) |
| Fixture condivise: lo stesso contenuto in 3 codifiche (pipe, JSON completo, JSON leggero) | `android/app/src/test/java/.../domain/food/NutritionAdviceFixtures.kt` |
| Test contratto, robustezza, executeValidated, portabilita dello schema | `.../NutritionAdviceJsonContractTest.kt` (21 test) |
| Misure caratteri/token e confronto errori pipe vs JSON | `.../NutritionAdviceFormatComparisonTest.kt` (5 test) |

Schema `myfitai_nutrition_advice_v2` (campi tutti `required`, `additionalProperties:false`):

```
{ inScope: boolean, answer: string,
  options: [ {title, reason, kcal:int 1..2500, proteinG, carbsG, fatG: number con limiti,
              ingredients: [ {name, quantity: number 0.1..2000, unit: enum g|ml|pz|cucchiaino|cucchiaio|bicchiere|fetta} ] 1..10 } ] 0..5,
  assumptions: string }
```

- Stesso modello di dominio del parser a pipe (`NutritionAdviceContract.Response`): `NutritionAdviceJsonContract.parse` produce un valore
  **uguale** a `NutritionAdviceCompactContract.parse` per ogni fixture (test `everyFixtureParsesToTheSameDomainModelFromPipeAndFromJson`;
  cambia solo `agentValidation`, che per questo agente non e mai letto: nel JSON non viene richiesto e vale `NOT_REQUESTED_JSON_V2`).
- Business riusata **senza modifiche**: `NutritionAdviceContract.validateBusiness` (struttura, lunghezze, esattamente 5 opzioni, `FoodConstraintValidator`
  sui nomi degli ingredienti). Il JSON ha sempre `ingredients` (minItems 1), quindi "FOODS_REQUIRED_FOR_CONSTRAINT_VALIDATION" non puo piu nascere da un'omissione.
- Quantita/unita sono validate dallo schema e dal parser ma **non conservate** nel dominio (il contratto non e persistito, `persisted=false`): in futuro
  possono alimentare UI o calcolo locale senza cambiare il contratto (utile per D3).
- Flag: `NutritionAdviceContractMode { PIPE, JSON }`, `DEFAULT = PIPE`; `NutritionAdviceService(..., contractMode = DEFAULT)`. Nessun chiamante lo imposta.
- **Non registrato** in `AiContractRegistry` (un test lo garantisce). Per attivarlo: aggiungere `NutritionAdviceJsonContract.SPEC` ad `AiContractRegistry.all`
  (una riga) e aggiornare in `AiContractRegistryTest` due asserzioni (`registryHasNoStaleOrDuplicateEntries` e il conteggio `expected.size + 1` di `outputTokenBudgets...`; le altre restano valide); senza la riga `AiRuntimeService` rifiuta il nome con `ContractNotRegistered` (nessuna chiamata a pagamento).

## 2. Misure (stesso contenuto, nessun tokenizer, nessuna rete)

Metodo: ogni `Sample` e codificato in tre modi (pipe nell'involucro `{"data":"..."}`, JSON completo con quantita/unita, JSON leggero con soli nomi, cioe
la stessa informazione della pipe). Il golden `golden_short` e esattamente la risposta `na1.valid` di `ai_golden_fixtures.json` (un test lo fissa);
`medium`, `long`, `xlong`, `out_of_scope` sono varianti nuove (rispettano i limiti del prompt: title<=45, reason<=70, answer<=120). Stima token = caratteri/3
(prudente) e caratteri/4. JSON minificato come lo emette `org.json`.

### Output

| Campione | Pipe car. (tok /3, /4) | JSON completo car. (tok /3, /4) | JSON leggero car. | completo/pipe | leggero/pipe |
|---|---|---|---|---|---|
| golden_short | 475 (159, 119) | 1190 (397, 298) | 812 | 2,51 | 1,71 |
| medium | 807 (269, 202) | 1894 (632, 474) | 1164 | 2,35 | 1,44 |
| long | 1262 (421, 316) | 2907 (969, 727) | 1649 | 2,30 | 1,31 |
| xlong (10 ingredienti/opzione) | 1440 (480, 360) | 3642 (1214, 911) | 1857 | 2,53 | 1,29 |
| out_of_scope | 133 (45, 34) | 132 (44, 33) | 132 | 0,99 | 0,99 |

Lettura: a parita di informazione (JSON leggero) il JSON costa +29..+71% in uscita; il resto del 2,3-2,5x e **contenuto in piu** (quantita e unita per
ogni ingrediente, che la pipe non trasporta). Il rifiuto fuori ambito costa uguale.

### Prompt (system + schema inviato + contesto di esempio di 402 car.)

| Voce | Pipe | JSON |
|---|---|---|
| System prompt (scope + regola lingua + regole agente) | 3553 | 3322 |
| Schema inviato a OpenAI | 228 | 1013 (leggero: 743) |
| Schema mappato per Gemini | 199 | 926 |
| Parte fissa (system + schema) car. (tok /3, /4) | 3781 (1261, 946) | 4335 (1445, 1084) |
| Totale con contesto | 4183 | 4737 (rapporto **1,13**) |

Il JSON toglie la regola `COMPACT` e il testo del protocollo pipe dal system prompt (-231 car.) ma lo schema tipizzato e +785 car.: saldo +13% in ingresso.

### Totale per chiamata (ingresso + uscita)

| Campione | Pipe car. (tok /4, /3) | JSON car. (tok /4, /3) | Rapporto | Delta token /4 |
|---|---|---|---|---|
| golden_short | 4658 (1165, 1553) | 5927 (1482, 1976) | 1,27 | +317 |
| medium | 4990 (1248, 1664) | 6631 (1658, 2211) | 1,33 | +410 |
| long | 5445 (1362, 1815) | 7644 (1911, 2548) | 1,40 | +549 |
| xlong | 5623 (1406, 1875) | 8379 (2095, 2793) | 1,49 | +689 |

**Sintesi: +27%..+49% di token per chiamata, +2,3..2,5x di token in uscita** (+179..+551 token /4 di sola uscita, che e la parte che
determina la latenza). Il budget di uscita va portato da 900 a 1500 (`MAX_OUTPUT_TOKENS`; il caso xlong stimato /3 e 1214).
Limite delle stime: `caratteri/N` sottostima il JSON, perche virgolette, parentesi e nomi di chiave si tokenizzano peggio del testo corrente; la misura
reale richiede `usage` di una chiamata per formato (`AiUsageMetadata` gia disponibile, vedi §6). Assoluto: stiamo parlando di ~300-700 token a chiamata.

## 3. Robustezza (test locali, nessuna rete)

Risposte JSON, con esito del validatore v2 + business (`NutritionAdviceJsonContractTest`):

| Caso | Esito |
|---|---|
| JSON valido (5 fixture) | VALID, stesso modello di dominio della pipe |
| Enum non ammesso (`unit:"q.b."`) | `SCHEMA:ENUM_MISMATCH @ $.options[0].ingredients[1].unit` (nessun valore del modello nell'errore) |
| Tipo sbagliato (`kcal:"450"`, `inScope:"true"`, `proteinG:"35"`) | `SCHEMA:TYPE_MISMATCH @ <percorso>` |
| Campo mancante (`fatG`, `assumptions`, `ingredients`, `answer` null) | `SCHEMA:MISSING_REQUIRED @ <percorso>` |
| Campo sconosciuto (`status`) | `SCHEMA:NOT_ALLOWED` |
| Fuori range (kcal 0, carbo -1, quantita 0), 0 ingredienti | `OUT_OF_RANGE` / `TOO_FEW_ITEMS` |
| 6 opzioni / 4 opzioni | schema `TOO_MANY_ITEMS` / business `EXPECTED_FIVE_SUGGESTIONS` |
| Troncato (cut o `MAX_TOKENS`) | `NOT_JSON_OBJECT` (-> `MALFORMED_JSON`) / `OUTPUT_TRUNCATED` (finishReason prevale) |
| Con code fence ```` ```json ```` | `NOT_JSON_OBJECT` (`MALFORMED_JSON`), non accettato |
| Testo **prima** del JSON | `NOT_JSON_OBJECT` |
| Testo **dopo** il JSON | **accettato**: `org.json` ignora il resto (JVM e Android); innocuo perche l'oggetto e validato per intero |
| Titolo > 70 car., assunzioni > 120, risposta vuota, nome ingrediente vuoto | business (`INVALID_SUGGESTION_TITLE`, ...) |
| Allergia/intolleranza (`yogurt greco` con lattosio) | business `FOOD_CONSTRAINT_VIOLATION` |
| Separatori nel testo (`|`, a capo, virgola in un ingrediente) | **contenuto normale**, preservato (`Pollo | riso`, `olio, extravergine`) |

Percorso reale `AiExecutionService.executeValidated` con provider simulato (contratto registrato solo nel test): valido a 1 chiamata; errore di schema -> 1 retry con
suggerimento JSON (non quello dell'involucro `data`) e poi successo; troncamento e JSON malformato mantengono le categorie `AiFailure`.

### Confronto con la tolleranza della pipe (16 classi di difetto, stesso contenuto)

| Difetto della pipe | Pipe | JSON tipizzato | Classe |
|---|---|---|---|
| `|` dentro un testo | rifiutata (`Failed requirement.`) | valido | impossibile |
| a capo dentro un testo | rifiutata | valido | impossibile |
| virgola dentro un nome di cibo | **accettata ma sbagliata** (3 cibi invece di 2) | valido, 1 ingrediente | impossibile |
| numero di campi errato | rifiutata (messaggio generico) | `MISSING_REQUIRED` | prevenuta dallo schema |
| campo in piu | rifiutata | `NOT_ALLOWED` | prevenuta |
| kcal non numerico | `NA_KCAL` | `TYPE_MISMATCH` | prevenuta |
| virgola decimale (`35,5`) | `NA_P` | `TYPE_MISMATCH` | prevenuta |
| flag ambito non 0/1 | `NA_SCOPE` | `TYPE_MISMATCH` (boolean) | prevenuta |
| record sconosciuto | `NA_RECORD_INVALID` | `NOT_ALLOWED` | prevenuta |
| header sbagliato / V mancante / record dopo V | rifiutata | non esiste | non applicabile |
| troncata a meta record / a fine riga | rifiutata | `NOT_JSON_OBJECT` | uguale |
| 4 opzioni invece di 5 | business | business | uguale |
| code fence | rifiutata | `NOT_JSON_OBJECT` | uguale |

**12 classi su 16 spariscono o diventano violazioni dello schema** (3 impossibili, 6 prevenute dalla modalita strict del provider e diagnosticate con
percorso+categoria, 3 non applicabili); 4 restano uguali (troncamento, conteggio, fence). In piu: la pipe produce `Failed requirement.` senza regola ne campo
(diagnostica debole), il JSON dice **dove** (`$.options[0].kcal`) e **perche** (categoria chiusa). Il caso grave della pipe e la virgola nel cibo: nessun errore, valore
diverso da quello inteso (con vincoli alimentari rischia di far passare/scartare il cibo sbagliato).

## 4. Come Gemini e OpenAI applicano uno schema nativo (letto dal codice, nessuna chiamata)

| Aspetto | Gemini (`GeminiByokProvider`) | OpenAI (`OpenAiProvider`, Responses API) |
|---|---|---|
| Parametri | `generationConfig.responseMimeType=application/json` + `responseSchema` da `GeminiSchemaMapper.map` + `maxOutputTokens`; `thinkingConfig` solo se `thinkingBudget != null` | `text.format = {type:"json_schema", name: schemaName, strict:true, schema}` + `max_output_tokens` |
| Keyword portate | `type` (maiuscolo), `description`, `enum`, `nullable`, `minimum/maximum`, `minItems/maxItems`, `items`, `required`, `properties`. **Scartate**: `additionalProperties`, `minLength/maxLength`, `pattern` | schema grezzo (`remoteSchemaJson` non usato); strict richiede **tutte** le proprieta `required` e `additionalProperties:false`; supporta `minimum/maximum`, `minItems/maxItems`, `enum`, `pattern`, `format` |
| Ordine dei campi | il mapper **non emette `propertyOrdering`**: l'ordine non e garantito | rispettato come nello schema |
| Rifiuti/troncamenti | `promptFeedback.blockReason`/`SAFETY` -> `Refused`; `MAX_TOKENS` -> `OutputTruncated` | parte `refusal` -> `Refused`; `incomplete` -> `OutputTruncated` |
| Stato per questo nome | supportato dal codice: `Spec.geminiNativeSchema=true` (default) -> `useNativeSchema` resta true | supportato: invia sempre lo schema |

Lo schema v2 e stato scritto per l'intersezione dei due provider (test `schemaFollowsTheOpenAiStrictRules`, `geminiMapperKeeps...`,
`openAiRequestWouldCarryTheSchemaAsStrictJsonSchemaUnderThisSchemaName`): niente `nullable`, `minLength/maxLength`, `pattern`, `$ref`, `oneOf/anyOf`.
Le lunghezze dei testi sono quindi imposte dalla business (la pipe aveva gia gli stessi limiti).

**Gia supportato dal gateway per `myfitai_nutrition_advice_v2`?** Il trasporto si (entrambi i provider, schema nativo, parita errori). **Non ancora**, e manca:

1. Riga in `AiContractRegistry` (+ aggiornamento di 2 asserzioni di `AiContractRegistryTest`): oggi il nome e rifiutato da `AiRuntimeService.prepare`.
2. `AiExecutionService`: per i contratti `NATIVE_JSON` un errore **business non viene ritentato** (il retry business c'e solo per `COMPACT_PIPE`); lo schema si, con suggerimento generico.
   Con il JSON le violazioni di business (lunghezze, 5 opzioni, vincoli alimentari) diventano fallimenti definitivi a prima risposta, a differenza della pipe. Da estendere a parte (file di un'altra tranche).
3. `thinkingBudget`: per i contratti non compatti `AiRuntimeService` lo inoltra cosi com'e e Gemini 3.5 Flash-Lite rifiuta `0`: il contratto imposta `null`
   (la pipe lo impostava a 0 ma il runtime lo azzerava perche compatta). Con `null` il ragionamento di Gemini puo consumare parte di `maxOutputTokens`: da misurare.
4. Gemini: `propertyOrdering` assente nel mapper (ordine non garantito) e profondita dello schema (6 livelli: radice -> options -> items -> ingredients -> items -> proprieta).
   Per `MA1` e `MFP1` c'e gia `geminiNativeSchema=false` perche Gemini 3.5 Flash-Lite ha rifiutato lo schema nativo: l'accettazione di uno schema annidato va verificata dal vivo.
5. OpenAI: la validita dello schema strict in produzione (primo uso, latenza di compilazione dello schema) e verificabile solo con una chiamata.
6. Verifica reale di token e tasso di errore: una chiamata per formato e provider con `AiUsageMetadata` (vietata qui: nessuna rete).

## 5. Vantaggi e svantaggi

Vantaggi: 12/16 difetti pipe impossibili o prevenuti; errori con percorso+categoria (niente `Failed requirement.`); testo libero con `|`, a capo, virgole senza
fragilita; enum/range/tipi controllati dal provider (strict/responseSchema) e di nuovo dal validatore locale; quantita+unita disponibili per un calcolo locale (D3) e per
il controllo dei vincoli; nessun involucro `data` ne regola `COMPACT`; stessi `executeValidated`/`ValidatedOutput` gia in uso, un solo modello di dominio.

Svantaggi: +2,3..2,5x di token in uscita (+29..+71% a parita di informazione), +13% in ingresso, +27..+49% per chiamata (stima), latenza di uscita maggiore;
schema piu grande da mantenere per due provider (intersezione delle keyword); retry business assente per i `NATIVE_JSON` (§4.2); un prompt JSON duplicato da tenere allineato
con quello pipe finche coesistono; D3 non risolta: kcal/macro restano numeri scritti dal modello (il JSON li tipizza ma non li deriva).

## 6. Raccomandazione

- **NutritionAdvice: non abbandonare ora la pipe come default.** Contratto breve, piatto, non persistito, con retry business gia funzionante; il JSON costa +27..+49% token e
  il guadagno di robustezza riguarda soprattutto difetti rari e gia ritentati. Tenere il percorso JSON pronto (flag spento, registro non toccato) e attivarlo solo se la misura
  dal vivo (una chiamata per formato e provider con `usage`, piu il tasso di `INVALID_SCHEMA`/`BUSINESS` della pipe in produzione) lo giustifica.
- **BIA e review (persistite, testo libero lungo, liste annidate, D4): si, passare al JSON tipizzato.** E li che la pipe e piu fragile (separatori e a capo nel testo libero,
  errori silenziosi come la virgola nel cibo, risultati salvati). L'infrastruttura necessaria e dimostrata: schema v2, `ValidatedOutput`, `executeValidated`, supporto di entrambi
  i provider. Ipotesi da verificare: dove domina la prosa il sovraccosto relativo delle chiavi si riduce (qui scende da 2,51 a 2,30 al crescere del testo, ma risale con molti elementi di lista).
  Prerequisiti: riga nel registro, retry business anche per `NATIVE_JSON`, `propertyOrdering` per Gemini, prova schema nativo su Gemini.
- Compatibilita: nessun dato persistito da NutritionAdvice, modello di dominio e payload del job handler invariati; chiamanti esistenti intatti (parametro con default).

## 7. Rischi

1. Costo/latenza reali diversi dalla stima caratteri (JSON si tokenizza peggio): misurare con `usage` prima di decidere.
2. Gemini potrebbe rifiutare lo schema annidato come gia accaduto per MA1/MFP1: il fallback `allowSchemaFallback` e opt-in e oggi spento per questo contratto.
3. Business non ritentata sui `NATIVE_JSON`: piu fallimenti utente-visibili finche `AiExecutionService` non e esteso.
4. Thinking di Gemini con `thinkingBudget=null` puo erodere `maxOutputTokens`: rischio `OutputTruncated` da verificare.
5. Doppio prompt (pipe/JSON) da mantenere allineato; un test non verifica l'equivalenza semantica dei due testi.
6. Il registro non contiene il nome: attivare il flag senza la riga produce `ContractNotRegistered` (fallimento sicuro, nessun costo).
7. Il testo dopo il JSON viene ignorato da `org.json`: innocuo ma e una tolleranza non dichiarata dal validatore.
