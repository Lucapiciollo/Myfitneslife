# Contratto MFP2: piano alimentare con macro calcolati dall'app

Stato: IMPLEMENTATO dietro interruttore locale (default spento), lotto MACRO L3. Il contratto MFP1 (`myfitai_weekly_nutrition_pipe_v1`) resta invariato e continua a essere quello di default. Riferimento di progetto: `docs/PROGETTO_MACRO_DA_DATABASE_2026-11-14.md`.

## Regola
L'IA sceglie SOLO alimenti e grammi. L'app calcola kcal, macro, fibre, sodio, totali del pasto e del giorno e le dosi pratiche dal catalogo alimenti in sola lettura (`assets/food/catalog_v1.json`). Il modello non emette mai numeri nutrizionali, totali, `displayDose`, unita' o `weightState`.

Vale in modo identico per Gemini e OpenAI: UNA sola costante di prompt di sistema (`FoodCatalogPlanPrompt.SYSTEM_PROMPT`) e UN solo contratto, con la stessa busta `{"data": "<record pipe>"}` di MFP1 (`schemaName` = `myfitai_weekly_nutrition_pipe_v2`, intestazione `MFP2`). Nessun cambio ai file di `06-ai/schemas`: la busta JSON e' identica a quella di MFP1.

## Record

```
MFP2
W|weekStartEpochDay
D|dateEpochDay
M|type|title|timeMinutes|preparation
I|foodId|grams
S|kind|foodId|grams|timeMinutes|notes
H|hydrationNote
V|1_or_0|notes
```

- Una `D` per ogni data richiesta, seguita da esattamente `MEALS_PER_DAY` record `M`; ogni `M` ha almeno un `I`.
- `foodId`: copiato dall'elenco `CAT` del messaggio utente. Vocabolario CHIUSO: un id non presente nel catalogo e' rifiutato.
- `grams`: numero positivo con il punto, senza unita'. Rifiutati zero, negativi, testo, virgola, esponente, unita' attaccata.
- `S`: `PROTEIN_POWDER` con un foodId del catalogo (categoria INTEGRATORI), oppure `CREATINE` con foodId `-` (nessun nutriente; solo con `SM=SPORT`). Gli integratori non sono scalabili dalla riparazione.
- `preparation`: testo SENZA quantita'. Rifiutate cifre seguite da unita' di misura o di dose (`200 g`, `2 cucchiai`, `1/2 bicchiere`, `600 kcal`...). Tempi e temperature restano ammessi ("10 minuti", "180 gradi").
- Condimenti e bevande calorici (olio, burro, salse, zucchero, miele, latte, succhi, vino...) NON si omettono: hanno il loro record `I` con foodId, come da `CONDIMENTS_BEVERAGES_RULES.md`. Gli alimenti senza calorie (acqua, te, spezie) possono mancare.

## Prompt utente: blocco `CAT`
Il messaggio utente contiene le stesse righe di contesto di MFP1 (W, E, T, TD, REC, DP, ...) piu' il blocco di catalogo prodotto da `FoodCatalog.subsetFor` con i vincoli del profilo (allergie, intolleranze, esclusioni, stile alimentare) e il mese di inizio generazione (stagionalita'), ordinato per categoria e id (deterministico):

```
CAT:v1|<n>|id|name|state|kcal100|P|C|F|minG-maxG|stepG|units
#CEREALI
it.riso_basmati|Riso bianco (crudo)|R|365|7.1|80|0.7|30-130|5|
```

`state`: `R` peso a crudo/secco, `C` peso a cotto, `S` come venduto, `-` non applicabile. `units`: equivalenze pratiche note del catalogo (`nome=grammi`).

Le voci personalizzate dell'utente (`custom_foods`) NON sono ancora incluse: esiste solo la persistenza, senza tag di allergeni ne' limiti di porzione, quindi non possono passare il controllo per tag.

## Validazione e riparazione (app)
1. Parsing e rifiuti con motivi stabili (`PIPE_I_FOOD_UNKNOWN:<id>`, `PIPE_I_GRAMS_INVALID`, `PIPE_M_PREPARATION_HAS_QUANTITY`, `PIPE_MEAL_WITHOUT_INGREDIENTS`, `PIPE_DAY_MUST_HAVE_<n>_MEALS`, ...).
2. Allergeni/esclusioni per TAG del catalogo (`FOOD_CONSTRAINT_VIOLATION:<id,...>`), oltre al controllo per nome gia' esistente.
3. Riparazione per giorno (`DayRepair`): grammi riscalati entro 0,6x-1,5x per voce e +-40% kcal per pasto, nei limiti min/max del catalogo; priorita' kcal, poi proteine, grassi, carboidrati. Nessun pasto e' bloccato come "consumato" in questo flusso (la generazione parte da oggi e non conosce pasti consumati del periodo).
4. Se le kcal del giorno non sono raggiungibili con gli alimenti scelti, il rifiuto contiene l'intervallo raggiungibile per il retry: `TARGET_TOLERANCE_EXCEEDED:<epochDay>:target=<kcal>;actual=<kcal proposta>;reach=<min>-<max>|...`.
5. Il piano calcolato passa dal gate unico di MFP1 (struttura, date, vincoli per nome, soffitto di mantenimento).

Politica (decisione di prodotto): BLOCCANO solo le kcal calcolate: +-3% al primo tentativo, +-4% nei retry, e il riferimento registrato torna a +-3% per il piano successivo. Le proteine sotto target sono un obiettivo di riparazione non bloccante: avviso `PROTEIN_BELOW_TARGET` (mai tra gli errori). Carboidrati e grassi sono informativi. I piani gia' esistenti restano `DECLARED` (nessun ricalcolo).

## Persistenza
- Ingrediente: `name` = nome del catalogo (con "(crudo)"/"(cotto)" se il nome non lo dice), `quantity` = grammi (ml per i liquidi), `unit` = `g`/`ml`, `foodId`, `grams`, `kcal`, `proteinG`, `carbsG`, `fatG`, `fiberG`, `sodiumMg`, `displayDose` solo da equivalenze note del catalogo (altrimenti grammi).
- Pasto: macro = somma degli ingredienti, `nutritionSource = COMPUTED`. Giorno: somma dei pasti.
- Versione del piano: `catalogVersion` valorizzata; nuova versione, mai sovrascrittura.
- `appValidationJson` v2 con la chiave `computation`: sorgente, versione del catalogo, tentativo, tolleranza e, per giorno, stato della riparazione, kcal e proteine proposte/finali/target, fasce di scarto, passate, intervallo raggiungibile e le voci cambiate (`mealIndex|foodId|grammiOriginali|grammiFinali`, al massimo 300 per piano). Il lettore tollerante ignora i campi che non conosce.

## Interruttore
Preferenza locale `use_food_catalog_plan` (file `shared_prefs/ai_settings.xml`, `AiSettingsStore.useFoodCatalogPlan`), default `false`. Con `false` il comportamento e' quello di MFP1, invariato. Nessuna UI.
