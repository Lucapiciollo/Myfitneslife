# MyFitAI

## Riferimento canonico UI
`assets/MOCK_APPROVATO_MYFITAI_V2_COMPLETO.png`

## Piano di sviluppo autorevole
Lo stato reale del progetto e l'ordine delle attività sono definiti in `DEVELOPMENT_PLAN.md`.

## Stato attuale
MyFitAI è un'app Android local-first per profilo corporeo, BIA, misure, allenamenti, piano alimentare, adattamento post-sgarro, lista spesa, notifiche, review ed export.

La UI/mock navigabile e gran parte della logica applicativa sono presenti su `develop`. Il framework di collaudo V1 usa `SixMonthHistoryFixture` con seed `20260914`, clock testabile, regression test sui trend/calcoli e integration test Room/export.

## Architettura IA
L'app è l'orchestratore. I calcoli affidabili restano locali e deterministici.

Pipeline runtime:

`App -> Local Calculation Engine -> AiProvider -> Agent -> tiny JSON envelope -> MyFitAI Pipe Protocol -> parser locale -> Business Validator -> Persistenza -> UI`

Il JSON verboso non è più il formato di trasporto dei payload degli agenti. Per mantenere lo structured output dei provider senza ripetere centinaia di chiavi, ogni agente restituisce soltanto un envelope JSON minimo con una proprietà `data`; il contenuto di `data` usa un protocollo pipe posizionale versionato e specifico della funzione. Esempi di versioni: `MFP1` piano settimanale, `CU1/CA1` sgarro, `MA1` alternative pasto, `NA1` consiglio nutrizionale, `WR1` review, `BIA1` import BIA e `BP1` interpretazione proporzioni.

Il parser locale ricostruisce gli stessi oggetti dominio usati dall'app e i Business Validator restano autorevoli. I parser rifiutano record sconosciuti, numero di campi errato e strutture incomplete. Nei campi testuali del protocollo il carattere `|` e i newline sono vietati.

La policy token è centralizzata: gli agenti compact usano thinking disabilitato, limiti di output specifici per workload e istruzioni/prompt sintetici. JSON completo resta valido per persistenza/export o compatibilità interna quando utile, ma non viene richiesto al modello per i payload runtime ad alto volume.

Provider supportati:
- Gemini Developer API via BYOK
- OpenAI/GPT

Gemini usa la API key personale dell'utente, cifrata localmente con Android Keystore/AES-GCM,
e chiama direttamente la Gemini Developer API. Firebase resta configurabile per altri servizi,
ma Firebase AI Logic è disabilitato e non viene usato come gateway Gemini. OpenAI usa lo stesso
credential store cifrato.

Le due chiavi possono essere configurate contemporaneamente. Lo switch seleziona il provider attivo;
una chiave salvata non viene più mostrata e può solo essere sostituita o eliminata. La sezione
Alimentazione è bloccata finché il provider attivo non è configurato e porta direttamente alle Impostazioni.

Tutti gli agenti sono vincolati al solo dominio nutrizionale. Dati corporei, BIA, misure e allenamenti possono essere usati solo come contesto per decisioni nutrizionali, non per coaching generico, diagnosi mediche o altri domini.

`agentValidation` resta informativa; `appValidation` locale è autorevole.

## Nutrizione e piano alimentare
Regole principali:
- target kcal e macro calcolati localmente;
- tolleranza ufficiale dal target -3% al target, mai sopra il target;
- piani immutabili/versionati;
- nessuna compensazione punitiva dopo uno sgarro;
- modifica solo dei pasti futuri;
- condimenti e bevande caloriche conteggiati;
- stagionalità come preferenza, non vincolo superiore ai target;
- nessuna diagnosi, allergia o intolleranza inventata;
- richieste alimentari esplicite dell'utente hanno priorità sull'adeguatezza tipica dell'orario; l'orario serve poi per porzione, timing e impatto sul piano;
- per richieste generiche l'orario resta un criterio forte per ordinare i suggerimenti.

## BIA, composizione corporea e nutrizione
Il piano alimentare riceve anche contesto corporeo descrittivo:
- peso;
- body fat %;
- massa muscolare;
- massa muscolare scheletrica;
- acqua corporea %;
- circonferenza vita;
- trend di peso, grasso, massa muscolare e vita;
- stato di ricomposizione.

Questi dati non autorizzano diagnosi. In particolare la BIA non deve essere interpretata come prova diretta di carenza proteica o disidratazione.

## Nutrizione sportiva e integrazione
La modalità sportiva non è un flag manuale: viene classificata localmente usando livello di attività e allenamenti della settimana.

Modalità:
- `NORMAL`
- `SPORT`

Regole:
- proteine in polvere possono essere proposte anche in `NORMAL` se utili per raggiungere il target proteico o per praticità nutrizionale;
- le proteine in polvere contribuiscono a kcal e macro e vengono conteggiate nei totali;
- creatina consentita solo in `SPORT`;
- creatina conteggiata a 0 kcal e 0 macro;
- alimenti normali restano la prima scelta;
- integrazione e idratazione sono rappresentate separatamente dai pasti;
- l'idratazione può essere suggerita in modo prudente usando il contesto BIA, senza diagnosi.

Il giorno alimentare canonico ora comprende:

`meals[] + supplements[] + hydrationNote`

## Consiglio IA rapido
La funzione “Chiedi all'IA” è one-shot, non salva la conversazione e restituisce 5 suggerimenti compatti ordinati dal migliore al peggiore.

Per richieste alimentari esplicite, ad esempio “mi va un gelato”, il cibo richiesto deve restare il centro della risposta anche se l'orario non è tipico. Per richieste generiche, ad esempio “ho fame, cosa mangio?”, l'orario guida maggiormente la classifica.

## Cambio pasto con IA
Ogni pasto futuro del piano può generare 5 alternative.

Vincoli:
- stesso tipo di pasto e stesso orario;
- kcal esattamente uguali al pasto originale;
- macro il più possibile vicini;
- nessuna modifica fino ad accettazione esplicita;
- nuova versione immutabile del piano;
- protezione da piano stale;
- integrazione e idratazione della giornata vengono preservate durante il cambio pasto.

## Sicurezza AI BYOK
Le chiavi Gemini e OpenAI sono protette tramite Android Keystore e AES-GCM. Non devono mai finire in chiaro in Room, SharedPreferences normali, file, log, crash report, backup, analytics, repository, BuildConfig, intent, navigation args o export.

## Stato verifica tecnica
L'HEAD precedente a questa ottimizzazione è stato compilato con `:app:assembleDebug` usando Java 17, Gradle 9.6.0 e Android SDK locale. `:app:testDebugUnitTest` risultava verde con 44 test e `:app:connectedDebugAndroidTest` verde su `SM-A546B - 16` con 41 test. Le modifiche al protocollo compact aggiunte successivamente richiedono una nuova esecuzione prima di estendere tale risultato all'HEAD corrente.

La bottom bar usa una transizione root-tab senza animazioni e senza stack duplicati; il dispositivo fisico ha verificato tab attivo no-op, rapid tap, Back non ciclico e inset corretti rispetto alla navigation bar di sistema.

La V1 non è ancora certificata integralmente: restano pendenti provider reali, QA grafica/pixel, E2E UI completo, camera/foto sgarro, delivery notifiche e stress performance. Stato dettagliato in `docs/TEST_RESULTS_V1.md` e `docs/TEST_TRACEABILITY_V1.md`.

CI non viene eseguita automaticamente sui push a `develop`; il workflow resta disponibile su pull request e avvio manuale.

## Branching
- `develop`: sviluppo corrente.
- `main`: stabile.

Il passaggio su `main` va fatto solo dopo build, test e QA previsti.
