# Piano lavori - semplificazione attivita (senza allenamenti)

Data: 13/11/2026. Base: develop 45ef74b. Fonte: mappa di sola lettura (explore) del codice, 72 file main, 26 test unit, 17 test device.

## Obiettivo
Eliminare il discorso allenamenti (programma, giorni di allenamento/riposo, check-in giornaliero, energia extra da allenamento, schermata Allenamenti) e tenere SOLO il livello di attivita del profilo, che e gia la base del calcolo: TDEE = BMR x fattore (1,20 / 1,375 / 1,55 / 1,725 / 1,90). Un solo target giornaliero, uguale per tutti i giorni.

## Principi
1. Nessun DROP di tabelle Room: le tabelle workouts e daily_activity_checkins restano inerti (nessuna perdita di dati reali, backup vecchi importabili, nessuna migrazione 19->20).
2. Prima si toglie la logica, poi la UI, poi prompt/documenti, poi export. Tranche sequenziali (toccano gli stessi file), max 10 file di produzione, test prima del fix, gate completo, installazione in-place.
3. Dati utente: profili Luca (programma Lun/Mer/Ven/Dom 90 min, livello 'Moderatamente attivo') e Mamma (sedentaria) non vanno perduti ne alterati senza avviso.

## Punti delicati emersi
- Significato del livello: dalla Fase 3 il testo dice 'esclusi gli allenamenti'. Senza programma deve tornare 'include l'attivita abituale, anche sportiva' (helper in strings.xml:130, guida in SettingsActivity:333-336).
- Oggi senza check-in la Home usa il riposo (BMR x 1,2) come TDEE operativo: va sostituito dal TDEE del profilo (coerente con la generazione del piano).
- Chi ha confermato la base EVERYDAY_ONLY (livello scelto come 'solo vita quotidiana') perderebbe un gradino di fabbisogno: avviso una tantum 'ricontrolla il livello di attivita'.
- Export CSV: ProfileExportService.kt:415-416 usa getJSONArray('workouts'): togliere la chiave JSON e la voce CSV insieme.
- SportsNutritionClassifier (creatina consentita solo in SPORT, riga SM nel prompt) dipende dai workout: va semplificato (decisione di prodotto).
- ProgressAnalysisCompactContract.PatternCode.TRAINING e noRecentWorkout nelle BIA (condizione di misura) NON vanno rimossi.

## Fasi
F0 Decisioni e rete di sicurezza (0,5 g): decisioni D1-D4; export JSON completo dei profili Luca e Mamma dal telefono prima di cambiare; tag git pre-semplificazione.
F1 Dominio, in 4 tranche sequenziali:
 - A1: OperationalNutritionMetrics e DayTargetResolver = numeri del profilo (TDEE operativo = abituale); eliminare DayEnergyProvider, TrainingDayResolver/Inputs, DailyActivityCheckInEngine; Intensity spostata dove serve; riscrivere OperationalNutritionMetricsTest, DashboardNumbersTest, CheckInParityAndDstTest (parte check-in).
 - A2: NutritionPlanGenerationService senza ramo trainingDays/TRAINING (un solo target e tetto), TrainingEnergyPlanner/DayEnergyEngine/TrainingProgram/TrainingEnergyPreview eliminati, SportsNutritionClassifier semplificato (D3), MealVariety senza trainingSameAsRest, NutritionPlanContract.
 - A3: prompt e servizi di analisi: WeeklyReviewService, ProgressAnalysisService, PersonalResponseEngine/Service, PlanReviewService (WO/WorkoutSignal/PW/TC), BiaAnalysisReport/Service (tipo_giorno, riga ALLENAMENTI), AiRuntimeService.
 - A4: semantica del livello: helper e dropdown profilo/onboarding (ProfileEdit, OnboardingWizard), etichette, avviso una tantum per profili EVERYDAY_ONLY, golden CalculationGoldenProfilesTest invariato.
F2 UI, in 2 tranche:
 - B1: eliminare WorkoutsActivity, NewWorkoutActivity, WorkoutViewModel, widget, layout, manifest, asset; card 'Prossimo allenamento' e 'Attivita di oggi' in Home; riga 'Allenamenti' nel Profilo; switch 'Abilita allenamenti'; WorkoutPreferences/TrainingProgramPreferences inerti.
 - B2: Home/Cibo etichette TDEE unificate (niente 'operativo/da riposo/attivita del giorno'), card 'Giorni di allenamento' in NutritionPlanSettingsActivity, dialog IA (trainingPreviewText), WeeklyReviewActivity (contatore), PhysicalEvolution/AiAnalysis testi, guida in Impostazioni, ExportActivity.
F3 Export/Backup/PDF (1 tranche): ProfileExportService (schema v3 senza workouts/check-in/trainingProgram, CSV insieme), ProfileBackupService (resta tollerante verso v1/v2), PdfExportRenderer (card ALLENAMENTI/RIPOSI), DataDeletionService (categoria allenamenti), test di import di un backup v2 reale.
F4 Prompt e documentazione (1 tranche, solo testo): 06-ai/providers/shared/TRAINING_DAYS_VARIETY_RULES.md (rimuovere), agenti NUTRITION/PLAN_REVIEW/PROGRESS_ANALYSIS (gemini/openai), PROVIDER_CONTRACT, SEASONALITY_TIMING_RULES, schemi (trainingDay non piu required) ed esempi; AGENTS.md (schermata Allenamenti, decisione di prodotto), DEVELOPMENT_PLAN.md, README, 03-screens/05-workouts.md, docs/MODAL_MAP.md.
F5 Test device e collaudo (1 tranche): eliminare/riscrivere i 17 test device dedicati, aggiornare i 10 parziali; fixture SixMonthHistory (workout) lasciati per i dati demo.
F6 Verifica finale: gate completo, installazione in-place, prove a schermo su Luca e Mamma (Home, Cibo, piano rigenerato, BIA Coach, review, export/import di un backup vecchio), confronto target prima/dopo.

Stima: 9-10 tranche, circa 70 file di produzione, 26 test unit e 17 device da eliminare o riscrivere. Rischio principale: regressione dei test che fissano target e etichette; rischio dati: nullo senza DROP.

## Decisioni prese dall'utente (13/11/2026)
D1 3 livelli di attivita nel profilo: Sedentario (x1,20), Moderato (x1,55), Molto attivo (x1,725). I valori storici gia salvati (es. 'Leggermente attivo' x1,375, 'Estremamente attivo' x1,90) restano validi e leggibili dal mapper; non si riscrivono i profili. Il livello si imposta UNA volta in configurazione (Profilo e onboarding) e resta stabile: non si chiede alla generazione del piano ne ogni giorno.
D2 Luca resta 'Moderatamente attivo' (livello stabile in configurazione, ~2.528 kcal fissi); Mamma resta Sedentaria. Nessun avviso a ogni generazione; un solo avviso informativo una tantum per i profili che avevano la base EVERYDAY_ONLY.
D3 Modalita sportiva (creatina nel piano, riga SM del prompt) dipende solo dal livello 'Molto attivo' (o valori storici molto/estremamente).
D4 ELIMINARE le tabelle workouts e daily_activity_checkins con MIGRATION_19_20 (DROP TABLE). Attenzione: perdita dei dati salvati. Mitigazioni obbligatorie: (a) export JSON completo di Luca e Mamma dal telefono PRIMA della migrazione e copia fuori dal telefono; (b) schema 20.json e DatabaseMigrationTest 19->20 che verifica che le altre tabelle restino intatte; (c) la migrazione parte solo dopo che UI e logica non leggono piu quelle tabelle (ultima tranche del DB); (d) i backup v1/v2 restano importabili ignorando workouts e check-in; (e) installazione solo in-place, nessun reset.

## Fasi aggiornate
F0 Rete di sicurezza: export JSON di Luca e Mamma salvato fuori dal telefono; tag git pre-semplificazione.
F1 Dominio A1-A3 (come sopra) e A4: 3 livelli in Profilo/onboarding con testo 'comprende l'attivita abituale, anche sportiva', mapper tollerante verso i 5 livelli storici, avviso una tantum per EVERYDAY_ONLY.
F2 UI B1-B2.
F3 Export/Backup/PDF/cancellazione dati (schema export v3).
F4 Prompt e documentazione.
F5 DATABASE (nuova, dopo F1-F4): MIGRATION_19_20 che elimina workouts e daily_activity_checkins, entita/DAO/repository rimossi, schema 20.json, DatabaseMigrationTest, SharedPreferences training_program_preferences e workout_preferences eliminate o ignorate; nessun fallbackToDestructiveMigration.
F6 Test device e collaudo; F7 Verifica finale (Luca, Mamma, import backup vecchio, migrazione su installazione in-place, confronto target prima/dopo).

## Ordine e prima mossa
Sequenza: F0 -> A1 -> A2 -> A3 -> A4 -> B1 -> B2 -> F3 -> F4 -> F5 -> F6 -> F7. Non c'e parallelismo utile (stessi file), tranne F4 (solo documenti) che puo girare in parallelo ad A3/A4.

## Chiarimento (13/11/2026): il calcolo calorico resta
Il TDEE in Home e il target calorico restano, calcolati dal livello di attivita scelto nel profilo: TDEE = BMR x fattore del livello (Sedentario 1,20 / Moderato 1,55 / Molto attivo 1,725), target = TDEE x fattore dell'obiettivo (es. -15% dimagrimento) con minimo di sicurezza. Il livello sportivo ('Molto attivo') quindi alza il TDEE e il target come oggi. Si elimina SOLO la variazione giorno per giorno (check-in, programma, giorni di allenamento/riposo, kcal extra del singolo allenamento). Home mostra BMR, TDEE e target, un solo valore per tutti i giorni.
