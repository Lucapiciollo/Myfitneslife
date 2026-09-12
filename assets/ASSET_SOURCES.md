# Asset sources
- `MOCK_APPROVATO_MYFITAI_V2_COMPLETO.png`: mock generato appositamente nel progetto; riferimento visuale, non asset runtime.
- `body_front.xml`, `body_back.xml`: vector drawable originali creati per il progetto, nessuna dipendenza esterna.
- `img_next_meal.png`, `img_next_workout.png` (android drawable-nodpi): ritagli a bassa risoluzione (50x58 e 48x55 px) estratti da `02-dashboard/dashboard_next_meal_reference.png` e `dashboard_next_workout_reference.png`, a loro volta derivati dal mock V2 approvato (vedi `README_ASSETS.md`). Da sostituire con sorgenti originali ad alta risoluzione quando disponibili, mantenendo la stessa composizione.
- `img_profile_avatar.png` (android drawable-nodpi): ritaglio a bassa risoluzione (51x47 px) estratto da `03-profile/profile_avatar_reference.png`, derivato dal mock V2 approvato. Stessa nota di sostituzione futura.
- Emoji presenti nei mock runtime: placeholder di sviluppo; sostituire in fase asset-final con Material Symbols/Lucide o asset originali/licenziati mantenendo la geometria del mock.
- Non introdurre asset esterni senza URL fonte, autore/licenza e data di acquisizione in questo file.
