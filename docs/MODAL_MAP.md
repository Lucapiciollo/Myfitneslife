# Mappa delle modali e degli overlay di MyFitAI

Censimento ricavato dal codice sorgente (`android/app/src/main`) al 2026-10-06, sul working tree della branch `feature/android-compose-modernization-plan`. Riferimenti `File:riga` validi a quella data. Non descrive come dovrebbe essere l'app: elenca cosa esiste, come si apre e cosa è già coperto da test.

## 1. Riepilogo

| Famiglia | ID | Quantità | Note |
|---|---|---|---|
| Aiuto contestuale ("Ho capito") | H01-H23 | 23 | 22 `showHelpCard` + guida app |
| Conferme e avvisi | C01-C17 | 17 | include gate IA e conferme di importazione |
| Azioni distruttive | D01-D11 | 11 | catena di eliminazione profilo in 5 passi |
| Scelte (liste / single choice) | S01-S14 | 14 | S14 non raggiungibile |
| Form e viste personalizzate | F01-F08 | 8 | contenuto custom |
| Picker data / ora | P01-P13 | 6 data + 7 ora | Material Date/Time Picker |
| Menu e dropdown | U01-U14 | 1 `PopupMenu` + 13 dropdown | |
| Modale Activity | A01 | 1 | `NutritionAdviceActivity` |
| Overlay di sistema | X01-X07 | 7 | permessi, picker foto, camera, SAF, impostazioni Android |
| Feedback non modale | - | 53 `Toast`, 0 `Snackbar` | non bloccanti, fuori mappa |

Punti di costruzione dialog nel codice: **55** (`MaterialAlertDialogBuilder`), di cui **2 non raggiungibili** (S14 e `showStandardHelpDialog`). Nessun `BottomSheet`, nessun `Dialog` custom, nessun componente Compose di dialog.

## 2. Pattern comune (dove vive lo stile)

| Elemento | Sorgente |
|---|---|
| Tema dialog (`materialAlertDialogTheme`) | `ThemeOverlay.MyFitAI.AlertDialog` in `themes.xml` |
| Contenitore del contenuto custom | `BaseShellActivity.normalizeRuntimeDialogContent` → `ui/widgets/DialogContentFrame` |
| Layout stock del dialog | `res/layout/m3_alert_dialog.xml` (+ `_title`, `_actions`) |
| Righe liste / single / multi choice | `dialog_list_item_myfitai`, `dialog_single_choice_item_myfitai`, `dialog_multi_choice_item_myfitai` |
| Margini verticali del dialog | `dialog_vertical_inset` (24dp) in `MaterialAlertDialog.MyFitAI` |
| Pulsanti | `Widget.MyFitAI.Dialog.PositiveButton` / `.NeutralButton` / `.ButtonBar` |
| Picker | `ThemeOverlay_MyFitAI_MaterialCalendar`, `ThemeOverlay_MyFitAI_MaterialTimePicker` |
| Popup dropdown | `bg_dropdown_popup`, `setMyFitAiDropdownItems` (`MyFitAiDropdown.kt`) |
| Menu popup | `Widget.MyFitAI.PopupMenu`, `Text.MyFitAI.PopupMenuItem` |
| Layout dialog con vista propria | `dialog_bia_value_input`, `dialog_bia_import_preview`, `dialog_notification_settings`, `dialog_pricing_editor` |

Layout non usato da nessun codice: `dialog_standard_value_input.xml`.

## Stato centralizzazione famiglia Aiuto (H01-H23)

Tutti i punti H sono ora descritti da `HelpDialogSpec` e mostrati tramite `HelpDialog`, che usa un solo layout (`dialog_help.xml`), sezioni testuali senza card colorate e divider neutri (`item_help_section.xml`), stili e tema AlertDialog comuni. `BaseShellActivity.showHelpCard` è il facade per i messaggi; `showHelpDialog` accetta anche sezioni parametriche. `SettingsActivity.showAppGuide` usa lo stesso componente con `HelpSection`; l'aiuto BIA di Misure (H15) e l'aiuto BIA dei valori insufficienti passano dal facade comune. I wrapper locali `showHomeHelp` e `PhysicalEvolutionActivity.showHelp`, il formatter privato duplicato e il builder `showStandardHelpDialog` senza chiamanti sono stati rimossi.

Contratto di presentazione: `title` centrato nel contenuto (20sp, ruolo ScreenTitle), `message` (paragrafi separati da riga vuota; prefisso `• ` rimosso). Il formato `Titolo: valore` seguito da una riga di spiegazione diventa un blocco gerarchico: intestazione, valore in evidenza e spiegazione secondaria rientrata; gli altri paragrafi restano testo normale. Le `sections` usano titolo, sottotitolo secondario, corpo indentato e divider neutri. Superficie bianca, nessuna card/tinta verde nel contenuto; il verde resta solo sulla CTA primaria. `confirmLabel` resta parametrico (default "Ho capito"); callback Material invariata.

Verifica della centralizzazione: `HelpTextParserTest` (6 casi), `HelpDialogDeviceTest` (3 casi: solo testo, sezioni lunghe + scroll/barra fissa, etichetta personalizzata/testo senza heading) PASS; screenshot `help-message-only.png` e `help-sections-scrolled.png`; font 1.1/1.3. `DialogActionBarIntegrityDeviceTest` (aiuto Home, guida Settings) PASS; build, unit, AndroidTest package e lint PASS. H01-H23 hanno tutti un percorso al componente comune; le azioni che li attivano non sono state modificate.

### Specifica visiva attuale del contenuto HelpDialog

- Titolo centrato a 20sp con il ruolo `Text.MyFitAI.HelpDialogTitle`.
- Corpo start-aligned, tipografia e line-height centrali; le intestazioni in `Heading: testo` sono in grassetto `text_primary`; il testo che segue usa un rientro condiviso (`help_text_indent`).
- Sezioni senza card/pannelli colorati: titolo primario, sottotitolo e corpo secondari con rientro coerente; sezioni divise dal token `divider`.
- Contenitore bianco, scrollabile; CTA Material esistente fissa e invariata.
## 3. Aiuto contestuale (H) - titolo + testo, pulsante "Ho capito"

Tutti passano da `BaseShellActivity.showHelpCard` (`BaseShellActivity.kt:94`); il dialog è una `ScrollView` con testo formattato. Copertura test: `DialogThemeDeviceTest#homeCalorieHelp…` (H06), `DialogActionBarIntegrityDeviceTest` (H06, H23).

| ID | Titolo | Schermata e attivazione | Sorgente |
|---|---|---|---|
| H01 | Panoramica del corpo | Home, `bodyOverviewHelpButton` | `HomeActivity.kt:148` |
| H02 | Possibile calo teorico | Home, `weeklyExpectationHelpButton` | `HomeActivity.kt:151` |
| H03 | Riserva di recupero | Home, `recoveryHelpButton` | `HomeActivity.kt:159` |
| H04 | Prossimo pasto | Home, `nextMealHelpButton` | `HomeActivity.kt:162` |
| H05 | Prossimo allenamento | Home, `nextWorkoutHelpButton` | `HomeActivity.kt:165` |
| H06 | Come calcoliamo le calorie | Home, tap su `caloriesCard` o `caloriesHelpButton` | `HomeActivity.kt:142,143,690` |
| H07 | Azioni settimana | Alimentazione, `weekActionsHelpButton` | `FoodPlanActivity.kt:83` |
| H08 | Piano alimentare | Alimentazione, `foodPlanHelpButton` | `FoodPlanActivity.kt:89` |
| H09 | Pasti del giorno | Alimentazione, `dayMealsHelpButton` | `FoodPlanActivity.kt:95` |
| H10 | Come leggere le calorie | Alimentazione, `dailyTotalHelpButton` | `FoodPlanActivity.kt:72,457` |
| H11 | Data e ora | BIA, `biaDateHelpButton` | `BiaActivity.kt:243` |
| H12 | Condizioni della misura | BIA, `biaConditionsHelpButton` | `BiaActivity.kt:249` |
| H13 | Risultati | BIA, `biaResultsHelpButton` | `BiaActivity.kt:255` |
| H14 | Misure corporee | Rilevazioni, aiuto della card misure | `MeasurementsActivity.kt:69` |
| H15 | Dati BIA e dati insufficienti | Rilevazioni, aiuto della card BIA (builder diretto, solo "Chiudi") | `MeasurementsActivity.kt:49-50` |
| H16 | Altri indicatori | Progresso, `otherIndicatorsHelpButton` | `PhysicalEvolutionActivity.kt:69` |
| H17 | Progresso | Progresso, `progressMetricHelpButton` | `PhysicalEvolutionActivity.kt:77` |
| H18 | Analisi progressi IA | Progresso, aiuto sezione analisi (pulsante non verificato) | `PhysicalEvolutionActivity.kt:139` |
| H19 | Review settimanale | Progresso, aiuto sezione review (pulsante non verificato) | `PhysicalEvolutionActivity.kt:218` |
| H20 | Generale | Impostazioni, `generalSectionHelpButton` | `SettingsActivity.kt:502` |
| H21 | Gestione dati | Impostazioni, `dataSectionHelpButton` | `SettingsActivity.kt:508` |
| H22 | Intelligenza artificiale | Impostazioni, `aiSectionHelpButton` | `SettingsActivity.kt:514` |
| H23 | Come funziona MyFitAI | Impostazioni, riga `rowAppGuide`; vista a card per sezione | `SettingsActivity.kt:196,791` |

## 4. Conferme e avvisi (C)

| ID | Titolo | Attivazione | Azioni | Sorgente | Test |
|---|---|---|---|---|---|
| C01 | Uscire da MyFitAI? | Back su una tab radice | Annulla / Esci | `TabHostActivity.kt:85`, `BaseShellActivity.kt:526` | `BottomNavigationUiTest` |
| C02 | Confermare richiesta IA? | Prima di ogni azione IA (11 chiamanti, vedi sotto) | Annulla / Conferma | `BaseShellActivity.kt:457` | `CheatEntryE2ETest` |
| C03 | Nessun provider IA configurato | Azione IA senza provider (gate) | Annulla / Apri impostazioni | `AiProviderAccess.kt:19` | `CheatEntryE2ETest` (ramo condizionale) |
| C04 | Confermare modello? | Impostazioni IA, cambio modello Gemini/OpenAI | Annulla / Conferma | `AiModelSelectionBinder.kt:53` | - |
| C05 | Salvare la lista della spesa? | Lista spesa alla prima apertura di una versione | Annulla / Conferma | `ShoppingListActivity.kt:157` | `DialogThemeDeviceTest` (dipende dai dati) |
| C06 | Aggiornare il piano alimentare? | Dopo il salvataggio di un profilo esistente, se cambiano dati nutrizionali e c'è un piano in corso | Non ora / Rigenera alimentazione | `ProfileEditActivity.kt:225` (da `:210`) | - |
| C07 | Costi Gemini | Impostazioni IA, riga costi | Modifica prezzi / Ripristina listino | `GeminiCostSettingsBinder.kt:62` | - |
| C08 | Costi OpenAI | Impostazioni IA, riga costi | Modifica prezzi / Ripristina listino | `OpenAiCostSettingsBinder.kt:61` | - |
| C09 | Analisi proporzioni | Risultato dell'analisi IA delle misure | Chiudi | `BodyMeasuresActivity.kt:319` | - |
| C10 | Privacy e dati | Impostazioni, `rowPrivacy` | OK | `SettingsActivity.kt:207` | `DialogThemeDeviceTest`, `SettingsSecurityUiTest` |
| C11 | Importa backup completo? | Esporta/importa, `importBackupRow` + scelta file | Annulla / Importa | `ExportActivity.kt:43,64` | - |
| C12 | Importa storico BIA | Esporta/importa, `importBiaHistoryRow` + scelta file | Annulla / Importa | `ExportActivity.kt:41,144` | - |
| C13 | File BIA non valido | Errore sul file scelto in C12 | Chiudi | `ExportActivity.kt:167` | - |
| C14 | Carica profilo dal backup? | Impostazioni, `rowImportProfileBackup` + scelta file | Annulla / Importa e riavvia | `SettingsActivity.kt:215,227` | - |
| C15 | Ripristina profilo dal backup? | Configurazione iniziale, `importProfileBackupButton` | Annulla / Importa e riavvia | `OnboardingWizardActivity.kt:126,245` | - |
| C16 | Importazione non riuscita (Impostazioni) | Errore su C14 | Chiudi | `SettingsActivity.kt:236` | - |
| C17 | Importazione non riuscita (Configurazione) | Errore su C15 | Chiudi | `OnboardingWizardActivity.kt:256` | - |

Chiamanti di C02 (`confirmAiRequest`): lettura IA della foto BIA (`BiaActivity.kt:424`), analisi Progress Coach BIA (`:792`), proporzioni corporee (`BodyMeasuresActivity.kt:307`), valutazione sgarro (`CheatEntryActivity.kt:312`), conferma sgarro (`:319`), generazione piano (`FoodPlanActivity.kt:109`), alternative pasto (`MealAlternativeActivity.kt:56`), consiglio nutrizionale (`NutritionAdviceActivity.kt:55`), applicazione suggerimento (`:170`), analisi progressi (`PhysicalEvolutionActivity.kt:279`), review settimanale (`WeeklyReviewActivity.kt:41`).

Chiamanti del gate C03: `gateAiClick` (BIA 3, Misure 1, Sgarro 4, Alimentazione 1, Consiglio 2, Progresso 1, Review 1), apertura Alimentazione (`BaseShellActivity.kt:88`), prossimi pasti Home (`HomeActivity.kt:576`), avvio percorso nutrizionale Impostazioni (`SettingsActivity.kt:95`).

## 5. Azioni distruttive (D)

| ID | Titolo | Attivazione | Azioni | Sorgente | Test |
|---|---|---|---|---|---|
| D01 | Elimina misurazione | BIA, pressione prolungata su una rilevazione dello storico | Annulla / Elimina | `BiaActivity.kt:723,1055` | - |
| D02 | Elimina misurazione? | Misure corporee, pressione prolungata su una riga | Annulla / Elimina | `BodyMeasuresActivity.kt:434,506` | - |
| D03 | Elimina dallo storico? | Allenamenti, pressione prolungata su una sessione | Annulla / Elimina | `WorkoutsActivity.kt:188,195` | - |
| D04 | Azzera stato lista? | Lista spesa, `resetButton` | Annulla / Azzera | `ShoppingListActivity.kt:72,227` | - |
| D05 | Eliminare … ? / Eliminare tutti i dati di attività? | Da F08, scelta di una categoria o di tutti i dati | Annulla / Elimina | `SettingsActivity.kt:884,940` | - |

Catena di eliminazione profilo (Impostazioni, `rowDeleteProfile`, `SettingsActivity.kt:218`):

| ID | Passo | Condizione | Azioni | Sorgente | Test |
|---|---|---|---|---|---|
| D06 | Seleziona il profilo da eliminare | solo se i profili sono più di uno | Annulla | `SettingsActivity.kt:269` | - |
| D07 | Vuoi salvare i dati prima di procedere alla cancellazione del profilo? | sempre | Annulla / Senza backup / Salva backup | `SettingsActivity.kt:286` | `SettingsSecurityUiTest` |
| D08 | (selettore file) | "Salva backup" apre `CreateDocument` (X05) | - | `SettingsActivity.kt:55,58` | - |
| D09 | Backup salvato | dopo il salvataggio | Annulla / Elimina profilo | `SettingsActivity.kt:308` | - |
| D10 | Conferma eliminazione definitiva | da D09 o da "Senza backup" | Annulla / Elimina definitivamente | `SettingsActivity.kt:326` | - |
| D11 | Eliminazione profilo | errore (nessun profilo, backup non salvato, eliminazione fallita) | Chiudi | `SettingsActivity.kt:258,315,344,355` | - |

I test non confermano mai D01-D11, per regola di QA non distruttivo.

## 6. Scelte da elenco (S)

| ID | Titolo | Attivazione | Tipo | Sorgente | Test |
|---|---|---|---|---|---|
| S01 | Importa BIA da foto | BIA, `importPhotoButton`; apre X03/X04 | items | `BiaActivity.kt:389` | - |
| S02 | Foto etichetta nutrizionale | Sgarro (Dettagliato), `addLabelPhotoButton`; apre X03/X04 | items | `CheatEntryActivity.kt:197` | `CheatEntryE2ETest` (instabile, vedi §11) |
| S03 | Foto profilo | Profilo, tap sull'avatar; apre X03/X04 | items | `ProfileActivity.kt:225` | `ProfileSummaryVisualDeviceTest` |
| S04 | Metrica BIA | BIA andamento, `metricButton` | single | `BiaTrendBinder.kt:83,120` | - |
| S05 | Prossimi pasti | Home, `todayMenuButton` | items | `HomeActivity.kt:168,573` | - |
| S06 | Allenamento previsto oggi | Home, check-in attività (`activityCheckInWorkoutButton`, `…EditButton`) | items | `HomeActivity.kt:145,146,835` | `HomeActivityCheckInDeviceTest` |
| S07 | Pasti al giorno | Impostazioni piano, `mealCountButton` | single | `NutritionPlanSettingsActivity.kt:25,45` | - |
| S08 | Frequenza piano | Impostazioni piano, `scheduleButton`; poi picker ora (P12) | single | `NutritionPlanSettingsActivity.kt:26,50` | - |
| S09 | Frequenza <funzione> | Impostazioni IA, riga di automazione (Progress Coach BIA, Analisi progressi, Proporzioni corporee, Review settimanale) | single | `SettingsActivity.kt:447-450` | - |
| S10 | Giorno di generazione | Catena S11, per frequenze settimanali | single + OK | `SettingsActivity.kt:640` | - |
| S11 | Frequenza generazione pasti | Impostazioni, riga piano (`:673`); poi S10 e/o picker ora (P13) | single + OK | `SettingsActivity.kt:655` | - |
| S12 | Frequenza analisi progressi | Impostazioni, riga analisi progressi | items | `SettingsActivity.kt:718,723` | - |
| S13 | (nome della voce) | Lista spesa, tap su una riga: stato della voce | single | `ShoppingListActivity.kt:196,215` | - |
| S14 | Pasti al giorno | **nessun chiamante**: `showMealCountDialog` non è invocata | single | `SettingsActivity.kt:743,748` | - |

## 7. Form e viste personalizzate (F)

| ID | Titolo | Attivazione | Contenuto | Azioni | Layout / sorgente | Test |
|---|---|---|---|---|---|---|
| F01 | (nome del valore) | BIA, tap su una riga del form | un campo numerico | Annulla / Svuota / Conferma | `dialog_bia_value_input` · `BiaActivity.kt:289,302` | - |
| F02 | Controlla importazione BIA | BIA, dopo la lettura IA della foto (`:439`) | Metadati e 16 campi sulla superficie bianca standard; scroll unico | Annulla / Usa valori | `dialog_bia_import_preview` · `BiaActivity.kt:539` | `BiaImportDialogGeometryDeviceTest`, `BiaDeviceVisualTest` |
| F03 | Analisi nutrizionale e sportiva BIA | BIA, `reviewSavedBiaAnalysisButton` oppure al termine dell'analisi IA (conferma C02) | sezioni di testo in scroll | Chiudi | programmatico · `BiaActivity.kt:903` | - |
| F04 | Prezzi Gemini manuali | Da C07, "Modifica prezzi" | 3 campi decimali + messaggio | Annulla / Salva | `dialog_pricing_editor` · `GeminiCostSettingsBinder.kt:90` | - |
| F05 | Prezzi OpenAI manuali | Da C08, "Modifica prezzi" | 3 campi decimali + messaggio | Annulla / Salva | `dialog_pricing_editor` · `OpenAiCostSettingsBinder.kt:89` | - |
| F06 | Notifiche | Profilo, `rowNotifications` | 3 switch, dropdown anticipo, link impostazioni Android | Annulla / Salva | `dialog_notification_settings` · `ProfileActivity.kt:64,100` | `InputModalStyleAuditDeviceTest` |
| F07 | Frequenza rilevazione BIA | Impostazioni, `rowBiaFrequency` | radio settimanale / mensile / bimestrale | Annulla / Salva | `SettingsActivity.kt:201,372` | - |
| F08 | Scegli cosa eliminare | Impostazioni, `rowDeleteRecordedData` | righe descrittive per categoria; apre D05 | Chiudi | programmatico · `SettingsActivity.kt:849,931` | `SettingsSecurityUiTest` |

## 8. Picker data e ora (P) - Material Date/Time Picker

| ID | Tipo | Schermata e campo | Sorgente |
|---|---|---|---|
| P01 | data | BIA, "Data rilevazione" | `BiaActivity.kt:211-212` |
| P02 | ora | BIA, "Ora" | `BiaActivity.kt:225-226` |
| P03 | data | Sgarro, riga data | `CheatEntryActivity.kt:279-280` |
| P04 | ora | Sgarro, riga ora | `CheatEntryActivity.kt:293-294` |
| P05 | data | Nuova misura corporea, data | `NewBodyMeasurementActivity.kt:129,141` |
| P06 | data | Nuovo allenamento, "Data" | `NewWorkoutActivity.kt:76-77` |
| P07 | ora | Nuovo allenamento, "Ora" | `NewWorkoutActivity.kt:89-90` |
| P08 | data | Configurazione iniziale, data di nascita | `OnboardingWizardActivity.kt:227,517` |
| P09 | ora | Configurazione iniziale, sveglia / sonno / ora piano (3 campi) | `OnboardingWizardActivity.kt:309,313,340,526` |
| P10 | data | Modifica profilo, data di nascita | `ProfileEditActivity.kt:108` |
| P11 | ora | Modifica profilo, sveglia / sonno | `ProfileEditActivity.kt:120,121,125` |
| P12 | ora | Impostazioni piano, ora di generazione | `NutritionPlanSettingsActivity.kt:52-54` |
| P13 | ora | Impostazioni, ora di generazione (fine catena S11) | `SettingsActivity.kt:615-617,665` |

Copertura test: tema calendario/orologio verificato in passato (`DEVELOPMENT_PLAN.md`); nessun test strumentato apre i picker per campo.

## 9. Menu e dropdown (U)

| ID | Componente | Schermata e campo | Sorgente |
|---|---|---|---|
| U01 | `PopupMenu` | Profilo: tap sul nome, elenco profili + "+ Aggiungi profilo" | `ProfileActivity.kt:129,198` |
| U02 | dropdown programmatico | Header della sola Home, selettore profilo (`profileSwitcher`) | `BaseShellActivity.kt:308,355` |
| U03 | dropdown | Home, `bodyTrendMetricSelector` (metrica del grafico) | `activity_home.xml`, `HomeActivity.kt:432` |
| U04 | dropdown | Misure corporee, `trendMetricInput` | `activity_body_measures.xml`, `BodyMeasuresActivity.kt:137` |
| U05 | dropdown | Sgarro, `quantityInput` (Dettagliato) | `CheatEntryActivity.kt:92` |
| U06 | dropdown | Preferenze alimentari, `dietStyleInput` | `DietaryPreferencesActivity.kt:55` |
| U07 | dropdown | Nuovo allenamento, `typeInput` | `NewWorkoutActivity.kt:67` |
| U08 | dropdown | Modifica profilo, `sexInput` | `ProfileEditActivity.kt:101` |
| U09 | dropdown | Modifica profilo, `goalInput` | `ProfileEditActivity.kt:102` |
| U10 | dropdown | Modifica profilo, `activityInput` | `ProfileEditActivity.kt:103` |
| U11 | dropdown | Impostazioni IA, `geminiModelInput` (poi C04) | `AiModelSelectionBinder.kt:43` |
| U12 | dropdown | Impostazioni IA, `openAiModelInput` (poi C04) | `AiModelSelectionBinder.kt:44` |
| U13 | dropdown | Dialog Notifiche (F06), `mealLeadInput` | `ProfileActivity.kt:85` |
| U14 | dropdown | Configurazione iniziale, `onboardingDropdownInput` (più passi) | `OnboardingWizardActivity.kt:602` |

Copertura: `ProfileDropdownDeviceTest` (U01, U08-U10), `InputModalStyleAuditDeviceTest` (geometria di tutti i campi dropdown).

## 10. Modale Activity e overlay di sistema

| ID | Elemento | Attivazione | Sorgente |
|---|---|---|---|
| A01 | `NutritionAdviceActivity` (Activity flottante con scrim, tema `Theme.MyFitAI.NutritionAdviceModal`): domanda, invio, caricamento, risposta, errore | Pulsante `nutritionAdviceButton` dell'header (`BaseShellActivity.kt:339-341`); tap su notifica del job IA (`AiJobNotifier.kt:61`) | `NutritionAdviceActivity.kt`, `activity_nutrition_advice.xml` |
| X01 | Permesso `POST_NOTIFICATIONS` (una sola volta) | Home, `requestNotificationPermissionOnce` | `HomeActivity.kt:77,390-396` |
| X02 | Impostazioni notifiche di Android | F06, "Apri impostazioni notifiche Android" | `ProfileActivity.kt:94` |
| X03 | Photo Picker (`PickVisualMedia`) | S01, S02, S03 → "Scegli dalla galleria" | `BiaActivity.kt:101`, `CheatEntryActivity.kt:64`, `ProfileActivity.kt:40` |
| X04 | Fotocamera (`TakePicture`) | S01, S02, S03 → "Scatta foto" | `BiaActivity.kt:105`, `CheatEntryActivity.kt:68`, `ProfileActivity.kt:44` |
| X05 | Salva file (`CreateDocument`) | Esporta/importa "Salva backup"; D08 | `ExportActivity.kt:28`, `SettingsActivity.kt:55` |
| X06 | Apri file (`OpenDocument`) | C11, C14, C15 | `ExportActivity.kt:31`, `SettingsActivity.kt:51`, `OnboardingWizardActivity.kt:85` |
| X07 | Scegli file (`GetContent`) | C12, storico BIA JSON | `ExportActivity.kt:25` |

Non sono modali: `MealAlternativeActivity` (aperta con `StartActivityForResult` da `FoodPlanActivity.kt:50`) e `NotificationsActivity` (usa uno sfondo con scrim ma è una Activity normale).

## 11. Anomalie e lacune rilevate

1. **Codice non raggiungibile**: `showMealCountDialog` (S14, `SettingsActivity.kt:743`) e `showStandardHelpDialog` (`SettingsActivity.kt:798`) non hanno chiamanti. "Pasti al giorno" esiste raggiungibile solo in Impostazioni piano (S07).
2. **Layout inutilizzato**: `dialog_standard_value_input.xml`.
3. **Duplicazioni**: `showHomeHelp` (Home) e `showHelp` (Progresso) sono solo inoltri a `showHelpCard`; `chooseFrequency` e `chooseTime` esistono in due Activity con significato diverso.
4. **Copertura test per titolo**: di 43 titoli distinti con testo fisso, **33 non compaiono in nessun test strumentato**. Tra questi tutte le conferme distruttive (per scelta), le importazioni (C11-C17), i prezzi manuali (F04-F05), la frequenza BIA (F07) e quasi tutte le scelte S04-S13. Il conteggio cerca la stringa esatta nei test: titoli verificati con `textContains` possono risultare "non coperti" (es. C01 è verificato da `BottomNavigationUiTest`).
5. **Test già rossi sulla baseline** che toccano modali: `DialogThemeDeviceTest#shoppingListFirstOpenDialog…` (richiede il piano del 31/08/2026 del seed distruttivo), `CheatEntryE2ETest#labelPhotoDialog…` (S02, causa non determinata), `SettingsSecurityUiTest#threeActionDialog…` a font 1.3.
6. **Aiuti H18-H19**: il pulsante che li attiva non è stato individuato con certezza (chiamanti `showHelp` alle righe 139 e 218).

## 12. Metodo e limiti

- Estrazione automatica dei punti di costruzione (`MaterialAlertDialogBuilder`, `MaterialDatePicker`/`TimePicker`, `PopupMenu`, `registerForActivityResult`, permessi) e risalita ai chiamanti con ricerca testuale; casi ambigui letti a mano.
- Non è un walkthrough runtime: ogni voce è stata individuata nel sorgente, non aperta una per una sul dispositivo. Le aperture verificate sul Samsung sono quelle citate nella colonna Test.
- Le attivazioni via `setOnClickListener` indicano l'id della vista; i gesti (tap, pressione prolungata) sono letti dal listener corrispondente.
- Rigenerare la mappa dopo modifiche ai flussi: ogni nuovo `MaterialAlertDialogBuilder` o picker va aggiunto a questo elenco.
