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
- [ ] QA pixel/runtime completo su device/emulatore dopo le ultime modifiche.

### Persistenza e profilo
- [x] Room con migrazioni esplicite e senza destructive fallback.
- [x] Multi-profilo con profilo attivo.
- [x] Sesso biologico esplicito e peso iniziale.
- [x] BIA persistente.
- [x] Misure corporee persistenti.
- [x] Allenamenti/rest day persistenti.
- [x] Piani/versioni/giorni/pasti/ingredienti persistenti.
- [x] Sgarri e review persistenti.
- [x] DB v4: ogni giorno del piano supporta `supplementsJson` e `hydrationNote`.
- [x] Migration test reale 3 -> 4 su database storico.

### Motore locale
- [x] BMI.
- [x] BMR con priorità Katch-McArdle quando body fat è disponibile e plausibile.
- [x] Fallback Mifflin-St Jeor con sesso biologico.
- [x] TDEE.
- [x] Target calorico dinamico.
- [x] Proteine/grassi/carboidrati dinamici.
- [x] Trend peso/body fat/massa muscolare/vita.
- [x] Waist/height ratio.
- [x] Classificazione ricomposizione.
- [x] Business Validator locale ±3%.

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
- [x] Lista spesa deterministica.
- [x] Notifiche locali con refresh su cambio piano.
- [x] Export JSON / CSV ZIP / PDF profilo / PDF piano settimanale.
- [ ] QA runtime completo degli export più recenti.

### Sicurezza OpenAI BYOK
- [x] Android Keystore come root of trust.
- [x] AES-GCM.
- [x] Nessuna API key in chiaro in Room, SharedPreferences normali, file, log, crash report, backup, analytics, repository, BuildConfig, intent o export.
- [x] Backup disabilitato.

## QA E VERIFICA

### Ultimo stato noto
- `:app:assembleDebug` verde sull'HEAD verificato con Gradle 9.6.0, Java 17 e Android SDK locale.
- `:app:testDebugUnitTest` verde: 44 test eseguiti.
- `:app:connectedDebugAndroidTest` verde: 33 test eseguiti su `SM-A546B - 16`.
- Il framework storico `SixMonthHistoryFixture` usa seed `20260914`, copre circa 6 mesi, 26 settimane, BIA, misure, workout, versioni, sgarri, review, supplementi, hydration e multiprofilo.
- La suite instrumented copre persistenza Room, riapertura di un database persistente temporaneo, piani legacy senza supplementi, migration 3 -> 4, export JSON/CSV ZIP/PDF, isolamento multiprofilo inclusi record annidati di piano, workflow AI deterministici con fake runtime provider-neutral, scheduler notifiche deterministico, lifecycle foto etichetta, UIAutomator E2E bottom-tab, stress dataset con tempi osservati e conservazione di supplementi/hydration note.
- Bottom tab smoke test fisico completato: root tab riusati senza stack duplicati, tap sul tab attivo no-op, cambio tab senza animazione Activity e Back senza ciclo tra tab.
- Inset fisici verificati su `SM-A546B`: shell app tra status bar e navigation bar, bottom navigation non sovrapposta ai comandi di sistema.
- La CI non parte sui push a `develop`; resta disponibile via pull request o `workflow_dispatch`.

### Prossimi step obbligatori
- [x] Build `:app:assembleDebug` sull'HEAD corrente.
- [x] Unit test `:app:testDebugUnitTest` sull'HEAD corrente.
- [x] Correggere le regressioni di compilazione/migrazione introdotte da DB v4 e nuovo contratto nutrizionale.
- [x] Test runtime Room di supplementi, hydration note, compatibilità legacy e cambio versione del pasto.
- [x] Verifica migrazione DB 3 -> 4.
- [x] Framework test storico deterministico di sei mesi e test export/multiprofilo.
- [x] Seam `AiRuntimeGateway` e test integration deterministici per generation, meal swap, advice, sgarro e weekly review.
- [ ] QA grafica/pixel su device/emulatore.
- [ ] Smoke test delle Activity principali.
- [ ] E2E UI completo di profilo, dieta, sgarro, review, export e foto.
- [ ] Test runtime provider Gemini/OpenAI, notifiche Android e stress performance.

## Branching
- `develop`: sviluppo corrente.
- `main`: stabile.

Il passaggio su `main` va fatto solo dopo build, test e QA previsti.
