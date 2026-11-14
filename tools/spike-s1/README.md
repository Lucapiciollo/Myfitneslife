# Spike S1: macro dichiarati dal modello vs macro calcolati da catalogo

Script di misura, solo analisi: non tocca app, database, UI, schemi o telefono. Il design finale sara' rifatto in Kotlin.
Risultati e conclusioni: `docs/SPIKE_S1_RISULTATI.md`.

## Cosa contiene
- `usda.py`: legge i CSV USDA FoodData Central (Foundation + SR Legacy) da una cartella temporanea.
- `search.py`: ricerca a parole nel catalogo USDA (aiuto per scegliere gli fdcId a mano).
- `build_catalog.py`: mappatura manuale alias italiani -> fdcId (80 voci) e generazione di `catalog_mini.json`.
- `catalog_mini.json`: mini-catalogo generato (valori per 100 g USDA, CC0). Tier A = USDA equivalente, B = proxy USDA, C = placeholder NON USDA (solo bresaola, da sostituire con CREA).
- `spike_s1.py`: copertura, unita', deviazione dichiarato-calcolato, simulazione del ciclo di riparazione, rischi crudo/cotto.

## Dati (non versionati)
Dump USDA e backup profilo (dati di salute) restano fuori dal repo. Lo script scrive solo aggregati.

1. Scaricare da https://fdc.nal.usda.gov/download-datasets (URL reali verificati il 14/11/2026):
   - `https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_foundation_food_csv_2025-12-18.zip`
   - `https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip`
   Nota: lo zip Foundation `2026-10-29` (release piu' recente) nel nostro download NON conteneva `food.csv`,
   `food_nutrient.csv` ne' `food_portion.csv`, quindi e' stato usato il `2025-12-18`.
2. Estrarli in `<TMP>/f2025/` e `<TMP>/srlegacy/` (il loader cerca le sottocartelle con questi nomi).
3. Eseguire (Python 3, nessuna dipendenza esterna):

```
python build_catalog.py <TMP>                     # rigenera catalog_mini.json
python spike_s1.py --backups <backup1.json> <backup2.json> --out <cartella_fuori_dal_repo>
```

`results.json` (aggregati per tier di catalogo A, AB, ABC) viene scritto nella cartella `--out`.
I profili sono anonimizzati come P1, P2 e non compaiono nomi, pesi, BIA o misure.
