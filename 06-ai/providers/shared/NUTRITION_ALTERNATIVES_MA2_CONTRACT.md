# Contratto MA2: alternative pasto con macro calcolati dall'app

Stato: IMPLEMENTATO dietro lo stesso interruttore di MFP2 (`use_food_catalog_plan`, default spento), lotto MACRO L5a. Il contratto MA1 (`myfitai_meal_alternatives_pipe_v1`, il modello scrive kcal e macro) resta invariato ed e' l'unico usato per i pasti `DECLARED` o con l'interruttore spento. Riferimento: `NUTRITION_PLAN_MFP2_CONTRACT.md` e `docs/PROGETTO_MACRO_DA_DATABASE_2026-11-14.md`.

## Quando si usa
Solo se il pasto da sostituire e' `COMPUTED` (macro calcolati dal catalogo) E `use_food_catalog_plan` e' acceso E il catalogo e' disponibile. Altrimenti flusso MA1, identico a prima.

## Regola
Come in MFP2: l'IA sceglie SOLO alimenti e grammi, l'app calcola kcal, macro, totali e dosi. Il modello non scrive kcal, macro, totali, `displayDose`, unita' o `weightState`. Vale in modo identico per Gemini e OpenAI.

## Trasporto: MFP2 ridotto (nessun nuovo schema)
Stessa busta `{"data": "<record pipe>"}`, stesso `schemaName` (`myfitai_weekly_nutrition_pipe_v2`), stessa intestazione `MFP2`, stesso parser (`FoodCatalogPlanContract`). Nessun file di `06-ai/schemas` e nessuna voce di `AiContractRegistry` cambiano. Prompt di sistema dedicato (`MealAlternativeCatalogEngine.SYSTEM_PROMPT`), stesso blocco `CAT` del piano (filtrato dai vincoli del profilo e dal mese del giorno).

```
MFP2
W|weekStartEpochDay            (copiato dal messaggio utente)
D|dateEpochDay                 (copiato dal messaggio utente)
M|type|title|timeMinutes|preparation   (esattamente 5, una per alternativa; ognuna con i suoi I)
I|foodId|grams
V|1_or_0|notes
```

- Un solo giorno, nessun record `S` (`MA2_SUPPLEMENT_NOT_ALLOWED`) ne' `H`. `W`/`D` diversi da quelli richiesti: `MA2_WEEK_MISMATCH` / `MA2_DAY_MISMATCH`.
- `type` e `timeMinutes` dei record `M` non contano: l'app usa quelli del pasto sostituito.
- `title` e `preparation` senza quantita' (regola MFP2). Il titolo e' reso coerente con gli ingredienti (`coherentTitle`).
- Messaggio utente: `WEEK`, `DAY`, `MEAL_TYPE`, `MEAL_TIME_MINUTES`, `CURRENT` (titolo e totali del pasto attuale), `CURRENT_FOODS` (`foodId:grammi`), `TARGET_KCAL`, `DP`, poi il blocco `CAT`. Nei messaggi di retry il numero davanti a `target=` e' il numero dell'alternativa (1-5).

## Validazione e riparazione (app)
Per ogni alternativa si riusa `FoodCatalogPlanBuilder.build` come "giorno con un solo pasto" il cui target e' il pasto originale (kcal e, come obiettivo non bloccante, proteine/carboidrati/grassi):
1. Alimento ignoto: rifiuto dell'intero tentativo (`PIPE_I_FOOD_UNKNOWN:<id>`).
2. Allergeni/esclusioni per TAG del catalogo e per nome: rifiuto dell'intero tentativo (`FOOD_CONSTRAINT_VIOLATION:<id,...>`).
3. Riparazione dei grammi (`DayRepair`, 0,6x-1,5x per voce, +-40% kcal per pasto, limiti min/max del catalogo).
4. Accettazione: |kcal calcolate - kcal del pasto originale| <= 3% al primo tentativo, 4% ai retry; a ogni nuova generazione si riparte da 3%. Un'alternativa non riparabile entro la tolleranza (o uguale al pasto attuale, o con titolo duplicato) viene scartata; il tentativo e' rifiutato se ne restano meno di 3 (`TARGET_TOLERANCE_EXCEEDED:<n>:target=<kcal>;actual=<kcal proposta>;reach=<min>-<max>|...`, con le sole alternative fuori tolleranza).
5. Proteine sotto target: avviso non bloccante, mostrato nel testo dell'alternativa (`reason`: "Proteine X g contro Y g del pasto attuale."). Carboidrati e grassi sono informativi.

## Applicazione
L'alternativa mostrata porta solo `foodId` e `grams` per ingrediente e `catalogVersion` (payload compatto: i dati del job WorkManager hanno il limite di 10 KB). Alla conferma l'app RICOSTRUISCE il pasto da (alimento, grammi) con `FoodCatalogPlanBuilder.computedMeal` e riverifica: versione del catalogo, alimenti noti, TAG del profilo, kcal entro 4% dal pasto sostituito e numeri identici a quelli mostrati. Il pasto risultante e' `COMPUTED` con lo snapshot per ingrediente (`foodId`, `grams`, `kcal`, macro, fibre, sodio); la nuova versione del piano eredita `catalogVersion`. Nuova versione del piano, mai sovrascrittura.

## Cronologia (nessuna IA)
Un'alternativa dalla cronologia `COMPUTED` con snapshot completo resta `COMPUTED`: lo snapshot degli ingredienti viene copiato dal mapper unico (`PlanMappers`) e i totali del pasto sono la somma dello snapshot. Un'alternativa `DECLARED` (o con snapshot incompleto) resta `DECLARED` con i totali dichiarati, come prima.
