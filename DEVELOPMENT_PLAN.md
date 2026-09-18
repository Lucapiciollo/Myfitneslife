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
- Tolleranza nutrizionale ufficiale: ±3%.
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
- [ ] Verifica runtime provider Gemini e parità reale Gemini/OpenAI.
- [x] Generazione piano Gemini verificata sul device: modello `gemini-3.5-flash-lite`, HTTP 200, usageMetadata ricevuto, fallback JSON-only, parsing/business validation locale e piano persistito; un primo output è stato respinto per `DAY_TOTALS_INCONSISTENT` e il retry è riuscito.
- [x] Validazione nutrizionale di integrità integrata sulla base Room v7: controllo indipendente di macro↔kcal per pasto e supplemento, somma pasti↔totale giorno, target giornalieri, numero pasti e ingredienti non verificabili; gli errori bloccano la persistenza e il risultato autorevole viene salvato come `appValidationJson` sulla versione piano con migration Room `7->8`. Test unitari e migration device `4/4 PASS`.
- [x] Prima slice infrastruttura AI comune sulla base corrente: `AiJobType`, `AiJobHandler`, `AiJobRegistry`, `AiJobScheduler`, `AiJobWorker` e notifica/deep-link condivisi. `NutritionPath` migrata dal worker dedicato al registry comune con retry per errori temporanei; `ProgressAnalysisWorker` resta invariato fino alla migrazione dedicata.
- [x] ProgressAnalysis migrata sulla pipeline AI comune: handler e job type condivisi, cadenza automatica mantenuta tramite `initialDelay`, analisi manuale da Progresso accodata in WorkManager e notifica/deep-link centralizzati. La persistenza del risultato continua a usare `ProgressAnalysisPreferences`.
- [x] Generazione WeeklyPlan migrata sulla pipeline AI comune: handler con `profileId` esplicito, job WorkManager unico per profilo/settimana, retry/notifica centralizzati e refresh promemoria solo dopo persistenza riuscita. La validazione target/integrità e la persistenza immutabile restano nel `NutritionPlanGenerationService`.
- [x] WeeklyReview migrata sulla pipeline AI comune: handler con `profileId` e settimana espliciti, job WorkManager con retry/notifica/deep-link centralizzati e UI riagganciata al risultato persistito; il vincolo di settimana completata e la validazione del contratto restano nel `WeeklyReviewService`.
- [x] NutritionAdvice one-shot migrato sulla pipeline AI comune: domanda passata come parametro WorkManager, payload delle cinque opzioni restituito nell'output del job, notifica/deep-link centralizzati e accettazione esplicita ancora separata dal job di analisi.
- [x] Alternative pasto migrate sulla pipeline AI comune: contesto del pasto codificato nella chiave job, cinque alternative restituite nell'output WorkManager, notifica/deep-link centralizzati e generazione separata dall'applicazione; `StalePlan`, pasto trascorso, vincoli alimentari e nuova versione immutabile restano verificati dal service prima della sostituzione.
- [x] Diagnostica schema weekly-plan: probe A-E reale sul device PASS; schema completo F bloccato da quota Gemini gratuita esaurita (`429 RESOURCE_EXHAUSTED`).
- [x] Fallback weekly-plan Gemini a `responseMimeType=application/json` senza schema remoto; validazione canonica locale e business validation restano obbligatorie.
- [x] Settings supporta entrambe le chiavi cifrate contemporaneamente, con un solo provider attivo tramite switch.
- [x] Chiavi salvate nascoste in UI; disponibili solo `Sostituisci` ed `Elimina`.
- [x] Navigazione Alimentazione bloccata con dialog e link Settings quando il provider attivo non è configurato.
- [x] Cataloghi modelli Gemini/OpenAI selezionabili e pricing locale associato al modello attivo.
- [x] Tracking locale usage/costi Gemini e OpenAI con token cached/thinking/reasoning e snapshot del listino.

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
- [ ] Test runtime provider Gemini/OpenAI, notifiche Android e stress performance.
- [x] Rieseguire la suite connected completa dopo il gate Alimentazione e la nuova UI delle credenziali: 55/55 PASS su `SM-A546B - 16`.
- [x] Testare Gemini BYOK reale su device con API key personale inserita manualmente; risposta strutturata, usageMetadata e persistenza piano verificate senza esporre la chiave.
- [x] Risolvere output non parsabile del weekly-plan Gemini in JSON-only; il flusso ora registra usageMetadata e mantiene la business validation locale. Il costo monetario effettivo resta verificabile solo tramite Google Cloud Billing.
- [x] Diagnosi parser weekly-plan completata sul device: output troncato (`JSONException: End of input`, categoria `TRUNCATED_JSON`), `finishReason=MAX_TOKENS`; nessun repair automatico e nessuna persistenza di output invalido.

## Branching
- `develop`: sviluppo corrente.
- `main`: stabile.

Il passaggio su `main` va fatto solo dopo build, test e QA previsti.
