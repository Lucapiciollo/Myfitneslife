# MyFitAI — Design tokens UI

Fonte grafica: mock V2 approvato. I valori seguenti descrivono il sistema Android Views/XML corrente e sono la base per le nuove schermate e per l'uniformazione progressiva delle Activity esistenti.

## Identità e principi

- Tema di prodotto unico: chiaro, fondo caldo, superfici chiare, verde MyFitAI e testo antracite.
- Il navy è riservato alle testate/aree focali previste dal mock; non è un secondo tema scuro.
- Verde pieno per azione primaria, selezione e stato positivo; non per decorare ogni card.
- Card prevalentemente bianche, bordo neutro sottile, ombra minima o nulla.
- Le composizioni splash/onboarding, hero dettaglio pasto e promemoria full-screen sono eccezioni intenzionali.
- Contenuti, grafici, campi, badge e controlli restano viste native reali; solo sfondi e decorazioni sono immagini/layer.

## Colori semantici

| Ruolo | Risorsa Android | Valore | Uso |
|---|---|---:|---|
| Fondo app | `bg_primary` | `#F7F8F4` | Fondo caldo standard |
| Fondo secondario | `bg_secondary` | `#F0F2EE` | Raggruppamenti e aree secondarie |
| Superficie primaria | `surface_primary` | `#FFFFFF` | Card e contenuti |
| Superficie secondaria | `surface_secondary` | `#F3F4F1` | Tile neutre e selezioni leggere |
| Superficie morbida | `surface_soft` | `#E9ECE8` | Scrim/contrasti leggeri su temi chiari |
| Brand primary | `accent_green` | `#20B653` | CTA, selezione, indicatori primari |
| Brand strong | `accent_green_dark` | `#11853A` | Testo/icone verdi su fondo chiaro |
| Testo principale | `text_primary` | `#18201C` | Titoli, valori, contenuto |
| Testo secondario | `text_secondary` | `#59635D` | Descrizioni, label |
| Testo attenuato | `text_muted` | `#8B948E` | Metadati non essenziali |
| Bordo/divisore | `divider` | `#D8E2D9` | Contorni neutri e separatori |
| Successo | `semantic_positive` | `#20B653` | Esito positivo, sempre con testo/icona |
| Attenzione | `semantic_warning` | `#D99A22` | Attenzione non bloccante |
| Errore | `semantic_error` | `#D94C46` | Errori e azioni distruttive |
| Info blu | `accent_blue` | `#4A8FD8` | Serie/indicatore dati quando etichettato |
| Info viola | `accent_purple` | `#8B6ACB` | Serie/indicatore dati quando etichettato |
| Info arancio | `accent_orange` | `#E78A43` | Serie/indicatore dati quando etichettato |
| Navy Home | `dashboard_header_bg` | `#10241F` | Testata dashboard focalizzata |
| Selezione | `pill_selected_bg` | `#DDE9DF` | Stato selected discreto |
| Pannello AI | `bg_ai` | token dedicato | Contenuto IA secondario, mai primaria CTA |

Non affidare mai solo al colore la comunicazione di stato; affiancare label, icona o forma.

## Tipografia

Famiglia: sans-serif di sistema Android, supporto `sp`/font scaling. Usare Bold per titoli principali e di sezione, SemiBold/Bold per valori-card e label importanti; testo continuo normale.

| Ruolo | Stile XML | Scala corrente | Peso/colore |
|---|---|---:|---|
| Hero / numero focale | `Text.MyFitAI.Hero` | 32–58sp, solo composizioni dedicate | Bold, primary/on-dark |
| Titolo schermata | `Text.MyFitAI.ScreenTitle` | 20sp | Bold, `text_primary` |
| Titolo sezione | `Text.MyFitAI.Section` | 16sp | Bold, `text_primary` |
| Titolo card | `Text.MyFitAI.CardTitle` | 15sp | SemiBold, `text_primary` |
| Valore metrica | `Text.MyFitAI.MetricValue` | 28sp | Bold, `text_primary` |
| Valore metrica compatto | `Text.MyFitAI.MetricValueCompact` | 16sp | Bold, `metric_value`; indicatori secondari |
| Valore chiave | `Text.MyFitAI.KeyValueValue` | 14sp | SemiBold/Bold, `text_primary` |
| Corpo | `Text.MyFitAI.Body` | 14sp | Regular, `text_secondary` |
| Corpo compatto | `Text.MyFitAI.BodyCompact` | 13sp | Regular, `text_secondary`; descrizioni già compatte |
| Corpo enfasi | `Text.MyFitAI.BodyEmphasis` | 14sp | SemiBold, `text_primary` |
| Scelta in dialog | `Text.MyFitAI.DialogChoice` | 14sp | Regular, `text_primary`; righe di selezione con target minimo 48dp |
| Label/caption | `Text.MyFitAI.Caption` | 12sp | Regular, `text_secondary` |
| Micro-metadato | `Text.MyFitAI.Micro` | 11sp | Regular, `text_muted`; solo se non essenziale |
| Navigazione | `Text.MyFitAI.Navigation` | 11–12sp | Medium/SemiBold, contrasto leggibile |

Valori dinamici che devono essere confrontati (per esempio macro, giorno e stato) non vanno compressi in microtesto. Le grandezze hero restano specifiche per splash e promemoria.

## Spaziature e dimensioni

Scala di riferimento: `space_4`, `space_8`, `space_12`, `space_16`, `space_24`, `space_32`; usare `space_2`/`space_3`/`space_5`/`space_6` soltanto per dettagli ravvicinati. `space_14` conserva i padding verticali specifici di messaggio vuoto ingredienti e card alternative; `space_18` conserva il padding verticale della card integrazione.

- Gutter pagina compact: 16dp; medium: 24dp; expanded: max-width centrale e gutter 32dp.
- Padding card standard: 16dp; card compatte 12dp; contenuto hero 20–24dp.
- Distanza tra sezioni: 12–16dp; tra gruppi maggiori: 24dp.
- Titolo/contenuto: 8–12dp.
- Target interattivo: minimo 48x48dp; non ridurre per far entrare più testo.
- Controllo compatto del selettore trend BIA: `control_compact_min_height` (44dp); non usare per input o CTA.
- Selettore intervallo del grafico misure corporee: `body_trend_range_control_height` (40dp), dimensione compatta specifica del controllo.
- Pallino stato piano alimentare: `food_plan_status_dot_size` (12dp).
- Grafico trend BIA: `bia_trend_chart_height` (240dp), dimensione del componente grafico specifico.
- Visualizzazione fronte/retro misure corporee: `body_measurement_illustration_height` (290dp); card del grafico misure: `body_measurement_chart_card_height` (260dp).
- Immagine hero dettaglio pasto: `meal_detail_hero_image_height` (200dp), mantenuta come composizione dedicata.
- Indicatore semantico nelle righe stato: `status_indicator_size` (20dp); chevron percorso nutrizionale: `nutrition_path_chevron_size` (28dp).
- Azioni help nelle card dinamiche del progresso: target `icon_button_size` (48dp), coerente con le altre azioni icona dell'app.
- Editor prezzi manuali dei provider in Settings: altezza massima `settings_pricing_editor_max_height` (320dp), ulteriormente limitata al 45% dell'altezza display disponibile.
- Preview di importazione BIA: altezza massima `bia_import_preview_max_height` (300dp), limitata anche al 46% dell'altezza display per mantenere visibili i controlli del dialog.
- Indicatore di caricamento analisi progressi: `progress_indicator_size` (32dp).
- Traccia progressi: `progress_track_corner_radius` (4dp) e `progress_track_thickness` (6dp); punto delle etichette corporee: `body_marker_dot_size` (10dp).
- Spessore barra onboarding: `onboarding_progress_track_thickness` (4dp); overlap della sheet Home sul hero: `dashboard_sheet_overlap` (-16dp).
- Spinner secondari: `compact_progress_indicator_size` (24dp) e `progress_compact_size` (26dp), secondo la densità prevista dal componente.
- Avatar profilo: `profile_avatar_size` (72dp); marchio onboarding: `brand_mark_size` (28dp).
- Colonna dell'indicatore per i dettagli progressi: `progress_pattern_indicator_width` (20dp), con il testo descrittivo allineato su una colonna flessibile.
- Riga pasto del piano: altezza minima `food_meal_row_min_height` (72dp), per mantenere allineati immagine, testo e azione cambio pasto.
- Segmento filtro della lista spesa: `shopping_filter_segment_height` (36dp), dimensione specifica del controllo compatto.
- Header standard: `screen_header_height` (56dp); header settimanali: `weekly_screen_header_height` (64dp), eccezione della composizione con navigazione settimana.
- Slot/icona header: `screen_header_icon_size` (40dp); controlli di navigazione settimanale: `week_navigation_icon_size` (36dp), mantenuti compatti dalla composizione dedicata.
- Icone decorative inline nei campi data/ora e nelle azioni secondarie: `inline_icon_size` (18dp).
- Dimensione intrinseca icone Material standard: `vector_icon_size` (24dp); bottom navigation: `bottom_nav_icon_size` (20dp); indicatori di direzione trend: `trend_direction_icon_size` (12dp).
- Illustrazioni vettoriali misure corporee: `body_measurement_vector_width` × `body_measurement_vector_height` (120 × 260dp), preservano le proporzioni dell'asset.
- Composizioni splash: `splash_corner_decoration_size`, `splash_bottom_decoration_size` e `splash_logo_size`; altezza dello scrim superiore onboarding: `onboarding_top_scrim_height`.
- Campi multilinea: `multiline_notes_field_height` per note; `cheat_clarification_field_height` e `nutrition_question_field_height` per le composizioni dedicate.
- Header sezione Settings con azione help: altezza `settings_section_header_height` (40dp), allineata al target del pulsante contestuale.
- Bottom navigation: token condiviso; label non deve essere inferiore alla scala Navigation senza QA font-scale. Il padding delle voci e la distanza icona-etichetta usano `bottom_nav_item_horizontal_padding`, `bottom_nav_item_vertical_padding` e `bottom_nav_icon_label_spacing`.

I layout più vecchi contengono alcune misure letterali: vanno sostituite con questi token durante le relative tranche, senza modificare proporzioni approvate senza confronto con il mock.

## Forme, bordi, elevazione e motion

- Scala forme di riferimento: 8, 12, 16, 20, 28dp e full/pill.
- Card standard: 16dp, bordo `divider` da 1dp, elevazione 0–1dp.
- Spessore bordo standard: `border_width_default` (1dp); focus dei campi: `border_width_focused` (2dp).
- Outline del marker corporeo: `body_marker_outline_width` (2dp); angoli inferiori squadrati delle sheet: `radius_square` (0dp).
- Campi: 12dp, bordo neutro 1dp, focus 2dp, altezza minima 48dp.
- Chip/segmenti: pill; selezione con `pill_selected_bg` e testo/indicatore distinguibile.
- Dialog standard: 16dp; superfici bianche con controlli/selezioni neutri. Calendar: giorno/anno selezionati su `surface_secondary` con testo `text_primary` per mantenere contrasto; TimePicker: superficie e quadrante `white` (elevation overlay disattivato), lancetta neutra. CTA positiva in `dialog_action_primary`; il verde brand non colora lo stato selected delle date/ore.
- Dialog di aiuto e Guida app: stesso contenitore Material bianco, raggio `radius_dialog`, superficie e CTA standard; non aggiungere barre verdi o bordi esterni decorativi. Card informative interne usano `radius_card` e bordo `divider`.
- Riepilogo calorie Home/Alimentazione: distinguere visivamente/semanticamente stima base BMR, stima con attività abituale TDEE, target del piano, menu stimato e consumo registrato; consumi assenti restano non disponibili, non zero. Totale giornaliero espone calorie e proteine; la spesa di un allenamento singolo non è stimata.
- Motion Android Views centralizzato in `UiMotion`: ingresso pagina (`motion_screen_enter_ms`), reveal (`motion_reveal_ms`, offset `motion_reveal_offset`), cambi di layout (`motion_layout_change_ms`), selezione (`motion_selection_ms`, `motion_selection_scale`), feedback checkbox (`motion_feedback_ms`, `motion_checkbox_scale`) e transizioni tab (`motion_tab_transition_ms`). Interpolatore Decelerate condiviso; rispetta Animator duration scale, risparmio energetico e feedback vocale accessibile. Nessun motion decorativo infinito.
- Limite QA motion: build/unit test/package AndroidTest verdi; resta da verificare visivamente l'interazione animata sui flussi Alimentazione, Lista spesa e Dettaglio pasto su device.

## Tema e adattività

- Tema prodotto approvato: chiaro. Evitare l'introduzione di una variante dark non approvata senza nuova specifica visuale.
- Dimensionare layout sullo spazio disponibile: compact, landscape, medium/expanded e split-screen.
- Tutte le superfici scrollabili devono rispettare system bars, cutout e tastiera.
- Provare almeno font scale 1.3 e dimensione expanded; evitare altezza fissa per testi dinamici.
- Grafici: usare palette serie documentata, legenda/label e selezione accessibile.
