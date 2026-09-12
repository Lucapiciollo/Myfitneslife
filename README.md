# MyFitAI Development Pack — UI V2

## Stato
Repository di sviluppo MyFitAI. Il lavoro attivo va eseguito su `develop`; `main` resta stabile finché non viene autorizzato il merge.

## UI
Le Activity coprono il flusso approvato: dashboard, profilo, BIA, misure corporee con corpo fronte/retro, evoluzione fisica, piano alimentare, dettaglio pasto, lista spesa, sgarro, notifiche, progressi, analisi, storico/export, allenamenti e impostazioni.

## Regola principale
Fedeltà visuale 100% al mock approvato. Background/decorazioni e componenti reali devono restare separati.

## AI
Provider predisposti: Gemini e OpenAI. Stesso contratto JSON per entrambi. Target nutrizionali dinamici calcolati dall'app; tolleranza validator ±3%. La validazione definitiva è locale (`appValidation`).

## Sicurezza OpenAI
La API key BYOK deve essere protetta tramite Android Keystore e AES-GCM. Mai salvarla in chiaro, Room, normali SharedPreferences, log, backup, export, analytics o repository.

Leggere `AGENTS.md` prima di qualunque modifica.