# Giorni di allenamento e varietà — regole provider-neutral

Valgono identiche per Gemini e OpenAI. Il prompt reale è `SYSTEM_PROMPT` in `NutritionPlanGenerationService`; questo documento è la specifica.

## Target per giorno (autorità dell'app)
- Le calorie e i macro di ogni data sono calcolati localmente da `TrainingEnergyPlanner` (`DayEnergyEngine`): fabbisogno del giorno = BMR × livello di attività quotidiana (allenamenti esclusi) + costo dell'allenamento; target = fattore obiettivo × fabbisogno del giorno, mai sotto il BMR. Proteine e grassi per kg, carboidrati come resto.
- Il modello non calcola né corregge le calorie: legge le righe `TD:` (target del giorno, autorevoli) e rispetta `ED:<data>|<kcal>` (tetto del giorno, sostituisce `E` per quella data: la somma di pasti e integratori deve restare sotto).
- `TR:<data>|<orario in minuti o ?>|<durata>|<intensità>` marca un giorno di allenamento; ogni altra data è di riposo. Il costo dell'allenamento è già incluso nel `TD`: nessuna aggiunta o sottrazione.
- `WO:` elenca gli allenamenti registrati dall'utente, `SM:` la modalità di nutrizione sportiva.
- Senza programma settimanale non compaiono `ED`/`TR` e vale il solo target abituale.

## Timing
- Con orario noto si può rendere più leggero e digeribile il pasto prima dell'allenamento e più ricco di proteine e carboidrati quello dopo, usando gli slot esistenti (`MEALS_PER_DAY`), senza slot extra.
- Nessuna regola rigida sui carboidrati, nessuna "finestra anabolica"; vale `SEASONALITY_TIMING_RULES.md`.

## Varietà
- `RM:<tipo>|<titolo>`: pasti serviti nelle ultime 2 settimane (più recenti prima, massimo 60). Non riusarli, né usarne di equivalenti con gli stessi ingredienti principali, salvo assenza di alternative ragionevoli per target, allergie e preferenze.
- I menu dei giorni di allenamento devono essere diversi da quelli di riposo (pasti diversi, non solo porzioni).
- Le ripetizioni sono ammesse quando non evitabili: l'app non rifiuta né fa ritentare il piano. `MealVariety` le segnala come `WARNING` (`VARIETY_REPEATS_RECENT`, `VARIETY_REPEATS_IN_PLAN`, `VARIETY_TRAINING_SAME_AS_REST`) in `appValidation` e con un avviso breve all'utente.
- Il responso definitivo resta `appValidation` locale; `agentValidation` è solo autovalutazione.