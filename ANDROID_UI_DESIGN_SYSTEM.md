# MyFitAI — Specifica visuale per modernizzazione UI

Stato: **specifica approvata per pianificazione; implementazione non iniziata**.

Questo documento definisce la direzione visuale per la migrazione incrementale da Android Views/XML a Jetpack Compose. È la fonte di verità per la nuova presentazione visuale; non dichiara Compose, token o componenti come già implementati.

## Fonti di verità e confini

- `AGENTS.md` definisce regole operative e invarianti del prodotto.
- `DEVELOPMENT_PLAN.md` è la fonte dello stato implementato e del lavoro ancora da fare.
- `assets/MOCK_APPROVATO_MYFITAI_V2_COMPLETO.png` resta la fonte funzionale/informativa per schermate, contenuti, testi, azioni, destinazioni e ordine informativo.
- Questo documento sostituisce il mock **solo** per le scelte visive qui autorizzate: palette e superfici, ruoli delle card, tipografia, spaziatura, forme, trattamento delle icone, grafici e motion.
- Se il mock e questa specifica sembrano confliggere su un contenuto, una funzione, un'azione, una route o sulla sequenza delle informazioni, prevalgono mock e comportamento attuale. Se il conflitto riguarda una decisione puramente visiva elencata sopra, prevale questa specifica.
- L'anteprima HTML/PNG `Calma Attiva` è un concept statico, non codice di produzione, non una fonte di dati e non un'autorizzazione a introdurre nuovi testi, target o azioni.

Il mock non va appiattito in una bitmap: ogni testo, controllo, card, grafico, icona funzionale, tab, lista e badge resta un componente UI reale. Asset decorativi e funzionali vanno identificati separatamente.

## Direzione visuale — Calma Attiva

- Fondo caldo quasi bianco; superfici chiare, prevalentemente bianche, con tonalità delicate per distinguere gruppi o stati.
- Verde MyFitAI come colore principale e per l'azione primaria; accenti secondari desaturati, usati con parsimonia e con semantica coerente.
- Massimo un accento dominante per card e, in generale, 3–4 famiglie cromatiche per viewport.
- Card distinguibili per ruolo: hero/riepilogo, metrica, insight, azione, lista e stato/avviso. Forme coerenti, bordi sottili ed elevazione leggera; evitare pile di card identiche e card annidate senza necessità.
- Titoli principali e di sezione Bold; titoli card e label importanti SemiBold; corpo normale e leggibile; testo secondario con contrasto sufficiente.
- Testi allineati su griglia, con wrapping naturale. Nessun clipping o ellissi su informazioni essenziali.
- Icone Material coerenti solo quando chiariscono concetto, stato o azione; descrizioni accessibili appropriate. Niente emoji come icone di prodotto.
- Motion breve, intenzionale, interrompibile e rispettoso delle preferenze di riduzione animazioni. La logica non deve dipendere dall'animazione.
- No palette arcobaleno, neon, grandi aree nere o gradienti ripetuti. Il tema di prodotto resta chiaro e coerente tra Activity durante questa iniziativa.

## Regola architetturale: un'unica sorgente di token

La palette e le misure visuali devono essere modificabili da un punto centrale, senza valori duplicati nelle Activity, nei composable, nei widget o nei grafici.

### Fonte runtime prevista

Per mantenere Views/XML e Compose coerenti durante la convivenza:

- `android/app/src/main/res/values/colors.xml`: unica sorgente editabile per valori colore e palette semantica.
- `android/app/src/main/res/values/dimens.xml`: unica sorgente editabile per spacing, gutter, dimensioni, touch target, raggi, bordi, elevazioni, ruoli tipografici scalati, motion geometry e parametri di rendering dei grafici.
- `android/app/src/main/res/values/themes.xml`: temi Material, typography e stili dei widget Views; evitare definizioni ripetute specifiche di singole schermate quando esiste uno stile condivisibile.
- Adapter Compose previsto in `ui/theme/`: costruisce `MaterialTheme` leggendo colori e dimensioni dalle risorse Android centrali. `ColorScheme`, `TextStyle`, `Shape`, spacing e motion Compose non devono contenere una seconda copia indipendente dei valori sorgente.
- Eventuali interi/booleani di configurazione puramente visuale (durate motion, soglie responsive o switch di varianti) devono stare in risorse di configurazione appropriate e nominative, non come numeri ripetuti nei componenti.

I valori letterali della palette saranno presenti solo nelle risorse centrali. È corretto che il codice UI usi riferimenti semantici come `R.color.text_primary`, `colorResource(...)` o `dimensionResource(...)`; non deve incorporare valori esadecimali/RGB o valori arbitrari di dp/sp per riprodurre lo stile.

### Classi di token

1. **Colori semantici:** background, surface, on-surface, testo primario/secondario, outline/divider, brand/azione, focus/selection, success, warning, error, info, AI e chart. Le schermate consumano ruoli semantici, non scelgono colori in base a preferenze locali.
2. **Dimensioni:** scala spacing, gutter per classi di finestra, contenuto card, altezze minime accessibili, raggi, bordi, elevazione, icone e misure adattive.
3. **Tipografia:** ruoli condivisi per display/hero, titolo schermata, sezione, card, body, label, caption e microcopy; Compose e Views mappano agli stessi ruoli in `dimens.xml` + `themes.xml`, rispettando sempre la font scale del sistema.
4. **Forme/elevazione:** scale limitate condivise per card, campi, chip, dialog e superfici focali; niente nuove forme/elevazioni ad hoc nella schermata.
5. **Motion:** durate e curve nominate e condivise; supporto a reduced motion, battery saver e animator scale.
6. **Grafici:** token condivisi per serie, fill, griglie, label, linee, marker e indicatori semantici. Il grafico non introduce colori o misure autonome.

Le attuali risorse `colors.xml`, `dimens.xml` e `themes.xml` contengono già token parziali, ma la baseline ha riferimenti visuali diretti dalle Activity/widget, alcune misure di grafico definite nelle classi e due colori esadecimali inline nel layout `m3_alert_dialog.xml`. L'audit non li considera già normalizzati. Il consolidamento o la loro correzione appartiene alle successive fasi implementative, non è stato eseguito scrivendo questa specifica.

## Componenti riutilizzabili previsti

Creare solo componenti che eliminano duplicazione reale; prima adattare/riusare i 23 custom View esistenti dove opportuno. Ogni nuovo componente deve avere una API piccola, stato presentazionale esplicito, callback, semantica accessibile e test/preview rappresentativi.

Famiglie candidate, non ancora implementate come Compose:

- **Fondazioni:** `MyFitAiTheme`, surface/scaffold di contenuto per le Activity esistenti, top bar e slot di pagina senza possedere navigazione.
- **Superfici:** `AppCard` con varianti semanticamente nominate (hero, metric, insight, action, list, status); evitare parametri arbitrari di colore o raggi per ogni chiamata.
- **Testo e dati:** titoli sezione, righe chiave-valore, metriche, delta/trend, badge di stato.
- **Azioni e input:** pulsanti primary/secondary/text/icon, campi, dropdown, segmenti e chip selezionabili.
- **Grafici:** grafici trend e indicatori di progresso che ricevono serie/valori già calcolati e rispettano token condivisi.
- **Feedback:** loading, empty, error, disabled, snackbar, dialog e bottom sheet; il contenuto funzionale e i callback esistenti restano invariati.

Non creare un nuovo componente solo per rinominare un `Text`/`View`; prima verificare riuso, comportamento, accessibilità e costo di interop.

## Semantica grafici

- Anello soltanto per proporzioni/progressi con un range e un riferimento o target già presenti nei dati correnti.
- Linea/area per serie temporali reali; barre per confronti tra valori.
- Visualizzare sempre label, valore, unità, periodo e contesto. Il colore non è l'unica codifica del significato.
- Non aggiungere calcoli, target o interpretazioni nella UI e non trasformare metriche isolate in grafici decorativi.

## Ordine di lavoro e verifica

Procedere per fasi approvate, mantenendo ogni fase compilabile e utilizzabile:

1. Baseline, inventario, specifica visuale e contratti funzionali (audit già eseguito; questa specifica è la fase corrente).
2. Audit/centralizzazione completa delle risorse e fondazioni Compose/interoperabilità, senza cambiare shell o navigazione.
3. Componenti riutilizzabili che consumano l'unica sorgente di token.
4. Home come candidata pilota: Activity, `TabHostActivity`, `HomeViewModel`, eventi e callback esistenti.
5. Migrazione per piccoli gruppi di schermate a rischio crescente.
6. Consolidamento e rimozione XML solo dopo equivalenza e assenza di riferimenti.

Per ogni slice: build, unit test e instrumented test pertinenti; screenshot comparativi con gli stessi dati/viewport; verifica di tap, Back, scroll, IME, lifecycle, stati loading/empty/error/disabled, orientamento e font scale. Verificare expanded/split-screen solo con un ambiente stabile e riportare se non disponibile. Le Activity, la shell embedded e la bottom navigation restano quelle esistenti; una migrazione di navigazione è esclusa e richiede un progetto separato.

## Criterio anti-duplicazione prima del completamento

Una schermata non è completata se palette, typography, spacing, shape, elevazione o motion sono definiti localmente invece di usare token condivisi. Prima di rimuovere legacy cercare riferimenti XML/runtime, eseguire test e confrontare screenshot. Non dichiarare implementato ciò che esiste solo in questa specifica o nel concept statico.
