# Provider AI

Questa cartella contiene gli agenti separati per provider.

- `openai/agents/`: prompt ottimizzati per GPT/OpenAI.
- `gemini/agents/`: prompt equivalenti ottimizzati per Gemini.
- `shared/`: contratto comune e regole che non dipendono dal provider.

## Vincolo
I due provider devono produrre lo stesso contratto JSON e passare gli stessi validator. Il cambio provider è un dettaglio runtime.

## Configurazione prevista in app
- Switch: `Usa Gemini`
- Se ON: Gemini è il provider attivo.
- Se OFF: viene mostrato il campo `OpenAI API key`.
- La chiave OpenAI non deve mai essere committata o inclusa in log/export.

In questa fase il selettore è predisposto ma non sono presenti chiamate di rete reali.

## Tolleranza nutrizionale runtime
La tolleranza ufficiale corrente del progetto è dal -3% allo 0% sui target nutrizionali dinamici calcolati dall'app: `target * 0.97 .. target`, mai sopra il target. Gli agenti Gemini e OpenAI devono usare i target ricevuti a runtime e non valori fissi di esempio.

## Validazione
Gli agenti di entrambi i provider possono restituire `agentValidation`, che è solo informativa. Il runtime calcola `appValidation` localmente nel range target -3% .. target; solo `appValidation` è autorevole.
