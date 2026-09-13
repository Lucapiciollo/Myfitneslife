# Step 8 — Profilo reale multi-profilo

## Obiettivo
Trasformare il profilo da singolo record statico a contesto attivo dell'intera app. Ogni profilo deve avere dati completamente separati: BIA, misure corporee, allenamenti, piani alimentari/versioni, sgarri e review.

## Implementato
- `UserProfileEntity` multi-record con id autogenerato.
- `photoPath` persistito come path nello storage privato; nessun BLOB immagine in Room.
- `profileId` aggiunto alle entità radice di BIA, misure, allenamenti, piani, sgarri e review.
- DAO e repository filtrano esplicitamente per `profileId`.
- `ActiveProfileStore` conserva solamente l'id del profilo attivo.
- Room schema v2 con migrazione 1→2 che assegna i dati legacy al profilo id=1.
- `ProfilePhotoStore` per import galleria, foto fotocamera e rimozione foto nello storage privato.
- `FileProvider` configurato per lo scatto fotocamera.
- `ProfileActivity` consente cambio profilo, creazione nuovo profilo, foto da galleria, scatto da fotocamera e rimozione foto.
- Test Room aggiornato con verifica isolamento dati tra due profili.

## Regole vincolanti
- Nessuna schermata dati deve usare query globali: deve sempre risolvere `activeProfileId` e passarlo al repository.
- Cambiare profilo deve cambiare tutto il contesto applicativo, non solo nome/avatar.
- Le foto restano nello storage privato dell'app; Room conserva solo il path.
- Le API key IA non sono per-profilo e non vengono mai salvate nelle tabelle profilo.
- Eliminando un profilo, prima di rendere la funzione disponibile in UI, dovrà essere definita una cancellazione transazionale di tutti i suoi dati e della sua foto.

## Ancora da fare nello Step 8
- Form reale modifica dati personali.
- Obiettivo, attività, orari, preferenze alimentari e allenamenti configurabili.
- ViewModel dedicato al profilo e stato UI osservabile.
- Propagazione dell'`activeProfileId` a Home e a tutte le schermate dati negli step successivi.
- Eventuale crop/rotate manuale della foto prima del salvataggio; al momento l'avatar usa `centerCrop`.
- UI eliminazione profilo con conferma e gestione profilo attivo successivo.
- Test runtime Photo Picker/Camera su device/emulatore.
