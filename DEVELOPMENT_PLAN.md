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
- Tolleranza nutrizionale: ±3% sui target correnti al primo tentativo. In caso di errore i retry di generazione usano ±4%; al successo si torna a ±3%.
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
- [x] Root tab operative in modalita standalone: Home, Progresso e Altro restano navigabili senza provider IA; Alimentazione resta protetta dal gate provider.
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

### Motore locale
- [x] BMI.
- [x] BMR con priorità Katch-McArdle quando body fat è disponibile e plausibile.
- [x] Fallback Mifflin-St Jeor con sesso biologico.
- [x] TDEE.
- [x] Target calorico dinamico.
- [x] Proteine/grassi/carboidrati dinamici.
- [x] Trend peso/body fat/massa muscolare/vita.
- [x] Qualità comparabilità BIA e interpretazione locale deterministica dei trend corporei.
- [x] Waist/height ratio.
- [x] Classificazione ricomposizione.
- [x] Business Validator locale ±3%.
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
- [x] Icona cambio per ogni pasto futuro.
- [x] 5 alternative.
- [x] Stesso tipo/orario del pasto.
- [x] Kcal esattamente uguali al pasto originale.
- [x] Nessuna persistenza prima dell'accettazione.
- [x] Nuova versione immutabile del piano dopo accettazione.
- [x] Protezione stale-plan.
- [x] Integrazione e hydration note vengono preservate quando un pasto viene sostituito.

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
- [x] Backup JSON completo locale tramite Storage Access Framework: `Salva backup su disco` e `Importa backup da disco`; il restore crea un nuovo profilo e rimappa gli ID Room di piani, versioni, pasti e consumi senza sovrascrivere i dati esistenti.
- [x] Prima riorganizzazione UX: aggiunto hub `Configurazione alimentazione` per pasti/generazione automatica/percorso nutrizionale, collegato direttamente da Alimentazione; Impostazioni mantiene il ruolo amministrativo e di configurazione generale.
- [x] Progresso riorganizzato visivamente per gerarchia: sezione `Sintesi del corpo` sopra andamento e sezione `Review e letture` prima delle card IA dinamiche, mantenendo le Activity esistenti e i dati invariati.
- [x] QA runtime completo degli export più recenti: JSON, CSV ZIP, PDF profilo e PDF piano verificati su `SM-A546B - 16` tramite `ExportFormatsE2ETest` e `ExportNavigationUiTest`.

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
- [ ] Verifica runtime provider OpenAI e parità reale Gemini/OpenAI: la copertura provider-neutral con fake runtime è verde e Gemini è stato verificato via HTTP reale sul device; OpenAI e il confronto reale tra provider restano `NOT RUN` finché una chiave OpenAI personale non viene inserita manualmente nel device, senza essere esposta o automatizzata.
- [x] Generazione piano Gemini verificata sul device: modello `gemini-3.5-flash-lite`, HTTP 200, usageMetadata ricevuto, fallback JSON-only, parsing/business validation locale e piano persistito; un primo output è stato respinto per `DAY_TOTALS_INCONSISTENT` e il retry è riuscito.
- [x] Validazione nutrizionale di integrità integrata sulla base Room v7: controllo indipendente di macro↔kcal per pasto e supplemento, somma pasti↔totale giorno, target giornalieri, numero pasti e ingredienti non verificabili; gli errori bloccano la persistenza e il risultato autorevole viene salvato come `appValidationJson` sulla versione piano con migration Room `7->8`. Test unitari e migration device `4/4 PASS`.
- [x] Prima slice infrastruttura AI comune sulla base corrente: `AiJobType`, `AiJobHandler`, `AiJobRegistry`, `AiJobScheduler`, `AiJobWorker` e notifica/deep-link condivisi. `NutritionPath` migrata dal worker dedicato al registry comune con retry per errori temporanei; `ProgressAnalysisWorker` resta invariato fino alla migrazione dedicata.
- [x] ProgressAnalysis migrata sulla pipeline AI comune: handler e job type condivisi, cadenza automatica mantenuta tramite `initialDelay`, analisi manuale da Progresso accodata in WorkManager e notifica/deep-link centralizzati. La persistenza del risultato continua a usare `ProgressAnalysisPreferences`.
- [x] Generazione WeeklyPlan migrata sulla pipeline AI comune: handler con `profileId` esplicito, job WorkManager unico per profilo/settimana, retry/notifica centralizzati e refresh promemoria solo dopo persistenza riuscita. La validazione target/integrità e la persistenza immutabile restano nel `NutritionPlanGenerationService`.
- [x] WeeklyReview migrata sulla pipeline AI comune: handler con `profileId` e settimana espliciti, job WorkManager con retry/notifica/deep-link centralizzati e UI riagganciata al risultato persistito; il vincolo di settimana completata e la validazione del contratto restano nel `WeeklyReviewService`.
- [x] NutritionAdvice one-shot migrato sulla pipeline AI comune: domanda passata come parametro WorkManager, payload delle cinque opzioni restituito nell'output del job, notifica/deep-link centralizzati e accettazione esplicita ancora separata dal job di analisi.
- [x] Alternative pasto migrate sulla pipeline AI comune: contesto del pasto codificato nella chiave job, cinque alternative restituite nell'output WorkManager, notifica/deep-link centralizzati e generazione separata dall'applicazione; `StalePlan`, pasto trascorso, vincoli alimentari e nuova versione immutabile restano verificati dal service prima della sostituzione.
- [x] Flusso comprensione sgarro migrato sulla pipeline AI comune: testo e foto etichetta passano da WorkManager; la foto usa un file temporaneo privato con solo il percorso nei parametri, viene letta dal worker e cancellata dopo esito definitivo, mantenuta durante retry. Preview senza persistenza, fingerprint anti-stale e conferma/adattamento restano separati.
- [x] Fase di conferma/adattamento sgarro migrata sulla pipeline AI comune: il payload della preview confermata e il fingerprint passano al job `CHEAT_ADJUSTMENT`; il worker invoca `registerAndAdapt`, mantenendo validazione dei soli pasti futuri, persistenza del record e nuova versione immutabile nel service.
- [x] Interpretazione proporzioni corporee migrata sulla pipeline AI comune: il report deterministico viene serializzato nei parametri del job, l'interpretazione viene restituita con notifica/deep-link e nessun calcolo locale viene sostituito.
- [x] Import BIA da foto migrato sulla pipeline AI comune: immagine in file cache privato, path nel job, cleanup dopo esito definitivo/retry sicuro, preview BIA restituita senza salvataggio automatico nello storico.
- [x] Diagnostica schema weekly-plan: probe A-E reale sul device PASS; schema completo F bloccato da quota Gemini gratuita esaurita (`429 RESOURCE_EXHAUSTED`).
- [x] Fallback weekly-plan Gemini a `responseMimeType=application/json` senza schema remoto; validazione canonica locale e business validation restano obbligatorie.
- [x] Settings supporta entrambe le chiavi cifrate contemporaneamente, con un solo provider attivo tramite switch.
- [x] Chiavi salvate nascoste in UI; disponibili solo `Sostituisci` ed `Elimina`.
- [x] Navigazione Alimentazione bloccata con dialog e link Settings quando il provider attivo non è configurato.
- [x] Cataloghi modelli Gemini/OpenAI selezionabili e pricing locale associato al modello attivo.
- [x] Tracking locale usage/costi Gemini e OpenAI con token cached/thinking/reasoning e snapshot del listino.
- [x] DB v11: migratione di riparazione idempotente per database v10 creati da build intermedie senza le colonne BIA opzionali estese.

## QA E VERIFICA

### Ultimo stato noto
- `:app:assembleDebug` e `:app:assembleRelease` verdi sull'HEAD remoto allineato, con Gradle 9.6.0, Java 17 e Android SDK locale.
- `:app:testDebugUnitTest` verde sul nuovo HEAD, inclusi pricing IA, quality engine BIA e interpretazione trend.
- `:app:connectedDebugAndroidTest` verde: 56/56 test su `SM-A546B - 16`, inclusi smoke Activity, bottom navigation, QaSeeder, Progress, Settings, Export, notifiche, migration/usage runtime, sessione profilo attivo, E2E consumo pasto, gate sgarro, workflow AI deterministici e formati export inclusi PDF piano.
- Full connected suite sull'HEAD corrente: `63/63 PASS` su `SM-A546B - 16`; include il test di migration v10->11. `BottomNavigationUiTest` è stato stabilizzato facendo asserzioni sulle Activity root embedded tramite il test seam del `TabHostActivity`, senza modificare la navigazione utente.
- Il nuovo HEAD remoto aggiunge test unitari per pricing IA, usage e trend BIA; la suite aggiornata è stata rieseguita e risulta verde.
- `:app:assembleRelease` verde; il source set debug-only non entra nella build release.
- Release-readiness review: build release e lint verdi, ma la variante release non ha una configurazione di firma (`signingReport: Config null`); l'APK release non è quindi distribuibile finché non viene configurato un keystore protetto.
- Riorganizzazione grafica estesa verificata: PhysicalEvolution, Profile, History, Workouts, NewWorkout, Export, Advice, AdjustedPlan, MealAlternative, NutritionPlanSettings, FoodPlan, ShoppingList, CheatEntry e WeeklyReview riallineati a shell/header/card/spacing condivisi; le composizioni full-screen della notifica e hero del dettaglio pasto restano specifiche per fedeltà visiva.
- Dopo il pass UI esteso: `:app:assembleDebug`, `:app:testDebugUnitTest`, test mirati `9/9 PASS`, suite connected completa `63/63 PASS` e `:app:assembleRelease` verdi su `SM-A546B - 16` / Gradle `8.14.3`.
- Pass grafico globale completato su tutte le Activity: shell/header, spacing, card, input, CTA, stati vuoti e gerarchie ora seguono i token condivisi; sono state preservate le composizioni specifiche di onboarding, notifica full-screen e hero del dettaglio pasto. Verifica finale dopo tutte le modifiche: `:app:assembleDebug`, `:app:assembleRelease`, `:app:testDebugUnitTest` e `63/63 PASS` connected su `SM-A546B - 16`.
- Pass BIA/Misure/Rilevazioni verificato dopo l'allineamento degli stati vuoti, degli input compact e dei dropdown: `:app:assembleDebug`, `:app:testDebugUnitTest`, `ActivitySmokeTest`, `NewBodyMeasurementDeviceTest`, `MeasurementHistoryDeviceTest` e suite connected completa `63/63 PASS` su `SM-A546B - 16`; APK debug installato in-place con `adb install -r` senza cancellare il dataset.
- Copertura Room deterministica aggiunta per l'aggiornamento in-place di una rilevazione BIA e di una misura corporea: identità, profilo e sostituzione dei valori verificati su database in-memory con `MyFitAiDatabaseTest` `12/12 PASS`; l'E2E UI reale di modifica/import resta aperto.
- Audit visuale BIA completato sul device: form nuova BIA verificato con `BiaDeviceVisualTest` `1/1 PASS` su `SM-A546B - 16`; card data/condizioni/risultati, input data/ora, contenuti e CTA sono presenti nelle viewport top/bottom dopo la normalizzazione delle superfici bianche e dei token compact.
- Correzione leggibilità campi BIA: introdotti token `InputReadableCompact`/`InputEditTextReadableCompact` da 48dp per label flottanti, icone e valori interni; applicati a data/ora, preview import e dialog valore per evitare clipping. Build debug/unit test e `BiaDeviceVisualTest` `1/1 PASS` rieseguiti su `SM-A546B - 16`.
- Normalizzazione completa della raccolta dati corporea: `InputCompact`/dropdown compact portati a 48dp per evitare valori tagliati; card correnti/trend/proporzioni e form Nuova/Modifica misura forzati a superfici bianche, bordi neutri ed elevazione zero. Verifiche mirate `BiaDeviceVisualTest`, `ActivitySmokeTest` e `NewBodyMeasurementDeviceTest` passate su `SM-A546B - 16`.
- Gate regressione finale dopo il pass raccolta dati: suite `:app:connectedDebugAndroidTest` `67/67 PASS` su `SM-A546B - 16`, includendo `MeasurementHistoryDeviceTest` con apertura non distruttiva dei form BIA e Misure via ID del profilo attivo; APK debug installato in-place con `adb install -r`, dataset preservato.
- Stabilizzata la sincronizzazione del profilo attivo tra processi/test: `ActiveProfileStore` viene riletto prima dell'inizializzazione delle Activity e `ProfileExportService` prima dell'export; `StressPerformanceTest` isolato e suite connected completa hanno chiuso a `67/67 PASS` su `SM-A546B - 16`.
- Schermata Sgarro normalizzata: card etichetta nutrizionale e pannello IA bianchi/neutri, input/dropdown leggibili a 48dp, tipografia condivisa e CTA standard; verifiche `CheatEntryE2ETest`, `ActivitySmokeTest` e `FoodReviewScreensDeviceTest` verdi su `SM-A546B - 16`.
- Settings normalizzata a tappeto: card e pannelli dinamici Gemini/OpenAI forzati a superfici bianche con bordi neutri/elevazione zero, input API leggibili a 48dp, righe modello/costi e controlli dinamici allineati ai token Settings; `SettingsDeviceVisualTest` e `SettingsSecurityUiTest` `2/2 PASS` su `SM-A546B - 16`.
- Dettaglio pasto, Lista della spesa e Review settimanale normalizzati: macro/empty state neutri, card dinamiche bianche senza elevazione, spacing più compatto e CTA Review standard; `FoodReviewScreensDeviceTest` e `ActivitySmokeTest` verificati su `SM-A546B - 16`.
- Pass densità/leggibilità raccolta dati completato: token `InputCompact` e dropdown compact portati a 48dp; BIA, Rilevazioni, Misure corporee e Nuova/Modifica misura normalizzano card funzionali a bianco/bordo neutro/elevazione zero, con contenuti dinamici non sovrapposti. Build/unit, `BiaDeviceVisualTest`, `ActivitySmokeTest`, `NewBodyMeasurementDeviceTest` e suite connected `67/67 PASS` su `SM-A546B - 16`.
- Direzione UI spettacolare consolidata senza migrazione a Compose: Home come riferimento, design token condivisi, card tonali con bordi sottili, CTA coerenti, bottom navigation alleggerita e micro-animazione d'ingresso comune sulle Activity `BaseShellActivity`; comportamento e contratti invariati.
- Revisione gerarchica richiesta dopo QA visiva: le superfici verdi non funzionali sono state rimosse; header secondari ora usano il navy della Home con testo chiaro, contenuti e card sono bianchi/tonali, pannelli AI lavanda chiari, stati positivi verde appena accennato; il verde pieno resta per CTA, selezioni e indicatori. Card runtime e drawable condivisi aggiornati, non solo i layout.
- Dopo l'integrazione della pipeline AI comune, build pulita, unit test, APK debug/release e AndroidTest packaging risultano verdi; connected suite completa rieseguita con fixture weekly-plan/advice allineati ai target dinamici e chiusa a `56/56 PASS`.
- Settings device test dopo il refactor BYOK: 2/2 PASS su `SM-A546B - 16`.
- Le prove precedenti Firebase sono storiche e non rappresentano il provider finale: il runtime attuale è Gemini BYOK diretto.
- Il framework storico `SixMonthHistoryFixture` usa seed `20260914`, copre circa 6 mesi, 26 settimane, BIA, misure, workout, versioni, sgarri, review, supplementi, hydration e multiprofilo.
- La suite instrumented copre seeder QA debug sul vero Room dell'app e riapertura normale, grafici Progress reali con screenshot 1M/3M/6M/1Y, persistenza Room, piani legacy senza supplementi, migration 3 -> 4, export JSON/CSV ZIP/PDF inclusa apertura export dalla UI e creazione JSON, isolamento multiprofilo inclusi record annidati di piano, workflow AI deterministici con fake runtime provider-neutral, scheduler e receiver notifiche deterministici, lifecycle foto etichetta, UIAutomator E2E bottom-tab, Settings/privacy/provider status, stress dataset con tempi osservati e conservazione di supplementi/hydration note.
- Bottom tab smoke test fisico verificato: Home, Progresso e Altro sono navigabili senza provider IA, Alimentazione mostra il gate provider, il tap sul tab attivo e il back sono verificati in `BottomNavigationUiTest` 3/3; eliminato il ciclo di accessibilità prodotto dal precedente embedding dei `DecorView`.
- Inset fisici verificati su `SM-A546B`: shell app tra status bar e navigation bar, bottom navigation non sovrapposta ai comandi di sistema.
- La CI non parte sui push a `develop`; resta disponibile via pull request o `workflow_dispatch`.

### Prossimi step obbligatori
- [x] Build `:app:assembleDebug` sull'HEAD corrente.
- [x] Unit test `:app:testDebugUnitTest` sull'HEAD corrente.
- [x] Build debug/release e unit test rieseguiti dopo l'allineamento al nuovo HEAD remoto.
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
- [ ] Test runtime provider OpenAI e parità reale Gemini/OpenAI, notifiche Android e stress performance; Gemini runtime reale è già verificato nel punto dedicato sopra.
- [x] Rieseguire la suite connected completa dopo il gate Alimentazione e la nuova UI delle credenziali: 55/55 PASS su `SM-A546B - 16`.
- [x] Testare Gemini BYOK reale su device con API key personale inserita manualmente; risposta strutturata, usageMetadata e persistenza piano verificate senza esporre la chiave.
- [x] Risolvere output non parsabile del weekly-plan Gemini in JSON-only; il flusso ora registra usageMetadata e mantiene la business validation locale. Il costo monetario effettivo resta verificabile solo tramite Google Cloud Billing.
- [x] Diagnosi parser weekly-plan completata sul device: output troncato (`JSONException: End of input`, categoria `TRUNCATED_JSON`), `finishReason=MAX_TOKENS`; nessun repair automatico e nessuna persistenza di output invalido.
- [x] Compatibilita database verificata sul device: la suite ha rilevato un database v10 creato da una build intermedia senza colonne BIA estese; aggiunta migration idempotente v10->11, test migration `6/6 PASS` e suite connected completa `63/63 PASS` dopo installazione in-place senza cancellazione dati.

## Branching
- `develop`: sviluppo corrente.
- `main`: stabile.

Il passaggio su `main` va fatto solo dopo build, test e QA previsti.
