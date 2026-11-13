# Giorni di allenamento e varietà - DISMESSO (13/11/2026)

Questa specifica è stata superata dalla semplificazione del 13/11/2026 (decisione di prodotto, vedi `docs/PIANO_SEMPLIFICAZIONE_ATTIVITA.md`): l'app non gestisce più allenamenti. Il nome del file è mantenuto solo per non rompere i riferimenti storici; il contenuto precedente (target e tetto per giorno, giorni di allenamento e di riposo, elenco degli allenamenti registrati, differenziazione dei menu per tipo di giorno) non è più in vigore e non deve essere applicato da Gemini né da OpenAI.

## Cosa vale oggi
- Il target calorico e i macro sono calcolati localmente dal livello di attività del profilo (TDEE = BMR x fattore del livello; target = TDEE x fattore dell'obiettivo, con minimo di sicurezza). Il valore è unico e identico per tutti i giorni della settimana: il modello non riceve né deduce target diversi per data.
- Il modello non calcola né corregge le calorie: rispetta il target ricevuto entro la tolleranza di `AGENTS.md`.
- Non esistono giorni di allenamento o di riposo, programma settimanale, check-in giornaliero o energia extra da allenamento. Il modello non deve introdurre queste distinzioni né differenziare i menu per tipo di giorno.
- La modalità sportiva (riga `SM:`, creatina nel piano) dipende solo dal livello di attività `Molto attivo` (o dai valori storici equivalenti); non dipende da allenamenti registrati.
- Un pasto prima o dopo attività fisica non è più legato a un programma: il modello non costruisce pasti dedicati a un allenamento non presente nei dati e gli slot seguono sveglia, lavoro e abitudini ricevute (`MEALS_PER_DAY`), senza slot extra.

## Timing
- Nessuna regola rigida sui carboidrati, nessuna "finestra anabolica"; vale `SEASONALITY_TIMING_RULES.md`.

## Varietà (ancora valida)
- `RM:<tipo>|<titolo>`: pasti serviti nelle ultime 2 settimane (più recenti prima, massimo 60). Non riusarli, né usarne di equivalenti con gli stessi ingredienti principali, salvo assenza di alternative ragionevoli per target, allergie e preferenze.
- Le ripetizioni sono ammesse quando non evitabili: l'app non rifiuta né fa ritentare il piano. `MealVariety` le segnala come `WARNING` (`VARIETY_REPEATS_RECENT`, `VARIETY_REPEATS_IN_PLAN`) in `appValidation` e con un avviso breve all'utente. Il segnale `VARIETY_TRAINING_SAME_AS_REST` è dismesso.
- Il responso definitivo resta `appValidation` locale; `agentValidation` è solo autovalutazione.
