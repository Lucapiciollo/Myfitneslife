# Glossario UI MyFitAI (UX3)

Vocabolario unico per testi e etichette visibili. Valido per Home, Cibo, help/dialog, Progresso, review, export PDF/testi.
Vincolo: si cambiano solo i testi; id, route, classi, campi dati, colonne Room, chiavi JSON/export e prompt/contratti IA (`06-ai`) non si rinominano.

## Termini definitivi

| Concetto | Termine UI | Dove si usa | Note |
|---|---|---|---|
| Numero calorico grande del giorno (piano o, senza piano, profilo) | **Obiettivo di oggi** | Home, Cibo (giorno corrente), help | Stessa fonte `DayTargetResolver`. In Cibo, per un altro giorno: **Obiettivo del giorno**. |
| Origine dell'obiettivo | `dal piano`, `da profilo`, `del piano precedente` (piano da aggiornare) | sottotitolo Home, legenda Cibo | Il testo "piano da aggiornare" resta. |
| Obiettivo calcolato da profilo e attività, senza adattamento né recupero (prima "Target teorico") | **Obiettivo da profilo** | Home, Cibo, PDF snapshot, BIA Coach (sezione app) | Stesso concetto del "Target abituale" della sezione scritta dall'app nel BIA Coach. |
| Obiettivo del piano salvato (versione/settimana) | **Obiettivo del piano** / **Obiettivo: n** (review) | PDF, Weekly review, Misure ("Obiettivo attuale") | |
| Consumo energetico giornaliero | **Consumo giornaliero (TDEE)** | ovunque | Italiano prima, sigla tra parentesi. |
| Metabolismo a riposo | **Metabolismo a riposo (BMR)**; da BIA: **Metabolismo a riposo da BIA (BMR)** | ovunque | Prima "BMR locale". |
| Pasto fuori piano | **Sgarro** (plurale sgarri) | pulsanti, titoli, help, impostazioni | Prima "Extra". |
| Calorie ancora da recuperare (Riserva di recupero) | **recupero** / "calorie da recuperare" | Home riserva di recupero | Non "extra". |

## Cosa NON si rinomina

- id e view (`addExtraButton`, `cheatButton`, `dailyTargetLabel`, `caloriesTargetValue`, ...), classi, route, chiavi `strings.xml`.
- Campi Room/JSON/export: `targetKcal`, `baseTargetKcal`, `targetProteinG`, ...
- Stringhe verso l'IA (`TARGET_ABITUALE_KCAL`, `REGOLA_TARGET`, system prompt): il modello continua a citare "target abituale".
- `EXTRA_*` degli Intent.

## Guardia

`UiGlossaryTest` (JVM) verifica le stringhe chiave e che in `strings.xml` e nei layout Home/Cibo/Weekly review non restino "Target", "target", "teorico", "extra".

## Non ancora allineato (fuori perimetro UX3)

Etichette brevi dei campi BIA ("BMR", "Metabolismo basale" in `BiaActivity`, `BiaTrendBinder`, `BiaHistoryText`, `BiaAnalysisContract`, `BodyValueRanges`, `dialog_bia_import_preview`), messaggi di validazione piano (`NutritionPlanAssessment`, `FoodPlanViewModel`), `WeeklyBodyExpectation` ("TDEE valido").
