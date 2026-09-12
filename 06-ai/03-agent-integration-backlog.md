# Backlog integrazione agenti — NON IMPLEMENTATO

Le definizioni sono già presenti nel pacchetto; i seguenti punti devono essere fatti solo nella fase IA.

1. AI Gateway unico nell'app/backend.
2. Configurazione chiave OpenAI separata dal codice sorgente.
3. Structured Outputs / JSON Schema per ogni agente.
4. Mapping request domain -> DTO JSON.
5. Validazione JSON Schema.
6. Validazione business locale (totali kcal/macros, pasti futuri, duplicati, orari, alimenti esclusi).
7. Chiamata al `PlanReviewAgent` per ogni piano nuovo/modificato.
8. Persistenza del piano solo se APPROVED.
9. Versionamento immutabile delle modifiche.
10. Logging tecnico senza chiavi API e senza dati superflui.
11. Gestione retry/fallback controllato.
12. Test con fixtures presenti in `06-ai/examples/`.
13. Knowledge base controllata e versionata; passare agli agenti solo `evidenceIds` esistenti.
14. Telemetria costi/token opzionale.

## Stop attuale
NON collegare ancora questi agenti alle Activity. La UI approvata resta indipendente e navigabile con mock data.
