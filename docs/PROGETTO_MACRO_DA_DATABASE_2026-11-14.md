# Progetto 14/11/2026: macro calcolati da database alimentare (architetto-software)

Stato: PROGETTO, nessuna modifica al codice. Fonti consultate il 14/11/2026 dall'architetto, non riverificate dall'orchestratore.

## Stato attuale (verificato dall'architetto, file:riga)
- Il contratto reale e il protocollo pipe MFP1 (NutritionPlanCompactContract.kt:33-41): il record M porta kcal/P/C/F DICHIARATI dal modello; il record I non ha macro. 06-ai/schemas sono documentazione con test di retro-compatibilita.
- Il validatore giudica l'aritmetica del modello, non la verita (NutritionPlanAssessment.kt:15-18: nessuna tabella alimenti locale). Solo le kcal bloccano (+-3% primo tentativo, +-4% retry); i macro sono warning. AGENTS.md dice invece +-3% su kcal e macro: conflitto da decidere.
- Dipendenze dai macro dichiarati: alternative pasto (MealAlternativeContract.kt:43 impone kcal == target), sgarro (scaleMeal non scala i grammi: CheatAdjustmentService.kt:326), NA1/CU1, lista spesa (nome libero), export, review, UI.
- 8 siti copiano un ingrediente campo per campo (Repositories.kt, ProfileExportService.kt, CheatAdjustmentService.kt, MealAlternativeService/Activity): un campo nuovo dimenticato azzera il calcolo.

## Fonti dati
USDA FoodData Central: CC0, Foundation 10/2026 (CSV 4 MB zip) e SR Legacy 2018 (CSV 7 MB zip); nomi in inglese; ID nutrienti e cotto/crudo da verificare nello zip. CREA tabelle 2019: ~900 alimenti, Tabella C con rese cotto/crudo, ricette italiane, MA nessuna licenza aperta ne download: serve autorizzazione scritta (crea@crea.gov.it), nessun parere legale. Open Food Facts: ODbL share-alike, escluso dall'MVP. Ciqual/ANSES: Licence Ouverte, secondo ripiego. BDA/IEO e SINU: non aperti/non verificati.

## Design
Catalogo JSON in sola lettura (assets/food/catalog_v1.json, 350-500 voci, id stabili, alias italiani, per100 kcal/p/c/f/fibre/sodio/acqua, crudo/cotto con pairId e resa, unita pratiche, allergeni, min/max grammi). L'agente emette solo {foodId, grammi} (protocollo MFP2, nessun macro/totale/displayDose). Calcolo, dosi e spesa deterministici. Ciclo di riparazione per giorno: riscala i grammi con limiti [0,6x,1,5x] per voce e +-40% per pasto, priorita kcal poi proteine poi grassi poi carboidrati; se infeasible restituisce al modello l'intervallo kcal raggiungibile. Prova giocattolo: da -24% a +0,7% kcal. Room v22 additiva (colonne nullable su meal_ingredients, custom_foods), piani vecchi marcati 'stima del modello'. Alternative: accettazione |kcal-target|<=3% invece di ==. Sgarro: alimenti+grammi, adattamento via motore.

## Lotti
L0 decisioni/licenze; L1 catalogo; L2 nucleo di calcolo e riparazione; L4 persistenza v22; L3 contratto MFP2 e integrazione; L5a alternative; L5b sgarro; L6 spesa/export/UI; L7 revisione/doc/prompt; L8 valutazione reale e device. Dipendenze: L0 -> (L1 || L2 || L4) -> L3 -> (L5a || L5b || L6) -> L7 -> L8. Prima: spike S1 solo JVM (copertura alias, distribuzione |dichiarato-calcolato|, riparabilita, tempi).

## Decisioni all'utente
1 politica validatore (A solo kcal / B kcal+proteine>=97% consigliata / C tutti e quattro); 2 fonte dati (USDA subito + richiesta CREA, Ciqual ripiego); 3 voci personalizzate; 4 alimento mancante (rifiuto consigliato); 5 sgarri da foto; 6 dosi solo equivalenze note; 7 piani vecchi 'stima del modello'; 8 rollout con interruttore e shadow mode 2-4 settimane.

## Non verificato
NUTRITION_AGENT_GEMINI/GPT.md; chi consuma i JSON nei job; importer backup con chiavi sconosciute; strumenti per lo script del catalogo; ID nutrienti USDA; Ciqual 2025; licenza SINU; effetti legali sul riuso CREA; copertura reale sui nomi degli ingredienti; costo in token con catalogo nel prompt.