# JSON Format Parity

Per la stessa operazione, Gemini e OpenAI DEVONO restituire lo stesso identico schema JSON. Possono cambiare contenuti e alimenti proposti, ma non struttura, campi, tipi, casing o enum.

Pipeline obbligatoria:
provider -> JSON parse -> JSON Schema validation -> business validation -> persistence -> UI.

Output non conforme: `INVALID_SCHEMA` e retry controllato. Non introdurre alias come `proteinG` vs `proteinGrams` o `title` vs `dishName`.