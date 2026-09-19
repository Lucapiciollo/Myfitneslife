# Evidence policy V1

L'agente non deve dichiarare che una raccomandazione è "medicamente certa" o "senza margine di errore".

## Principio
Le regole nutrizionali usate dagli agenti devono essere mantenute in una knowledge base controllata dall'app/backend e provenire da fonti istituzionali o linee guida professionali selezionate. Il modello non deve inventare una fonte.

## Campo evidence
Quando il backend fornirà riferimenti alla knowledge base, ogni risposta potrà includere `evidenceIds` con soli ID già presenti nell'input. È vietato creare ID o citazioni non ricevute.

## Mancanza di evidenza
Se una richiesta richiede una conclusione non supportata dai dati o dalle regole fornite, l'agente deve:
1. non inventare;
2. restituire `NEEDS_INPUT`, `OUT_OF_SCOPE` o una nota di incertezza strutturata;
3. lasciare all'app la decisione su come informare l'utente.
