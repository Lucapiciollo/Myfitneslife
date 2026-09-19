# Step 24 — Audit mock residui, navigazione e coerenza funzionale

## Obiettivo
Eliminare contenuti dimostrativi ancora visibili nel runtime e rimuovere percorsi di navigazione che apparivano attivi ma non eseguivano alcuna azione reale.

## Correzioni applicate

### Storico
- Rimossi i record hardcoded di settimane, BIA, misure e percentuali di aderenza.
- Lo storico ora legge esclusivamente il profilo attivo.
- Categorie disponibili: Misure, BIA, Piani, Sgarri.
- Le righe sono costruite dinamicamente dai repository Room.
- I dati mancanti restano assenti e non vengono sostituiti con valori demo.
- I piani storici aprono esattamente la settimana selezionata in `FoodPlanActivity`.
- Gli sgarri sono mostrati come eventi storici reali e non sono resi cliccabili finché non esiste un dettaglio persistente dedicato.

### Piano alimentare
- Aggiunto supporto all'apertura diretta di una settimana tramite `EXTRA_WEEK_START_EPOCH_DAY`.
- Il `FoodPlanViewModel` normalizza sempre la data richiesta al lunedì della settimana.

### Impostazioni
- `Profilo` apre la gestione profilo reale.
- `Preferenze alimentari` apre l'editor del profilo, dove le preferenze sono persistite.
- `Unità di misura` non appare più come navigazione finta: mostra `Metrico` ed è disabilitata finché non verrà introdotta una reale scelta di unità.
- `Privacy e dati` mostra informazioni coerenti con il comportamento effettivo dell'app.
- `Esporta dati` continua ad aprire l'export reale.

### Schermata promemoria
- Rimossi ora, data, tipo pasto e descrizione hardcoded.
- La schermata usa gli extra del reminder realmente ricevuto.
- Ora/data sono calcolate al momento dell'apertura.
- Titolo, lead time, descrizione e immagine vengono derivati dal promemoria reale.

## Regole di coerenza confermate
- Nessuna percentuale di aderenza viene mostrata se non esiste una persistenza reale dei pasti consumati.
- Nessun elemento storico viene inventato per riempire la UI.
- Le righe non supportate da un dettaglio reale non simulano una navigazione.
- I dati restano isolati per profilo attivo.
- Le settimane del piano mantengono lo storico versionato già implementato.

## Verifica finale ancora da eseguire
La build non è stata lanciata in questo step per la quota GitHub Actions concordata. Prima della release vanno eseguiti build locale/CI, migration test Room, test su device delle notifiche e verifica end-to-end delle rotte.
