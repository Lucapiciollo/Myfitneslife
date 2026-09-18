# MyFitAI — Piano di sviluppo autorevole

Questo documento è la fonte di verità per lo stato attuale del progetto. Va aggiornato quando cambia il comportamento reale del codice. Non segnare come verificato ciò che non è stato compilato, testato o provato su device/emulatore.

## Obiettivo V1
App Android personale, local-first, per monitorare profilo corporeo, BIA e misure, seguire i trend fisici, calcolare target nutrizionali dinamici localmente, generare piani alimentari settimanali strutturati via IA, adattare solo i pasti futuri, mantenere storico/versioni, lista spesa deterministica, notifiche locali, review ed export.

## Regole architetturali vincolanti
- L'app è l'orchestratore, non l'LLM.
- Calcoli numerici affidabili locali/deterministici.
- Pipeline: `App -> Local Calculation Engine -> AiProvider -> Agent -> JSON -> Schema Validator -> Business Validator -> Persistenza -> UI`.
- Gemini e OpenAI devono produrre contratti canonici equivalenti.
- `agentValidation` è informativa; `appValidation` è autorevole.
- Tolleranza nutrizionale ufficiale: dal target -3% al target, mai sopra il target.
- Piani alimentari immutabili/versionati.
- Nessuna compensazione punitiva.
- I pasti già trascorsi restano storico.
- Tutti gli agenti lavorano solo nel dominio nutrizionale.
- BIA, misure, allenamenti e storico possono essere usati soltanto come contesto per decisioni nutrizionali.
- Nessuna diagnosi medica, coaching fitness generico o inferenza sanitaria non supportata.

## STATO IMPLEMENTAZIONE

### Fondazione / UI
- [x] Material 3 / Android Views.
- [x] Tema chiaro/verde.
- [x] Bottom navigation condivisa.
- [x] Splash, Onboarding, Home, Profilo, BIA, Misure, Progressi, Allenamenti, Piano alimentare, Dettaglio pasto, Lista spesa, Sgarro, Piano adattato, Notifiche, Storico, Review, Export, Impostazioni.
- [x] Componenti grafici reali per trend.
- [x] Hub Rilevazioni separato per inserimento e gestione di BIA e misure corporee.
- [x] Home dashboard aggiornata con giorno corrente, azioni rapide e stati vuoti contestuali.
- [x] Root tab operative in modalita standalone: Home, Progresso e Altro restano navigabili senza provider IA; Alimentazione resta protetta dal gate provider. Le quattro root sono Fragment persistenti dentro `TabHostActivity`.
- [x] QA pixel/runtime delle tab principali e del tracking consumi verificato su device; smoke lifecycle delle Activity locali eseguito su device.

### Persistenza e profilo
- [x] Room con migrazioni esplicite e senza destructive fallback.
- [x] Multi-profilo con profilo attivo.
- [x] Profilo attivo come sessione unica di processo: tutte le istanze di `ActiveProfileStore` condividono lo stato, senza profileId obsoleti dopo un cambio profilo.
- [x] Sesso biologico esplicito e peso iniziale.
- [x] BIA persistente.
- [x] Misure corporee persistenti.
- [x] Allenamenti/rest day persistenti.
- [x] Piani/versioni/giorni/pasti/ingredienti persistenti.
- [x] Sgarri e review persistenti.
- [x] DB v4: ogni giorno del piano supporta `supplementsJson` e `hydrationNote`.
- [x] Migration test reale 3 -> 4 su database storico.
- [x] DB v5: consumi alimentari persistenti con snapshot nutrizionale, stato consumato/saltato e isolamento per profilo, piano e giorno.
- [x] DB v6: usage IA persistiti con token, pricing snapshot e costo stimato per provider/modello.
- [x] DB v8: dispendio energetico per allenamento persistente, default `500 kcal` per allenamenti reali, nessuna riga per i giorni di riposo.
- [x] Dispendio allenamento aggiornabile con origine `EXTERNAL_APP`, timestamp e lettura giornaliera per il ricalcolo locale.
- [x] DB v9: intensità percepita opzionale `1-10` persistente per gli allenamenti.

### Motore locale
- [x] BMI.
- [x] BMR con priorità Katch-McArdle quando body fat è disponibile e plausibile.
- [x] Fallback Mifflin-St Jeor con sesso biologico.
- [x] TDEE.
- [x] TDEE giornaliero = TDEE base + dispendio esercizio letto dal ledger workout; il BMR e il TDEE base restano separati per trasparenza.
- [x] Totale alimentazione separa cibo registrato, esercizio registrato e bilancio netto; le kcal di allenamento non vengono sommate artificialmente al cibo consumato.
- [x] Recovery tank: le kcal di esercizio giornaliere riducono l'eccedenza alimentare prima del calcolo del recupero e possono azzerare il prelievo necessario.
- [x] Home: aggiunte pila calorie alimentari saturabile al target e barra separata del serbatoio recovery, con percentuale, kcal residue ed esercizio considerato.
- [x] Home: serbatoio recovery spostato subito sotto la pila calorie nella card energetica; visibile anche senza recovery attivo con stato `Nessun recovery attivo`.
- [x] Obiettivo energetico: aggiunta descrizione operativa sotto `Ricomposizione` e testi equivalenti per gli altri obiettivi; verificata su device.
- [x] Obiettivo energetico: corretto layout della descrizione, ora sotto la riga titolo/badge senza comprimere o spostare il `−5%`; grafica Home verificata su device.
- [x] Trend corporeo Home: trasformato in card `Lettura del trend corporeo`, con situazione sintetica in grassetto e spiegazione separata del criterio grasso/massa muscolare.
- [x] Trend corporeo Home: card resa più esplicita con titolo `Cosa significa il trend`, situazione semantica sintetica e chiarimento che il confronto non è una diagnosi.
- [x] Home: card `Oggi` semplificata rimuovendo il duplicato del prossimo pasto; ora mostra solo calorie registrate, pasti completati e stato allenamento con colori e gerarchia semantica.
- [x] Home: aggiunto tasto rapido `Vedi menu di oggi` con modale contenente il menu completo della giornata, orari, kcal e nomi dei pasti; test device `home_todayMenuButton_opensTodayMenuDialog` PASS.
- [x] Target calorico dinamico.
- [x] Proteine/grassi/carboidrati dinamici.
- [x] Trend peso/body fat/massa muscolare/vita.
- [x] Qualità comparabilità BIA e interpretazione locale deterministica dei trend corporei.
- [x] Waist/height ratio.
- [x] Classificazione ricomposizione.
- [x] Business Validator locale con range target -3% .. target.
- [x] Import BIA da foto con JSON strutturato, preview modificabile e conferma prima del salvataggio.
- [x] Import BIA mantiene immagini temporanee solo in memoria/cache privata e riusa lo storico Room esistente.
- [x] Agent import BIA strict: immagini non-BIA vengono rifiutate senza valori, preview o persistenza.
- [ ] Verifica reale Gemini/OpenAI dell'agent di import BIA con immagine autorizzata.

### Generazione piano alimentare
- [x] Target calcolati localmente prima dell'IA.
- [x] Piano settimanale strutturato.
- [x] Quantità, unità, `displayDose`, `weightState`, `nutritionConfidence`.
- [x] Condimenti e bevande caloriche conteggiati.
- [x] Stagionalità come preferenza.
- [x] Timing coerente con allenamenti e orari.
- [x] Nessuna allergia/intolleranza/diagnosi inventata.
- [x] BIA e trend corporei passati come contesto descrittivo al Nutrition Agent.
- [x] La BIA non può essere usata come prova diretta di carenza proteica o disidratazione.

### Nutrizione sportiva e integrazione
- [x] `SportsNutritionClassifier` locale con modalità `NORMAL` / `SPORT`.
- [x] Classificazione derivata da activity level + allenamenti settimanali, non da un flag manuale.
- [x] Proteine in polvere consentite anche in `NORMAL` se utili al target proteico o alla praticità nutrizionale.
- [x] Proteine in polvere conteggiate in kcal e macro.
- [x] Creatina consentita solo in `SPORT`.
- [x] Creatina vincolata a 0 kcal / 0 macro.
- [x] Integratori separati dai pasti nel modello canonico.
- [x] `hydrationNote` separata dai pasti.
- [x] Validator locale blocca integratori non ammessi e verifica la coerenza dei totali.
- [x] Pasti normali restano la prima scelta; integratori sono strumenti opzionali.

### Consiglio nutrizionale one-shot
- [x] Pagina “Chiedi all'IA”.
- [x] Nessuna conversazione persistita.
- [x] Output compatto/tabellare.
- [x] Esattamente 5 suggerimenti ordinati dal migliore al peggiore.
- [x] Richieste fuori dominio nutrizionale rifiutate.
- [x] Per richieste generiche l'orario è un criterio forte.
- [x] Se l'utente chiede esplicitamente un alimento, il desiderio alimentare ha priorità sul meal window; l'orario influenza porzione/timing, non sostituisce il cibo richiesto.
- [x] Accettazione esplicita -> flusso esistente di registrazione/adattamento.

### Cambio pasto IA
- [x] Icona cambio per ogni pasto futuro, inclusa la card del prossimo pasto in Home.
- [x] 5 alternative.
- [x] Stesso tipo/orario del pasto.
- [x] Calorie alternative vincolate a `> 0` e `<=` quelle del pasto originale.
- [x] Macro ricalcolati dal nuovo pasto e verificati dall'app; nessun pasto successivo viene modificato.
- [x] Nessuna persistenza prima dell'accettazione.
- [x] Modale di conferma prima della generazione e prima dell'applicazione.
- [x] Nuova versione immutabile del piano dopo accettazione.
- [x] Protezione stale-plan.
- [x] Integrazione e hydration note vengono preservate quando un pasto viene sostituito.
- [x] Eventuale eccedenza giornaliera registrata nel recovery ledger senza compensare automaticamente pasti successivi.
- [x] Raccomandazione locale di proteine in polvere quando il nuovo totale giornaliero è sotto il target proteico; nessuna aggiunta automatica.
- [x] Test contratto dedicato: alternative con calorie inferiori accettate e alternative sopra il pasto originale rifiutate.
- [x] Test servizio/integration dedicato per recovery aggiunto e deficit proteico post-sostituzione (`AiWorkflowIntegrationTest` 9/9).
- [x] Recovery `MEAL_SWAP` idempotente per profilo/giorno: sostituzioni successive non duplicano l'evento attivo.
- [x] Test UI Home per presenza del controllo cambio pasto quando esiste un pasto futuro (`BottomNavigationUiTest` 6/6).

### Sgarro / deviazione
- [x] Descrizione testuale.
- [x] Foto etichetta opzionale e temporanea.
- [x] Preview di comprensione prima della conferma.
- [x] Possibilità di chiarire e rivalutare.
- [x] Persistenza solo dopo conferma.
- [x] Adattamento dei soli pasti futuri.
- [x] Nessuna compensazione punitiva.

### Personal Response / Weekly Review / Shopping / Notifiche / Export
- [x] Personal Response Engine.
- [x] Weekly Review.
- [x] Tracking consumi deterministico con copertura e aderenza nella Weekly Review.
- [x] Lista spesa deterministica.
- [x] Notifiche locali con refresh su cambio piano.
- [x] Export JSON / CSV ZIP / PDF profilo / PDF piano settimanale.
- [x] Consumi inclusi negli export JSON e CSV ZIP.
- [ ] QA runtime completo degli export più recenti.

### Sicurezza OpenAI BYOK
- [x] Android Keystore come root of trust.
- [x] AES-GCM.
- [x] Nessuna API key in chiaro in Room, SharedPreferences normali, file, log, crash report, backup, analytics, repository, BuildConfig, intent o export.
- [x] Backup disabilitato.

### Provider Gemini BYOK via Gemini Developer API
- [x] Gemini usa una API key personale cifrata con Android Keystore + AES-GCM.
- [x] Provider Gemini diretto con structured output, schema canonico e immagini in memoria.
- [x] Firebase AI Logic non è usato nel runtime Gemini; Firebase generico resta configurabile.
- [x] OpenAI e Gemini condividono il credential store cifrato e la stessa interfaccia `AiProvider`.
- [ ] Verifica runtime provider Gemini e parità reale Gemini/OpenAI. Gemini BYOK reale risponde con HTTP/usageMetadata e passa alla PlanReviewAgent, ma il workflow weekly-plan sul device non ha ancora persistito una nuova versione dopo retry locali (`PIPE_H_INVALID`, `PIPE_S_INVALID`, `TARGET_TOLERANCE_EXCEEDED`); nessun output invalido è stato salvato.
- [x] Generazione piano: spostata su WorkManager con unique work per profilo/settimana, rete obbligatoria, retry solo per errori temporanei e nessun retry automatico per output/schema/business invalidi; la UI osserva lo stato persistente anche dopo cambio tab o ricreazione.
- [x] Generazione piano: al completamento positivo del worker viene inviata una notifica `Piano alimentare aggiornato` su canale dedicato, con apertura diretta della settimana nel tab Alimentazione; nessuna notifica per errori o output rifiutati.
- [ ] Verifica device completa WorkManager/notifica piano: build e wiring verificati, ma il run runtime Gemini non è ancora certificato perché il device ha mantenuto una generazione precedente pendente e ha prodotto un ANR di avvio separato; nessuna notifica di successo è stata dichiarata senza persistenza reale del piano.
- [x] Risolta la regressione di avvio che impediva il test WorkManager: `activity_food_plan.xml` aveva due `LinearLayout` senza `layout_height`, causando `InflateException`/ANR di `TabHostActivity`; avvio pulito e smoke UI Alimentazione/Home `2/2 PASS` su device.
- [x] Generazione piano verificata end-to-end sul device: WorkManager → Gemini → validazione locale → nuova versione persistita (`Piano v4`) → notifica `Piano alimentare aggiornato` → tab Alimentazione aggiornata; esito terminale consumato una sola volta e non ripetuto a riapertura.
- [x] Convergenza validazione nutrizionale: il rifiuto `TARGET_TOLERANCE_EXCEEDED` ora riporta giorno, macro fuori range, intervallo ammesso e valore obiettivo (`target - tolleranza/2`), e l'istruzione di retry chiede di correggere solo le porzioni del giorno indicato. Prima il retry riceveva solo il codice di errore e non convergeva mai.
- [x] Parser compatto: il record `H` (nota idratazione, testo libero) tollera separatori extra ricongiungendo il testo; i record numerici restano rigorosamente posizionali.
- [x] Infrastruttura comune per tutte le operazioni IA: `AiJobType` (canale notifica per tipo), `AiJobScheduler` (unique work, cancel, consume), `AiJobWorker` (dispatch, retry solo per errori transitori, notifica esito), `AiJobRegistry` (handler per operazione) e tabella `ai_job_results` con migrazione `MIGRATION_9_10` per il riaggancio della UI dopo la notifica.
- [x] Migrate su job in background con notifica e verificate sul device: generazione piano settimanale (canale `ai_weekly_plan`) e analisi progressi manuale (canale `ai_progress_analysis`), quest'ultima prima eseguita in `lifecycleScope` e quindi persa uscendo dalla schermata.
- [x] Review settimanale migrata su job in background con notifica (canale `ai_weekly_review`); la UI si riaggancia al job e ricarica la review persistita.
- [x] Rimosso il percorso duplicato `NutritionPlanGenerationScheduler`/`NutritionPlanGenerationWorker` e la notifica `showPlanGenerated`, sostituiti dall'infrastruttura comune.
- [ ] Operazioni IA ancora eseguite nello scope della UI, da migrare sull'infrastruttura comune: consiglio nutrizionale, interpretazione proporzioni corporee, analisi sgarro e adattamento pasti, alternative pasto, import BIA da foto. Handler già pronti per consiglio nutrizionale e proporzioni corporee, UI non ancora collegata.
- [x] Consiglio nutrizionale e interpretazione proporzioni corporee collegati all'infrastruttura comune: richiesta in WorkManager, risultato persistito/riagganciabile, notifica su canali `ai_nutrition_advice` e `ai_body_proportions`, deep-link alla schermata corretta. Build debug, test unitari e suite UI `8/8 PASS`; notifica consiglio verificata sul device in modalità QA senza nuova chiamata provider.
- [x] Flusso sgarro predisposto sull'infrastruttura comune a due fasi: `CHEAT_UNDERSTANDING` analizza/stima senza persistere, salva preview+input normalizzati e notifica su `ai_cheat`; solo dopo conferma esplicita viene accodato `CHEAT_ADJUSTMENT`, che riusa la preview confermata, applica l'adattamento dei soli pasti futuri e notifica il risultato. L'immagine etichetta passa da file temporaneo privato e viene cancellata solo dopo esito definitivo, mantenendola durante i retry transitori.
- [ ] Verifica device del flusso sgarro: build debug e migrazione `9->10` `6/6 PASS`; il device `RZCX924RQMV` si è disconnesso prima del test runtime UI/WorkManager, quindi nessun PASS device dichiarato.
- [x] Alternative pasto migrate all'infrastruttura comune: `MEAL_ALTERNATIVES` genera in WorkManager, persiste piano/versione/pasto/5 alternative nel risultato del job, notifica su `ai_meal_alternatives` e riapre `MealAlternativeActivity`; la sostituzione resta un'azione esplicita e continua a usare il controllo `StalePlan` prima di creare una nuova versione.
- [x] Verifica device delle alternative pasto: Gemini ha inizialmente prodotto `MA_P` e poi marker `V` intermedi; il parser compatto ora normalizza numeri con virgola, espone errori diagnostici e ricompone i marker `V` intermedi mantenendo la validazione locale. Run finale verificato sul device: `AiJobWorker` `SUCCESS`, 5 alternative persistite/riagganciate in `MealAlternativeActivity`, notifica `Alternative pasto pronte` su `ai_meal_alternatives`.
- [x] Import BIA da foto: il passaggio dell'immagine al worker avviene tramite file temporaneo privato, mai tramite base64 in WorkManager/Room.
- [x] Import BIA da foto collegato all'infrastruttura comune: immagine copiata in file temporaneo privato, percorso passato al worker, file cancellato dopo esito definitivo, risultato/errore persistito e notifica su `ai_bia_import`, riaggancio a `BiaActivity` senza salvataggio automatico nello storico.
- [x] Parser BIA reso prudente sui timestamp opzionali: epoch millis, ISO instant/date e formati `dd/MM/yyyy`, `dd-MM-yyyy`, `dd.MM.yyyy`; timestamp non interpretabile diventa assente senza inventare una data e non blocca valori numerici leggibili. Aggiunto test unitario dedicato.
- [x] Verifica device positiva import BIA: foto dalla galleria → file temporaneo privato → `AiJobWorker` → Gemini con retry protocollo → `Worker SUCCESS` → notifica `ai_bia_import` → preview riagganciata in `BiaActivity`. Valori letti e mostrati senza salvataggio automatico: peso `90,7 kg`, grasso `20,4%`, viscerale `6`, massa `67,3 kg`, scheletrico `41,5 kg`, acqua `52,9%`; provider `GEMINI · gemini-3.5-flash-lite`. Conferma storico lasciata all'utente.
- [x] Modale preview BIA ridisegnata secondo il tema MyFitAI: stato lettura in card verde, provider/confidenza separati, valori in griglia a due colonne, campi Material filled, scroll interno controllato e azioni di conferma/annullamento coerenti. Build, unit test e APK AndroidTest verificati dopo la modifica.
- [x] Rifinitura modale/form BIA: rimossi gli underline duplicati causati dalla combinazione `TextInputLayout`/`TextInputEditText`, sostituiti con card compatte a tema con label, valore e unità; `bindMeasurementRows()` ora conserva e mostra i valori importati dopo `Usa valori` invece di reimpostare sempre `—`. Build, installazione device e avvio pulito verificati.
- [x] Parser BIA aggiornato per normalizzare anche payload Gemini concatenati/serializzati (`BIA1B|...`, `BIA1 B|...`, `BIA1\\nB|...`) prima della validazione posizionale; build e avvio device verificati senza crash.
- [x] Schermata BIA riallineata alla grammatica di Misure corporee: header centrato, segmenti coerenti, controlli data/ora con stile centrale, card separate per condizioni e risultati, padding/angoli/stroke dai token MyFitAI e azioni con pulsanti condivisi. Verificata su device dopo installazione APK: `Bioimpedenziometria`, `biaSegment`, data/ora e card risultati presenti, nessun crash.
- [x] Tab centralizzati per tutta l'applicazione: `SelectableSegmentView` ora usa un track `surface_secondary` condiviso, tab attiva verde pieno, testo bianco/secondario, padding e angoli uniformi, content description e selezione singola senza listener duplicati. Applicato automaticamente a BIA, Misure corporee, andamento, storico, spesa, review, alternative e analisi. Build/unit test/androidTest packaging PASS; device verificato su BIA con `Nuova misurazione`, `Storico`, `Andamento` e nessun crash.
- [x] Risolto crash entrando in Progresso introdotto dal nuovo callback tab: `PhysicalEvolutionFragment` usava un formatter Kotlin con spazio nel pattern (`%+.1f $unit`), causando `IllegalFormatFlagsException` durante il render. Corretto con `String.format(..., "%+.1f %s", ...)`, corretto anche il formatter analogo di Home. Device verificato su Progresso con grafico, metricSegment e range selector; suite UI `8/8 PASS`.
- [x] Storico ridisegnato con card giornaliere coerenti con Home: ogni elemento ora è una card autonoma con icona in badge, giorno come titolo principale, data/settimana o riepilogo come sottotitolo, padding e gerarchia tipografica più leggibili; rimossi divisori e contenitore unico piatto. Build/unit test PASS e suite UI device `8/8 PASS`.
- [x] Verifica API key provider: resta volutamente sincrona, non è una generazione di contenuti ma una validazione di credenziale attesa dall'utente al salvataggio.
- [x] Impostazioni piano alimentare: aggiunto switch per generazione automatica (disattivato di default, quindi il pulsante manuale resta l'unico trigger), frequenza `ogni giorno`, `ogni settimana`, `ogni 2 settimane`, `ogni 3 settimane` o `ogni mese`, e configurazione orari pasti per profilo.
- [x] Pattern acquisizione centralizzato: schermata aperta `ProfileEditActivity` usata come riferimento e sezioni raggruppate in card per dati personali, obiettivo/attività, giornata e preferenze; stili comuni per input outlined/date/dropdown e pulsanti. Applicato anche a BIA, nuova misurazione corporea, nuovo allenamento e quantità sgarro senza cambiare id o logica.
- [x] Corretto rendering delle combo nelle schermate di acquisizione: `Widget.MyFitAI.Input.Dropdown` ora eredita dalla variante `ExposedDropdownMenu` Material mantenendo i token MyFitAI, quindi conserva icona/freccia e popup per sesso, obiettivo, attività, tipo allenamento, intensità e quantità. Build e suite UI `8/8 PASS`; navigazione device senza crash.
- [x] Navigazione contestuale Profilo → acquisizione: le voci Dati personali, Obiettivi, Orari della giornata e Preferenze alimentari aprono `ProfileEditActivity` con `EXTRA_INITIAL_SECTION` e scroll automatico alla card interessata, invece di mostrare una schermata generica. I riepiloghi profilo per dati/obiettivo usano lo stesso comportamento.
- [x] Profilo: aggiunta riga `Chiave Gemini` parallela a `Chiave OpenAI`, con badge `Configurata`/`Non configurata` letto da `SecureAiCredentialStore` e navigazione a Impostazioni per la gestione.
- [x] Regola card acquisizione uniformata: `ProfileEditActivity` ora usa lo stesso padding interno, margine tra sezioni, titolo `Text.MyFitAI.Section`, card `bg_card` e stili input per Dati personali, Obiettivo/attività, Giornata e Preferenze; gli stessi token sono applicati alle schermate BIA, misure, allenamento e sgarro. Rimosso il margine interno extra `space_24` che rendeva Obiettivo/Giornata diversi da Dati personali.
- [x] Verifica uniformità acquisizione: build/unit/androidTest packaging PASS, APK installato, avvio pulito `SplashActivity -> TabHostActivity`, suite UI `8/8 PASS`.
- [x] Verifica del pattern acquisizione: build debug/unit/androidTest packaging PASS, APK installato e avvio pulito sul device, suite UI `8/8 PASS`.
- [x] Orari pasti centralizzati: default 5 pasti `08:00, 11:00, 13:00, 16:00, 20:00`; con 6 pasti default `08:00, 11:00, 13:00, 16:00, 18:00, 20:00`. `NotificationScheduler` usa questi orari configurati per i promemoria, mentre la generazione automatica riusa `AiJobWorker` e invia la notifica di piano completato.
- [x] Export JSON evoluto a `myfitai_profile_export_v2`: metadata schema/app/locale/timezone, DTO-like mapper nell'orchestratore export, distinzione `dataSource`/`isTestData`, separazione `mealPlans`/`foodConsumptions`/`nutritionAdherence`, `dataQuality`, `computedTrends`, `anomalies`, summary workout/sgarri/review, workout energy ledger, AI usage senza credenziali, provenance e ISO timestamp.
- [x] Export v2 normalizza ingredienti (`weightState`, `category`, `nutritionConfidence`), conserva versioni/`source`/`reason` dei piani e dichiara esplicitamente quando `appValidation` storica non è disponibile. La validazione nutrizionale centrale resta `NutritionBusinessValidator` con tolleranza -3%..target.
- [x] Aggiunto `NutritionIntegrityValidator` separato dalla validazione target: controlla macro↔kcal per pasto/supplemento, somma pasti↔totale giorno, target giornaliero e ingredienti non verificabili; produce `NutritionValidationIssue` strutturate e blocca il salvataggio se esistono errori.
- [x] Le nuove versioni piano persistono `appValidationJson` autorevole con migrazione Room `10->11`; le versioni storiche dichiarano validazione non disponibile nell'export. `agentValidation` resta informativa e non autorizza il salvataggio.
- [x] Test `NutritionIntegrityValidatorTest` aggiunti sul caso incoerente `355 kcal / 19P / 112C / 6F` e su tolleranza macro realistica; build/unit/androidTest packaging, export `5/5` e suite UI `8/8 PASS`.
- [x] Test export v2 sul fixture storico: `SixMonthHistoryExportTest` `5/5 PASS`; verificati schema/versione, dati QA, trend, qualità, piani/consumi, summary, ledger energia, AI usage e assenza di API key/secret. Suite UI `8/8 PASS`.
- [x] Agent nutrizionale aggiornato e verificato: il prompt runtime del piano invia `BM0`, `BM`, `BMD`, `BMT` con le 11 misure corporee nell'ordine canonico; le regole agent documentano le misure come contesto non diagnostico e non autorizzano modifiche autonome dei target.
- [x] Verifica device finale impostazioni automatiche: APK aggiornato installato, sezione `Piano alimentare` visibile, switch `Generazione automatica` spento, testo `Disattivata · generazione manuale`, orari default `08:00 · 11:00 · 13:00 · 16:00 · 20:00` visibili; nessun job `nutrition-auto-generation-*` presente nel JobScheduler. Build/unit/androidTest packaging PASS.
- [x] Generazione piano Gemini verificata sul device: modello `gemini-3.5-flash-lite`, HTTP 200, usageMetadata ricevuto, fallback JSON-only, parsing/business validation locale e piano persistito; un primo output è stato respinto per `DAY_TOTALS_INCONSISTENT` e il retry è riuscito.
- [x] Diagnostica schema weekly-plan: probe A-E reale sul device PASS; schema completo F bloccato da quota Gemini gratuita esaurita (`429 RESOURCE_EXHAUSTED`).
- [x] Fallback weekly-plan Gemini a `responseMimeType=application/json` senza schema remoto; validazione canonica locale e business validation restano obbligatorie.
- [x] Settings supporta entrambe le chiavi cifrate contemporaneamente, con un solo provider attivo tramite switch.
- [x] Chiavi salvate nascoste in UI; disponibili solo `Sostituisci` ed `Elimina`.
- [x] Navigazione Alimentazione bloccata con dialog e link Settings quando il provider attivo non è configurato.
- [x] Cataloghi modelli Gemini/OpenAI selezionabili e pricing locale associato al modello attivo.
- [x] Tracking locale usage/costi Gemini e OpenAI con token cached/thinking/reasoning e snapshot del listino.
- [x] Indice locale di aspettativa corporea settimanale aggiunto a Progresso: calcola deficit teorico da mantenimento, piano alimentare, esercizio e, quando disponibili, consumi reali; converte il deficit in un intervallo prudente di grasso/peso teorico (`7700 kcal/kg`, margine 70%-120%), separa `PLAN` da `CONSUMPTION` e avvisa che acqua/glicogeno possono alterare il peso osservato. Nessuna promessa o diagnosi.
- [x] Regola UI numerica centralizzata applicata ai nuovi indicatori e ai valori visibili: una sola cifra decimale dopo la virgola (`%.1f`), mentre precisione interna, validator e export restano non arrotondati; corretti anche rapporti corporei e variazioni analisi che mostravano due o più decimali.
- [x] Test unitari `WeeklyBodyExpectationTest` aggiunti per piano senza consumi e consumi reali parziali; build/unit/androidTest packaging PASS e suite UI `8/8 PASS`.
- [x] NutritionPathAgent aggiunto alla pipeline comune: contratto `NP1` con massimo due alternative, validazione business locale, job WorkManager, risultato Room, notifica/deep-link e schermata di scelta esplicita. Il trigger parte dopo il completamento di profilo+BIA+misure, senza vincolare il percorso suggerito; la scelta canonica viene persistita e avvia la generazione del piano solo dopo conferma. Build/unit/androidTest packaging PASS e avvio pulito device verificato; resta da certificare il run provider reale end-to-end.

## QA E VERIFICA

### Ultimo stato noto
- `:app:assembleDebug` e `:app:assembleRelease` verdi sull'HEAD remoto allineato, con Gradle 9.6.0, Java 17 e Android SDK locale.
- `:app:testDebugUnitTest` verde sul nuovo HEAD, inclusi pricing IA, quality engine BIA e interpretazione trend.
- `:app:connectedDebugAndroidTest` verde: 55/55 test su `SM-A546B - 16`, inclusi smoke Activity, bottom navigation, QaSeeder, Progress, Settings, Export, notifiche, migration/usage runtime, sessione profilo attivo, E2E consumo pasto, gate sgarro e formati export inclusi PDF piano.
- Il nuovo HEAD remoto aggiunge test unitari per pricing IA, usage e trend BIA; la suite aggiornata è stata rieseguita e risulta verde.
- `:app:assembleRelease` verde; il source set debug-only non entra nella build release.
- Settings device test dopo il refactor BYOK: 2/2 PASS su `SM-A546B - 16`.
- Le prove precedenti Firebase sono storiche e non rappresentano il provider finale: il runtime attuale è Gemini BYOK diretto.
- Il framework storico `SixMonthHistoryFixture` usa seed `20260914`, copre circa 6 mesi, 26 settimane, BIA, misure, workout, versioni, sgarri, review, supplementi, hydration e multiprofilo.
- La suite instrumented copre seeder QA debug sul vero Room dell'app e riapertura normale, grafici Progress reali con screenshot 1M/3M/6M/1Y, persistenza Room, piani legacy senza supplementi, migration 3 -> 4, export JSON/CSV ZIP/PDF inclusa apertura export dalla UI e creazione JSON, isolamento multiprofilo inclusi record annidati di piano, workflow AI deterministici con fake runtime provider-neutral, scheduler e receiver notifiche deterministici, lifecycle foto etichetta, UIAutomator E2E bottom-tab, Settings/privacy/provider status, stress dataset con tempi osservati e conservazione di supplementi/hydration note.
- Bottom tab smoke test verificato sul nuovo host Fragment: Home, Progresso e Altro sono navigabili senza provider IA, Alimentazione mostra il gate provider, il tap sul tab attivo e il back passano in `BottomNavigationUiTest`.
- Inset fisici verificati su `SM-A546B`: shell app tra status bar e navigation bar, bottom navigation non sovrapposta ai comandi di sistema.
- La CI non parte sui push a `develop`; resta disponibile via pull request o `workflow_dispatch`.

### Prossimi step obbligatori
- [x] Regola calorie + attività fisica: quando è presente un allenamento, il TDEE giornaliero considera il dispendio dell'attività oltre al TDEE base; il valore di default è `500 kcal` letto dalla tabella dedicata.
- [ ] Implementare il bridge inter-app autenticato per permettere all’app separata di aggiornare il ledger senza accesso diretto al database privato di MyFitAI.
- [x] Home: riepilogo operativo "Oggi" con target energetico, prossimo pasto e prossimo allenamento usando dati locali già disponibili.
- [x] Home: blocchi operativi separati in card per energia/Oggi, due prossimi pasti e allenamento di oggi; lo stato allenamento deriva dal ledger workout/rest-day esistente, senza nuovo flag duplicato.
- [x] Notifiche: schermata di configurazione per promemoria pasti, review settimanale, anticipo e stato permesso/canale Android; resta da verificare il banner reale nella barra su device.
- [x] Navigazione: `TabHostActivity` ospita quattro Fragment root persistenti, creati una sola volta e cambiati con `FragmentManager` `show/hide`; il click tab non crea o riordina Activity root.
- [x] Navigazione: rimosso dal percorso runtime l'embedding `LocalActivityManager`/`decorView` e le task affinity delle root; le Activity di dettaglio restano figlie standard del task dell'host.
- [x] Navigazione: QA lifecycle/device della migrazione Fragment root eseguito; `BottomNavigationUiTest` 6/6 PASS sul device.
- [x] Navigazione: `WorkoutsActivity` verificata come schermata figlia senza bottom navigation root e con back verso la Home padre.
- [x] Dispendio allenamento: test device per default `500 kcal`, aggiornamento a valore esterno e ricalcolo `TDEE base + esercizio`; migration `7 -> 8` verificata.
- [x] Allenamenti: layout riallineato al linguaggio card della Home con riepilogo settimana, card giorno selezionato e lista interna compatta.
- [x] Allenamenti: registrazione e visualizzazione dell'intensità percepita `1-10`, durata, tipo, orario e kcal stimate.
- [x] Allenamenti: test device della nuova UI e delle migrazioni `8 -> 9`; suite mirata `23/23 PASS`.
- [x] Alimentazione: integrazione mostrata in card autonoma con istruzioni su dose/orario, valori nutrizionali e note; etichette del target e del totale giornaliero riscritte in linguaggio operativo e verificate su device con piano demo.
- [x] Alimentazione: flusso operativo riordinato in `obiettivo -> pasti -> integrazione -> totale giornaliero -> azioni`; i pasti mostrano `Da registrare`, `Registrato da te` o `Pasto concluso`, con motivazione accessibile quando il cambio IA non è disponibile.
- [x] Alimentazione: QA UI del nuovo flusso verificata su device con card obiettivo, stato pasto, integrazione, totale e stato vuoto; `BottomNavigationUiTest#foodPlan_explainsDailyFlowAndMealStatus` PASS.
- [x] Alimentazione: card totale aggiornata con `Esercizio registrato` e `Bilancio netto`; test unitari del recovery verificano che l'esercizio riduca il budget e il prelievo.
- [x] Alimentazione: titolo `Totale giornaliero` spostato dentro la card, con padding e gerarchia coerenti; test UI rieseguito dopo la rifinitura grafica.
- [x] Alimentazione: `Azioni settimana` trasformata in card autonoma con titolo e pulsanti contenuti nello stesso bordo; test UI del flusso rieseguito con PASS.
- [x] Generazione piano: il job resta attivo nel `ViewModel` del Fragment persistente durante il cambio tab e il completamento forza il reload dello snapshot, così il menu aggiornato compare senza refresh manuale.
- [x] Build `:app:assembleDebug` dopo la migrazione Fragment.
- [x] Unit test `:app:testDebugUnitTest` dopo la migrazione Fragment.
- [x] Build `:app:assembleDebugAndroidTest` dopo la migrazione Fragment.
- [x] Verificare la parità funzionale dei quattro Fragment root per il perimetro migrato e i flussi principali; restano eventuali rifiniture QA puntuali dei binder Settings.
- [x] Ripristinare HomeFragment: energia/recovery, ricomposizione, delta semantici, riepilogo Oggi, pasto/allenamento e provider gate.
- [x] Ripristinare FoodPlanFragment: energia/recovery, generazione, pasti, integrazione, hydration, totali consumi e cambio pasto ActivityResult.
- [x] Correggere lifecycle view-safe dei Fragment principali e reschedule della frequenza analisi progressi da SettingsFragment.
- [x] Ripristinare PhysicalEvolutionFragment: grafico/date, indicatori secondari, help, review settimanale e analisi IA con stato/dettagli.
- [x] SettingsFragment: binder costi lifecycle-safe, frequenza analisi con reschedule e protezione host `FLAG_SECURE` verificati in build e navigazione device.
- [x] Build debug/release e unit test rieseguiti dopo l'allineamento al nuovo HEAD remoto.
- [x] Validator nutrizionale asimmetrico verificato: range target -3% .. target, sopra target rifiutato; cap recovery giornaliero 10% invariato.
- [x] Correggere le regressioni di compilazione/migrazione introdotte da DB v4 e nuovo contratto nutrizionale.
- [x] Test runtime Room di supplementi, hydration note, compatibilità legacy e cambio versione del pasto.
- [x] Verifica migrazione DB 3 -> 4.
- [ ] Recuperare gli schema asset canonici v1/v2 prima di testare le migrazioni storiche; non ricostruire schemi mancanti per supposizione.
- [x] Framework test storico deterministico di sei mesi e test export/multiprofilo.
- [x] Seam `AiRuntimeGateway` e test integration deterministici per generation, meal swap, advice, sgarro e weekly review.
- [x] QA grafica/pixel su device/emulatore per la tabella giornaliera Previsto/Reale nella schermata Alimentazione.
- [x] QA device del tracking consumi: test Room isolato verde, workflow Weekly Review consumi 6/6 verde nel run selettivo e UI delle tab principali verificata su device.
- [x] Smoke test lifecycle delle Activity principali locali: 17 schermate raggiungono almeno `STARTED` e dispongono di content view senza crash; restano aperti gli E2E funzionali completi.
- [x] E2E UI del flusso consumo verificato su device: piano seminato, dettaglio pasto caricato, registrazione consumo, copertura aggiornata nel piano e Review raggiungibile.
- [x] E2E UI del flusso sgarro locale verificato: descrizione, gate di conferma IA, annullamento senza chiamata provider e nessuna persistenza prima della conferma; dialog foto raggiungibile senza aprire fotocamera.
- [x] Fixture foto etichetta verificata: conversione JPEG in memoria, ridimensionamento, rifiuto immagini invalide e cleanup dei file temporanei; import completo da Photo Picker/fotocamera reale resta non certificato.
- [x] E2E UI export verificato per JSON, CSV ZIP, PDF profilo e PDF piano con dataset QA completo; la foto etichetta reale resta aperta.
- [ ] E2E UI completo di profilo, dieta, sgarro, review, export e foto.
- [ ] Test runtime provider Gemini/OpenAI, banner notifiche Android e stress performance.
- [x] Rieseguire la suite connected completa dopo il gate Alimentazione e la nuova UI delle credenziali: 55/55 PASS su `SM-A546B - 16`.
- [x] Testare Gemini BYOK reale su device con API key personale inserita manualmente; risposta strutturata, usageMetadata e persistenza piano verificate senza esporre la chiave.
- [x] Risolvere output non parsabile del weekly-plan Gemini in JSON-only; il flusso ora registra usageMetadata e mantiene la business validation locale. Il costo monetario effettivo resta verificabile solo tramite Google Cloud Billing.
- [x] Diagnosi parser weekly-plan completata sul device: output troncato (`JSONException: End of input`, categoria `TRUNCATED_JSON`), `finishReason=MAX_TOKENS`; nessun repair automatico e nessuna persistenza di output invalido.

## Branching
- `develop`: sviluppo corrente.
- `main`: stabile.

Il passaggio su `main` va fatto solo dopo build, test e QA previsti.
