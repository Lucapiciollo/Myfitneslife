# Step 20 — Lista della spesa deterministica

Stato: **IMPLEMENTATO LATO CODICE — BUILD/DEVICE TEST PENDING**.

## Principi
- Nessuna IA viene usata per costruire la lista.
- La sorgente è esclusivamente l'ultima versione persistita del piano della settimana selezionata.
- La lista è isolata per profilo e settimana.
- Quantità mancanti/non positive non vengono inventate.

## Aggregazione
- Gli ingredienti vengono normalizzati per nome, unità e stato peso.
- `g` e `kg` sono convertiti e sommati in grammi.
- `ml` e `l` sono convertiti e sommati in millilitri.
- Le unità a pezzi sono normalizzate a `pz`.
- Ingredienti con stato peso differente (es. crudo/cotto) non vengono sommati tra loro.
- Le categorie provengono dai dati del piano; se assenti viene usato `Altro`.
- La presentazione converte automaticamente quantità >=1000 g/ml in kg/l.

## Stato acquisto / dispensa
Ogni articolo può essere:
- `TO_BUY` — da comprare;
- `PURCHASED` — preso;
- `PANTRY` — già disponibile in dispensa.

Lo stato è salvato localmente in SharedPreferences con chiave composta da profilo + settimana + chiave normalizzata ingrediente. Questo permette di mantenere lo stato per gli ingredienti equivalenti anche dopo una rigenerazione della stessa settimana senza mescolare profili o settimane diverse.

La checkbox è una scorciatoia per Preso/Da comprare. Toccando la riga si può scegliere esplicitamente tra Da comprare, Preso e Già in dispensa.

## Filtri e viste
- Vista `Settimana`: lista unica aggregata.
- Vista `Categorie`: raggruppamento dinamico per categoria.
- Filtri: Tutte, Da comprare, Presi, Dispensa.
- Reset con conferma: riporta gli articoli a Da comprare senza modificare il piano o le quantità.

## Condivisione
`Condividi lista` usa Android `ACTION_SEND` con testo semplice e include categoria, quantità richiesta e stato. Non esporta credenziali o altri dati del profilo.

## Storico/versioni
La lista legge sempre la versione corrente della settimana scelta. Lo stato acquisto/dispensa è intenzionalmente associato alla settimana e alla chiave ingrediente, non all'id della singola versione, per mantenere le spunte quando una nuova versione conserva lo stesso ingrediente.

## Test predisposti
- somma g + kg;
- separazione crudo/cotto;
- esclusione quantità non positive.

La build e il test UI/device verranno eseguiti nello Step 23 finale, come concordato.