# Sessione QA Splash/Onboarding + Background globale — Stato al 2026-09-13

## Contesto
L'utente ha fermato la sessione con un elenco dettagliato di problemi rilevati visivamente
sull'app in esecuzione: placeholder Unicode in Splash/Onboarding, sfondo verde scurissimo non
approvato, e background "crema" globale non corrispondente al mock. Richiesta: riaprire lo
STEP 6 QA VISUALE, correggere prima di procedere, NON iniziare Room/step 7.

## Cosa è stato fatto

### 1. Placeholder Unicode eliminati
- `❧` (logo Splash) — rimosso.
- `● ╱╲ ●` / `▂▄▆█` / `⚙ ⚡` (pseudo-illustrazione Onboarding) — rimossi.
- `● ○ ○` (page indicator testuale Onboarding) — rimosso, sostituito da un componente reale.
- Il carattere `‹` per il back button è stato **mantenuto** (non è un placeholder illustrativo,
  è il glifo di back già usato in modo consistente in tutte le altre schermate dell'app).

### 2. Asset reali utilizzati (copiati in `android/app/src/main/res/drawable-nodpi/`)
- `brand/myfitai_logo_full.png` → `img_logo_full.png` (Splash, logo centrale).
- `brand/myfitai_leaf_mark.png` → `img_leaf_mark.png` (Onboarding, icona inline nel wordmark).
  **Nota tecnica**: l'asset originale ha uno sfondo bianco squadrato baked-in (non trasparente).
  È stato rielaborato con un chroma-key (pixel quasi bianchi → alpha 0) per renderlo trasparente
  e farlo apparire correttamente sopra la foto scura, invece del riquadro grigio che si vedeva
  prima della correzione.
- `backgrounds/onboarding_mountain.jpg` → `img_onboarding_mountain.jpg` (sfondo full-bleed Onboarding).
- `decorative/leaf_corner.png` → `img_leaf_corner.png` (accento decorativo Splash, angolo).
- `decorative/green_blur.png` → `img_green_blur.png` (accento decorativo Splash, sfumatura).
- **Non utilizzati in questa sessione**: `backgrounds/green_header_abstract.png`,
  `decorative/leaf_decoration.png`, `decorative/green_wave_bottom.png` (nessun punto d'uso
  individuato con certezza in questa fase; disponibili per un secondo passaggio).

### 3. Nuovo colore pagina (campionato dal mock, non assunto)
Campionati pixel reali da `99-screen-references/02_dashboard_reference.png` e
`07_food_plan_reference.png` con uno script PowerShell/System.Drawing: valori RGB rilevati
nell'intervallo 243–253 su tutti e tre i canali (R≈G≈B, nessuna dominante calda/crema).
- `@color/bg_primary`: `#F7F6F1` (crema) → **`#FFFFFF`** (bianco reale).
- `@color/surface_primary` invariato (`#FFFFFF`, già corretto).
- `@color/surface_secondary` invariato (`#F3F5F1`, non risultava usato come background di root
  in nessuna Activity — verificato con grep su tutti i layout).
- Essendo `bg_primary` anche il valore di `android:windowBackground`,
  `android:statusBarColor` e `android:navigationBarColor` nel tema, la correzione si propaga
  automaticamente a **tutte** le schermate (Home, Alimentazione, ecc.), risolvendo la banda
  crema segnalata sotto il contenuto.

### 4. Splash ricostruito (`activity_splash.xml` + nessuna modifica a `SplashActivity.kt`)
`ConstraintLayout` reale: `ImageView` logo (`img_logo_full`), due `ImageView` decorativi
(`img_leaf_corner`, `img_green_blur`) in overlay leggero, `MaterialTextView` per tagline
("I tuoi dati. Il tuo percorso.") e per la riga funzionalità ("Misura · Comprendi · Migliora").
Nessun mock dedicato esiste per questa schermata (il crop `01_splash_onboarding_reference.png`
mostra solo l'Onboarding) — ricostruzione fatta in coerenza di stile con gli asset reali, non
per fedeltà pixel-perfect (nessuna immagine di riferimento con cui confrontarla).

### 5. Onboarding ricostruito (`activity_onboarding.xml` + `OnboardingActivity.kt`)
`FrameLayout` con `ImageView` full-bleed (`img_onboarding_mountain`), due `View` scrim
gradient (`bg_onboarding_scrim_top`/`bottom`) per leggibilità testo, `LinearLayout` con:
logo lockup (icona foglia + "MyFitness" bianco + "AI" verde), tagline, nuovo componente
riutilizzabile `OnboardingPageIndicatorView` (3 pallini reali, non testo Unicode),
`MaterialButton` "Inizia" (`style="Widget.MyFitAI.Button.Primary"`, nuovo stile condiviso),
`MaterialButton` outlined "Ho già un account" (`style="Widget.MyFitAI.Button.Outlined"`, nuovo).
**Nota**: il crop di riferimento mostrato all'utente NON contiene pallini indicatore visibili;
sono stati comunque implementati su indicazione esplicita e ripetuta dell'utente (sezioni 4 e 10
della richiesta), con dimensioni di esempio (8dp cerchio/8dp gap) in assenza di pallini misurabili
nel crop.

### 6. Nuovi stili condivisi (`themes.xml`)
- `Widget.MyFitAI.Button.Primary` (MaterialButton, verde pieno, testo bianco bold, no all-caps).
- `Widget.MyFitAI.Button.Outlined` (MaterialButton outlined, bordo/testo bianco).

## PROBLEMA APERTO — NON RISOLTO, DA INVESTIGARE ALLA RIPRESA

Durante la verifica visiva ripetuta di Onboarding sull'emulatore è stato osservato un
comportamento anomalo e riproducibile **ancora irrisolto**:

1. **Rendering duplicato**: in alcuni screenshot di Onboarding, il testo "Ho già un account"
   appare due volte (una volta come testo semplice/fantasma subito sotto il tagline in alto, non
   verificato) e un'icona a forma di foglia compare erroneamente dentro il pulsante "Inizia".
   Riprodotto sia con la versione `ConstraintLayout` sia dopo la riscrittura con
   `FrameLayout`/`LinearLayout` (quindi non è chiaramente imputabile alla sola struttura XML).
2. **Navigazione automatica non richiesta**: in più occasioni, senza alcun tap manuale da parte
   mia (solo `am start` + attesa + screenshot), l'app è stata trovata più avanti nel flusso
   (una volta su `FoodPlanActivity`, poi su `SettingsActivity`, un'altra volta su `HomeActivity`
   subito dopo Onboarding) — come se i pulsanti "Inizia"/altri elementi venissero premuti da soli.
   Verificato: nessun servizio di accessibilità attivo (`settings get secure
   enabled_accessibility_services` → `null`), nessun processo `monkey`/`uiautomator` residuo sul
   device. Causa non ancora identificata.
3. **Non è stato possibile ottenere uno screenshot definitivo e stabile di Onboarding** che
   confermi in modo pulito il fix del punto 1, perché il problema del punto 2 continua a far
   avanzare l'app oltre la schermata prima di poter catturare uno stato stabile.

**Ipotesi non ancora verificate per la prossima sessione:**
- Possibile artefatto della sessione dell'emulatore stesso (troppe ore di uptime in questa
  sessione lunghissima) piuttosto che un bug reale del codice — da testare con un riavvio pulito
  dell'emulatore (cold boot) prima di indagare oltre nel codice.
- Verificare se `OnboardingActivity`/`SplashActivity` vengono ricreate più volte (log
  `onCreate` con contatore) per escludere doppie schedulazioni del `Handler.postDelayed`.
- Controllare `adb logcat` per eventi di input (`Input: KeyEvent`/`MotionEvent`) sincronizzati
  con i momenti di navigazione imprevista, per capire se sono tap reali iniettati da qualche
  processo esterno all'app.

**Nessuna schermata è stata marcata come QA-completa in questa sessione.** Splash e Onboarding
restano `QA VISUAL PENDING` come richiesto esplicitamente dall'utente, finché il problema sopra
non è chiarito e uno screenshot stabile non conferma il fix.

## Cosa NON è stato ancora fatto (dal messaggio originale dell'utente)
- Smoke test sistematico di TUTTE le altre Activity dopo il cambio di `bg_primary` globale
  (il cambio è a rischio impatto ampio, come segnalato dall'utente stesso al punto 12).
- Verifica pixel-perfect finale di Home e Alimentazione col nuovo background bianco.
- Nessun commit ancora effettuato prima di questo salvataggio.

## Build status
`assembleDebug` e `clean assembleDebug`: **BUILD SUCCESSFUL** (ultima verifica dopo il ripristino
del delay Splash a 900ms). Manifest invariato rispetto a `origin/develop` (nessun flag
`android:exported` temporaneo rimasto).
