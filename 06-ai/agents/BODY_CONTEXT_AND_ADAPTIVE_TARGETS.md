# Body Context & Adaptive Nutrition Targets V1

Questa regola è condivisa dal NutritionAgent indipendentemente dal provider.

## Autorità dei target

L'app decide localmente calorie e macro. Il modello NON può ricalcolare, aumentare o ridurre `T` usando BIA, peso o circonferenze. Il modello usa i dati corporei esclusivamente come contesto per scegliere alimenti, distribuzione e timing del piano.

Il target iniziale deriva dal `LocalCalculationEngine`. Per `WEIGHT_LOSS` parte da TDEE -15%; per `RECOMPOSITION` da TDEE -5%. Gli altri obiettivi mantengono le regole deterministiche del motore locale.

Dopo uno storico sufficiente, `AdaptiveNutritionTargetEngine` può modificare localmente e in modo conservativo il deficit. Sono richiesti almeno 21 giorni e almeno due segnali corporei confrontabili; uno stallo richiede almeno 28 giorni. Le correzioni sono di 2,5 punti percentuali del TDEE e restano limitate a 12,5-17,5% di deficit per dimagrimento e 2,5-7,5% per ricomposizione.

## Protocollo corporeo compatto

BIA, ordine fisso:
`weightKg|bodyFatPct|muscleMassKg|skeletalMuscleKg|bodyWaterPct|visceralFat`

- `B0`: baseline iniziale.
- `B`: valori correnti.
- `BT`: delta del trend recente, finestra massima 56 giorni.

Misure corporee, ordine fisso:
`chest|waist|abdomen|shoulders|glutes|armLeft|armRight|thighLeft|thighRight|calfLeft|calfRight`

- `BM0`: baseline iniziale.
- `BM`: valori correnti.
- `BMD`: delta rispetto alla rilevazione precedente.
- `BMT`: delta del trend recente, finestra massima 56 giorni.

`?` significa dato non disponibile. Il modello non deve inventarlo.

## Regole di interpretazione

- Peso, BIA e circonferenze vanno letti congiuntamente; il solo peso non giustifica una modifica nutrizionale.
- Una singola rilevazione non prova un trend e non va interpretata causalmente.
- Vita/addome in calo con massa muscolare stabile o crescente può essere trattato come contesto compatibile con progresso favorevole, senza diagnosi e senza modificare `T`.
- Differenze sinistra/destra sono osservazioni, non diagnosi.
- Non dedurre disidratazione, edema, perdita muscolare patologica, intolleranze o malattie dai dati.
- Il modello può adattare composizione pratica e timing dei pasti ma deve rispettare `T` entro la tolleranza ufficiale ±3%.

## Persistenza e tracciabilità

Ogni piano resta versionato. La versione salva il target effettivo applicato; `reason` registra decisione adattiva, codice motivo e finestra di evidenza. Il target precedente resta ricostruibile dalle versioni precedenti, senza introdurre una seconda fonte di verità.
