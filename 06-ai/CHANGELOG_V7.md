# AI Agent Update V7

## Modifiche principali
- Tolleranza nutrizionale ufficiale aggiornata a **±3%** sui target dinamici correnti.
- Formula condivisa: `minValid = target * 0.97`, `maxValid = target * 1.03`.
- Gli agenti non sono più autorità finale sulla validazione numerica.
- Le risposte degli agenti possono includere `agentValidation` solo come autovalutazione informativa.
- L'app calcola sempre `appValidation` tramite Business Validator locale; in caso di conflitto prevale `appValidation`.
- Le note sul comfort digestivo devono essere fattuali e prudenti, senza claim fisiologici o marketing non necessari.
- Schemi JSON aggiornati con `agentValidation`, `nutritionConfidence`, `weightState` e `digestiveProfile` dove applicabile.
- Prompt benchmark Gemini/OpenAI aggiornato alla tolleranza ±3% e a `agentValidation`.

## Regola runtime
`App -> target dinamici -> provider AI -> agentValidation -> schema validation -> appValidation -> persistenza -> UI`
