# AI Provider Contract — V1

## Obiettivo
L'app sceglie a runtime il provider AI senza modificare agenti, Activity o modelli di dominio.

## Provider supportati
- `GEMINI`
- `OPENAI`

## Regola di selezione
1. Se `useGemini == true`, è richiesta una Gemini API key cifrata e il runtime usa `GeminiByokProvider` con chiamata diretta alla Gemini Developer API.
2. Se `useGemini == false`, è richiesta una OpenAI API key cifrata e il runtime usa `OpenAiProvider`.
3. Se il provider selezionato non ha una chiave disponibile, l'AI è in stato `NOT_CONFIGURED`.
4. La selezione provider non cambia target nutrizionali, dati, validazioni o schema JSON.

Firebase resta disponibile per servizi futuri; Firebase AI Logic è esplicitamente disabilitato e non è coinvolto nel runtime Gemini.

## Confine di responsabilità
L'app calcola i target dinamici. Gli agenti ricevono i target correnti e non possono sostituirli arbitrariamente.

Pipeline comune:

`App -> Local Nutrition Engine -> AiProvider -> Agent -> JSON -> Schema Validator -> Business Validator -> Persistenza -> UI`

Nessuna risposta AI entra direttamente in UI. Gli agenti possono restituire `agentValidation`, ma l’app calcola sempre `appValidation` in modo indipendente; in caso di conflitto prevale `appValidation`.

## Tolleranza nutrizionale
Il Business Validator considera valido un valore nutrizionale entro ±3% del relativo target dinamico corrente. La stessa regola deve essere comunicata a entrambi i provider. Non sono necessarie micro-correzioni se il risultato rientra nella soglia.

## Regola condivisa su comfort digestivo
Gemini e OpenAI devono applicare la stessa policy su gonfiore e ritenzione: nessuna teoria non documentata di food-combining, nessuna diagnosi, valutazione solo di fattori concreti (sodio, grassi/fibre/volume pre-workout, carico fermentabile se supportato dallo storico, tolleranza individuale). Gli stessi input devono produrre gli stessi campi strutturati e passare gli stessi validator.

## Condimenti e bevande — regola condivisa vincolante
Gemini e OpenAI devono applicare integralmente `CONDIMENTS_BEVERAGES_RULES.md`.

Ogni condimento o bevanda con impatto nutrizionale deve essere quantificato e conteggiato. Ogni ingrediente deve avere quantità numerica, unità e `displayDose` pratica e coerente. Sono vietate quantità vaghe per elementi che incidono su calorie, macro o sodio. Le conversioni domestiche devono derivare da equivalenze note e non possono essere inventate.

## Stagionalità e timing — regola condivisa vincolante
Gemini e OpenAI devono applicare integralmente `SEASONALITY_TIMING_RULES.md`.

Quando più alternative sono equivalenti e compatibili, preferire frutta, verdura e altri alimenti realmente stagionali rispetto alla data e all'area geografica fornite dall'app. La stagionalità è subordinata a sicurezza, target dinamici ±3%, timing, comfort digestivo, preferenze e aderenza. Non inventare la stagionalità se i dati necessari non sono disponibili.

Gli orari devono essere coerenti con quelli forniti dall'app e con allenamento/sonno, senza regole pseudo-scientifiche rigide (es. frutta solo al mattino, carboidrati vietati la sera). Ogni sostituzione stagionale deve mantenere calorie e macro nei target dinamici entro ±3%.

## JSON parity vincolante — V8
Gemini e OpenAI devono usare gli stessi schema canonici in `06-ai/schemas/`. Nessun provider può introdurre un DTO alternativo o alias di campo. Una risposta semanticamente valida ma con chiavi diverse è comunque `INVALID_SCHEMA` e non raggiunge Business Validator, persistenza o UI. Vedi `JSON_FORMAT_PARITY.md`.
