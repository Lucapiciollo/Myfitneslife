# MyFitAI — HANDOFF OBBLIGATORIO PER AGENTI

## Regola vincolante UI
Il mock approvato è una specifica, non un'ispirazione. Fedeltà visuale 100%. Prima di modificare una Activity classificare ogni elemento come BACKGROUND/DECORAZIONE oppure COMPONENTE REALE. Vietato reinterpretare autonomamente layout, colori, gerarchie, spacing e componenti.

## Scope V1
App personale. Niente social, marketplace, multiutente o funzionalità commerciali salvo richiesta esplicita.

## Flusso AI
App = orchestratore. Calcoli deterministici locali. Gli agenti ricevono JSON e restituiscono JSON conforme agli schema condivisi. Nessuna risposta IA entra direttamente nella UI.

Agenti:
- NutritionAgent
- PlanReviewAgent
- ProgressAnalysisAgent

Provider runtime:
- Gemini
- OpenAI/GPT

Gemini e OpenAI DEVONO restituire lo stesso identico formato JSON per la stessa operazione. Nessun alias provider-specifico.

## Nutrizione
I target sono dinamici e calcolati dall'app. Tolleranza ufficiale ±3%. `agentValidation` è solo autovalutazione; `appValidation` è definitiva. Gli agenti devono restare nel dominio alimentare, non inventare dati e usare note digestive fattuali basate su sodio, fibre, grassi, volume, timing e sensibilità storiche realmente dichiarate.

## Sicurezza OpenAI — IMPERATIVA
La chiave OpenAI non deve mai essere salvata in chiaro. Android Keystore è root of trust; cifratura autenticata AES-GCM; persistere solo ciphertext/IV/metadata. Vietato inserirla in Room, normali SharedPreferences, log, crash report, analytics, export, backup, source code, BuildConfig, Intent o navigation arguments. La schermata credenziali deve impedire screenshot/registrazione. Questa regola non è derogabile.

## Branching
Sviluppare su `develop`. Portare su branch stabile solo dopo verifica e autorizzazione.