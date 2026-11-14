# Catalogo alimenti (tools/food-catalog)

Rigenera `android/app/src/main/assets/food/catalog_v1.json` (catalogo in sola lettura dell'app) a partire da:

- i CSV di USDA FoodData Central (CC0 1.0): **SR Legacy 2018-04** e **Foundation 2025-12-18**, in una cartella
  TEMPORANEA fuori dal repo (i dump non si versionano);
- la tabella curata a mano `aliases.json` (una voce per riga: id, nome, alias italiani, categoria, `fdc`, stato, coppia
  crudo/cotto, tag allergeni, stagionalita', porzioni, confidence).

Contesto, scelte e dati dello spike: `docs/PROGETTO_MACRO_DA_DATABASE_2026-11-14.md`, `docs/SPIKE_S1_RISULTATI.md`,
`tools/spike-s1/` (le 80 voci dello spike sono tutte presenti con lo stesso id, prefisso `it.`).

## Uso

```
python build_catalog.py --download      # scarica e decomprime i dump in <TMP>/myfitai-usda-fdc (una volta)
python build_catalog.py                 # rigenera catalog_v1.json
python build_catalog.py --usda <cartella> --out <file>
```

Python 3, nessuna dipendenza. La cartella dei dump si puo' dare anche con `FDC_DIR`. Lo script si ferma con errore se un
id e' duplicato, un alias collide (dopo normalizzazione), un `pairId` non e' simmetrico, un tag non e' nel vocabolario,
una porzione USDA richiesta non esiste, o acqua+macro superano 100,5 g.

Test che controllano l'asset: `FoodCatalogAssetTest` (JVM). Il controllo a campione dei valori per 100 g contro i CSV
parte solo se trova i CSV: imposta `MYFITAI_USDA_DIR` alla cartella dei dump (default `<TMP>/myfitai-usda-fdc`), altrimenti
viene saltato.

## Regole dei dati

- **kcal della fonte conservato cosi' com'e'** (Foundation: energia Atwater, id 2047/2048, quando manca l'id 1008).
  Fibra, sodio (mg), acqua: dalla fonte, assenti se la fonte non li ha. Nessun testo descrittivo USDA e' copiato.
- **confidence**: `A` = voce USDA equivalente; `B` = proxy USDA dichiarato (la `note` e' obbligatoria, es. skyr -> yogurt
  greco 0%, orata -> branzino); `C` = stima NON USDA (fdcId null, `note` "da verificare con etichetta"). Una voce C non
  diventa mai A.
- **unita' pratiche** (`units`): solo porzioni USDA `food_portion` con descrizione esatta (cucchiaio = `tbsp`,
  cucchiaino = `tsp`, fetta, uovo, pezzo), g per UNA unita'. Fa eccezione `bicchiere` = 200 ml per le bevande con
  densita' nota, marcato `convenzione 200 ml`. Mai equivalenze inventate.
- **resa** (`yield`, cotto/crudo) solo per cereali e legumi che assorbono acqua: rapporto kcal crudo/cotto della fonte
  (l'energia resta nel piatto). Per carne, pesce e verdure il rapporto kcal non e' una resa affidabile: assente, resta il
  `pairId` per cambiare stato.
- **tag** (`tags`): identificatori dei concetti di `FoodSynonyms` (domain/food) che la voce contiene (GLUTEN, MILK,
  LACTOSE, EGG, FISH, TREE_NUTS...). La dieta (vegetariana, vegana, pescetariana) e' derivata dai tag. NICKEL e SULPHITES
  non sono modellati. Controllo su elenco, non garanzia medica (vedi `06-ai/providers/shared/FOOD_CONSTRAINTS_LIMITS.md`).
- **stagionalita'** (`s`: mesi, es. `6-9`, `11-3`), categorie, `min/max/step` sono giudizi curati, non dati USDA.
- `edible` e' 1.0 salvo la voce `uovo` (0,88: valori USDA per parte edibile, senza guscio).
- Alias: normalizzati (minuscolo, senza accenti, punteggiatura, articoli/preposizioni; stessa funzione di
  `FoodCatalog.normalizeAlias`). Unici in tutto il catalogo, nome incluso.

## Limiti noti

- USDA non ha equivalenti italiani esatti per salumi, pane, formaggi freschi e piatti: proxy B o stime C da etichetta.
- Nessun alcolico, integratori solo proteine in polvere, nessuna voce personalizzata dell'utente.
- Per le voci cotte si usano le varianti USDA senza sale aggiunto (sodio confrontabile con il crudo).
