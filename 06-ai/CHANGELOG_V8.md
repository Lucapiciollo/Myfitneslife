# V8 — Provider JSON parity
- Reso vincolante un unico formato JSON per Gemini e OpenAI.
- Gli schema in `06-ai/schemas/` sono la sola fonte di verità.
- Vietati alias provider-specifici (`proteinGrams`, `dishName`, ecc.).
- Nessuna normalizzazione silenziosa: formato errato -> `INVALID_SCHEMA` -> retry.
- Aggiornati tutti e 6 gli agenti provider-specifici con JSON FORMAT PARITY.
- Aggiunto `JSON_FORMAT_PARITY.md` e fixture canonica.
