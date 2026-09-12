# MyFitAI Development Pack — UI V2

## Riferimento canonico
`assets/MOCK_APPROVATO_MYFITAI_V2_COMPLETO.png`

## Stato
Pacchetto fermato alla fase UI/mock navigabile. Le Activity coprono il flusso V2 approvato, incluse misure corporee fronte/retro, BIA, evoluzione fisica, menu alimentare, dettaglio pasto, lista spesa, sgarro, notifiche, progressi, review, storico/export e profilo.

## Regola principale
Fedeltà visuale 100% al mock approvato. Leggere `AGENTS.md` prima di qualunque modifica.

## Non ancora implementato
Room/persistenza reale; API OpenAI; agenti reali; JSON schema runtime; motore calorie/macros; Personal Response Engine; scheduler notifiche Android; versionamento reale dei piani; dati dinamici.

## Prossimo step quando autorizzato
Verifica visuale su device/emulatore Activity per Activity, correzione differenze rispetto al mock, quindi solo dopo collegamento dati e logica.

## Stato agenti IA
Le definizioni dei tre agenti V1 sono ora incluse nel pacchetto, insieme agli schemi JSON e a esempi di richieste. Sono **solo specifiche**: non sono ancora collegate al codice Android né a OpenAI.

Agenti pronti:
- NutritionAgent
- PlanReviewAgent
- ProgressAnalysisAgent

La lista della spesa resta deterministica e locale: deriva dal piano alimentare, non richiede un LLM.

## Aggiornamento V4 — provider AI selezionabile
Predisposta architettura runtime Gemini/OpenAI senza chiamate di rete:
- UI Impostazioni: `Usa Gemini` + campo OpenAI API key quando Gemini è OFF.
- Contratto `AiProvider` e selector runtime.
- Cartelle agenti separate Gemini e OpenAI/GPT.
- Stessi JSON Schema e stessi validator per entrambi i provider.

### V6 AI policy
Allineati Gemini e OpenAI su tolleranza ±3%, comfort digestivo/ritenzione basati su criteri documentabili e benchmark provider-parity.


Le note digestive devono essere fattuali e prudenti, senza claim fisiologici o marketing non necessari.

## V8 — JSON parity Gemini/OpenAI
Entrambi i provider devono restituire esattamente gli stessi DTO JSON definiti in `06-ai/schemas/`. Non sono ammessi alias o formati diversi per provider. Vedi `06-ai/providers/shared/JSON_FORMAT_PARITY.md`.

## V9 security update
OpenAI BYOK credentials are now required to use Android Keystore-backed encrypted storage. See `06-ai/05-openai-key-security.md`. Raw API keys must never enter ordinary persistence, exports, logs or backups.
