# Step 17 — Sgarro e adattamento piano

Stato: **IMPLEMENTATO LATO CODICE — BUILD/DEVICE TEST PENDING**.

## Flusso

1. Lo sgarro viene sempre salvato subito per il profilo attivo, anche se il provider IA non è disponibile o la validazione successiva fallisce.
2. Se esiste un piano per la settimana, l'app identifica il giorno e separa i pasti in:
   - `locked`: orario <= momento dello sgarro;
   - `future`: orario > momento dello sgarro.
3. L'IA riceve testo libero, quantità indicativa, note, target autorevoli e soltanto i pasti futuri modificabili.
4. L'output strutturato contiene stima kcal/macro, confidence, flag `adaptationPossible` e replacement dei soli pasti futuri.
5. Il validator locale rifiuta:
   - modifica di pasti passati;
   - modifica di giorni successivi;
   - cambio orario;
   - replacement mancanti/extra;
   - pasti futuri sotto 100 kcal;
   - ingredienti privi di quantità/metadati;
   - giornata effettiva fuori ±3% quando l'adattamento è dichiarato possibile.
6. Se lo sgarro rende impossibile rientrare nel target senza restrizioni eccessive, l'unico risultato accettabile è `adaptationPossible=false`: lo sgarro resta registrato e il piano non viene compensato in modo punitivo.
7. Quando l'adattamento è valido, viene creata una nuova versione immutabile del piano con reason `CHEAT_ADAPTATION:<cheatId>`.

## Storico

- `CheatEntryEntity` resta nello storico del profilo con timestamp, testo, quantità, note e stima strutturata quando disponibile.
- La versione del piano precedente non viene modificata.
- I giorni diversi da quello dello sgarro vengono copiati invariati nella nuova versione.
- Nello stesso giorno, i pasti già trascorsi vengono copiati invariati.

## Failure mode

La persistenza dello sgarro è indipendente dalla riuscita dell'IA: un errore provider/schema/business validation non fa perdere l'evento registrato e non modifica il piano.

## Verifica finale pendente

Build Gradle, test unitari/instrumented e test su device vengono eseguiti nello Step 23 come concordato.
