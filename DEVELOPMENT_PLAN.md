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

La QA visuale dello step 6 non è marcata completata: la build Android non è stata eseguita perché il checkout non contiene `gradlew` e Gradle non è installato, e non è stato usato un emulatore/device.

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