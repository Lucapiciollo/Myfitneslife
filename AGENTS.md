# MyFitAI — HANDOFF OBBLIGATORIO PER AGENTI

## Fonte di verità sul piano lavori
Prima di qualsiasi modifica leggere `DEVELOPMENT_PLAN.md`.

Quel documento è il piano autorevole del progetto e distingue rigorosamente:
- FATTO: implementato realmente o definito e presente nel repository;
- DA FARE: ancora da implementare o verificare.

Non anticipare fasi successive, non ampliare il perimetro e non segnare come completato ciò che esiste solo come mock, specifica, scaffold o documento.

## Stato attuale
La UI navigabile è costruita sul mock approvato V2 `assets/MOCK_APPROVATO_MYFITAI_V2_COMPLETO.png`.
L'integrazione dati e IA reale non va considerata completata finché non viene implementata e verificata secondo `DEVELOPMENT_PLAN.md`.

## REGOLA VINCOLANTE: FEDELTÀ VISIVA 100%
Il mock V2 è una specifica, non un'ispirazione. Prima di modificare una Activity classificare ogni elemento in:
1. BACKGROUND/DECORAZIONE: layer non interattivo, separato.
2. COMPONENTE REALE: testo, card, input, pulsante, grafico, icona, tab, lista, badge, immagine funzionale.

È vietato trasformare una schermata intera in bitmap per simulare fedeltà. È vietato reinterpretare layout, colori, gerarchie, spacing o componenti.
Confrontare mock e Activity su device/emulatore prima di considerare concluso il lavoro.

## Tema approvato
Tema unico chiaro: fondo caldo quasi bianco, surface bianche, verde MyFitAI, testo antracite. Niente mix casuale dark/light tra Activity.

## Schermate coperte
- Splash / onboarding
- Dashboard
- Profilo
- Misure corporee con corpo fronte/retro
- Bioimpedenziometria
- Evoluzione fisica
- Piano alimentare settimanale
- Dettaglio pasto con ingredienti/pesi/macros
- Lista della spesa settimanale aggregata
- Inserimento sgarro
- Piano adattato
- Promemoria/notifica pasto
- Progressi
- Analisi/review settimanale
- Storico ed export
- Allenamenti
- Impostazioni

## Architettura già decisa
App = orchestratore. Calcoli deterministici locali. Gli agenti ricevono JSON e restituiscono JSON validato; mai testo IA direttamente in UI.

Pipeline:
`App -> Local Calculation Engine -> AiProvider -> Agent -> JSON -> Schema Validator -> Business Validator -> Persistenza -> UI`

NutritionAgent: solo alimentazione/timing, menu strutturato, nessuna diagnosi/invenzione.
PlanReviewAgent: revisione indipendente del piano.
ProgressAnalysisAgent: collega piano, aderenza, allenamenti, BIA e misure.
Lista spesa: aggregazione deterministica dal piano; IA non è fonte di verità per i totali.

## Trigger futuri già decisi
- sgarro -> micro-adattamento solo pasti futuri + nuova versione piano
- fine settimana -> weekly review
- nuova BIA/misure -> body response analysis
- nuova settimana -> nuovo piano basato anche sullo storico
- orario pasto -> notifica locale poco prima

## Storico obbligatorio futuro
Non sovrascrivere piani. Conservare versioni, sgarri, aderenza, misure, BIA, review e risultati per il Personal Response Engine.

## AI provider runtime
L'app deve supportare due provider selezionabili a runtime:
- Gemini
- OpenAI/GPT

Gli agenti provider-specifici devono produrre esattamente gli stessi contratti JSON canonici.

Percorsi principali:
- `06-ai/providers/gemini/agents/`
- `06-ai/providers/openai/agents/`
- `06-ai/providers/shared/PROVIDER_CONTRACT.md`
- `06-ai/providers/shared/JSON_FORMAT_PARITY.md`
- `06-ai/schemas/`

## Regola validator nutrizionale
I target sono dinamici e calcolati dall'app. Tolleranza ufficiale: ±3% su calorie e macro rispetto ai target correnti.
Gli LLM possono solo auto-valutarsi con `agentValidation`; il responso definitivo è `appValidation` calcolato localmente.

## Comfort digestivo e ritenzione
Gemini e OpenAI devono applicare identiche regole prudenti e fattuali. Vietate teorie non documentate di food-combining e diagnosi inventate.
Considerare solo fattori concreti e contestuali: sodio, fibre, grassi, volume, timing e sensibilità storica dichiarata.
La ritenzione idrica transitoria non va interpretata automaticamente come aumento di grasso.

## Condimenti e bevande — VINCOLANTE
Applicare `06-ai/providers/shared/CONDIMENTS_BEVERAGES_RULES.md`.

Regole chiave:
- nessun condimento calorico o bevanda calorica può essere omesso;
- ogni ingrediente deve avere quantità numerica, unità e `displayDose` pratica coerente;
- esempi: `1 cucchiaino`, `1/2 cucchiaino`, `1 cucchiaio`, `1 bustina da X g`, `1 bicchiere da X ml`;
- vietati `q.b.`, `un filo`, `un po'` quando l'ingrediente incide su calorie, macro o sodio;
- riequilibrare i pasti successivi quando un pasto è più carico di sodio/grassi/fibre/volume, senza creare blacklist arbitrarie.

## Stagionalità e timing — VINCOLANTE
Applicare `06-ai/providers/shared/SEASONALITY_TIMING_RULES.md`.

Ordine di priorità:
1. sicurezza, allergie/intolleranze e dati utente;
2. target dinamici e tolleranza ±3%;
3. timing nutrizionale e comfort digestivo;
4. preferenze e storico;
5. stagionalità, varietà e praticità.

Preferire frutta, verdura e altri alimenti stagionali solo quando equivalenti e compatibili con target, timing, tolleranza e preferenze.
Non imporre regole arbitrarie tipo frutta solo al mattino o carboidrati vietati la sera.

## Asset
Solo asset originali o con licenza documentata. Material Symbols/Lucide per icone; fotografie solo da fonti con licenza certa. Mai immagini prese casualmente dal web. Registrare fonti/licenze in `assets/ASSET_SOURCES.md`.

## IMPERATIVE — OpenAI credential security
AI API keys are security-sensitive and MUST NOT be stored in plaintext anywhere. Use `SecureAiCredentialStore` and Android Keystore as the root of trust for Gemini and OpenAI. Persist only AES-GCM ciphertext + IV. Never put an API key in Room, ordinary settings, logs, exports, backups, crash reports, analytics, source code, BuildConfig, intents or navigation arguments. Do not weaken `android:allowBackup="false"`. Settings credential UI must remain protected against screenshots. This rule is non-negotiable.

## Regola operativa finale
Completare gli step nell'ordine di `DEVELOPMENT_PLAN.md`. Quando uno step viene realmente completato e verificato, aggiornare quel file. Non dichiarare completato nulla solo perché documentato o predisposto.
