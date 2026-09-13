# MyFitAI — Piano di sviluppo autorevole

Questo documento è la fonte di verità per lo stato del progetto e per l'ordine dei prossimi step. Non anticipare fasi successive e non ampliare il perimetro senza decisione esplicita.

## Obiettivo V1
App Android personale, local-first, per monitorare BIA e misure corporee, seguire l'evoluzione di peso/massa grassa/massa muscolare, calcolare target nutrizionali dinamici localmente, generare piani alimentari settimanali strutturati via IA, adattare solo i pasti futuri dopo uno sgarro, mantenere storico/versioni, generare lista spesa deterministica, notifiche locali, review settimanali ed export personale.

## Regole architetturali vincolanti
- L'app è l'orchestratore, non l'LLM.
- I calcoli numerici affidabili sono locali/deterministici.
- Pipeline: `App -> Local Calculation Engine -> AiProvider -> Agent -> JSON -> Schema Validator -> Business Validator -> Persistenza -> UI`.
- Nessun output AI va direttamente in UI.
- Gemini e OpenAI devono produrre lo stesso JSON canonico.
- `agentValidation` è informativa; `appValidation` è autorevole.
- Tolleranza nutrizionale ufficiale: ±3% sui target dinamici correnti.
- I piani sono versionati e non vengono sovrascritti.
- Lista spesa locale e deterministica.
- Nessuna blacklist automatica di alimenti comuni senza dato utente o regola documentata.

## FATTO

### 1. Fondazione e design
- [x] Tema chiaro/verde approvato.
- [x] Fedeltà visuale al mock come vincolo.
- [x] Separazione asset decorativi / componenti UI reali.
- [x] Asset e reference screen presenti nel repository.
- [x] Background pagina principale corretto a bianco.
- [x] Material 3 / Android Views.
- [x] Bottom navigation condivisa.

### 2. Schermate UI presenti
- [x] Splash.
- [x] Onboarding.
- [x] Dashboard/Home.
- [x] Profilo.
- [x] Misure corporee.
- [x] Nuova misurazione corporea.
- [x] BIA.
- [x] Evoluzione fisica/Progressi.
- [x] Allenamenti.
- [x] Piano alimentare.
- [x] Dettaglio pasto.
- [x] Lista spesa.
- [x] Sgarro/deviazione.
- [x] Piano adattato.
- [x] Notifiche.
- [x] Storico.
- [x] Analisi IA/review.
- [x] Export.
- [x] Impostazioni.

### 3. Componenti UI riutilizzabili già presenti
- [x] `SelectableSegmentView`.
- [x] `MeasurementRowView`.
- [x] `MetricCardView`.
- [x] `SectionHeaderView`.
- [x] `TimeRangeSelectorView`.
- [x] `MealCardView`.
- [x] `WorkoutCardView` / `WorkoutRowView`.
- [x] `SettingRowView`.
- [x] `WeekDaySelectorView`.
- [x] `OnboardingPageIndicatorView`.
- [x] `WeightTrendChartView`.
- [x] `BodyMeasurementTrendView`.

### 4. Libreria grafici
- [x] Integrata `com.github.AppDevNext.AndroidChart:chartLib:5.3` via JitPack.
- [x] I grafici sono data-driven, non PNG/screenshot.
- [x] `WeightTrendChartView` usa un vero `LineChart`.
- [x] `BodyMeasurementTrendView` usa un vero `LineChart` per l'andamento delle circonferenze.

### 5. Sicurezza OpenAI BYOK
- [x] Android Keystore come root of trust.
- [x] AES-GCM.
- [x] Persistenza solo ciphertext + IV.
- [x] Nessuna API key in chiaro in Room/file/log/backup/analytics/source/BuildConfig/intent/export.
- [x] Backup disabilitato.
- [x] Settings protette con `FLAG_SECURE`.

### 6. Regole alimentari IA già definite
- [x] Target locali/dinamici, mai sostituiti dall'agente.
- [x] Tolleranza ±3% calorie e macro.
- [x] Nessuna compensazione punitiva.
- [x] Modifica solo pasti futuri.
- [x] Quantità numeriche + unità + `displayDose`.
- [x] `nutritionConfidence` e `weightState`.
- [x] Regole su sodio/fibre/grassi/volume/timing.
- [x] Condimenti e bevande caloriche sempre conteggiati.
- [x] Stagionalità come preferenza, non vincolo superiore ai target.
- [x] Nessuna diagnosi/intolleranza inventata.

## STEP 6 — QA VISUALE / UI SCAFFOLD

Stato: **CHIUSO LATO IMPLEMENTAZIONE UI**.

Lo Step 6 viene considerato chiuso per poter passare alla navigazione reale e al codice applicativo. Non viene però dichiarata una verifica runtime/device che non è stata eseguita nell'ultima sessione: la regressione completa su device viene mantenuta nello Step 23.

### Verificato/corretto
- [x] Portrait sulle Activity principali nelle sessioni QA precedenti.
- [x] Confronto delle 14 schermate con reference disponibili nelle sessioni QA precedenti.
- [x] Campione landscape su pattern a rischio.
- [x] Campione multi-size telefono compatto / grande.
- [x] Avatar profilo ripulito da testo baked-in.
- [x] Contrasti corretti su Piano adattato e Analisi IA.
- [x] Splash/Onboarding convertiti da placeholder Unicode ad asset/componenti reali.
- [x] Protezioni contro doppia istanza/tap-through/navigazione anomala Splash→Onboarding.
- [x] Background globale corretto a bianco.
- [x] Home e Piano alimentare ricontrollati lato layout dopo il cambio background.
- [x] Rimossi gli ultimi glifi Unicode di navigazione individuati su Home, Piano alimentare e Nuova misurazione, sostituiti con asset vector.
- [x] MaterialDatePicker usato anche nella nuova acquisizione misure per coerenza con BIA.
- [x] CTA e controlli principali basati su componenti Material reali.

### Misure corporee — UI completa
- [x] `BodyMeasuresActivity` con `Misura | Andamento | Storico`.
- [x] `+ Nuova misurazione` apre `NewBodyMeasurementActivity`.
- [x] Data con `MaterialDatePicker` e icona calendario.
- [x] Campi numerici per Torace, Vita, Addome, Spalle, Glutei, Braccio sx/dx, Coscia sx/dx, Polpaccio sx/dx.
- [x] Vista `Andamento` con selettore metrica.
- [x] Range `1M / 3M / 6M / 1Y` realmente interattivi sul dataset mock.
- [x] `BodyMeasurementTrendView` basato su AndroidChart.
- [x] Valore corrente, delta vs precedente e delta nel periodo.
- [x] Vista `Storico` dedicata alle misure corporee.
- [x] Dati mock usati esclusivamente per completare la UI prima di Room.

### Nota di accettazione
- Allenamenti e Impostazioni restano `IMPLEMENTATE DA SPECIFICA — VERIFICA VISIVA PENDING` perché non esiste un mock dedicato.
- Build/emulatore finale del nuovo flusso Misure non è stato eseguito tramite il connettore GitHub; non viene quindi dichiarato come verificato.
- La verifica runtime completa, multi-device, landscape e regressione visuale finale confluisce nello Step 23 e non blocca l'avvio dello Step 7.

## STEP 7 — Persistenza locale
Stato: **NON INIZIATO**.
- [ ] Introdurre Room.
- [ ] Entity/DAO/Database/Repository layer.
- [ ] Profilo utente.
- [ ] BIA.
- [ ] Misure corporee.
- [ ] Allenamenti.
- [ ] Piano alimentare/versioni.
- [ ] Sgarri/deviazioni.
- [ ] Review settimanali.
- [ ] Storico/export.
- [ ] Migrazioni/versioning DB.

## STEP 8 — Profilo reale
- [ ] Collegare campi profilo alla persistenza.
- [ ] Altezza, peso, obiettivo, attività, orari, allenamenti, preferenze.
- [ ] Raccogliere solo dati necessari alla V1.

## STEP 9 — BIA reale
- [ ] Inserimento/salvataggio.
- [ ] Storico cronologico.
- [ ] Delta e medie.
- [ ] BIA trattata come stima, non misura clinica assoluta.

## STEP 10 — Misure corporee reali
UI scaffold completato nello Step 6; qui va collegata ai dati reali.
- [ ] Persistenza delle rilevazioni.
- [ ] Salvataggio di Torace, Vita, Addome, Spalle, Glutei, Braccio sx/dx, Coscia sx/dx, Polpaccio sx/dx.
- [ ] Collegamento figura fronte/retro ai campi corretti.
- [ ] Storico reale ordinato per data.
- [ ] Grafico alimentato da Room.
- [ ] Selettore metrica alimentato dai dati persistiti.
- [ ] Delta vs misura precedente.
- [ ] Delta sul periodo selezionato.
- [ ] Range temporali 1M/3M/6M/1Y reali.
- [ ] Gestione assenza dati / una sola misura / misure incomplete.

## STEP 11 — Motore locale di calcolo e target dinamici
- [ ] BMI.
- [ ] BMR.
- [ ] TDEE.
- [ ] Target calorico dinamico.
- [ ] Proteine/grassi/carboidrati dinamici.
- [ ] Medie, delta e trend.
- [ ] Waist/height ratio se disponibile.
- [ ] Classificazione ricomposizione.
- [ ] Business Validator locale ±3%.

## STEP 12 — Dashboard e trend dinamici
- [ ] Sostituire mock con dati locali.
- [ ] Trend peso/grasso/massa muscolare.
- [ ] Stato ricomposizione dal motore locale.
- [ ] Nessun claim causale non supportato.

## STEP 13 — Allenamenti reali
- [ ] Persistenza e storico.
- [ ] Training/rest day.
- [ ] Contesto allenamento al motore nutrizionale e agli agenti.

## STEP 14 — Modello dati piano alimentare
- [ ] DTO/domain model canonici.
- [ ] Ingredienti con `quantity`, `unit`, `displayDose`, `weightState`, `nutritionConfidence`.
- [ ] Pasti con orari e macro.
- [ ] Totali giornalieri/medie settimanali.
- [ ] Versionamento immutabile.

## STEP 15 — Integrazione IA reale
- [ ] Trasporto Gemini.
- [ ] Trasporto OpenAI.
- [ ] OpenAI key letta just-in-time.
- [ ] Parsing JSON.
- [ ] JSON Schema validation.
- [ ] Business validation locale.
- [ ] Retry `INVALID_SCHEMA`.
- [ ] Nessuna normalizzazione silenziosa provider-specific.

## STEP 16 — Generazione reale piano settimanale
- [ ] Target calcolati localmente prima della richiesta IA.
- [ ] Generazione strutturata.
- [ ] Validazione ±3%.
- [ ] Validazione quantità/unità/displayDose/condimenti/bevande.
- [ ] Stagionalità e timing.

## STEP 17 — Sgarro e adattamento piano
- [ ] Registrazione free-text.
- [ ] Stima strutturata impatto.
- [ ] Modifica solo pasti futuri.
- [ ] Nuova versione del piano.
- [ ] Nessuna compensazione punitiva.

## STEP 18 — Personal Response Engine
- [ ] Collegare piano/versione + aderenza + sgarri + allenamenti + BIA + misure.
- [ ] Individuare pattern associativi, non causali.
- [ ] Storico sintetico ai futuri piani.

## STEP 19 — Weekly Review
- [ ] Trigger fine settimana.
- [ ] PlanReviewAgent / ProgressAnalysisAgent.
- [ ] Confronto target/aderenza/allenamenti/BIA/misure.
- [ ] Persistenza review.

## STEP 20 — Lista spesa deterministica
- [ ] Aggregazione ingredienti.
- [ ] Normalizzazione nomi quando necessaria.
- [ ] Somma quantità compatibili.
- [ ] Categorie.
- [ ] Quantità richiesta/acquisto.
- [ ] Checkbox/filtri/dispensa.

## STEP 21 — Notifiche locali
- [ ] Scheduler Android.
- [ ] Reminder pasto.
- [ ] Tap -> dettaglio corretto.
- [ ] Refresh su nuova versione piano.

## STEP 22 — Export
- [ ] JSON completo.
- [ ] BIA/misure/piani/versioni/sgarri/review.
- [ ] Nessun segreto/API key.
- [ ] CSV/PDF solo successivamente se richiesto.

## STEP 23 — QA finale V1
- [ ] Build pulita release/debug.
- [ ] Smoke test di tutte le Activity.
- [ ] Test runtime del flusso Misure e `NewBodyMeasurementActivity`.
- [ ] Verifica grafici su device/emulatore.
- [ ] Portrait/landscape e almeno 2 classi di dimensione.
- [ ] Regressione visuale completa contro i mock disponibili.
- [ ] Test schema Gemini/OpenAI.
- [ ] Test Business Validator ±3%.
- [ ] Test condimenti/bevande/displayDose.
- [ ] Test stagionalità/timing.
- [ ] Test sicurezza API key.
- [ ] Test versioning dopo sgarro.
- [ ] Test lista spesa.
- [ ] Test notifiche.
- [ ] Test export.
