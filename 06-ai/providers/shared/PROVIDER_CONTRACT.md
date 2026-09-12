# AI Provider Contract — V1

## Obiettivo
L'app sceglie a runtime il provider AI senza modificare agenti, Activity o modelli di dominio.

## Provider supportati
- `GEMINI`
- `OPENAI`

## Regola di selezione
1. Se `useGemini == true`, il runtime usa `GeminiProvider`.
2. Se `useGemini == false`, è richiesta una `openAiApiKey` valida e il runtime usa `OpenAiProvider`.
3. Se OpenAI è selezionato ma la chiave non è disponibile, l'AI è in stato `NOT_CONFIGURED`.
4. La selezione provider non cambia target nutrizionali, dati, validazioni o schema JSON.

## Confine di responsabilità
L'app calcola i target dinamici. Gli agenti ricevono i target correnti e non possono sostituirli arbitrariamente.

Pipeline comune:

`App -> Local Nutrition Engine -> AiProvider -> Agent -> JSON -> Schema Validator -> Business Validator -> Persistenza -> UI`

Nessuna risposta AI entra direttamente in UI. Gli agenti possono restituire `agentValidation`, ma l’app calcola sempre `appValidation` in modo indipendente; in caso di conflitto prevale `appValidation`.

## Tolleranza nutrizionale
Il Business Validator considera valido un valore nutrizionale entro ±3% del relativo target dinamico corrente. La stessa regola deve essere comunicata a entrambi i provider. Non sono necessarie micro-correzioni se il risultato rientra nella soglia.

## Regola condivisa su comfort digestivo
Gemini e OpenAI devono applicare la stessa policy su gonfiore e ritenzione: nessuna teoria non documentata di food-combining, nessuna diagnosi, valutazione solo di fattori concreti (sodio, grassi/fibre/volume pre-workout, carico fermentabile se supportato dallo storico, tolleranza individuale). Gli stessi input devono produrre gli stessi campi strutturati e passare gli stessi validator.

## JSON parity vincolante — V8
Gemini e OpenAI devono usare gli stessi schema canonici in `06-ai/schemas/`. Nessun provider può introdurre un DTO alternativo o alias di campo. Una risposta semanticamente valida ma con chiavi diverse è comunque `INVALID_SCHEMA` e non raggiunge Business Validator, persistenza o UI. Vedi `JSON_FORMAT_PARITY.md`.
