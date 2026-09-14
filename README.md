# MyFitAI

## Riferimento canonico UI
`assets/MOCK_APPROVATO_MYFITAI_V2_COMPLETO.png`

## Piano di sviluppo autorevole
Lo stato reale del progetto e l'ordine delle attività sono definiti in `DEVELOPMENT_PLAN.md`.

## Stato attuale
MyFitAI è un'app Android local-first per profilo corporeo, BIA, misure, allenamenti, piano alimentare, adattamento post-sgarro, lista spesa, notifiche, review ed export.

La UI/mock navigabile e gran parte della logica applicativa sono presenti su `develop`. Restano pendenti soprattutto build completa sull'HEAD corrente, test runtime/device e QA grafica/pixel su emulatore o dispositivo reale.

## Architettura IA
L'app è l'orchestratore. I calcoli affidabili restano locali e deterministici.

Pipeline:

`App -> Local Calculation Engine -> AiProvider -> Agent -> JSON -> Schema Validator -> Business Validator -> Persistenza -> UI`

Provider supportati:
- Gemini
- OpenAI/GPT

Tutti gli agenti sono vincolati al solo dominio nutrizionale. Dati corporei, BIA, misure e allenamenti possono essere usati solo come contesto per decisioni nutrizionali, non per coaching generico, diagnosi mediche o altri domini.

`agentValidation` resta informativa; `appValidation` locale è autorevole.

## Nutrizione e piano alimentare
Regole principali:
- target kcal e macro calcolati localmente;
- tolleranza ufficiale ±3%;
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

## Sicurezza OpenAI BYOK
La chiave OpenAI è protetta tramite Android Keystore e AES-GCM. Non deve mai finire in chiaro in Room, SharedPreferences normali, file, log, crash report, backup, analytics, repository, BuildConfig, intent, navigation args o export.

## Stato verifica tecnica
L'HEAD corrente è stato compilato con `:app:assembleDebug` usando Java 17, Gradle 9.6.0 e Android SDK locale. `:app:testDebugUnitTest` è verde con 36 test. La suite `:app:connectedDebugAndroidTest` è verde su `SM-A546B - 16` e `Medium_Phone(AVD) - 17`, con 11 test per device, inclusi migration Room 3 -> 4, piani legacy senza supplementi e conservazione di supplementi/hydration note nel cambio pasto.

La bottom bar usa una transizione root-tab senza animazioni e senza stack duplicati; il dispositivo fisico ha verificato tab attivo no-op, rapid tap, Back non ciclico e inset corretti rispetto alla navigation bar di sistema.

Restano pendenti la QA grafica/pixel completa e la verifica runtime manuale delle Activity e degli export più recenti.

CI non viene eseguita automaticamente sui push a `develop`; il workflow resta disponibile su pull request e avvio manuale.

## Branching
- `develop`: sviluppo corrente.
- `main`: stabile.

Il passaggio su `main` va fatto solo dopo build, test e QA previsti.
