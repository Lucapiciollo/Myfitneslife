# Provider Contract

Gemini e OpenAI implementano lo stesso contratto applicativo. Il provider può cambiare a runtime senza modificare DTO, schema o logica UI.

Regole:
- input JSON canonico
- output JSON canonico
- stessi enum, nomi campi, tipi e casing
- target nutrizionali dinamici forniti dall'app
- tolleranza ±3%
- `agentValidation` non autorevole
- `appValidation` calcolata localmente e definitiva
- `NEEDS_INPUT` se manca un dato essenziale
- nessuna normalizzazione silenziosa di output provider non conformi
