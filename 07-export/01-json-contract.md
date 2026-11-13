# Export JSON

## Schema corrente: `myfitai_profile_export_v3`

Il file deve contenere:
- profilo
- misurazioni BIA
- misure corporee
- target nutrizionali
- piani alimentari
- sgarri
- analisi IA
- trend calcolati
- preferenze di profilo (`profilePreferences`): `mealCount`, `biaIntervalDays`, `nutritionSchedule`, `aiAutomation`

Obiettivo: poter condividere il file con ChatGPT/agenti esterni senza dover ricostruire il contesto manualmente.

## Versioni e deprecazioni

| Schema | Stato | Note |
|---|---|---|
| `myfitai_profile_export_v1` | solo import | restore accettato, chiavi dismesse ignorate |
| `myfitai_profile_export_v2` | solo import | contiene `workouts`, `dailyActivityCheckIns`, `profilePreferences.workoutsEnabled/trainingProgram/trainingActivityBasis` |
| `myfitai_profile_export_v3` | export corrente | nessuna delle chiavi dismesse |

DEPRECATI e rimossi da v3: allenamenti (`workouts`), check-in giornalieri (`dailyActivityCheckIns`), programma di allenamento (`trainingProgram`), `workoutsEnabled`, `trainingActivityBasis`.

- L'export JSON, il CSV (zip: niente `workouts.csv`) e il PDF non includono piu allenamenti ne riposi.
- Il restore accetta v1, v2 e v3. Con v1/v2 le chiavi dismesse vengono ignorate in silenzio e non vengono scritte; tutto il resto (profilo, BIA, misure, piani, consumi, sgarri, analisi, preferenze) e importato come prima. Il livello di attivita del profilo importato resta quello del file.
- Le tabelle Room `workouts` e `daily_activity_checkins` sono state eliminate dalla migrazione Room 19->20 (MIGRATION_19_20, DROP TABLE; schema 20.json). Verificato su device il 13/11/2026: versione 20, tabelle assenti, conteggi delle altre 15 tabelle invariati.
- Implementazione e costanti del formato: `ProfileExportFormat` (`domain/export`).