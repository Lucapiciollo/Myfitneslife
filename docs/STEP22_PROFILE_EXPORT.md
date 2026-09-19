# Step 22 — Export profilo

L'export usa esclusivamente i dati del profilo attivo e non effettua chiamate IA o di rete.

## JSON

`myfitai_profile_export_v1` è il formato canonico consigliato per backup leggibile e analisi con ChatGPT. Include profilo, storico BIA, misure corporee, allenamenti/riposi, sgarri, review settimanali e tutti i piani alimentari con tutte le relative versioni, giorni, pasti e ingredienti.

I valori mancanti restano `null`: non vengono convertiti in zero e non vengono interpolate rilevazioni assenti. Gli array storici sono esportati in ordine cronologico.

## CSV

L'opzione CSV genera un archivio ZIP con tabelle CSV per i dati piatti e conserva la gerarchia completa dei piani in `meal_plans.json`, per evitare una trasformazione lossy di versioni/giorni/pasti/ingredienti.

## Report profilo PDF

Il report profilo segue il layout approvato dell'anteprima MyFitAI, con palette dark coerente con l'app e impaginazione A4. È composto da cinque sezioni/pagine logiche:

1. riepilogo profilo con peso, massa grassa, massa muscolare, vita e snapshot BMI/BMR/TDEE/target;
2. BIA, misure corporee e proporzioni con classificazione non diagnostica;
3. grafici trend di peso e vita con delta del periodo quando calcolabile;
4. sintesi alimentazione, allenamenti/riposi, sgarri, BIA e misure registrate;
5. contenuto/metodologia dell'export e indicazione che il JSON resta il formato completo.

Il PDF non inventa valori: i dati mancanti vengono mostrati come `-` o `Dati insufficienti`.

## Dieta settimanale PDF

È un export distinto dal report profilo e usa la versione più recente del piano della settimana corrente; se non esiste, usa l'ultimo piano disponibile del profilo attivo.

Il layout segue l'anteprima approvata:

- copertina con intervallo settimana, profilo, calorie target e macro;
- riepilogo dei sette giorni;
- giorni impaginati in blocchi compatti con orario, tipo pasto, titolo, ingredienti/dosi, calorie e macro;
- lista della spesa finale aggregata per categoria con checkbox e quantità.

La lista della spesa è calcolata dal `ShoppingListEngine`: non viene generata dall'IA e deriva esclusivamente dagli ingredienti della stessa versione del piano esportato. Le quantità vengono sommate solo quando unità e stato peso sono compatibili.

## Privacy e sicurezza

Le API key Gemini/OpenAI non vengono mai esportate. Non vengono esportati ciphertext del credential store, log o dati di altre identità/profili. Il file viene creato nella cache privata dell'app e condiviso tramite `FileProvider` con permesso di sola lettura temporaneo.

La foto profilo non viene incorporata nell'export strutturato: il suo percorso locale non è portabile e non viene divulgato.

## Criteri QA finali

Verificare export con profilo senza dati, profilo con storico parziale, più BIA nello stesso giorno, più misure nello stesso giorno, più versioni dello stesso piano, piani adattati dopo sgarro, review presenti/assenti, caratteri accentati e virgole/virgolette nei campi CSV. Verificare inoltre che un export del profilo A non contenga alcun record del profilo B e che il file condiviso sia leggibile dall'app destinataria senza accesso permanente allo storage privato.

Per i PDF verificare anche: paginazione con titoli lunghi, molti ingredienti nello stesso pasto, categorie spesa molto numerose, giorni con numero di pasti diverso, dati parziali, assenza totale di piano e corretto fallback all'ultimo piano disponibile.
