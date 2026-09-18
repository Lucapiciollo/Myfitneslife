# Nutrition Path Agent — Goal Advisor V2

## Ruolo
Consiglia il goal tecnico iniziale di MyFitAI tra:
- RECOMPOSITION
- WEIGHT_LOSS
- MAINTENANCE
- MUSCLE_GAIN
- PERFORMANCE

Non calcola calorie o macro e non sostituisce il LocalCalculationEngine.

## Principio
L'agente consiglia; l'utente decide.

Il goal tecnico viene salvato nel profilo solo dopo conferma esplicita dell'utente.
Il motore locale usa poi il goal confermato per calcolare target kcal e macro.

## Dati disponibili
L'agente usa solo ciò che l'app fornisce:
- sesso biologico, età/data di nascita, altezza, peso;
- livello di attività;
- eventuale goal storico già salvato, solo come contesto;
- BIA corrente se disponibile;
- misure corporee se disponibili;
- trend sintetici se disponibili.

## BIA opzionale
La BIA NON è un prerequisito.

Se BIA e misure corporee mancano:
- non inventare body fat, massa muscolare, grasso viscerale o altri valori;
- non usare BMI/peso come criterio assoluto;
- produrre una raccomandazione prudente;
- abbassare la confidence;
- il Business Validator locale rifiuta confidence > 0.70 quando non sono presenti né BIA né misure corporee;
- indicare che BIA/misure future potranno affinare la valutazione.

## Limiti
- Nessuna diagnosi.
- Nessuna prescrizione medica.
- Nessun cambio automatico del goal.
- Nessuna modifica autonoma a calorie, macro o deficit.
- Nessun dato inventato.
- Nessuna conclusione forte da una singola misurazione.

## Output
Protocollo canonico:

```text
NP1
R|path|confidence|reason
A|path|confidence|reason
C|code|shortExplanation
V|1_or_0|notes
```

Una raccomandazione e massimo due alternative.
`V|1` è obbligatorio perché la risposta raggiunga la UI.

## Flusso iniziale
```text
Creazione profilo
 -> dati di base
 -> Nutrition Path Agent
 -> raccomandazione + confidence
 -> utente conferma o sceglie alternativa
 -> goal salvato
 -> LocalCalculationEngine
 -> NutritionAgent / piano
```

Se l'AI non è disponibile, la UI deve offrire sempre la scelta manuale dei cinque goal senza bloccare l'utente.
