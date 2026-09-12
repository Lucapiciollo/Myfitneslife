# Asset sources
- `MOCK_APPROVATO_MYFITAI_V2_COMPLETO.png`: mock generato appositamente nel progetto; riferimento visuale, non asset runtime.
- `body_front.xml`, `body_back.xml`: vector drawable originali creati per il progetto, nessuna dipendenza esterna.
- `img_next_meal.jpg`, `img_next_workout.jpg` (android drawable-nodpi): foto reali ad alta risoluzione da `meals/meal_lunch.jpg` (450x246) e `workouts/workout_dumbbell.jpg` (408x240), fornite nel pacchetto asset ufficiale del progetto (vedi `README_ASSETS.md`, `docs/ASSET_ACTIVITY_MAPPING.md`).
- `img_profile_avatar.png` (android drawable-nodpi): foto reale da `avatars/avatar_default_male.png` (248x248), stesso pacchetto asset ufficiale.
- Emoji presenti nei mock runtime: placeholder di sviluppo; sostituire in fase asset-final con Material Symbols/Lucide o asset originali/licenziati mantenendo la geometria del mock.
- Non introdurre asset esterni senza URL fonte, autore/licenza e data di acquisizione in questo file.
