# MyFitAI — Specifica visuale per modernizzazione UI

Stato: **C · Fitness premium approvato come nuova direzione visuale; implementazione incrementale in corso**. Lo stato verificato di token, fondazioni, componenti e schermate è registrato in `DEVELOPMENT_PLAN.md`.

Questo documento definisce la direzione visuale per la modernizzazione incrementale dell'interfaccia Views/XML con Compose nei nuovi slice. È la fonte delle scelte di presentazione; l'implementazione effettivamente presente e verificata, inclusa la slice Compose pilota, è registrata in `DEVELOPMENT_PLAN.md`.

## Fonti di verità e confini

- `AGENTS.md` definisce regole operative e invarianti del prodotto.
- `DEVELOPMENT_PLAN.md` è la fonte dello stato implementato e del lavoro ancora da fare.
- `assets/MOCK_APPROVATO_MYFITAI_V2_COMPLETO.png` resta la fonte funzionale/informativa per schermate, contenuti, testi, azioni, destinazioni e ordine informativo.
- Questo documento sostituisce il mock **solo** per le scelte visive qui autorizzate: palette e superfici, ruoli delle card, tipografia, spaziatura, forme, trattamento delle icone, grafici e motion.
- Se il mock e questa specifica sembrano confliggere su un contenuto, una funzione, un'azione, una route o sulla sequenza delle informazioni, prevalgono mock e comportamento attuale. Se il conflitto riguarda una decisione puramente visiva elencata sopra, prevale questa specifica.
- L'anteprima HTML/PNG `Calma Attiva` è un concept statico, non codice di produzione, non una fonte di dati e non un'autorizzazione a introdurre nuovi testi, target o azioni.

Il mock non va appiattito in una bitmap: ogni testo, controllo, card, grafico, icona funzionale, tab, lista e badge resta un componente UI reale. Asset decorativi e funzionali vanno identificati separatamente.

## Direzione visuale — C · Fitness premium

La tavola allegata dall'utente è riferimento di stile, non un mock dei dati dell'app. Usarne la grammatica visiva (fondo chiaro, verde bosco, superfici ordinate, card leggibili e gerarchia fitness) senza copiarne schermate, testo, immagini, metriche, valori o azioni.

- Tema prevalentemente chiaro: canvas bianco/caldo molto chiaro, superfici bianche e grigi verdi appena tonali.
- Verde bosco profondo come brand/azione primaria, con verde MyFitAI semantico per progressi e conferme; accenti ambra/rosso/blu solo per significati esistenti e con moderazione.
- Testo antracite/verde molto scuro, secondari leggibili; niente grandi superfici nere.
- Card premium con bordi sottili, elevazione quasi impercettibile, raggi moderati e ritmo verticale regolare. Differenziare hero, metrica, lista, insight e stato solo dove serve alla gerarchia corrente.
- Titoli schermata/sezione chiari e decisi; label compatte ma leggibili; valori numerici in evidenza senza alterare le unità o il contesto.
- Linee e marker puliti per serie temporali reali. Anelli solo per consumi/progressi che hanno target esplicito già presente nei dati.
- Icone coerenti Material, esclusivamente per chiarire azioni o categorie già esistenti; evitare fotografie decorative o asset non licenziati.
- Wrapping naturale e layout adattivo: niente clipping di dati, etichette o azioni su schermi piccoli o font scale aumentata.
- Motion breve e funzionale, rispettoso di animator scale, battery saver e accessibilità.

## Inventario e sequenza di copertura

### Matrice schermate

| Area | Activity/schermata | Stato/azioni da preservare |
|---|---|---|
| Avvio | `SplashActivity`, `OnboardingActivity`, `OnboardingWizardActivity` | avanzamento onboarding, form profilo, validazione e salvataggio |
| Shell | `TabHostActivity`, `BaseShellActivity` | cinque tab, Activity root embedded, profilo attivo, back/exit, CTA IA |
| Home | `HomeActivity` | metriche, trend, calorie/consumi, check-in, recupero, aspettativa, pasti/workout, azioni e stati vuoti |
| Alimentazione | `FoodPlanActivity`, `NutritionPathActivity`, `NutritionPlanSettingsActivity`, `DietaryPreferencesActivity` | settimana/giorno, target, generazione, error/loading/empty, preferenze e schedulazione |
| Pasti | `MealDetailActivity`, `MealAlternativeActivity`, `CheatEntryActivity`, `AdjustedPlanActivity` | tab dettaglio, ingredienti/pesi, alternative, inserimento extra, loading/error e preview piano adattato |
| Spesa | `ShoppingListActivity` | aggregazione esistente, categorie/filtri, stato checkbox, reset e condivisione |
| Allenamento | `WorkoutsActivity`, `NewWorkoutActivity` | settimana/giorno, rest/workout, inserimento/validazione/eliminazione |
| Rilevazioni | `MeasurementsActivity`, `BiaActivity`, `BodyMeasuresActivity`, `NewBodyMeasurementActivity` | hub, form, storico, picker, import BIA, analisi, empty/loading/error |
| Progressi e IA | `PhysicalEvolutionActivity`, `AiAnalysisActivity`, `WeeklyReviewActivity` | selettori periodo/metrica, grafici, stati dati insufficienti, analisi e review |
| Profilo | `ProfileActivity`, `ProfileEditActivity` | profili, immagine/avatar, obiettivi, dati e preferenze |
| Dati/sistema | `HistoryActivity`, `ExportActivity`, `NotificationsActivity`, `SettingsActivity`, `NutritionAdviceActivity` | storico/export/import, reminder, provider/chiavi protette, costi/config, dialog modale IA |

Sono 30 Activity nel manifest includendo splash/host e flussi secondari. Il runtime attuale non dichiara bottom sheet di prodotto; usa AlertDialog, Material date/time picker, dropdown/PopupMenu, selettori custom e una schermata IA flottante. I componenti condivisi includono `BaseShellActivity`, `BottomNavBinder`, 23 custom View, grafici, controlli segmentati e le primitive Compose `MyFitAiCard`, `MyFitAiSectionTitle`, `MyFitAiStatusChip`, `MyFitAiButton`.

### Ordine di restyling

1. Token semantici centrali e tema Views/Compose in sincronia (slice attuale).
2. Home, completando header e gerarchia delle sezioni con screenshot top/scroll, mantenendo l'attuale pannello metriche Compose e i restanti componenti Views.
3. Primitive condivise e chrome: card, header, righe, CTA, bottom navigation, feedback/loading/empty/error.
4. Tab e schermate per gruppi verticali: Alimentazione/pasti/spesa; Profilo/rilevazioni/BIA; Progressi/review/analisi; Allenamenti; IA/Altro/Settings/Export/History/Notifications.
5. Form, picker, dropdown, dialog e modale IA; verifica integrazione con il tema comune.
6. QA per slice con gli stessi dati: compilazione, test, route/tap/back/scroll/IME, stati, viewport compact, landscape e font scale; expanded solo se stabile.

In ogni slice classificare decorazione/background separatamente da testo, card, input, pulsanti, grafici, icone, tab e liste funzionali. Nessuna schermata diventa bitmap; nessun dato o affordance viene aggiunto sulla base degli esempi illustrati nella tavola.

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

Le risorse runtime centrali `colors.xml`, `dimens.xml`, `integers.xml`, `fractions.xml` e `themes.xml` sono state ampliate; il bridge Compose legge token Android tramite `MyFitAiTheme`. Una scansione dell'attuale UI non ha rilevato dimensioni dp/sp o colori hex inline in Kotlin, layout e drawable, mentre le Activity/widget usano riferimenti a risorse. Restano aperti l'inventario delle varianti e dei parametri visuali programmatici, la chiusura formale dell'audit globale e la certificazione multi-window; vedere `DEVELOPMENT_PLAN.md` per evidenze e limiti aggiornati. Questo non implica che il redesign di tutte le schermate sia completato.

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
