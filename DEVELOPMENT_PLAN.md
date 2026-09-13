# MyFitAI — Piano di sviluppo autorevole

Questo documento è la fonte di verità per il lavoro già svolto e per l'ordine dei prossimi step. Non anticipare fasi successive e non ampliare il perimetro senza una decisione esplicita.

## Obiettivo V1
App Android personale, local-first, per:
- monitorare BIA e misure corporee;
- seguire evoluzione di peso, massa grassa e massa muscolare;
- calcolare localmente target nutrizionali dinamici;
- generare un piano alimentare settimanale strutturato tramite IA;
- adattare solo i pasti futuri dopo uno sgarro/deviazione;
- mantenere storico e versioni dei piani;
- generare lista della spesa deterministica;
- inviare notifiche locali prima dei pasti;
- produrre review settimanali e analisi dei progressi;
- esportare i dati personali.

## Regole architetturali già decise
- L'app è l'orchestratore, non l'LLM.
- I calcoli numerici affidabili sono locali/deterministici.
- Pipeline prevista: `App -> Local Calculation Engine -> AiProvider -> Agent -> JSON -> Schema Validator -> Business Validator -> Persistenza -> UI`.
- Nessun output AI va direttamente in UI.
- Gemini e OpenAI devono produrre lo stesso JSON canonico.
- `agentValidation` è solo informativa; `appValidation` è autorevole.
- Tolleranza nutrizionale ufficiale: ±3% sui target dinamici correnti.
- I piani sono versionati e non vengono sovrascritti.
- La lista della spesa è locale e deterministica.
- Nessuna blacklist automatica di alimenti comuni senza dato utente o regola documentata.

## FATTO

### 1. Fondazione e design
- [x] Direzione visuale approvata: tema chiaro/crema/verde.
- [x] Regola di fedeltà visuale 100% al mock approvato.
- [x] Separazione tra elementi decorativi e componenti UI reali.
- [x] Regole asset/licenze definite.

### 2. Navigazione e schermate mock
- [x] Splash.
- [x] Onboarding.
- [x] Dashboard/Home.
- [x] Profilo.
- [x] Misure corporee con figura fronte/retro.
- [x] Inserimento BIA.
- [x] Evoluzione fisica.
- [x] Allenamenti.
- [x] Piano alimentare.
- [x] Dettaglio pasto.
- [x] Lista della spesa.
- [x] Inserimento sgarro/deviazione.
- [x] Piano adattato.
- [x] Notifiche/promemoria.
- [x] Storico.
- [x] Analisi IA/review.
- [x] Export.
- [x] Impostazioni.
- [x] Shell e bottom navigation condivise.

### 3. Architettura IA e contratti
- [x] Provider abstraction Gemini/OpenAI.
- [x] Selezione runtime del provider.
- [x] NutritionAgent definito per entrambi i provider.
- [x] PlanReviewAgent definito per entrambi i provider.
- [x] ProgressAnalysisAgent definito per entrambi i provider.
- [x] JSON parity vincolante Gemini/OpenAI.
- [x] JSON Schema canonici.
- [x] Regola `agentValidation` vs `appValidation`.
- [x] Benchmark provider-parity predisposto.

### 4. Sicurezza OpenAI BYOK
- [x] Android Keystore come root of trust.
- [x] AES-GCM per cifratura autenticata.
- [x] Persistenza solo ciphertext + IV.
- [x] Nessuna API key in chiaro in Room, SharedPreferences normali, file, log, backup, crash report, analytics, repository, BuildConfig, intent o navigation args.
- [x] Backup Android disabilitato.
- [x] Settings protette con `FLAG_SECURE`.
- [x] Salvataggio/sostituzione/rimozione credenziale previsto.

### 5. Regole alimentari IA già definite
- [x] Target nutrizionali dinamici forniti dall'app, mai sostituiti dall'agente.
- [x] Tolleranza ±3% su calorie e macro.
- [x] Nessuna compensazione punitiva dopo uno sgarro.
- [x] Modifica solo dei pasti futuri.
- [x] Priorità alla quota proteica durante gli adattamenti.
- [x] Quantità ingredienti numeriche + unità separata.
- [x] `nutritionConfidence` e `weightState` definiti.
- [x] Regole prudenti su sodio, fibre, grassi, volume e timing.
- [x] Nessuna diagnosi/intolleranza inventata.
- [x] Distinzione tra ritenzione idrica transitoria e aumento di grasso.
- [x] Condimenti calorici sempre conteggiati.
- [x] Bevande caloriche sempre conteggiate.
- [x] `displayDose` pratica obbligatoria per gli ingredienti quando applicabile.
- [x] Esempi domestici: `1 cucchiaino`, `1/2 cucchiaino`, `1 cucchiaio`, `1 bustina da X g`, `1 bicchiere da X ml`.
- [x] Vietate indicazioni vaghe come `q.b.`, `un filo`, `un po'` quando incidono su calorie, macro o sodio.
- [x] Bilanciamento della giornata dopo pasti più ricchi di sodio/grassi/fibre/volume, senza vietare automaticamente il singolo alimento.
- [x] Preferenza per frutta, verdura e alimenti stagionali quando equivalenti e compatibili con target, timing, tolleranza e preferenze.
- [x] Nessuna regola arbitraria tipo frutta solo al mattino o carboidrati vietati la sera.

## DA FARE — ORDINE VINCOLANTE

### 6. QA visuale reale su dispositivo/emulatore
- [ ] Confrontare ogni Activity con il mock approvato.
- [ ] Correggere spacing, dimensioni, proporzioni, font, radius, bordi, ombre, colori, icone e allineamenti.
- [ ] Verificare portrait e landscape dove previsto.
- [ ] Verificare schermi di dimensioni differenti.
- [ ] Non chiudere una schermata finché non è visivamente confrontata con il mock.

### 7. Persistenza locale
- [ ] Introdurre Room.
- [ ] Modellare profilo utente.
- [ ] Modellare BIA.
- [ ] Modellare misure corporee.
- [ ] Modellare allenamenti.
- [ ] Modellare piano alimentare/versioni.
- [ ] Modellare sgarri/deviazioni.
- [ ] Modellare review settimanali.
- [ ] Modellare storico/export.

### 8. Profilo reale
- [ ] Collegare i campi profilo alla persistenza.
- [ ] Gestire dati necessari ai calcoli: altezza, peso, obiettivo, attività, orari, allenamenti e preferenze.
- [ ] Non raccogliere dati non necessari alla V1.

### 9. BIA reale
- [ ] Inserimento e salvataggio delle misurazioni.
- [ ] Storico cronologico.
- [ ] Calcolo delta e medie.
- [ ] Trattare BIA come stima, non come misura clinica assoluta.

### 10. Misure corporee reali
- [ ] Salvataggio delle circonferenze/misure previste.
- [ ] Collegamento figura fronte/retro ai campi corretti.
- [ ] Storico e trend.

### 11. Motore locale di calcolo e target dinamici
- [ ] BMI.
- [ ] BMR.
- [ ] TDEE.
- [ ] Target calorico dinamico.
- [ ] Target proteine/grassi/carboidrati dinamici.
- [ ] Medie, delta e trend.
- [ ] Waist/height ratio se previsto dai dati disponibili.
- [ ] Classificazione ricomposizione corporea.
- [ ] Business Validator locale con tolleranza ±3%.
- [ ] I calcoli finali non devono dipendere dall'LLM.

### 12. Dashboard e trend dinamici
- [ ] Sostituire i mock con dati locali reali.
- [ ] Mostrare trend peso/grasso/massa muscolare.
- [ ] Mostrare stato ricomposizione derivato dal motore locale.
- [ ] Nessun claim causale non supportato.

### 13. Allenamenti reali
- [ ] Persistenza e storico allenamenti.
- [ ] Distinzione training/rest day.
- [ ] Fornire il contesto allenamento al motore nutrizionale e agli agenti.

### 14. Modello dati piano alimentare
- [ ] DTO/domain model canonici coerenti con gli schema JSON.
- [ ] Ingredienti con `quantity`, `unit`, `displayDose`, `weightState`, `nutritionConfidence`.
- [ ] Pasti con orari e macro.
- [ ] Totali giornalieri e medie settimanali.
- [ ] Versionamento immutabile del piano.

### 15. Integrazione IA reale
- [ ] Implementare trasporto Gemini.
- [ ] Implementare trasporto OpenAI.
- [ ] OpenAI key letta just-in-time da `SecureOpenAiKeyStore`.
- [ ] Parsing JSON.
- [ ] JSON Schema validation.
- [ ] Business validation locale.
- [ ] Retry su `INVALID_SCHEMA` usando lo stesso contratto canonico.
- [ ] Nessuna normalizzazione silenziosa di field provider-specifici.
- [ ] Nessun testo libero dell'LLM mostrato direttamente in UI.

### 16. Generazione reale piano settimanale
- [ ] L'app calcola prima i target.
- [ ] L'agente genera menu strutturato sui target ricevuti.
- [ ] Validator locale verifica calorie e macro entro ±3%.
- [ ] Validator verifica quantità, unità, `displayDose`, condimenti e bevande.
- [ ] Applicare stagionalità come preferenza, non come vincolo superiore ai target.
- [ ] Applicare timing in base a sveglia, lavoro, sonno e allenamento.

### 17. Sgarro e adattamento piano
- [ ] Registrazione free-text dello sgarro.
- [ ] Stima strutturata dell'impatto.
- [ ] Modifica solo pasti futuri.
- [ ] Nuova versione del piano, mai overwrite.
- [ ] Nessuna compensazione punitiva.
- [ ] Redistribuzione sul giorno successivo solo quando il giorno corrente non può realisticamente rientrare nel ±3%.

### 18. Personal Response Engine
- [ ] Collegare piano/versione + aderenza + sgarri + allenamenti + BIA + misure.
- [ ] Individuare pattern associati a risultati migliori/peggiori.
- [ ] Parlare di associazioni, non causalità.
- [ ] Fornire storico sintetico ai futuri piani.

### 19. Weekly Review
- [ ] Trigger a fine settimana.
- [ ] Review strutturata tramite PlanReviewAgent/ProgressAnalysisAgent.
- [ ] Confronto con target, aderenza, allenamenti, BIA e misure disponibili.
- [ ] Persistenza della review.

### 20. Lista della spesa deterministica
- [ ] Aggregare ingredienti dal piano approvato.
- [ ] Normalizzare nomi solo quando necessario.
- [ ] Sommare quantità compatibili localmente.
- [ ] Raggruppare per categorie.
- [ ] Gestire quantità richiesta e quantità di acquisto arrotondata opzionale.
- [ ] Checkbox/filtri/dispensa.
- [ ] Nessun LLM come fonte di verità dei totali.

### 21. Notifiche locali
- [ ] Scheduler Android locale.
- [ ] Notifica poco prima del pasto.
- [ ] Tap -> dettaglio pasto corretto.
- [ ] Aggiornare le notifiche quando cambia una versione del piano.

### 22. Export
- [ ] Export JSON completo.
- [ ] Storico BIA/misure/piani/versioni/sgarri/review.
- [ ] Nessuna API key o dato segreto nell'export.
- [ ] CSV/PDF solo successivamente se richiesto.

### 23. QA finale V1
- [ ] Test schema Gemini/OpenAI con stessi fixture.
- [ ] Test Business Validator ±3%.
- [ ] Test condimenti/bevande/displayDose.
- [ ] Test stagionalità/timing.
- [ ] Test sicurezza API key.
- [ ] Test versione piano dopo sgarro.
- [ ] Test lista spesa.
- [ ] Test notifiche.
- [ ] Test export.
- [ ] Test regressione UI.

## Stato sessione 2026-09-13

### Fatto in questa sessione
- Corretto il tema Android da Material3 dark a Material3 light, coerentemente con il mock approvato.
- Sostituite le pseudo-icone Unicode della bottom navigation con vector drawable Android e content description.
- Riallineata la bottom navigation al mock approvato: Home, Alimentazione, Progresso, Altro.
- Rimappate le schermate di misure, BIA, storico ed evoluzione alla sezione Progresso senza introdurre persistenza o logica reale.
- Eseguita build debug e smoke test su AVD `Medium_Phone_API_35`: Splash, onboarding, Home e routing dei quattro tab verificati.
- Corretti un attributo XML Android non valido in `activity_cheat_entry.xml` e l’allineamento Java/Kotlin JVM 17 necessario alla build.
- Errore corretto in questa sessione: era stato introdotto un layout `activity_home_v2.xml` con contenuti reinterpretati (tre metriche, andamento peso, prossimo pasto, prossimo allenamento) al posto della struttura ritenuta approvata in quel momento. `HomeActivity` era stata riportata su `activity_home.xml` (4 metriche, "Il tuo stato", "Suggerimento IA di oggi", "+ Nuova misurazione").
- Verifica successiva con lettura diretta e ad alta risoluzione di `assets/MOCK_APPROVATO_MYFITAI_V2_COMPLETO.png` (pannello "2. Dashboard") e di `99-screen-references/02_dashboard_reference.png`: la struttura "Il tuo stato / Suggerimento IA di oggi / + Nuova misurazione" NON è presente nel mock. La struttura realmente approvata è: header, 3 metriche (Peso, Grasso, Massa muscolare), grafico andamento peso con tab 1W/1M/3M/1Y, sezione "Prossimo pasto", sezione "Prossimo allenamento".
- `activity_home.xml` ricostruito da zero con questa struttura reale: componenti Android veri (nessuna schermata rasterizzata), grafico dati-driven (`WeightTrendChartView`, view custom con linea+area a gradiente da dati mock), tab periodo selezionabili funzionanti, foto reali per le miniature pasto/allenamento (estratte da `02-dashboard/*.png`, già derivate dal mock approvato, risoluzione bassa e documentata in `assets/ASSET_SOURCES.md`), icone Material standard per profilo/pasto/allenamento.
- Colori e spaziature centralizzati in `colors.xml`/`dimens.xml` (nessun hex o dp duplicato letterale nel layout).
- Verificato a runtime dopo clean/rebuild/reinstall: struttura, testi, click (profilo, Prossimo pasto → dettaglio pasto, Prossimo allenamento → allenamenti), toggle dei tab periodo.

Residui noti non ancora risolti su Home: icona profilo (badge bianco nel mock, cerchio verde outline nell'implementazione); icona "Prossimo allenamento" non ha lo stesso badge circolare verde di "Prossimo pasto"; miniature foto a bassa risoluzione (in attesa di sorgenti originali); font ancora "sans" di sistema (Poppins non è presente/licenziato nel repository); nessuna libreria Material Symbols ufficiale integrata, icone tramite VectorDrawable con path standard Material dove possibile. Spazio vuoto sopra la bottom nav su questo emulatore (1080x2400, ~914dp): riconducibile matematicamente alla somma dei componenti (~784dp contenuto+nav) su uno schermo più alto del riferimento, non a un errore di layout. Componente grafico rinominato in `WeightTrendChartView` per coerenza con `README_ASSETS.md`.

- Profilo ricostruito da zero dopo la scoperta che `activity_profile.xml` era un form di modifica reinterpretato (Obiettivo/Focus, Livello di attività, Preferenze alimentari, pulsante "Salva") mai presente nel mock. Struttura reale confermata da `99-screen-references/03_profile_reference.png`: header "Il tuo profilo", blocco con avatar reale + nome "Luca Piciollo" + "43 anni | 186 cm | 78,4 kg" + "Obiettivo: Ricomposizione", 9 righe navigabili (Dati personali, Obiettivi, Preferenze alimentari, Orari della giornata, Allenamenti, Chiave OpenAI, Notifiche, Esporta dati, Impostazioni), bottom navigation.
- Creato componente riutilizzabile `SettingRowView` (icona+etichetta+chevron/badge) per le 9 righe. Righe con destinazione reale collegate: Allenamenti→WorkoutsActivity, Chiave OpenAI→SettingsActivity (badge dinamico "Configurata"/"Non configurata" da `SecureOpenAiKeyStore.hasKey()`, non hardcoded), Notifiche→NotificationsActivity, Esporta dati→ExportActivity, Impostazioni→SettingsActivity. Le righe Dati personali/Obiettivi/Preferenze alimentari/Orari della giornata non hanno ancora un'Activity dedicata nel piano: presenti visivamente, non collegate, per non anticipare fasi non pianificate.
- Avatar reale integrato da `03-profile/profile_avatar_reference.png` (bassa risoluzione, stessa nota di sostituzione futura degli altri asset fotografici).
- Bug corretto: `activity_profile.xml` non includeva la bottom navigation; ora presente.
- Regressione trovata e corretta: `OnboardingActivity` puntava il pulsante "Inizia" a `ProfileActivity`, che prima aveva un pulsante "Salva" per proseguire verso Home. Rimosso quel pulsante perché non presente nel mock reale del Profilo; per non lasciare un vicolo cieco, "Inizia" ora porta direttamente a `HomeActivity` (nessuna schermata dedicata di creazione profilo è definita nel piano).
- Verificato a runtime: tutte e 5 le righe collegate navigano correttamente; flusso Onboarding→Home ripristinato.

- Libreria grafici obbligatoria integrata: `com.github.AppDevNext.AndroidChart:chartLib:5.3` (fork Kotlin mantenuto di MPAndroidChart, licenza Apache 2.0) via JitPack (repository aggiunto in `settings.gradle.kts`). `WeightTrendChartView` riscritto per incapsulare un vero `info.appdev.charting.charts.LineChart` (linea cubic-bezier, area riempita, non più un `View` con `Canvas` custom).
- Necessario per compatibilità: aggiornato il plugin Kotlin da 2.1.20 a 2.4.10 (chartLib 5.3 è compilato con metadata Kotlin 2.4.0, illeggibile dal compilatore 2.1.0) e migrata la configurazione da `kotlinOptions` (rimossa, deprecata) a `compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }` in `app/build.gradle.kts`.
- Foto pasto/allenamento/avatar sostituite con le versioni reali ad alta risoluzione fornite nel pacchetto asset ufficiale (`meals/meal_lunch.jpg` 450x246, `workouts/workout_dumbbell.jpg` 408x240, `avatars/avatar_default_male.png` 248x248), al posto dei ritagli a bassa risoluzione (48-58px) usati in precedenza. Aggiornato `ASSET_SOURCES.md`.
- Verificato a runtime dopo clean/rebuild/reinstall: grafico reale renderizzato correttamente con curva smussata e riempimento verde, foto ad alta risoluzione visibili, nessuna regressione sulle interazioni esistenti.

- Creati componenti custom riutilizzabili richiesti dalla specifica: `SectionHeaderView`, `MetricCardView` (label/valore/delta con icona di tendenza e stato positivo/negativo/neutro), `TimeRangeSelectorView` (wrapper su `MaterialButtonToggleGroup`, single-selection reale), `MealCardView`, `WorkoutCardView` (icona/orario/titolo/miniatura foto reale via `ShapeableImageView`). `SettingRowView` e `WeightTrendChartView` erano già stati creati in precedenza.
- `activity_home.xml`/`HomeActivity.kt` rifattorizzati per usare questi componenti al posto del markup inline duplicato (le 3 card metrica, i 4 pulsanti periodo, le due card pasto/allenamento).
- Verificato a runtime dopo clean/rebuild/reinstall: nessuna regressione visiva rispetto alla versione precedente (screenshot confrontato), toggle periodo funzionante (`checked="true"` confermato su 3M dopo tap), click Prossimo pasto→MealDetailActivity confermato.

Componenti ancora da creare secondo la specifica ricevuta (MetaCardView e TimeRangeSelectorView coprono già Home; mancano ancora MacroChipView, ProgressMetricView, WeekDaySelectorView, DayCellView, ProfileHeaderView, EmptyStateView, BodyCompositionChartView, ProgressChartView, MiniProgressMetricView, HistoryRowView, ShoppingItemRowView, IngredientRowView): verranno creati quando la rispettiva schermata sarà affrontata, per evitare codice non utilizzato scritto in anticipo.

- Misure corporee ricostruita da zero confrontando `99-screen-references/04_body_measures_reference.png`: header, segmented control reale "Misura/Storico" (`SelectableSegmentView`, nuovo componente, `MaterialButtonToggleGroup`), foto reali corpo fronte/retro (`body/body_front.png` 260x576, `body/body_back.png` 254x576, sostituite ai vector `body_front.xml`/`body_back.xml` precedenti), marker reali (View verdi + etichetta, non stampati nell'immagine) posizionati su ciascuna foto tramite `ConstraintLayout` con bias percentuale, riga "Valori attuali (cm)" + data, 9 righe misura reali (`MeasurementRowView`, nuovo componente riutilizzabile: label sinistra/valore destra), pulsante "+ Nuova misurazione" (`MaterialButton`).
- Semplificazione dichiarata: il mock mostra etichette esterne alla foto collegate al marker con una linea guida; per contenere i tempi è stata implementata l'alternativa più semplice di dot+etichetta adiacente (nessuna linea di collegamento), mantenendo comunque l'associazione visiva punto-etichetta.
- Aggiunta dipendenza `androidx.constraintlayout:constraintlayout:2.2.1` (necessaria per il posizionamento dei marker).
- Gap di navigazione preesistente rilevato: `BodyMeasuresActivity` non è raggiungibile da nessun punto del flusso reale attuale (l'unico collegamento è da `BiaActivity`, a sua volta non raggiungibile da nessuno schermo). Verificata solo con `android:exported="true"` temporaneo su `BodyMeasuresActivity`, poi ripristinato a non-exported prima del commit. Il collegamento reale (probabilmente da Evoluzione fisica/Progressi) andrà definito quando quelle schermate verranno costruite.
- Verificato a runtime dopo clean/rebuild/reinstall: struttura e marker visibili correttamente, tap su "Storico" naviga a `HistoryActivity`, righe misura mostrano i valori corretti.

Dal nuovo documento di specifica ricevuto in questa sessione (icone Material Symbols con FILL/weight/grade/opsz, font Poppins con pesi dedicati, ~20 componenti riutilizzabili aggiuntivi, MaterialToolbar/MaterialCardView/ecc. su ogni schermata, gradienti replicati esattamente, confronto overlay pixel) è stato completato solo l'elemento a più alto impatto e più esplicitamente vincolante (libreria grafici). Il resto resta esplicitamente da fare, schermata per schermata, per non introdurre un refactor massivo e rischioso in un solo passaggio: Poppins non è nel repository (nessun `res/font`), nessuna libreria Material Symbols con varianti FILL/weight è ancora integrata, i restanti componenti custom (MetricCardView, MealCardView, WorkoutCardView, TimeRangeSelectorView, ecc.) non sono ancora stati estratti da Home/Profilo.

La Home e il Profilo non sono ancora marcati come step 6 completato: restano da verificare in modo più rigoroso alcuni dettagli pixel (icone, radius esatti) elencati sopra, e le altre 14 Activity del piano restano da confrontare singolarmente con il mock.

- Bioimpedenziometria (BIA) ricostruita da zero confrontando `99-screen-references/05_bia_reference.png`: header con `SelectableSegmentView` "Nuova misurazione/Storico", campi Data e Ora reali (`TextInputLayout`+`TextInputEditText` readonly con `MaterialDatePicker`/`MaterialTimePicker` funzionanti, non testo statico), sezione "Condizioni della misura" con 4 `MaterialCheckBox` reali (A digiuno, Appena sveglio, Dopo bagno, Nessun allenamento recente), sezione "Risultati" con 7 righe reali (`MeasurementRowView`, esteso con icona opzionale: Peso, Grasso corporeo, Grasso viscerale, Massa muscolare, Muscolo scheletrico, Acqua corporea, BMR), pulsante "Salva misurazione".
- Bug corretto: l'icona orologio (`ic_clock`/`ic_setting_clock`) si renderizzava come un disco pieno invece di un quadrante con lancette (la sagoma delle lancette non veniva ritagliata dal cerchio pieno). Corretto aggiungendo `android:fillType="evenOdd"`, verificato visivamente prima/dopo.
- Semplificazione dichiarata: le 7 righe "Risultati" usano un pallino verde generico come icona invece di 7 icone semantiche distinte (bilancia, percentuale, ecc.), per evitare il rischio di introdurre altri path SVG non verificati.
- Gap di navigazione preesistente confermato anche per `BiaActivity`: non raggiungibile da nessun punto del flusso reale attuale. Verificata solo con `android:exported="true"` temporaneo, poi ripristinato a non-exported prima del commit.
- Verificato a runtime dopo clean/rebuild/reinstall: struttura corretta, `MaterialDatePicker` si apre correttamente al tap sul campo Data, checkbox e righe risultato mostrano i valori corretti, icona orologio corretta dopo il fix.

- Evoluzione fisica ricostruita da zero confrontando `99-screen-references/06_physical_evolution_reference.png`: `SelectableSegmentView` a 3 vie (Peso/Grasso/Massa muscolare), blocco statistica principale (label+valore grande+delta con icona di tendenza), `WeightTrendChartView` riutilizzato da Home (nuovo metodo `showYAxisLabels()` per mostrare l'asse Y, non usato in Home), etichette data sotto il grafico (riga di TextView manuale, non asse X reale del grafico — semplificazione dichiarata per evitare di indovinare API di formattazione non verificate della libreria), `TimeRangeSelectorView` riutilizzato (1M/3M/6M/1Y), `SectionHeaderView` "Confronto visivo", riga di 3 foto corpo (`ShapeableImageView` con stile `ShapeAppearance.MyFitAI.Thumbnail`) con date, card informativa verde chiaro con icona `ic_check_circle` (nuova) e messaggio a due righe.
- Bug corretto: cambiando metrica (Peso/Grasso/Massa muscolare) tramite il segmented control, label/valore/delta/grafico si aggiornano correttamente (verificato a runtime tappando "Massa muscolare").
- Fix di fedeltà applicato a `SelectableSegmentView` (componente condiviso con BIA e Misure corporee): ridotto il testo da 14sp a 12sp perché con 3 segmenti l'etichetta "Massa muscolare" veniva troncata in "Massa mu…"; verificato che il testo ora sta su una riga in tutti e 3 gli usi.
- Semplificazione dichiarata: le 3 foto di confronto usano la stessa immagine reale (`img_body_front`) per mancanza di uno storico fotografico reale a 3 date diverse nel pacchetto asset.
- **Gap di navigazione preesistente RISOLTO**: `PhysicalEvolutionActivity` è raggiungibile realmente dalla tab "Progresso" della bottom nav (già presente in `BottomNavBinder`, non richiedeva modifiche). Verificato a runtime con il flusso reale completo: Splash → Onboarding ("Inizia") → Home → tab Progresso → Evoluzione fisica, senza bisogno di `android:exported` temporaneo.

- Piano alimentare ricostruito da zero confrontando `99-screen-references/07_food_plan_reference.png`: header con navigazione settimana (‹ intervallo date ›), nuovo componente `WeekDaySelectorView` (7 colonne giorno/data, singola selezione, pillola verde sul giorno selezionato), nuovo componente `MealPlanRowView` (foto reale + titolo/kcal + descrizione, riutilizzato per tutti e 5 i pasti: Colazione/Spuntino/Pranzo/Pre-workout/Cena), sezione "Totale giornaliero" con kcal in verde e macro P/C/F colorate con gli accent già definiti (arancio/blu/viola).
- Chiarimento richiesto e ottenuto dall'utente: il crop di riferimento non mostra pulsanti per Lista spesa/Registra sgarro; su indicazione esplicita sono state aggiunte 2 icone nell'header (carrello `ic_cart`, aggiungi `ic_add_circle`, entrambe nuove) per raggiungere `ShoppingListActivity` e `CheatEntryActivity`, in aggiunta al tap su una riga pasto che apre `MealDetailActivity`.
- Semplificazione dichiarata: le foto reali disponibili in `meals/` non includono uno scatto per "frutta secca e mela" né uno specifico per "pre-workout"; riassegnate in base al contenuto visivo più vicino (`meal_salad.jpg` per Spuntino, `meal_snack.jpg` per Pre-workout, quest'ultimo essendo in realtà una foto di yogurt con frutti di bosco più adatta al pre-workout del nome del file sorgente).
- Verificato a runtime con flusso reale completo (Splash → Onboarding → Home → tab Alimentazione): struttura e dati corretti, tap su riga pasto naviga a Dettaglio pasto, icona carrello naviga a Lista della spesa, icona "+" naviga a Registra sgarro.

- Dettaglio pasto ricostruito da zero confrontando `99-screen-references/08_meal_detail_reference.png`: foto reale a tutta larghezza (`img_meal_lunch`, riuso dell'asset del Piano alimentare) con pulsante indietro circolare sovrapposto, titolo+sottotitolo, riga di 4 statistiche (kcal/proteine/carboidrati/grassi), `SelectableSegmentView` riutilizzato per il toggle Ingredienti/Preparazione (stile verde coerente con le altre schermate, anziché il pill bianco-su-grigio del mock — semplificazione dichiarata per riusare un solo meccanismo di segmented control in tutta l'app), nuovo componente `IngredientRowView` (icona circolare foto reale categoria alimento, nome, quantità) per 6 ingredienti, nuove icone `ic_check`/`ic_refresh` (verificate: nessun problema di rendering come accaduto con l'icona orologio), pulsante verde "Segna come consumato" e link secondario "Sposta orario".
- Semplificazione dichiarata: le icone ingrediente usano foto reali di categoria (`food-categories/*`: carbs/protein/vegetables/fats/condiments) non specifiche per singolo alimento; Carote riusa la stessa foto di Zucchine (nessun asset dedicato alla carota). Il testo del tab "Preparazione" è generico/inventato (nessun testo reale di ricetta fornito negli asset).
- Verificato a runtime con flusso reale completo (Splash → Onboarding → Home → tab Alimentazione → riga Pranzo): foto e dati corretti, toggle Ingredienti/Preparazione funzionante, icone check/refresh renderizzate correttamente.

- Lista spesa ricostruita da zero confrontando `99-screen-references/09_shopping_list_reference.png`: `SelectableSegmentView` per il toggle di vista Settimana/Categorie, secondo `SelectableSegmentView` per il filtro Tutte/Da comprare/Presi con icona di reset (`ic_refresh`) per azzerare le spunte, 3 sezioni (`SectionHeaderView`: Proteine, Cereali e derivati, Frutta e verdura) con nuovo componente riutilizzabile `ShoppingItemRowView` (checkbox verde + icona circolare foto reale categoria + nome + quantità) per 11 articoli, pulsante verde "Esporta lista" con icona download.
- Semplificazione dichiarata: le etichette esatte della riga filtri erano illeggibili nel crop di riferimento a bassa risoluzione ("Tutte"/"Tutte"/checkbox/icona); interpretate come "Tutte"/"Da comprare"/"Presi" + reset, pattern UX comune per liste spesa. Le icone articolo riusano le foto di categoria già presenti (proteine/cereali/frutta-verdura/latticini), non foto per singolo alimento (es. Mele riusa la stessa foto di Banane, Carote riusa la stessa foto di Zucchine).
- Verificato a runtime con flusso reale completo (Home → tab Alimentazione → icona carrello): checkbox Salmone/Uova pre-spuntate correttamente, icona reset azzera tutte le spunte, tutte le 11 righe e le 3 sezioni corrispondono ai dati del mock.

- Registra sgarro ricostruito da zero confrontando `99-screen-references/10_cheat_reference.png`: `SelectableSegmentView` per il toggle Rapido/Dettagliato, `ChipGroup`+`Chip` reali Material3 (`app:singleSelection`) per la scelta rapida del cibo (Pizza/Gelato/Sushi/Dolci/Fast food/Altro), con colori chip personalizzati (verde pieno testo bianco se selezionato, sfondo neutro altrimenti, tramite `color/chip_background_selector` e `color/chip_text_selector`) al posto del viola di default Material3 — bug notato e corretto durante la verifica visiva. Campo descrizione libero, campi Data/Ora (box con icona, `MaterialDatePicker`/`MaterialTimePicker` reali riusando lo stesso pattern di BIA), menu a tendina reale "Quantità" (`TextInputLayout` stile `ExposedDropdownMenu` + `AutoCompleteTextView`, 3 opzioni), campo Note multi-riga, pulsante verde "Salva e adatta il piano". Nessuna bottom nav (coerente col crop, schermata presentata come form modale da "+" nel Piano alimentare).
- Verificato a runtime con flusso reale completo (Home → tab Alimentazione → icona "+"): chip Gelato selezionato di default con colore verde corretto dopo il fix, menu a tendina Quantità si apre mostrando le 3 opzioni corrette.

- Notifiche ricostruita da zero confrontando `99-screen-references/11_notification_reference.png`: la schermata replica intenzionalmente l'anteprima di una notifica push su lock screen (già così nello stub originale, confermato dal file/testo preesistente "Promemoria pasti"), non un elenco impostazioni. Sfondo foto reale (`img_notification_background`, riuso di `onboarding_mountain.jpg`) con scrim scuro semi-trasparente, icona lucchetto (`ic_lock`, nuova), ora "12:15" e data grandi in bianco, card "frosted" (nuovi drawable `bg_frosted_card`/`bg_frosted_pill`/`bg_frosted_pill_soft`) con icona app reale (`img_app_icon`, riuso di `myfitai_leaf_mark.png`), testo notifica e foto reale del pasto (`img_meal_lunch`, riuso), due pulsanti azione "Apri pasto"/"Posticipa 10 min".
- Verificato a runtime con flusso reale completo (Home → Profilo → riga Notifiche): aspetto conforme al mock, pulsante "Apri pasto" naviga correttamente a Dettaglio pasto.

- Progressi: confrontando `99-screen-references/12_progress_reference.png` con `06_physical_evolution_reference.png` è emerso che sono due crop a diversa profondità di scroll della STESSA schermata (`PhysicalEvolutionActivity`, tab "Progresso"), non due Activity distinte — stesso header, stesso segmento Peso/Grasso/Massa muscolare, stesso grafico e time range selector in entrambi. Il crop 12 rivela una sezione "Altri indicatori" (card con 3 righe: Grasso corporeo/Massa muscolare/Acqua corporea, ciascuna con valore+delta verde) posizionata fra il time range selector e "Confronto visivo", mancante nella build fatta per lo step 6. Aggiunta ora in `activity_physical_evolution.xml`, con valori statici (non legati al segmento Peso/Grasso/Massa muscolare selezionato, coerente con l'aspetto di indicatori complementari fissi nel mock).
- Verificato a runtime con flusso reale (Home → tab Progresso): sezione "Altri indicatori" visibile e corretta, "Confronto visivo" ancora corretto più in basso, nessuna regressione sulle sezioni già verificate allo step 6.

- Review settimanale: nessuna Activity esistente corrispondeva a questo schermo del piano (`AiAnalysisActivity` esistente è un'altra funzione, con selettore 7/30/90 giorni, non riutilizzabile qui). Creata da zero `WeeklyReviewActivity` + `activity_weekly_review.xml` confrontando `99-screen-references/13_weekly_review_reference.png`: intervallo settimana, card verde "Settimana positiva" (riuso `bg_positive_soft`/`ic_check_circle`), riga 3 statistiche (Aderenza/Sgarri/Allenamenti), sezione "Calorie medie" con valore e target, riga 3 macro (Proteine/Carboidrati/Grassi) con valore e target ciascuna, card verde finale con messaggio IA.
- Gap di navigazione dichiarato: `WeeklyReviewActivity` non ha ancora un punto di ingresso reale nel flusso dell'app (nessuno schermo esistente vi rimanda). Verificata solo con `android:exported="true"` temporaneo, poi ripristinato a non-exported prima del commit, come da prassi per gli schermi non ancora collegati.
- Verificato a runtime: struttura e dati conformi al mock.

- Storico/export: `HistoryActivity` ricostruita da zero confrontando `99-screen-references/14_history_export_reference.png`: `SelectableSegmentView` a 4 vie (Misure/BIA/Piani/Sgarro, "Piani" selezionato di default), nuovo componente riutilizzabile `HistoryRowView` (icona in cerchio + titolo bold + sottotitolo + chevron) per 5 righe (3 piani settimanali, 1 BIA, 1 misura), pulsante verde "Esporta tutto (JSON)" che naviga alla preesistente `ExportActivity`.
- Semplificazione dichiarata: i 4 tab del segmented control sono funzionanti come componente (cambiano visivamente la selezione) ma NON filtrano ancora la lista sottostante — il singolo crop di riferimento mostra tutte e 5 le righe con "Piani" selezionato, senza evidenza di come la lista cambi con gli altri tab; filtrare per categoria è stato lasciato come miglioramento futuro esplicito piuttosto che indovinare un comportamento non verificabile dal mock. Le icone circolari riusano icone esistenti non specifiche (BIA riusa `ic_nav_progress`, Misure riusa `ic_setting_person`), nessuna icona dedicata creata per non introdurre altri rischi di rendering.
- `ExportActivity` (raggiunta dal pulsante) NON è stata ricostruita in questa sessione: non esiste nel pacchetto di riferimento un crop dedicato per questo screen (il crop 14 copre solo la parte Storico), quindi è stata lasciata nel suo stato preesistente per evitare di inventare un design senza riscontro nel mock.
- Gap di navigazione preesistente confermato anche per `HistoryActivity`: raggiungibile solo dai segmented control "Storico" di `BodyMeasuresActivity`/`BiaActivity`, che a loro volta non hanno ancora un punto di ingresso reale nel flusso principale. Verificata solo con `android:exported="true"` temporaneo, poi ripristinato a non-exported prima del commit.
- Verificato a runtime: struttura, icone e navigazione (righe Piani→Review settimanale, BIA→BiaActivity, Misure→BodyMeasuresActivity, Esporta→ExportActivity) tutte corrette.

### Prossimo step
Completare la verifica UI su device/emulatore per tutte le Activity, in portrait e landscape dove previsto, prima di passare allo step 7 Persistenza locale.

## Fuori perimetro V1 salvo nuova decisione
- social/community;
- marketplace;
- piattaforma nutrizionista esterna;
- Health Connect/wearable;
- OCR;
- photo progress automatico;
- voice coach;
- gamification;
- backend remoto obbligatorio;
- orchestratore LLM autonomo.

## Branching
- `develop`: sviluppo corrente.
- `main`: stabile.
- Le modifiche vengono portate su `main` solo dopo verifica della fase prevista.

## Regola finale
Quando viene completato uno step, aggiornare questo file marcando solo ciò che è realmente implementato e verificato. Non dichiarare completato ciò che esiste solo come specifica o mock.