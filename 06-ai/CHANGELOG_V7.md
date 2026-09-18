# AI Agent Update V7

## Modifiche principali
- Tolleranza nutrizionale ufficiale aggiornata al range **target -3% .. target** sui target dinamici correnti.
- Formula condivisa: `minValid = target * 0.97`, `maxValid = target`.
- Gli agenti non sono più autorità finale sulla validazione numerica.
- Le risposte degli agenti possono includere `agentValidation` solo come autovalutazione informativa.
- L'app calcola sempre `appValidation` tramite Business Validator locale; in caso di conflitto prevale `appValidation`.
- Le note sul comfort digestivo devono essere fattuali e prudenti, senza claim fisiologici o marketing non necessari.
- Schemi JSON aggiornati con `agentValidation`, `nutritionConfidence`, `weightState` e `digestiveProfile` dove applicabile.
- Prompt benchmark Gemini/OpenAI aggiornato al range target -3% .. target e a `agentValidation`.

## Regola runtime
`App -> target dinamici -> provider AI -> agentValidation -> schema validation -> appValidation -> persistenza -> UI`
