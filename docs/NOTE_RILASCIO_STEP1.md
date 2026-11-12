# Note di rilascio — Step 1 (età profilo e prerequisiti piano)

## Cambio di comportamento (generazione piano)

- Il profilo con data di nascita **presente ma età calcolata fuori 14–120 anni** (o nel futuro) **blocca la generazione del piano**, anche se esiste già un piano per la settimana. Prima l'età non valida veniva ignorata dal motore locale e i target potevano arrivare da un piano precedente.
- Il blocco mostra "Completa prima: controlla la data di nascita…" con azione per aprire l'editor profilo. Data di nascita assente: comportamento invariato (si usano i target già presenti, altrimenti prerequisito "inserisci la data di nascita").
- Form profilo, onboarding e `ProfileEditViewModel.save` rifiutano la stessa condizione. Regola unica: `ProfileAgeRule` (limiti da `LocalCalculationEngine.MIN_AGE_YEARS/MAX_AGE_YEARS`).
- Nessuna modifica allo schema Room né ai dati salvati: i profili già fuori range vengono solo segnalati.
