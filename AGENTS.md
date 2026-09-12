# MyFitAI — HANDOFF OBBLIGATORIO PER AGENTI

## Stato attuale
La V1 UI navigabile è costruita e aggiornata sul mock approvato V2 `assets/MOCK_APPROVATO_MYFITAI_V2_COMPLETO.png`.
Non implementare ancora backend, Room, OpenAI o logica nutrizionale reale salvo richiesta esplicita.

## REGOLA VINCOLANTE: FEDELTÀ VISIVA 100%
Il mock V2 è una specifica, non un'ispirazione. Prima di modificare una Activity classificare ogni elemento in:
1. BACKGROUND/DECORAZIONE: layer non interattivo, separato.
2. COMPONENTE REALE: testo, card, input, pulsante, grafico, icona, tab, lista, badge, immagine funzionale.
È vietato trasformare una schermata intera in bitmap per simulare fedeltà. È vietato reinterpretare layout, colori, gerarchie, spacing o componenti.
Confrontare mock e Activity prima di considerare concluso il lavoro.

## Tema approvato
Tema unico chiaro: fondo caldo quasi bianco, surface bianche, verde MyFitAI, testo antracite. Niente mix casuale dark/light tra Activity.

## Schermate V2 coperte
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
- Promemoria/notifica pasto
- Progressi
- Analisi/review settimanale
- Storico ed export
- Allenamenti (supporto contestuale)
- Impostazioni

## Flussi futuri già decisi
App = orchestratore. Calcoli deterministici locali. Agenti ricevono JSON e restituiscono JSON validato; mai testo IA direttamente in UI.
NutritionAgent: solo alimentazione/timing, menu settimanale strutturato, nessuna diagnosi/invenzione.
PlanReviewAgent: valida piano e vincoli.
ProgressAnalysisAgent: collega piano, aderenza, allenamenti, BIA e misure.
Lista spesa: aggregazione deterministica dal piano; IA solo se indispensabile per normalizzazione.

## Trigger futuri
- sgarro -> micro-adattamento solo pasti futuri + nuova versione piano
- fine settimana -> weekly review
- nuova BIA -> body response analysis
- nuova settimana -> nuovo piano basato anche sullo storico
- orario pasto -> notifica locale poco prima

## Storico obbligatorio futuro
Non sovrascrivere piani. Conservare versioni, sgarri, aderenza, misure, BIA, review e risultati per il Personal Response Engine.

## Asset
Solo asset originali o con licenza documentata. Material Symbols/Lucide per icone; fotografie solo da fonti con licenza certa. Mai immagini prese casualmente dal web. Registrare fonti/licenze in `assets/ASSET_SOURCES.md`.

## Aggiornamento: definizioni agenti pronte
Sono stati aggiunti i prompt/contratti degli agenti ma NON l'integrazione runtime.
Percorsi:
- `06-ai/agents/NUTRITION_AGENT.md`
- `06-ai/agents/PLAN_REVIEW_AGENT.md`
- `06-ai/agents/PROGRESS_ANALYSIS_AGENT.md`
- `06-ai/schemas/*.schema.json`
- `06-ai/examples/*.request.json`
- `06-ai/00-ai-architecture.md`
- `06-ai/01-evidence-policy.md`
- `06-ai/03-agent-integration-backlog.md`

Gli agenti sono specifiche indipendenti e testabili. Non collegarli alle Activity finché non viene esplicitamente avviata la fase IA.

## AI provider runtime — decisione aggiornata
L'app deve supportare due provider selezionabili a runtime:
- Gemini
- OpenAI/GPT

Configurazione prevista: switch `Usa Gemini`; se OFF, l'utente inserisce la propria OpenAI API key. Gli agenti sono duplicati in cartelle provider-specifiche ma devono produrre gli stessi contratti JSON. Non collegare ancora rete/API finché non viene richiesto esplicitamente.

Percorsi:
- `06-ai/providers/gemini/agents/`
- `06-ai/providers/openai/agents/`
- `06-ai/providers/shared/PROVIDER_CONTRACT.md`

## Aggiornamento V6 — comfort digestivo e parity provider
Tutti gli agenti Gemini/OpenAI devono applicare identiche regole su comfort digestivo e ritenzione percepita. Vietate teorie non documentate di combinazione alimentare. Considerare soltanto fattori concreti e contestuali (sodio, fibre, grassi, volume, timing, eventuale sensibilità storica). Il benchmark `06-ai/examples/provider-parity-nutrition-benchmark.prompt.txt` va usato per confrontare i provider con lo stesso input.



## Regola validator nutrizionale
I target sono dinamici e la tolleranza ufficiale è ±3%. Gli LLM possono solo auto-valutarsi con `agentValidation`; il responso definitivo è `appValidation` calcolato localmente.

Le note digestive devono essere fattuali e prudenti, senza claim fisiologici o marketing non necessari.

## IMPERATIVE — OpenAI credential security
The OpenAI API key is security-sensitive and MUST NOT be stored in plaintext anywhere. Use `SecureOpenAiKeyStore` and Android Keystore as the root of trust. Persist only AES-GCM ciphertext + IV. Never put the API key in Room, ordinary settings, logs, exports, backups, crash reports, analytics, source code, BuildConfig, intents or navigation arguments. Do not weaken `android:allowBackup="false"`. Settings credential UI must remain protected against screenshots. This rule is non-negotiable.
