# Step 18 — Personal Response Engine

Stato: **IMPLEMENTATO LATO CODICE — BUILD/DEVICE TEST PENDING**.

## Obiettivo
Creare un contesto personale locale e sintetico per le generazioni alimentari future senza trasformare correlazioni in causalità e senza permettere all'IA di modificare i target nutrizionali deterministici dell'app.

## Fonti collegate
Il motore legge esclusivamente dati del profilo attivo:
- piani alimentari e storico versioni;
- sgarri/deviazioni;
- allenamenti e rest day;
- storico BIA;
- storico misure corporee.

L'aderenza ai singoli pasti **non viene inventata**: al momento non esiste ancora un evento persistente affidabile di pasto consumato/non consumato. Verrà aggiunta solo quando sarà presente un dominio di aderenza reale.

## Regole
- finestra predefinita: ultimi 56 giorni;
- nessun dato di un altro profilo può entrare nel riepilogo;
- pattern ricorrenti solo con evidenza minima;
- trend corporei solo con almeno due osservazioni reali della stessa metrica;
- nessuna interpolazione di valori mancanti;
- nessuna frase causale;
- nessuna diagnosi o inferenza clinica;
- nessuna modifica dei target kcal/macro;
- il contesto inviato al Nutrition Agent è limitato in lunghezza.

## Pattern locali attuali
- `RECURRENT_DEVIATIONS`: almeno 3 deviazioni nel periodo;
- `TRAINING_ROUTINE`: almeno 3 sessioni di allenamento;
- `REPEATED_PLAN_ADAPTATION`: almeno 2 versioni storiche create da adattamento sgarro;
- `SIMULTANEOUS_BODY_TRENDS`: più trend corporei osservabili nello stesso intervallo, dichiarati esplicitamente come co-occorrenza non causale.

## Integrazione con generazione piano
`NutritionPlanGenerationService` aggiunge al prompt un riepilogo generato localmente dal `PersonalResponseService`. Il prompt specifica che:
- il riepilogo serve solo per praticità, timing e varietà;
- le osservazioni storiche sono descrittive/associative;
- i target calcolati localmente restano autorevoli;
- la validazione finale ±3% resta dell'app.

Pipeline:

`Storici profilo -> PersonalResponseService -> PersonalResponseEngine -> sintesi bounded -> Nutrition Agent -> JSON -> validator locale -> business validator -> Room`

## Test predisposti
- pattern non emesso con evidenza insufficiente;
- ricorrenza deviazioni;
- routine allenamento;
- adattamenti ripetuti;
- trend corporei simultanei;
- delta corretti;
- contesto bounded;
- presenza esplicita del vincolo non causale e del divieto di alterare i target locali.

## QA residuo
Come concordato, CI/build e test runtime verranno eseguiti nel controllo finale dello Step 23.
