# Step 8 — Profilo reale

## Stato
IMPLEMENTATO LATO CODICE — QA runtime/device pending.

## Implementato
- Supporto multi-profilo locale con `activeProfileId`.
- Isolamento dei dati per profilo nel layer Room/repository.
- Selettore profilo nella schermata Profilo.
- Creazione di nuovi profili.
- Foto profilo da fotocamera o Photo Picker.
- Foto salvate nello storage privato dell'app; Room conserva solo il path.
- Nuova `ProfileEditActivity` collegata al profilo attivo.
- `ProfileEditViewModel`: la UI non accede direttamente ai DAO.
- Modifica e persistenza di:
  - nome;
  - data di nascita;
  - altezza;
  - peso corrente;
  - obiettivo;
  - livello di attività;
  - ora di sveglia;
  - ora di sonno;
  - preferenze/note alimentari.
- `MaterialDatePicker` per data di nascita.
- `MaterialTimePicker` per sveglia/sonno.
- Dropdown Material per obiettivo e livello di attività.
- Validazione minima di nome, altezza e peso.
- Le righe Dati personali / Obiettivi / Preferenze alimentari / Giornata aprono l'editor reale.
- Al salvataggio, tornando al Profilo, riepilogo e obiettivo sono alimentati dai dati persistiti.

## Regole
- I dati sensibili del provider IA non fanno parte del profilo e non vengono salvati in Room.
- Nessuna immagine viene salvata come BLOB nel database.
- Il profilo attivo governa tutti i futuri collegamenti di BIA, misure, allenamenti, piano, sgarri e review.

## Da verificare in QA
- Build CI completa.
- Inserimento/modifica dati e riapertura app.
- Cambio profilo e verifica isolamento.
- Photo Picker su API supportate.
- Fotocamera/FileProvider.
- Rotazione Activity durante picker/dialog.
- Visuale su device piccolo/grande.

## Prossimo collegamento
Step 9: BIA reale filtrata per `activeProfileId`, con inserimento, storico, ultimo valore, delta e medie.
