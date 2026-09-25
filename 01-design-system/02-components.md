# MyFitAI — Catalogo componenti UI

Architettura attuale: Android Views/XML e viste custom Kotlin, Material 3. Le pagine esistenti non sono Compose; introdurre un nuovo toolkit non fa parte dell'uniformazione visuale e richiede una decisione architetturale separata.

## Componenti condivisi esistenti

| Ruolo | Implementazione | Regola visuale |
|---|---|---|
| Shell/header e insets | `BaseShellActivity`, `TabHostActivity` | Header standard, inset system bar coerenti, back accessibile |
| Navigazione root | `BottomNavBinder`, `view_bottom_navigation.xml` | Quattro destinazioni, stato selezionato esplicito, icona + label |
| Titolo sezione | `SectionHeaderView` / `Text.MyFitAI.Section` | Un solo stile di sezione, gerarchia Bold |
| Card metrica | `MetricCardView`, `DashboardMetricsPanel` | Label, valore e delta con semantica; non duplicare palette localmente |
| Righe chiave/valore e stato | `KeyValueRowView`, `StatusRowView`, `MeasurementRowView` | Allineamento costante, descrizione accessibile, area touch adeguata |
| CTA primaria/secondaria | stili `Widget.MyFitAI.Button.*` | Una primaria per area; secondarie outlined/text |
| Campo/dropdown | stili `Widget.MyFitAI.Input*` | Fondo bianco, bordo neutro, focus visibile, min 48dp |
| Segmenti/giorni/intervalli | `SelectableSegmentView`, `WeekDaySelectorView`, `TimeRangeSelectorView` | Selezione con colore e forma, target minimo 48dp ove interattivo |
| Righe nutrizione | `MealPlanRowView`, `IngredientRowView`, `MealCardView` | Immagine funzionale, gerarchia titolo/descrizione/valore coerente |
| Stato / dialog | `MaterialAlertDialogBuilder`, status row e card | Stati loading/content/empty/error, azione primaria esplicita |

## Regole d'uso

- Preferire questi componenti o i loro stili rispetto a `TextView`, card e pulsanti ricreati per pagina.
- Componenti generici ricevono testo, stato, contenuto e callback dal chiamante; non contengono regole business.
- Mantenere gli ID pubblici usati da Activity, ViewModel e test durante i refactor visuali.
- Eccezioni deliberate: splash/onboarding immersivo, hero del dettaglio pasto, promemoria full-screen, diagrammi corporei, grafici.
- Non annidare card senza necessità funzionale; usare divider e surface token per creare gerarchia.
- Immagini food/workout sono thumbnail illustrative e non devono mascherare valori o controlli.
- Ogni componente interattivo espone content description/label, focus, target e stati disabled/loading/error.
