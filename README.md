# MyFitAI

## Riferimento canonico UI
`assets/MOCK_APPROVATO_MYFITAI_V2_COMPLETO.png`

## Piano di sviluppo autorevole
Lo stato reale del progetto e l'ordine vincolante delle attività sono definiti in:

`DEVELOPMENT_PLAN.md`

Quel file distingue esplicitamente **FATTO** e **DA FARE**. Quando cambia lo stato del progetto va aggiornato quel documento senza segnare come completato ciò che esiste solo come mock, specifica o scaffold.

## Stato attuale
La base Android e la UI/mock navigabile sono presenti sul branch `develop`. Le Activity coprono il flusso approvato: Splash, Onboarding, Dashboard, Profilo, Misure corporee fronte/retro, BIA, Evoluzione fisica, Allenamenti, Piano alimentare, Dettaglio pasto, Lista spesa, Sgarro, Piano adattato, Notifiche, Storico, Review/Analisi IA, Export e Impostazioni.

La UI deve mantenere fedeltà visuale 100% al mock approvato. Prima di considerare chiusa una schermata è obbligatorio il confronto su device/emulatore.

## Architettura IA definita
L'app è l'orchestratore. I calcoli affidabili restano locali/deterministici.

Pipeline prevista:

`App -> Local Calculation Engine -> AiProvider -> Agent -> JSON -> Schema Validator -> Business Validator -> Persistenza -> UI`

Provider previsti:
- Gemini
- OpenAI/GPT

Gli agenti disponibili come specifiche sono:
- NutritionAgent
- PlanReviewAgent
- ProgressAnalysisAgent

Gemini e OpenAI devono produrre lo stesso JSON canonico definito in `06-ai/schemas/`. Gli agenti possono restituire `agentValidation`, ma solo `appValidation` calcolato localmente è autorevole.

## Regole nutrizionali già definite
- target nutrizionali dinamici calcolati dall'app;
- tolleranza ufficiale ±3% su calorie e macro;
- nessuna compensazione punitiva dopo uno sgarro;
- modifica solo dei pasti futuri;
- piani versionati e non sovrascritti;
- valutazione prudente di sodio, fibre, grassi, volume e timing;
- nessuna blacklist automatica di alimenti comuni;
- condimenti e bevande caloriche sempre conteggiati;
- `displayDose` pratica e coerente con la quantità numerica, per esempio `1 cucchiaino`, `1/2 cucchiaino`, `1 bustina da X g`, `1 bicchiere da X ml`;
- vietate indicazioni vaghe come `q.b.` o `un filo` quando incidono sui valori nutrizionali;
- preferenza per frutta, verdura e altri alimenti stagionali quando equivalenti e compatibili con target, timing, tolleranza e preferenze;
- nessuna regola arbitraria come frutta solo al mattino o carboidrati vietati la sera.

Riferimenti condivisi:
- `06-ai/providers/shared/CONDIMENTS_BEVERAGES_RULES.md`
- `06-ai/providers/shared/SEASONALITY_TIMING_RULES.md`
- `06-ai/providers/shared/PROVIDER_CONTRACT.md`
- `06-ai/providers/shared/JSON_FORMAT_PARITY.md`

## Sicurezza OpenAI BYOK
La chiave OpenAI deve essere protetta tramite Android Keystore e AES-GCM. Non deve mai finire in chiaro in Room, SharedPreferences normali, file, log, crash report, backup, analytics, repository, BuildConfig, intent, navigation args o export.

Vedi `06-ai/05-openai-key-security.md`.

## Cosa NON è ancora completato
Sono ancora da implementare e verificare, nell'ordine definito in `DEVELOPMENT_PLAN.md`:
- QA visuale reale su device/emulatore;
- Room e persistenza reale;
- profilo/BIA/misure reali;
- motore locale di calcolo e target dinamici;
- dashboard e trend reali;
- allenamenti reali;
- modello dati piano alimentare;
- integrazione rete Gemini/OpenAI;
- JSON Schema validation runtime e Business Validator;
- generazione reale del piano;
- adattamento reale post-sgarro con versionamento;
- Personal Response Engine;
- weekly review reale;
- lista spesa deterministica;
- notifiche Android locali;
- export reale;
- QA finale V1.

## Branching
- `develop`: sviluppo corrente.
- `main`: stabile.

Il passaggio su `main` va fatto solo dopo verifica della fase prevista dal piano.