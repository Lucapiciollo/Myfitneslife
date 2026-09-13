# Step 22 — Export profilo

L'export usa esclusivamente i dati del profilo attivo e non effettua chiamate IA o di rete.

## JSON

`myfitai_profile_export_v1` è il formato canonico consigliato per backup leggibile e analisi con ChatGPT. Include profilo, storico BIA, misure corporee, allenamenti/riposi, sgarri, review settimanali e tutti i piani alimentari con tutte le relative versioni, giorni, pasti e ingredienti.

I valori mancanti restano `null`: non vengono convertiti in zero e non vengono interpolate rilevazioni assenti. Gli array storici sono esportati in ordine cronologico.

## CSV

L'opzione CSV genera un archivio ZIP con tabelle CSV per i dati piatti e conserva la gerarchia completa dei piani in `meal_plans.json`, per evitare una trasformazione lossy di versioni/giorni/pasti/ingredienti.

## PDF

Il PDF è un riepilogo umano dei dati disponibili. Non sostituisce JSON come export completo.

## Privacy e sicurezza

Le API key OpenAI/Gemini non vengono mai esportate. Non vengono esportati ciphertext del credential store, log o dati di altre identità/profili. Il file viene creato nella cache privata dell'app e condiviso tramite `FileProvider` con permesso di sola lettura temporaneo.

La foto profilo non viene incorporata nell'export strutturato: il suo percorso locale non è portabile e non viene divulgato.

## Criteri QA finali

Verificare export con profilo senza dati, profilo con storico parziale, più BIA nello stesso giorno, più misure nello stesso giorno, più versioni dello stesso piano, piani adattati dopo sgarro, review presenti/assenti, caratteri accentati e virgole/virgolette nei campi CSV. Verificare inoltre che un export del profilo A non contenga alcun record del profilo B e che il file condiviso sia leggibile dall'app destinataria senza accesso permanente allo storage privato.
