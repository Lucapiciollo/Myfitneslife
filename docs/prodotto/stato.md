# Stato iniziativa — rifacimento MyFitAI

Aggiornato: 12 novembre 2026 · Repo: `C:\Users\LucaPiciollo\Luca\Myfitneslife` · Branch: `develop`

## Decisioni
- Piano unificato: `docs/PIANO_UNIFICATO_2026-11-12.md` (recepisce `docs/PIANO_RIFACIMENTO_2026-11-12.md` e `docs/UX_NAVIGAZIONE_IA_E_PROFILO_2026-11-12.md`).
- Il tab IA viene rimosso: quattro tab (Home, Cibo, Progresso, Altro), rotellina per le impostazioni IA globali.
- Profilo Mamma corretto il 12/11/2026 a 10/05/1964 (62 anni); verificato in Home: BMR 1354, TDEE 1625, target 1381 kcal. Generazione del piano non ancora avviata (serve consenso).
- `OpenCode` non è un provider runtime documentato: fuori scope.
- Il telefono di prova resta acceso con USB collegato (`svc power stayon usb`).
- Decisioni D1–D13 del piano unificato in attesa dell'utente.

## Audit (tutti conclusi, sola lettura)
| Agente | Incarico | sessionID | Esito |
|---|---|---|---|
| sviluppo/architetto-software | Audit contratto AI/BIA | `ses_e3148230bffe0syWWGrg7oIYLM` | Concluso |
| prodotto/analista-funzionale | Preferenze e modifica dati | `ses_e314822eeffeARsnzc2nEjqMG8` | Concluso |
| design/ux-reviewer | Review gestione intolleranze | `ses_e314822dbffe3q3nNKVIOzuVTN` | Concluso |
| sviluppo/architetto-software | Provider, peso, diagnostica dieta | `ses_e3131c332ffeuPToD09OP2dJhr` | Concluso |
| prodotto/analista-funzionale | Piano in due step | `ses_e3131c321ffeccxKzkBwVBsnJn` | Concluso |
| design/ux-reviewer | Rotellina e accessibilità | `ses_e3131c2deffetXMi00TXlsJDEv` | Concluso |
| sviluppo/architetto-software | Infrastruttura contratto IA | `ses_e30cb2b51ffe60TsWFcoLLnazA` | Concluso |
| sviluppo/architetto-software | Contratti agenti nutrizione | `ses_e30cb0a99ffemVvqncJY9z67nB` | Concluso |
| sviluppo/architetto-software | Contratti agenti corpo/progressi | `ses_e30caee3affe8Zi5SJRfARK04m` | Concluso |
| design/ux-designer | Navigazione senza tab IA | `ses_e30cad13fffeKYrjYoVgKwSlb6` | Concluso |

## Step 1 — tranche in worktree (non committate)
Worktree in `C:\Users\LucaPiciollo\.local\share\opencode\worktree\bc2023\`.
| Agente | Tranche | sessionID | Stato |
|---|---|---|---|
| sviluppo/senior-developer | Età e messaggi (`s1-eta-messaggi`) | `ses_e310b2b47ffexNkzhwLT3UiVEe` | Codice scritto, gate non confermato |
| sviluppo/senior-developer | Rotellina IA e profilo (`s1-ia-gear-profilo`) | `ses_e310b096effez1L40YIDzbeaLD` | Da ricalibrare (tab IA rimosso) |
| sviluppo/senior-developer | Peso trasparente (`s1-peso-trasparente`) | `ses_e310aef8dffevckf4ThuMNPCq5` | Codice scritto, gate non confermato |
| sviluppo/senior-developer | Preferenze con chip (`s1-preferenze-chip`) | `ses_e310ad2fdffezd2ieJSfGus6bX` | Codice scritto, gate non confermato |

## Implementazione
Nessuna tranche integrata su `develop`. Il Samsung ha ancora l'APK precedente.

## Aggiornamento 12/11/2026 (branch committati, in revisione)
| Branch | Commit | sessionID | Stato |
|---|---|---|---|
| fix/a1-retry-handlers | db7c8ba | ses_e30b09ac5ffed0dW2bZor6qEMs | Revisione: approvabile con modifiche; seconda passata in corso (test worker, messaggi senza testo modello, tetto retry) |
| fix/a3-food-constraints | a2d73f5 | ses_e30b0739bffeORt1LORz5Lfine | Revisione: con modifiche; seconda passata in corso (vincoli non riconosciuti, etichetta vegetale, testo legacy) |
| fix/a2-bia-review | in corso | ses_e3085b092ffeBdcp7epaNAOJSQ | B1/B2/B3 in corso |
| fix/s1-eta-messaggi | 1e08341 | ses_e310b2b47ffexNkzhwLT3UiVEe | Rilievi minori in correzione |
| fix/s1-peso-trasparente | fb71f3b | ses_e310aef8dffevckf4ThuMNPCq5 | Scelta unica del peso in correzione |
| feat/s1-preferenze-chip | f76cf3d | ses_e310ad2fdffezd2ieJSfGus6bX | Ordine categorie, accessibilita, riga piano in correzione |
| feat/s1-ia-gear-profilo | df350ca | ses_e310b096effez1L40YIDzbeaLD | Da ricalibrare dopo Fase B |
Revisori (conclusi): ses_e3085f153ffeih545lDQnFYzYL (A1/A3), ses_e3085dc56ffeBJzu1wCi5rAHfn (Step 1).
Ordine merge: A1, eta, peso, chip, A3, a2-bia-review, gear dopo Fase B. Nessun merge su develop ancora.

## Aggiornamento 12/11/2026 pomeriggio
- Commit: A1 481c11a (336 test dichiarati), A3 aac2f4e (346 dichiarati), a2-bia-review 9cb1eac (325 dichiarati), peso c0f181d (315 test dichiarati, gate completo rieseguito da me in corso).
- Branch di integrazione integrazione/step1 (worktree integrazione-step1): merge puliti A1, a2, a3, peso; conflitto risolto in BiaAnalysisContract (codice errore senza testo modello); aggiunti codici in KNOWN_CODES. Da aggiungere: eta, chip. Poi gate completo sull'integrazione.
- Gate sequenziale da me: log Temp\opencode\gate-seq4.log (peso OK 24m48s; eta, chip in corso).

## Aggiornamento 12/11/2026 sera
- develop = d2ffffc (merge di A1, a2-bia-review, A3, peso, eta, chip, gear). Gate integrazione: BUILD SUCCESSFUL, 452 test unitari, 0 falliti (verificato da orchestratore).
- APK debug installato in-place sul Samsung (adb install -r, nessun reset). Verificato a schermo: rotellina nel Profilo, riga "Alimenti e preferenze", schermata preferenze con Allergie/Intolleranze/Esclusi in testa (vuote per Mamma).
- Piano settimanale di Mamma generato con Gemini (consenso utente): giorno 12/11 ~1381 kcal, ~160 g proteine = target. Altri giorni, pesi e vincoli non verificati.
- Non eseguiti: androidTest (solo compilati), TalkBack, font 1.3, OpenAI dal vivo.
- Prossimo: Fase B (4 tab, AiSettingsActivity, rotellina globale), poi Fase C.

## Avvio Fase B e avvisi preferenze (12/11/2026)
| Agente | Incarico | sessionID | Branch / worktree |
|---|---|---|---|
| sviluppo/senior-developer | Fase B: 4 tab, AiSettingsActivity, AiAutomationsActivity, rotellina globale | ses_e2fefdac4ffetTeioIWc0FJR7v | feat/fase-b-navigazione-ia (fase-b-navigazione-ia) |
| sviluppo/senior-developer | Avvisi vincoli nelle preferenze (testo legacy, NAME_ONLY, LIMITS_NOTICE) | ses_e2fefb355ffebvDf1wYBi2Ofxo | feat/pref-avvisi-vincoli (pref-avvisi-vincoli) |
Il gate completo lo riesegue l'orchestratore prima del merge.

- feat/pref-avvisi-vincoli 2c5f969 (463 test dichiarati, BUILD SUCCESSFUL 8m22s, su base d2ffffc): portato su develop con fast-forward. Da verificare su device: riquadro testo legacy, dialog conferma, rotazione, avvisi per voce.

## Fase B integrata (12/11/2026 pomeriggio)
- develop = ce226e1 (merge fase-b + avvisi preferenze). Gate sull'unione: BUILD SUCCESSFUL in 7m 5s, 471 test unitari, 0 falliti (verificato da orchestratore). APK installato in-place sul Samsung.
- Verificato a schermo: 4 tab, rotellina 'Impostazioni IA' in Home e Altro, AiSettingsActivity (Gemini configurato, OpenAI non configurato, screenshot piccolo = FLAG_SECURE verosimile), Altro con card IA e Automazioni IA, AiAutomationsActivity per profilo. Non verificati: Cibo/Progresso (rotellina), TalkBack, landscape/font 1.3, androidTest.

## Fase C1 avviata (12/11/2026)
| sviluppo/senior-developer | C1: AiFailure, registro contratti, finishReason/refusal, maxOutputTokens | ses_e2fcffa44ffe47bWeTDClp5a7e | feat/fase-c1-nucleo-errori (fase-c1-nucleo-errori) |
Seguiranno C2 (validatore schema v2 + ValidatedOutput) e C3 (fixture golden + parita) in sequenza sullo stesso perimetro.

## Fase C2 avviata in parallelo a C1 (12/11/2026)
| sviluppo/senior-developer | C2: validatore schema v2 + ValidatedOutput (solo CanonicalJsonSchemaValidator e file nuovi; non tocca AiExecutionService) | ses_e2fcad54effeUmkqErVYl9JkhB | feat/fase-c2-validatore-schema (fase-c2-validatore-schema) |
C3 (fixture golden + parita Gemini/OpenAI) resta in coda: dipende da AiFailure di C1. Integrazione ValidatedOutput in AiExecutionService dopo merge di C1 e C2.

- C2 4eee295 (500 test dichiarati, BUILD SUCCESSFUL 16m16s su base ce226e1) portato su develop con fast-forward. Integrazione ValidatedOutput in AiExecutionService: dopo C1.

- C2 4eee295 (500 test dichiarati, BUILD SUCCESSFUL 16m16s su base ce226e1) portato su develop con fast-forward. Integrazione ValidatedOutput in AiExecutionService: dopo C1.

## C1+C2 integrati (12/11/2026)
- develop = 979516e. Gate unione: BUILD SUCCESSFUL 5m49s, 549 test unitari, 0 falliti (verificato da orchestratore). APK installato in-place sul Samsung.
- C1 89ecbb6 (AiFailure, AiContractRegistry, finishReason/refusal, maxOutputTokens Gemini). RISCHIO APERTO: tetti token Gemini (350-900) possono troncare con thinking; da verificare con una chiamata reale (serve consenso utente).
- Prossimi: C3 (fixture golden + parita), integrazione ValidatedOutput in AiExecutionService, poi Fase D (NutritionAdvice PoC).

## Prova Gemini dopo C1 (12/11/2026, consenso utente)
- Consiglio alimentare (NutritionAdvice, 'Cosa cena leggera stasera ?') sull'APK con C1: risposta completa in ~8 s, 5 opzioni con kcal/macro, nessun troncamento (gemini-3.5-flash-lite). Il tetto token di NutritionAdvice e quindi sufficiente. Altri contratti con tetto (BIA coach, review, ecc.) non provati.
- C3 avviato: ses_e2fa64f37ffeFxAc1Qz2KE4lHO (feat/fase-c3-fixture-parita).

## Critica agente BIA e misure (12/11/2026, sola lettura, ses_e2f9c061dffe2uZ1pVhOj1hbPL, non verificata da orchestratore)
Verdetti: BIA Coach = da rifare (contesto di ingresso e validazione), prompt adeguato con riserve; Proporzioni = adeguato con riserve; Analisi progressi = adeguato con riserve; Review settimanale = adeguato con riserve + bug.
Alta: (1) BIA Coach riceve eta come epoch-day e date in formati diversi (BiaAnalysisAiJobHandler.kt:90,97-99); (2) rumore BIA non quantificato, qualita/condizioni non nel prompt (BiaMeasurementQualityEngine usato solo dalla UI); (3) soglie trend +-0,3 sotto il rumore, PA senza n/intervallo (LocalCalculationEngine.kt:207); (4) nessuna gestione profili vulnerabili/perdita rapida/eta; (5) ruolo BIA Coach eccede il confine nutrizionale (allenamento, creatina); (6) scenari di grasso calcolati dal modello, nessuna validazione numerica; (7) possibile bug: WeeklyReviewService usa il contesto del profilo ATTIVO per un job di altro profilo (WeeklyReviewService.kt:132).
Media: piano trattato come consumo; finestre disomogenee; vita/altezza non inviato; validatore UI proporzioni contraddice il service (BodyMeasuresActivity.kt:427,436); nota di sicurezza sempre obbligatoria; tre azioni sempre; tono; guard cause-effetto deboli, regola ritenzione != grasso assente nei prompt; delta WR senza intervallo.
Domande aperte per il responsabile prodotto: minorenni/gravidanza/disturbi alimentari; allenamento e integratori in perimetro?; soglia di rumore BIA e apparecchio.

- C3 6ec0aa7 (601 test dichiarati, BUILD SUCCESSFUL 7m43s su base 979516e) portato su develop con fast-forward. executeValidated in AiExecutionService/Gateway; fixture golden; parita errori Gemini/OpenAI. Nessun handler usa ancora executeValidated.

## Correzioni da critica BIA + rigidita controllo piano (12/11/2026)
| Agente | Incarico | sessionID | Branch |
|---|---|---|---|
| senior-developer | T1: bug contesto review, delta con tetto, guardie cause-effetto, legenda BP, validatore UI unico | ses_e2f940d1effexkwNgM204sE3VS | fix/bia-t1-review-validatori |
| senior-developer | T2: BIA Coach contesto, soglie rumore, SAFETY_FLAG, ruolo ristretto, regola ritenzione | ses_e2f939336ffeN1QyWD2ysLKvG7 | feat/bia-t2-coach-contesto |
| architetto-software | Analisi sola lettura: rigidita validazione piano, correzione locale deterministica, accettazione con avviso | ses_e2f9367ffffeEpHXhctXb1D9xP | - |
Ipotesi reversibili in T2 (da confermare dall'utente): nessuna esclusione utenti, SAFETY_FLAG locale; BIA Coach solo alimentazione/timing; soglie rumore BIA conservative (peso 0,8 kg, grasso 1,0 pt, magra 1,0 kg, acqua 1,0 kg).
Osservazione utente: l'IA puo non rispettare il piano calorico e il controllo dell'app puo essere troppo stretto -> progetto a livelli (accetta / correzione locale / retry mirato / accetta con avviso), vincoli di sicurezza restano bloccanti. Richiede aggiornare la regola in AGENTS.md (da proporre).

## Analisi rigidita controllo piano (ses_e2f9367ffffeEpHXhctXb1D9xP, sola lettura, non verificata dall'orchestratore)
- Oggi: kcal giornaliere +-3% bloccanti; macro MAI bloccanti (B8); +-4% sui retry NON esiste (B9); centratura 1% blocca i primi 2 tentativi (D7); al fallimento nessun piano parziale, caso peggiore 5 chiamate (3 generazione + 2 revisione); cambio pasto richiede kcal UGUALI esatte (rischio massimo).
- Vincolo tecnico: gli ingredienti non portano kcal/macro: correzione locale per ingrediente richiede il contratto con kcal per ingrediente (opzione C) o una tabella locale (B); subito solo scala pasto intero (A).
- Proposta a livelli L1 accetta / L2 correzione locale (<=8% per pasto) / L3 retry mirato / L4 accetta con avviso (<=10%); bloccanti: allergie, intolleranze, esclusioni, minimo kcal sicurezza, struttura, quota/auth/rete.
- Tranche: T0 misura scostamenti, T1 assessment unico +-4% centratura avviso macro, T2 correzione locale, T3 accetta con avviso + minimo kcal + UI, T4 retry mirato, T5 cambio pasto/sgarro, T6 kcal per ingrediente, T7 doc/AGENTS.md.
- In attesa decisioni utente (D5, D6, D7, D9 piano unificato + limiti 10%/8%/15%, minimo kcal, budget chiamate, revisione IA, cambio pasto, avviso UX).

## Decisioni D14 (utente: 'procedi' = ok ai default, 12/11/2026)
Approvati D5, D6, D7, D9 e: limite avviso 10% kcal giorno; correzione locale 8% per pasto / 15% giorno; budget 1 generazione + 1 retry mirato + 1 revisione; revisione IA da bloccante ad avviso salvo sicurezza; cambio pasto +-3%/+-4% con correzione locale <=8%. Minimo kcal di sicurezza = IPOTESI mia reversibile: max(BMR locale, 1200 donne / 1500 uomini), costanti in un solo punto. AGENTS.md da aggiornare SOLO a lavoro verificato (T7).
Tranche piano: P1 (assessment unico, +-4% reale, centratura avviso, macro avvisi, registrazione scostamenti) -> P2 (correzione locale pasto intero) -> P3 (accetta con avviso, minimo kcal, revisione avviso, UI) -> P4 (retry mirato) -> P5 (cambio pasto/sgarro).

| senior-developer | P1: NutritionPlanAssessment, +-4% reale, centratura avviso, macro avvisi, messaggio FIX con tutti i giorni, appValidation v2 | ses_e2f815e2dffeEK2ZVEw2Cy1W2q | feat/piano-p1-assessment |

- T1 c394cca (620 test dichiarati, BUILD SUCCESSFUL 25m5s, base 6ec0aa7) portato su develop con fast-forward. Fix: contesto review/piano per profileId del job; delta con tetto 14 gg; guard causali; legenda BP; BP1 token 1500; validatore UI unico. Non verificato su device. T2 e P1 da unire con gate sull'unione.

- T2 9e09334 (617 test dichiarati, BUILD SUCCESSFUL 15m20s su base 6ec0aa7). Unito con T1 in worktree bia-t2-coach-contesto (faa8a80), nessun conflitto; gate unione in corso (log gate-t1t2.log). Ipotesi T2 da far confermare: SAFETY_FLAG, minimo 1200/1500 kcal, soglie rumore BIA, ruolo solo alimentare, intervalli grasso target.

- P1 06e1869 (616 test dichiarati, BUILD SUCCESSFUL 10m12s, base 6ec0aa7). Unione di prova con develop(T1) in worktree piano-p1-assessment. Dopo gate T1+T2: unire P1 e rieseguire gate.

| senior-developer | D1: PoC NutritionAdvice JSON nativo vs pipe (contratto parallelo, flag spento, misure token, docs/D1_POC_NUTRITIONADVICE.md) | ses_e2f5cd9d6ffeQ3mghPb75m3G0n | feat/d1-poc-nutritionadvice (d1-poc-nutritionadvice) |

- develop = faa8a80 (T1+T2): gate unione BUILD SUCCESSFUL 7m41s, 636 test unitari, 0 falliti (verificato da orchestratore). Unito P1 in worktree piano-p1-assessment (3cd7ffa); gate T1+T2+P1 in corso (gate-t2p1.log).

- develop = 3cd7ffa (T1+T2+P1): gate BUILD SUCCESSFUL 7m22s, 651 test unitari, 0 falliti (verificato). APK installato in-place sul Samsung.

## Prova BIA Coach su device (12/11/2026, consenso utente, profilo Luca)
- develop 3cd7ffa installato. Analisi Progress Coach su ultima BIA (27/09): risposta in ~13 s, nessun errore di formato. Testo: verdetto prudente, peso 90,7->89,5 (-1,2 kg) 'oltre la soglia di rumore' (soglia 0,8 kg: nuova logica attiva). Dice 'Calorie target 2527 kcal, 179 g proteine, 292 g carbo, 72 g grassi': la Home mostra target 2242 (giorno di riposo); 2527 potrebbe essere il target di giorno di allenamento, NON verificato. Titolo UI ancora 'Analisi nutrizionale e sportiva BIA' (BiaActivity non toccata). Risultato salvato nel profilo Luca (additivo).
- Rigenerazione piano di Mamma NON eseguita: il selettore profilo in Home non apre un elenco via uiautomator.

## D1 PoC NutritionAdvice concluso (ses_e2f5cd9d6ffeQ3mghPb75m3G0n, non verificato dall'orchestratore)
- Commit 100e608 (feat/d1-poc-nutritionadvice), BUILD SUCCESSFUL 10m3s dichiarato, 646 test. Contratto JSON nativo in parallelo con flag spento (NutritionAdviceContractMode.DEFAULT=PIPE), non ancora in develop; unione con develop in worktree d1-poc-nutritionadvice, gate da rieseguire.
- Misura: JSON completo 2,3-2,5x la pipe in output; totale per chiamata +27-49%. Raccomandazione: NutritionAdvice resta PIPE; BIA/review -> JSON tipizzato, ma prima: riga di registro, retry di business per NATIVE_JSON, propertyOrdering Gemini, prova schema nativo su Gemini reale.
## Verifica da codice (orchestratore): 2527 kcal nel BIA Coach
BiaAnalysisAiJobHandler.kt:95 usa profileSnapshot(profileId).calculation.targetKcal = TDEE ABITUALE (2974) x 0,85 = ~2528; la Home mostra il target OPERATIVO del giorno (2638 x 0,85 = 2242, giorno di riposo). Il numero e dell'app, non inventato dal modello, ma due 'target' diversi senza etichetta. Da correggere in T3: passare entrambi con etichetta.

## Verifica globale BIA/misure + T3 (12/11/2026, richiesta utente: proposte risolutive, calcoli, accessibilita, uso di BIA e misure in IA)
| Agente | Incarico | sessionID | Note |
|---|---|---|---|
| senior-developer | T3: target etichettati, scenari calcolati dall'app, UI BiaActivity (titolo, sicurezza, affidabilita IT, accessibilita) | ses_e2f412bd3ffeyHlMw6rrnJwhBa | feat/bia-t3-scenari-locali (da develop 3cd7ffa) |
| architetto-software | Audit calcoli con ricalcolo a mano (BMR/TDEE/target/macro/trend/coerenza schermate) | ses_e2f410751ffe77m8CW9zY6nzFB | sola lettura |
| ux-reviewer | Usabilita e accessibilita flussi BIA/Misure e uscite IA | ses_e2f40e733ffeN9Qj6YAM0nnEuO | sola lettura |
| architetto-software | Audit end-to-end dati BIA/misure verso tutti i consumatori IA | ses_e2f40c907ffewU3Kr0F4SZpf1R | sola lettura |
In coda dopo gli esiti: correzioni dagli audit (tranche), retry business NATIVE_JSON + riga registro NutritionAdvice, integrazione D1 (branch feat/d1-poc-nutritionadvice), gate unione.

## Review UX/accessibilita BIA e Misure (ses_e2f40e733ffeN9Qj6YAM0nnEuO, sola lettura statica, non verificata su device)
25 rilievi: 1 bloccante, 8 alti, 9 medi, 5 bassi.
Bloccante B1: storico BIA/misure, card con contentDescription generico 'Modifica rilevazione BIA' senza data/valori, eliminazione solo con long-press (BiaActivity.kt:746-750, BodyMeasuresActivity.kt:559-563).
Alti: A1 form BIA 16 valori e data persi a rotazione (nessun onSaveInstanceState; NewBodyMeasurementActivity selectedDate non ricollegato); A2 import foto: job non riattaccato dopo ricreazione, no live region, no annulla; A3 salvare BIA/misura accoda 'consiglio obiettivo' IA a pagamento senza conferma (NutritionPathTrigger, BiaViewModel:129,214, BodyMeasurementsViewModel:104) e apre NutritionPathActivity da sola; A4 errori IA con codici tecnici in Toast (BiaActivity.kt:810) e 'Riprova' generico; A5 grafici senza alternativa testuale; A6 grafici fuorvianti (legenda 'Peso' per grasso/muscolo, asse % senza unita, 13 serie, 'measurement' in inglese); A7 contrasto text_muted ~3,3:1 con testo 11sp; A8 soglie contraddittorie: trend locale stabile <0,2 (BiaTrendBinder.kt:33-39) vs rumore IA 0,8/1,0 (BiaNoiseThresholds) + punteggio comparabilita /100 non spiegato.
Medi: validazione form senza errore inline e senza range plausibili; target sotto 48dp (TimeRangeSelector 32dp, back/help 40dp, righe 36-40dp); attesa IA non annunciata/non annullabile; dialog analisi 15 sezioni; conferma IA generica senza dire cosa e inviato (foto, dati salute); anteprima import senza foto e con confidenza grezza; uscita dal form senza avviso; eliminazione BIA senza data nel messaggio; FLAG_SECURE assente su BIA/Misure/Progresso.
Da rilevare: i trend locale/IA usano soglie diverse -> da unificare (collegato a T2 BiaNoiseThresholds).

## Audit flusso dati BIA/misure verso IA (ses_e2f40c907ffewU3Kr0F4SZpf1R, sola lettura, non verificato dall'orchestratore)
20 difetti. Alti: (1) BMR dipende da grasso della sola ultima BIA, senza limite di eta del dato; ultima BIA solo-peso => Katch->Mifflin senza avviso (~-166 kcal, ProfileCalculationService.kt:98,122); (2) nessuna plausibilita sul peso BIA (BiaViewModel.kt:72-80, BiaImportContract.kt:97); (3) job BIA automatico riparte a ogni avvio (chiave auto-<now>, BiaProgressCoachScheduler.kt:21-26) e analizza senza controllare se la BIA e gia analizzata; (4) consiglio obiettivo IA a ogni salvataggio BIA/misura senza conferma (NutritionPathTrigger.kt:23-39); (5) contesto BIA Coach: LC/BMI/safety con snapshot di OGGI anche per BIA vecchia, U con peso di profilo.
Medio-alti: (6) analisi IA orfane/non invalidate dopo modifica o cancellazione BIA (DataDeletionService.deleteBiaMeasurements non cancella bia_analysis_results); (7) peso di profilo statico in 4 prompt (Bia, Weekly U=, Advice, NutritionPath) mentre piano/progressi usano WeightResolver; (8) soglie rumore divergenti: AdaptiveNutritionTargetEngine (muscolo -0,5 kg, grasso 0,3-0,5) vs BiaNoiseThresholds vs UI 0,2; (9) import foto: abbinamento etichette per sottostringa ('peso' vs 'peso osseo'), BiaRawImportContract.kt:65,127-159; (10) provenienza/derivati persi al salvataggio import; (11) fusi: misure a mezzogiorno UTC vs BIA ora locale; PersonalResponseEngine esclude misure prima delle 13:00 locali; (12) finestre diverse, trend 'recente' ancorato all'ultimo dato; (13) Proporzioni: ultima riga solo-peso blocca analisi, risultato non salvato; (14) toggle automazioni Proporzioni/Review senza effetto, cambio frequenza lascia vecchio job.
Medi/bassi: qualita confronto inflazionata (N=N coerente, righe importate 12:00); delta calcolati in 5 punti con fonti diverse (PersonalResponseEngine solo BIA vs Weekly WR); storico non conservato (review REPLACE, progressi in SharedPreferences fuori da export/backup, BIA senza appValidation/versione); legenda BODY 11 vs 12 voci, BC senza legenda, date epoch nei prompt, delta Float non arrotondati; maybeEnqueue senza runCatching nelle misure; delete senza controllo profilo (teorico).
Verificato OK: isolamento profileId nei DAO e job; WeightResolver; BIA Coach contesto (ISO, eta in anni, finestre 36, nessun dato posteriore); parametri job piccoli; import foto con conferma e preview; import storico idempotente; export/backup completi; migrazioni testate; Review con intervallo reale.

## Audit calcoli (ses_e2f410751ffe77m8CW9zY6nzFB, sola lettura, ricalcoli a mano, non verificato dall'orchestratore)
Formule verificate corrette: IMC, Mifflin, Katch (2-70%), TDEE, target x0,85, macro 4/4/9, giorno allenamento, recupero sgarro, delta settimanale.
Errori certi: (1) Home 'Rimangono (kcal)' perde il segno sul superamento (HomeActivity.kt:629); (2) delta Home senza segno e icone legate a bene/male (HomeActivity.kt:261-280).
Probabili: aspettativa settimanale dipende dal check-in di oggi (HomeViewModel.kt:281); Home/Cibo target senza adattamento/recupero diverso da piano e IA (DayEnergyProvider.kt:39-50); Home non vede settimane 2..N di periodo multi-settimana (HomeViewModel.kt:204-207); peso di profilo vecchio in 4 prompt e nel Profilo; soglie stabile/variato in 4 verdetti diversi (0,05/0,2-0,3/0,5/0,8-1,0); allenamento: check-in +140 kcal vs programma +312 (DailyActivityCheckInEngine vs DayEnergyEngine); nessun minimo kcal nei percorsi senza programma (donna sedentaria con recupero 1224 < BMR 1334).
Dubbi: carbo 43,8 g su donna sedentaria (proteine 2 g/kg); peso senza plausibilita (785 kg => target 11.072 kcal); recomposizione su tutto lo storico; settimana completata la domenica stessa; periodi 1M/3M ancorati diversamente; PDF tronca (toInt) vs Home arrotonda; timestamp misure UTC mezzogiorno; DST nei giorni; Home 'oggi' congelato; BMR Katch con % vecchia e peso nuovo (-50 kcal); formatNumber duplicato 8 file; livello EVERYDAY_ONLY con doppio conteggio.
Proposta audit: test golden su 2 profili (D 62a 164cm 78kg: BMR 1334,0 TDEE 2067,7 target 1757,5 carbo 143,0; U 40a 180cm 89,5kg: BMR 1825 TDEE 2828,8 target 2404,4 carbo 261,0).

## Tranche correttive (da audit, 12/11/2026) - worktree da develop 3cd7ffa; T3 (BiaActivity/BiaAnalysis*) separata
K1 Home numeri (segno, delta, settimana 2, oggi congelato, target = piano) | K2 motore+soglie (minimo kcal unico, Katch, soglie uniche, DST, allenamento parita, peso risolto nei prompt) | K3 Misure a11y/grafici/errori IA in italiano | K5 job/validazioni (job BIA duplicati, trigger obiettivo senza conferma, toggle automazioni, orfani, range plausibili peso).
Dopo T3: K4 BiaActivity (storico accessibile, rotazione form, import foto, errori).

| Agente | Tranche | sessionID | Branch |
|---|---|---|---|
| senior-developer | K1 Home numeri | ses_e2f371be0ffeAqk5uejl9inPHs | fix/k1-home-numeri |
| senior-developer | K2 motore + soglie + minimo kcal | ses_e2f36d3fcffeXwNuZeKZitwDR8 | fix/k2-motore-soglie |
| senior-developer | K3 Misure a11y/grafici/errori IA | ses_e2f3692cbffeCx4UzU6hFIJhXb | fix/k3-misure-a11y |
| senior-developer | K5 job/validazioni/orfani | ses_e2f36460effeDFDv2WCwlJoPH3 | fix/k5-job-validazioni |
In corso anche: T3 ses_e2f412bd3ffeyHlMw6rrnJwhBa (BIA). Sequenza integrazione: T3, K2, K5, K1, K3 (ordine da rivedere per conflitti), gate sull'unione; poi K4 BiaActivity; D1 separato (feat/d1-poc-nutritionadvice).

- K3 si e fermato senza modifiche (perimetro ~20-25 file). Decisione orchestratore: sotto-tranche K3a (storico/form/validazioni), K3b (AiFailureMessages/attesa IA), K3c (grafici), K3d (contrasto/48dp, per ultimo) in sequenza sullo stesso branch; limite 10 file solo per produzione Kotlin, fino a 14 con risorse/test. K3a avviato (stessa sessione ses_e2f3692cbffeCx4UzU6hFIJhXb).

- T3 fe41c21 (678 test dichiarati, BUILD SUCCESSFUL 10m54s su base 3cd7ffa) portato su develop con fast-forward. Target abituale/operativo etichettati; scenari e grasso perso calcolati dall'app; guardia NUMBER_NOT_IN_CONTEXT; UI BiaActivity (titolo, affidabilita IT, nota sicurezza condizionale, heading). Rischio: guardia numerica con falsi positivi, da provare con chiamata reale. K4 BiaActivity (storico/rotazione/import/errori) ora sbloccato dopo K5 (BiaViewModel).

- K3a ec82bf5 (672 test dichiarati, BUILD SUCCESSFUL 21m43s, 2o tentativo; base 3cd7ffa; non unito): storico misure accessibile (azioni Modifica/Elimina), form con stato dopo rotazione + avviso uscita, intervalli plausibili con errori inline, date future bloccate. K3b avviato (stessa sessione). K3 usa BodyMeasurementRanges propri: K5 ha BodyValueRanges per ViewModel/import: da riallineare dopo il merge.

- K2 lotto 1 efe1508 (667 test dichiarati, BUILD SUCCESSFUL 3m50s al 2o giro, base 3cd7ffa; non unito): SafetyCalorieFloor max(BMR,1200 F/1500 M) unico per motore/adattamento/recupero; Katch con grasso dell'ultima BIA che lo contiene (<=180 gg) e peso della stessa rilevazione; golden D/U OK. Lotto 2 avviato (soglie uniche, parita allenamento, DST, peso risolto nei prompt, tetto proteico/minimo carbo).

- K5 411208c (680 test dichiarati, BUILD SUCCESSFUL 2m22s, base 3cd7ffa). Unito con develop(T3): conflitto in BiaAnalysisAiJobHandler.payloadJson risolto (firma unica result, measurement, measurementCount, safetyFlagActive). Gate unione T3+K5 in corso (gate-t3k5.log). K5 non fatto: appValidation nel payload BIA, progressi in export/backup, collegamento UI di goalAdvice e notes/warnings import (K4).

- K1 3842d4e (668 test dichiarati, BUILD SUCCESSFUL 9m39s al 3o giro, base 3cd7ffa; non unito): segno 'Oltre il target', delta con segno/direzione, aspettativa settimanale su TDEE abituale, DayTargetResolver unico Home/Cibo (target del piano, teorico etichettato), settimana 2..N, cambio giorno (DayChange.todayFlow), DisplayNumberFormatter half-up migliaia '2.068' (Home, Cibo, PDF). Residuo: formatNumber duplicato in HistoryActivity, ProfileActivity, ProfileEditActivity, OnboardingWizardActivity, MeasurementsActivity, BiaActivity. HomeActivityCheckInDeviceTest/HomeCaloriesLayoutDeviceTest solo compilati. Ordine unione: T3+K5 (gate in corso) -> K1 -> K2 (dopo lotto 2) -> K3 (a,b) -> D1.

- K3b 7fe59d6 (683 test dichiarati, BUILD SUCCESSFUL 13m57s, base ec82bf5->3cd7ffa; non unito): AiFailureMessages (tabella centrale, copertura categorie, 'Riprova' solo se retryable, Dettagli espandibile), Proporzioni con pannello errore persistente, attesa annunciata e Annulla. Limiti: nessun re-attach dopo rotazione (A2 BIA/K4); SchemaInvalid/ProviderProtocol non retryable in AiFailure quindi niente 'Riprova'. K3c avviato.

- develop = ea048df (T3+K5): gate BUILD SUCCESSFUL 10m51s, 707 test unitari, 0 falliti (verificato). K1 unito (badeaf8) senza conflitti; gate T3+K5+K1 in corso (gate-k1.log).

- K2 lotto 2 fc486ed (678 test dichiarati, BUILD SUCCESSFUL 6m59s al 2o giro; base efe1508): soglie uniche su BiaNoiseThresholds (trend, adattamento, binder, interpreter), frase UI soglia, qualita senza /100 e '>90 gg non Alta', peso risolto in Weekly/Advice/NutritionPath/Profilo. NON fatto: (7) tetto proteico/minimo carbo (rompe invarianti testate; decisione di prodotto aperta: D carbo 143,0 -> ~145,2 con tetto), (4) parita allenamento, (5) DST. Residuo: HomeViewModel (K1) righe ~371-372 chiama trend() senza metricKey (soglia storica 0,05): da collegare dopo merge. T3 stampa ancora score=/100 nel prompt (BiaAnalysisReport). Lotto 3 avviato (4,5 + chiavi ProfileCalculationService).

- develop = badeaf8 (T3+K5+K1): gate BUILD SUCCESSFUL 9m3s, 724 test unitari, 0 falliti (verificato). Prossimo: K2 (dopo lotto 3) e K3 (dopo K3c), poi D1; collegare metricKey in HomeViewModel.

- K3c 57c12cf (691 test dichiarati, BUILD SUCCESSFUL 8m19s, base 3cd7ffa): grafici con descrizione+tabella valori, legenda/unita per metrica, Misure con sola metrica scelta e asse date, serie distinguibili, 1A. Cambio visibile: Progresso e Misure da % normalizzate a valori reali. K3 (a,b,c) unito con develop (T3+K5+K1): conflitto solo strings.xml (aggiunte da entrambe le parti, tenute tutte). Gate develop+K3 in corso (gate-k3.log). K3d (contrasto/48dp) dopo.

- K2 lotto 3 c3c05af (686 test dichiarati, BUILD SUCCESSFUL 12m12s; base fc486ed; non unito): parita allenamento dichiarata (check-in = quota prudente sopra TDEE abituale, mai sommata al programma; check-in <= costo programma), CalendarDays (giorni di calendario locali, DST) in WeeklyBodyDelta e metricSnapshot, metricKey in ProfileCalculationService, nota (7) tetto proteico (decisione aperta). Residuo: HomeViewModel righe ~371-372 trend() senza metricKey (da collegare dopo merge). Ordine: gate develop+K3 in corso, poi K2.

- develop = 55da40a (T3+K5+K1+K3 a,b,c): gate BUILD SUCCESSFUL 8m33s, 764 test unitari, 0 falliti (verificato). K2 (3 lotti) unito a develop in worktree k2-motore-soglie: conflitto solo strings.xml (aggiunte), metricKey bodyFatPercent/muscleMassKg collegato in HomeViewModel:389-390; gate develop+K2 in corso (gate-k2.log).

- develop = 5ad7d30 (T3+K5+K1+K3+K2): gate BUILD SUCCESSFUL 8m19s, 799 test unitari, 0 falliti (verificato). APK in worktree k2-motore-soglie (android/app/build/outputs/apk/debug). Samsung non collegato al momento. Restano: K3d, K4 BiaActivity, D1, installazione e verifica a schermo.

## K4 e K3d avviati (12/11/2026) da develop 5ad7d30
| Agente | Tranche | sessionID | Branch |
|---|---|---|---|
| senior-developer | K4: BiaActivity (storico a11y, form con stato, import foto riattaccato/annulla/anteprima, invito consiglio, errori AiFailureMessages, analisi obsoleta, dialog analisi) | ses_e2ee334b9ffeEYGcAQ8dqfuDD1 | feat/k4-bia-activity |
| senior-developer | K3d: contrasto (text_muted >=4,5:1, test contrasto token), target 48dp, segmenti a piu righe, hub con etichette | ses_e2ee300f7ffeoUq7OuuNngBsyk | fix/k3d-contrasto-target |
Da integrare ancora: D1 (feat/d1-poc-nutritionadvice). Decisioni aperte: tetto proteico/minimo carbo; 'Riprova' per SchemaInvalid/ProviderProtocol (AiFailure.retryable); FLAG_SECURE su BIA/Misure/Progresso; automazioni Proporzioni/Review non collegate.

- K3d 7d24879 (803 test dichiarati, BUILD SUCCESSFUL 9m29s su base 5ad7d30) portato su develop con fast-forward. text_muted #839088 -> #5F6C65 (5,5:1), semantic_error/#warning scuriti, text_micro 12sp, target 48dp, segmenti a 2 righe, hub con etichette. Da verificare a vista: hub Rilevazioni, segmenti a font 1,3x/2x, header Home (screen_header_icon_size 48dp). Residuo: pulsante profilo Home area 20dp; contentDescription terza azione hub non contiene etichetta visibile.

## Verifica a schermo su device (12/11/2026 sera, APK develop 7d24879, profilo Luca, font_scale originale 1.1 ripristinato)
- Home: separatori migliaia (1.918, 2.638), riga fonte target 'Target del giorno dal piano alimentare, con adattamento e recupero'. OK.
- Rilevazioni: hub con etichette visibili Nuova/Storico/Analizza con IA, 135 px (>=48dp), anche a font 1,3x. OK.
- Storico Misure: card con data e valori, pulsanti Modifica/Elimina con data. OK. Andamento: solo metrica scelta, descrizione 'Peso corporeo, 2 rilevazioni dal ... da 87,4 a 88,6 kg', 'Mostra tabella dei valori', frase valori reali. OK.
- Progresso: descrizione grafico, tabella valori, 1M/3M/6M/1A con descrizioni 'Intervallo 1 anno'. A 1,3x i segmenti 1M..1A hanno altezza ~40 px (bounds 1985-2025) = TAGLIATI/schiacciati (<48dp) -> da correggere. Etichette asse date ripetute '12/09' x4 con 2 soli punti (difetto minore).
- K4 0be039c (823 test dichiarati, BUILD SUCCESSFUL 2m3s al 2o giro; base 5ad7d30): BiaActivity: storico a11y, stato dopo rotazione, uscita con avviso, errori in linea, invito consiglio, import riattaccato/annulla, analisi obsoleta, titolo unico. Non fatto: miniatura foto (volutamente), warnings import (serve riga in BiaImportAiJobHandler), schermata analisi, FLAG_SECURE, rinomina bottone hub. Unito con develop (K3d) in worktree k4-bia-activity (4a56707) senza conflitti; gate in corso (gate-k4.log).

- develop = 4a56707 (K4+K3d+...): gate BUILD SUCCESSFUL 5m11s, 827 test unitari, 0 falliti (verificato). APK installato sul Samsung (in-place).

- Verifica device (APK develop 4a56707 con K4): schermata BIA con 17 misurazioni salvate, storico con azioni Modifica/Elimina con data e descrizione completa (15 date distinte scorse). Non provati: form (rotazione), import foto, analisi IA nuova, TalkBack reale. Fix K3e avviato (selettore periodo >=48dp a font grande, asse date senza duplicati): ses in corso, branch fix/k3e-selettore-periodo.

- K3e c13d99e (gate BUILD SUCCESSFUL 5m20s dichiarato; base 4a56707) su develop (ff). Verifica device a 1,3x: asse date ora '12/09, 27/09' senza duplicati. NOTA: i 40 px del selettore 1M..1A erano bounds RITAGLIATE dal bordo inferiore dello schermo (UIAutomator): dopo scroll i tasti sono 135 px (>=48dp). K3d non era difettoso su questo punto. develop = c13d99e. D1: merge develop in worktree d1 per gate.

- develop = 6b86fc2 (+D1): gate BUILD SUCCESSFUL 3m48s, 862 test unitari, 0 falliti (verificato). Contratto JSON NutritionAdvice presente con flag spento (DEFAULT=PIPE). docs/D1_POC_NUTRITIONADVICE.md.

## CONSENSO UTENTE (12/11/2026): prove IA reali su device autorizzate in modo continuativo ('ti do il consenso ricordatelo, testa ogni cosa anche analisi bia e nutrizione'): BIA Coach, proporzioni, consiglio alimentare, piano settimanale (Mamma/Luca), cambio pasto. Restano vietati: reset/uninstall/pm clear/seed, lettura/log chiavi API, cancellazioni di dati. Le chiamate scrivono risultati additivi nei profili.

## Prove IA REALI su device (12/11/2026 sera, APK develop 6b86fc2, consenso utente)
- Consiglio alimentare (Mamma, 'Stasera ho voglia di dolce...'): OK in ~11 s, 5 opzioni coerenti con i target residui.
- Cambio pasto IA (Mamma, spuntino serale 161 kcal): OK in ~13 s, 2+ alternative a 161 kcal con dosi pratiche. Osservazione: macro identiche tra alternative diverse (28P/5C/3F) => numeri dichiarati dal modello, non calcolati (conferma audit).
- Piano settimanale Mamma rigenerato: OK in ~23 s, 1 tentativo; P1 attivo: appValidation v2 con deviazioni kcal 0% sui giorni, avvisi CARBS_OUT_OF_BAND (target carbo 41,3 g vs 61 g = +47,8%) e ripetizioni. DIFETTI: titoli pasti generici ('Colazione leggera', 'Pranzo equilibrato', 'Cena leggera') mentre il piano precedente aveva piatti specifici; target carboidrati 41 g/giorno con proteine 160 g su 1381 kcal (46% kcal) = problema segnalato dall'audit (tetto proteico/minimo carbo): decisione presa dall'orchestratore come IPOTESI REVERSIBILE: implementare tetto proteico e minimo carbo (tranche K6).
- BIA Coach (Luca, ultima BIA 27/09): risposta in ~7 s, ma DIFETTI: (1) 'Punti positivi: PROBABLY_POSITIVE' e 'Da monitorare: MONITOR' = token enum al posto di frasi (validatore li accetta); (2) classificazione grezza 'STABLE' visibile e verdetto='STABLE'; (3) testo calorie: 'target abituale 2974 kcal' ma 2974 e il TDEE (target abituale = 2528); target operativo 2242 e macro 179/220/72 corretti; (4) indicatori grezzi 'weightKg:' 'bodyFatPercent:' 'muscleMassKg:' e '-0.5 %' invece di pp; (5) 'Nessuna nota di sicurezza attiva' mostrato come nota; (6) decimali con punto nel testo del modello; scenari S1-S3 calcolati dall'app CORRETTI (87,4/85,4/83,4 kg a 18/16/14%).
- Proporzioni (Luca): OK, ma date ISO 'Precedente 2026-07-25' e interpretazione 'rientrano nelle normali oscillazioni' per fianchi +4 cm e addome +3 cm (non supportata: manca soglia di rumore per circonferenze).
- Tranche avviate: T4 (qualita output BIA Coach e proporzioni), K6 (tetto proteico/minimo carbo, titoli specifici piano). P2/P3 differiti: nei piani reali deviazione kcal 0%, attempts=1.

| senior-developer | T4 qualita output BIA Coach e proporzioni | ses_e2e98fa9effeZfyv7J0hE92Lvt | fix/t4-bia-output-qualita |
| senior-developer | K6 MacroPolicy (tetto proteico/minimo carbo) + titoli piano | ses_e2e98c4b4ffegeAFa9olNQowbU | fix/k6-macro-titoli-piano |

## Import foto BIA provato su device (12/11/2026 sera, foto SINTETICA creata dall'orchestratore: valori dimostrativi 82,4 kg ecc., poi rimossa dal telefono; anteprima annullata, NULLA salvato)
- Flusso: Importa foto -> Scegli dalla galleria -> conferma IA con frase privacy specifica -> anteprima in ~15 s: provider/modello, 'Confidenza della lettura: alta', provenienza, data rilevata 05/11/2026 07:45, avviso 'la foto non e conservata'. 15 campi su 16 letti correttamente (82,4; 24,6; 8; 58,9; 54,3; 1712; 20,3; 62,1; 44,7; 18,9; 3,3; 17,4; 14,3; 46; 26,8).
- DIFETTI: (1) 'Muscolo scheletrico (kg)' RIMASTO VUOTO (la foto aveva 'Massa muscolo scheletrico 33,1 kg'): abbinamento etichetta mancante in BiaRawImportContract (sinonimo 'massa muscolo scheletrico'); (2) provenienza dice 'Derivati: acqua kg da acqua%*peso' anche se la foto riportava 'Acqua 44,7 kg' esplicito. Da correggere (K7).

- K6 bb02eeb (878 test dichiarati, BUILD SUCCESSFUL 13m47s su base 6b86fc2) portato su develop con ff. MacroPolicy: proteine max(min(2,0 g/kg, 30% kcal),1,2 g/kg), grassi max(0,6 g/kg,20% kcal) min 35% kcal, carbo per differenza min 100 g (>=1500 kcal) / 80 g; golden D: 131,8P/46,8F/202,27C; U: 179/53,7/301,28; sedentaria 1381 kcal: 103,6/46,8/136,4. Titoli: TITLE_RULE nel prompt + avviso GENERIC_MEAL_TITLE non bloccante. Piani gia generati invariati: valgono alla prossima rigenerazione. Rischio: grassi 0,6 g/kg anche per performance.

| senior-developer | K7 import foto BIA: sinonimi etichette (muscolo scheletrico ecc.) e derivati solo se manca l'esplicito | ses_e2e81348fffeb6N1IZdbN6pPun | fix/k7-import-etichette (da develop bb02eeb) |

- T4 144bd4b (892 test dichiarati, BUILD SUCCESSFUL 8m48s al 2o giro; base 6b86fc2): validatore rifiuta placeholder/enum/testi <3 parole/verdetto=classificazione; etichette IT classificazioni e indicatori; sezione calorie e macro resa dall'app (appCalories); note neutre nascoste; decimali con virgola; proporzioni: soglia circonferenze 1,0 cm (ipotesi), APP_COMPARISON ABOVE/BELOW_THRESHOLD, date IT. Unito con develop(K6) senza conflitti (f4e3682); gate in corso (gate-t4.log).

- K7 92fc005 (890 test dichiarati, BUILD SUCCESSFUL 17m35s, base bb02eeb; non unito): BiaMeasurementNormalizer con sinonimi per ogni campo (muscolo scheletrico, acqua, ecc.), derivati solo se manca l'esplicito, avviso se divergono >5%. Solo 2 file. Non riprovato su device. Ordine: gate T4 in corso -> ff T4 -> merge K7 -> gate -> install -> riprove device.

- develop = f4e3682 (K6+T4): gate BUILD SUCCESSFUL 10m31s, 908 test unitari, 0 falliti (verificato). K7 unito (450a1e8) senza conflitti; gate finale in corso (gate-k7.log).

- develop = 450a1e8 (tutto: K6+T4+K7): gate finale BUILD SUCCESSFUL 9m36s, 920 test unitari, 0 falliti (verificato). APK installato sul Samsung (in-place).

## Prove IA su device, APK develop 450a1e8 (13/11/2026 mattina, consenso utente)
- BIA Coach Luca: 1a esecuzione RESPINTA dai controlli (codice BIA_CLASSIFICATION_MISSING, 8 s, analisi non salvata); 2a esecuzione OK in ~9 s. Output molto migliorato: 'Esito: Stabile', affidabilita Bassa con motivo, sezione calorie resa dall'app corretta (oggi allenamento 2699 kcal 179P/369C/56F; target abituale 2528 kcal 179/327/56; TDEE 3175 e 2974 etichettati), confronto con etichette IT e 'punti percentuali', scenari S1-S3 corretti, nota sicurezza vuota non mostrata. DIFETTI RESIDUI: (a) token '|MODERATE' compare nel testo degli scenari S1/S2 (artefatto del parser pipe); (b) rifiuto non deterministico alla 1a esecuzione (costa quota; nessun 'Riprova' perche non retryable); (c) refuso del modello 'digiono'.
- Proporzioni Luca: OK in ~9 s: date IT, 'da X a Y', 'oltre la soglia di rumore di 1,0 cm' per addome +3 e fianchi +4, nessuna 'normale oscillazione'.
- Import foto: provato con foto SINTETICA (prima del merge K7) e poi, per un MIO ERRORE di tap nel selettore foto, e stata inviata a Gemini una FOTO REALE dalla galleria dell'utente: era uno screenshot BIA Fitdays di Luca (27/09). Anteprima ANNULLATA, nulla salvato; da dichiarare all'utente. Esito: muscolo scheletrico ora letto (41,2 OK, K7 funziona) MA ERRORE GRAVE: 'Acqua corporea (%)' = 52,5 (e il valore in kg della foto; corretto sarebbe 58,7 %) e 'Acqua corporea (kg)' = 47,0 (derivato sbagliato); 'Proteine (%)' = 14,3 (era kg; corretto 16 %) e 'Proteine (kg)' = 12,8 (derivato sbagliato). Cioe valori in kg assegnati ai campi % per l'alias generico 'acqua'/'proteine' (regressione K7). Verita attesa dalla rilevazione salvata 27/09: acqua 58,7 % / 52,5 kg; proteine 16 % / 14,3 kg. Tranche K8 avviata.
- ADB instabile: device offline/not found a intermittenza (USB/cavo); prove interrotte prima della rigenerazione del piano di Mamma (nuovi macro) e del cambio profilo.

## Piano Mamma rigenerato con nuovi macro (13/11/2026, APK 450a1e8): OK in ~23 s, 1 tentativo
- Titoli specifici ('Yogurt greco con mela e cannella', 'Mandorle e pera', 'Petto di pollo con insalata e olio'), nessun avviso GENERIC_MEAL_TITLE.
- Target 1381 kcal: proteine 103,6 g, carboidrati 133,7 g, grassi 48 g (prima 160/41/62). kcal giornaliere -0,1%..0%; proteine +0,4%; carbo fino a +24,9% e grassi fino a -33% (avvisi FAT_OUT_OF_BAND, non bloccanti); varieta ripetizioni avviso. appValidation v2 valid=true, attempts=1.
- Aperto: T5 per BIA_CLASSIFICATION_MISSING e '|MODERATE' negli scenari (fix/t5-bia-parse-robusto).

- K8 36e4275 (932 test dichiarati, BUILD SUCCESSFUL 14m48s, base 450a1e8) su develop (ff). Import: abbinamento per UNITA (kg non riempie %), coppie %/kg risolte con coerenza al peso 4% e plausibilita, ambiguo -> vuoto + avviso, sanitize con avvisi di coerenza. Non verificato su device (solo fixture). Residuo: T5 in corso.

- T5 86a1133 (933 test dichiarati, BUILD SUCCESSFUL 5m59s, base 450a1e8): parser BA2 tollerante (record B, header, sinonimi), prompt senza esempio negativo con sintassi record, FIX specifico per BA2 in AiContractRegistry, 'Riprova' solo per tap manuale su rifiuti BIA (resolveForBiaAnalysis), parser G senza coda |MODERATE e validatore BIA_TEXT_ENUM_TAIL. Unito con develop(K8); gate in corso (gate-t5.log).

- develop = 19b2b5e (K8+T5): gate BUILD SUCCESSFUL 8m16s, 945 test unitari, 0 falliti (verificato). APK installato sul Samsung.

## Prove su device APK develop 19b2b5e (13/11/2026): BIA Coach Luca 5 esecuzioni su 5 OK in 9 s, nessuna coda enum, nessun token grezzo; proporzioni OK. Import foto NON riprovato (K8 non verificato su device: serve foto scelta dall'utente).

- Verifica 13/11 scritta in docs/VERIFICA_2026-11-13.md (non committata).

| senior-developer | T6: consiglio alimentare non deterministico (diagnosi causa, AiFailureMessages, token/validatore) + rifiuto di '86..5' nel dialog valore BIA | ses_e2bbd3694ffepRNz62Kl9YswRq | fix/t6-consiglio-parser-valori (da develop 19b2b5e) |

- T6 9037c85 (956 test dichiarati, BUILD SUCCESSFUL 33s al 2o giro; base 19b2b5e) su develop (ff). Causa consiglio NON PROVATA: messaggio generico che nascondeva il motivo; NA1 token 900->1500, parser NA1 con errori specifici, kcal '450.0' accettate, foodsCsv richiesto solo con vincoli rigidi, UI con AiFailureMessages e Riprova manuale; log categoria 'MyFitAiAi'. BIA dialog: keepRawNumericInput (numberDecimal filtrava il secondo punto). APK installato.

## Piano semplificazione attivita (13/11/2026): docs/PIANO_SEMPLIFICAZIONE_ATTIVITA.md. In attesa decisioni D1-D4. Nessun codice toccato.

- Decisioni D1-D4 prese dall'utente: 3 livelli (Sedentario/Moderato/Molto attivo), sport da livello, ELIMINARE tabelle con MIGRATION_19_20 (previo export), Luca 'Moderatamente attivo' stabile in configurazione. Piano aggiornato in docs/PIANO_SEMPLIFICAZIONE_ATTIVITA.md.

## F0 semplificazione attivita (13/11/2026): tag git pre-semplificazione-attivita su e26e489. Backup JSON completi dei profili esportati dall'app (schema myfitai_profile_export_v2) e copiati FUORI dal repository in C:\Users\LucaPiciollo\Luca\backup-myfitai\ (dati di salute, non committare): luca 1,2 MB, mamma 0,2 MB. Contenuto: workouts=0 e dailyActivityCheckIns=0 per ENTRAMBI i profili -> il DROP delle tabelle non perde dati reali. Programma di allenamento (SharedPreferences) in profilePreferences. Nota: Mamma ha una BIA 11/11/2026 (80 kg) non inserita dall'orchestratore.

## Semplificazione attivita - avanzamento (13/11/2026) [note ricostruite]
- A1 3ea3ee0 su develop (917 test dichiarati, BUILD SUCCESSFUL 4m28s): Home/Cibo/BIA/piano con un solo target dal profilo; golden Luca BMR 1918,5 TDEE 2973,7 target 2527,6 (179P/56,2F/326,5C). Card check-in Home ancora presente ma senza effetto fino a B1.
- A4 d06964f su develop (925 test dichiarati, BUILD SUCCESSFUL 14m58s): 3 livelli Sedentario/Moderato/Molto attivo, mapper tollerante ai valori storici, valore storico non tra i 3 mostrato in coda, ActivityBasis tolta da ProfileEdit/Onboarding. Da verificare: SportsNutritionClassifier deve riconoscere 'Molto attivo' ed eventualmente 'Moderato' (A2).
- In corso: A2 ses_e2b7aafcbffeXZgL2ZGabBZd7B (feat/sem-a2-sport-varieta), A3 ses_e2b7a8257ffei1HT6LWApwK645 (feat/sem-a3-prompt-analisi), B1 ses_e2b7a232affeB5ic27mwdNmPs2 (feat/sem-b1-ui-allenamenti).
- F4 documenti fa67407 su docs/sem-f4-documenti (ses_e2b780bc8ffe6U9QQ6HEgTJH9h), 34 file, nessun gate necessario; dubbi: esempio canonical-json-parity-example.json non valida gia prima; PA1 input A senza workouts (A3 deve allineare); PW/TC dismessi (parser A3 tollerante).
- Incidente orchestratore: ho scartato per errore un git checkout di docs/prodotto/stato.md con note non committate; ricostruite qui. Il subagente F4 aveva scritto DEVELOPMENT_PLAN.md anche nel repo principale (scartato: identico al commit del suo branch).

- B1 2f16724 (917 test dichiarati, BUILD SUCCESSFUL 13m20s) e A3 93476c2 (921 test dichiarati, BUILD SUCCESSFUL 14m6s) uniti nel worktree sem-int-1 (integrazione/sem-1) senza conflitti sopra develop 7b97b7c; gate unione in corso (gate-sem1.log). A2 ancora in corso.

- develop = b4b6a83: B1+A3+A2 (integrazione/sem-1 d09c645, gate BUILD SUCCESSFUL, 927 test unitari 0 falliti verificati) + F4 documenti. Primo gate sem-1 fallito per file locked (gate precedente non fermato) e secondo mai partito (filtro che uccideva il comando): non erano errori di codice. APK da worktree sem-int-1.

## Verifica device APK develop fd7b87b (13/11/2026): Home Luca (TDEE 2.974, target 2.698 dal piano, teorico 2.528) e Mamma (1.625 / 1.381) senza diciture allenamento; Profilo senza riga Allenamenti; Dati personali: livello con Sedentario/Moderato/Molto attivo e 'Moderatamente attivo' di Luca in coda (verificato con uiautomator --windows); Altro senza switch allenamenti; guida aggiornata; Impostazioni piano senza giorni di allenamento; BIA Coach Luca: un solo target 2528 (179/327/56) + TDEE 2974. Non provato: 'Molto attivo' su un profilo, import foto, TalkBack. B2 e F3 avviati.

| senior-developer | B2 testi residui senza allenamenti | ses_e2b1908eaffejmoVK7HiqRpnou | feat/sem-b2-testi-residui (da develop fd7b87b) |
| senior-developer | F3 export v3, backup tollerante v1/v2, PDF, cancellazione dati | ses_e2b18d326ffeG780W8ZmufyruK | feat/sem-f3-export-backup (da develop fd7b87b) |
Dopo: F5 migrazione Room 19->20 (elimina workouts e daily_activity_checkins), poi F6 test device e F7 verifica.

- B2 FERMATO (ses_e2b1908eaffejmoVK7HiqRpnou): commit e716951 su feat/sem-b2-testi-residui; il subagente riporta BUILD SUCCESSFUL, 927 test 0 falliti (da riverificare in integrazione). 4 file, 21 stringhe orfane rimosse, PatternCode.TRAINING mostra 'Routine'. Classi Training*/WorkoutPreferences restano finche F3/F5.

- develop = e05859f: B2+F3 (integrazione/sem-2, gate BUILD SUCCESSFUL, 932 test unitari 0 falliti, letti dai file XML). Installato in-place: schermata Esporta/importa senza 'allenamenti'; export JSON del profilo Mamma = schema myfitai_profile_export_v3 senza chiavi workouts/dailyActivityCheckIns (file di cache poi cancellato). NON eseguito: restore di un backup (ProfileBackupRestoreTest e androidTest mai eseguito; il restore sostituisce il profilo, non provato su dati reali).

| senior-developer | F5a rimozione codice allenamenti (senza Room) | ses_e2af2a6a7ffeGpsXmyGBI8H4F6 | feat/sem-f5a-codice-morto (da develop b627f16) |
Dopo F5a: F5b = MIGRATION_19_20 + entita/DAO/repository + schema 20 + fixture/seeder/test.

Decisione 13/11 (utente: 'fai il meglio'): NON ripristinare backup su profili reali e NON creare profili di prova; 'Molto attivo' coperto da test unitari del motore (x1,725), non provato a schermo; restore v2/v3 coperto solo da test compilato (androidTest non eseguito). Da dichiarare nel report finale.

- F5a FERMATO (ses_e2af2a6a7ffeGpsXmyGBI8H4F6): commit 85e77ef, develop = 57f69d7. Gate del subagente BUILD SUCCESSFUL, 923 test 0 falliti (riletti io dagli XML nel suo worktree). 6 file cancellati (TrainingProgram*, WorkoutPreferences, DailyActivityCheckInEngine, TrainingEnergyPlanner).
| senior-developer | F5b Room 19->20 | (vedi esito) | feat/sem-f5b-room-20 (da develop 57f69d7) |

- F5b FERMATO (ses_e2ae68a58ffeJ98C02QYIb5F5x): commit b4ec996, develop = 8ab33a8 (merge no-ff). Gate del subagente BUILD SUCCESSFUL, 923 test 0 falliti (riletti io). Room versione 20, MIGRATION_19_20 (DROP TABLE), schema 20.json senza le tabelle. Migrazione NON eseguita su device (androidTest mai lanciato). Installazione sul Samsung in attesa: adb non vede il device (lista vuota dopo kill-server). Prima dell'install: copiare db+wal+shm in backup-myfitai\db-prima-migrazione-20.

- Migrazione 19->20 VERIFICATA su device (13/11/2026, installazione in-place di develop 8ab33a8): copia db+wal+shm prima in backup-myfitai\db-prima-migrazione-20; dopo l'avvio user_version 19->20, tabelle workouts e daily_activity_checkins assenti, 15 tabelle con conteggio righe identico; app avviata senza crash; profilo Mamma: Home (TDEE 1.625 / target 1.381), Cibo, Progresso e BIA Coach (1381 kcal, 104/134/48) OK. Luca NON ricontrollato dopo la migrazione. Idea in valutazione: provider Grok (xAI) o Groq come terzo provider (vedi risposta utente).

| senior-developer | Grok L1: provider xAI backend, sperimentale e spento (utente: 'grok come llm') | vedi esito | feat/grok-l1-provider (da develop 4f498f5) |
Poi Grok L2 (UI selezione, prezzi, usage), L3 prove reali con chiave inserita dall'utente. In parallelo: F6/F7 verifica semplificazione (Luca dopo migrazione).

- Dopo migrazione 20, Luca ricontrollato su device: Home (TDEE 2.974, target 2.698), Progresso (89,5 kg, -1,2 kg), Cibo senza crash. Nota: il piano di Luca generato il 12/11 mostra ancora nel messaggio salvato '1 uguali tra allenamento e riposo' (testo memorizzato col vecchio codice; nessuna occorrenza nel codice attuale; sparisce alla rigenerazione). Lezione: max 2 BACK dall'app, altrimenti si esce al launcher.

- Grok L1 FERMATO (ses_e2ac66221ffeAjts6dwokF4M23): commit 5bf6727, develop = d3d0a43. 938 test unitari 0 falliti riletti io dagli XML. Modelli grok-4.3 (default, 1.25/2.50 USD per 1M) e grok-4.7 (2.00/6.00); xAI Responses API. Nessuna chiamata reale a xAI fatta; formato risposta dedotto dalla doc. Sperimentale e spento (grokEnabled=false).
| senior-developer | Grok L2a runtime: selezione a 3 vie, prezzi, usage, reasoning effort | vedi esito | feat/grok-l2a-runtime (da develop d3d0a43) |
Dopo: L2b UI (Impostazioni IA, onboarding), L3 prove reali con chiave dell'utente.

- Grok L2a CANCELLATO (ses_e2aad3aacffeKqpJ4j91KbOk6o, non per errore del subagente: nessun gate eseguito, nessun commit). Lavoro parziale NON committato e NON verificato nel worktree grok-l2a-runtime (7 file modificati + GrokPricingStore, GrokUsageTracker, GrokRuntimeTest nuovi). Non unito a develop. Riavvio in attesa della decisione dell'utente su costi/privacy Grok (a pagamento; crediti gratuiti solo con condivisione dati).

- RIAVVIO 1/2 Grok L2a: nuovo sessionID ses_e2a96cf2bffeFPPoa0wt8hWv45 (precedente ses_e2aad3aacffeKqpJ4j91KbOk6o cancellato), mantiene il lavoro parziale non committato. Scelta utente: strada 1 (chiave xAI senza condivisione dati, tetto basso, inserita dall'utente).

- Grok L2a FERMATO (ses_e2a96cf2bffeFPPoa0wt8hWv45): commit 5f7456c, develop = 9460efd. 950 test unitari 0 falliti (XML riletti; testDebugUnitTest era UP-TO-DATE, XML del 15:44 con GrokRuntimeTest). Selezione a 3 vie retrocompatibile, Grok usato solo se abilitato+scelto+chiave (altrimenti NOT_CONFIGURED), prezzi con x2 sopra 200k, reasoning.effort low (doc xAI), tracking ai_usage_records provider='GROK'. Nessuna UI, nessuna chiamata reale.
| senior-developer | Grok L2b UI Impostazioni IA/Onboarding/badge | vedi esito | feat/grok-l2b-ui (da develop 9460efd) |

- Grok L2b FERMATO (ses_e2a8b5863ffehVKxTi8Kd6KM8a): commit a0e33e7, develop = f6deaee. Gate del subagente BUILD SUCCESSFUL 22m49s, 955 test unitari 0 falliti (XML riletti, eseguiti davvero). Installato in-place: Altro > Impostazioni IA mostra 'Usa Grok (sperimentale)' con avviso xAI, spento; nessun crash. NON provato: accensione, conferma, inserimento chiave, modello, costi, font 1,3x/2x, onboarding (non toccato, non propone Grok), nessuna chiamata reale a xAI. Prossimo: L3 prove reali con chiave inserita dall'utente.

- PROVA device 13/11 (profilo Mamma, fake per l'utente; ripristinato): Sedentario -> Molto attivo + 'Non ora' al dialogo piano: TDEE 1.625 -> 2.336 (1354 x 1,725), BMR invariato, 'sul consumo' -15% -> -41%, riga 'Target teorico senza adattamento: 1.985'; il Target grande/anello/Rimangono restano 1.381 (target del piano salvato). Difetto confermato (comportamento da codice DayTargetResolver, non bug di calcolo): il piano non si aggiorna da solo e nulla segnala che e vecchio. Ripristinato Sedentario: Home 1.625/1.381/-15%. Piano NON rigenerato. Proposte: A avviso 'piano non aggiornato' in Home con Rigenera (consigliata), B target da profilo finche piano vecchio.

- CORREZIONE alla diagnosi (13/11): la card 'Il tuo piano alimentare potrebbe essere aggiornato' ESISTE ed e visibile quando il flag pending e acceso, ma sta sotto i conti (visibile solo scorrendo); sbagliavo dicendo che niente segnalava il piano vecchio. Mamma ripristinata (Sedentario, flag pending_3 riportato a false via run-as su profilo fake, Home 1.625/1.381/-15%).
| senior-developer | Fix Home: avviso piano non aggiornato sotto i conti, niente '-41%' con piano vecchio, audit | vedi esito | fix/home-piano-non-aggiornato |
Richiesta utente: poi rilasciare versione aggiornata (install in-place + tag; nessun push/store).

| ux-reviewer | Riesame UX Home/Cibo/Progresso/Altro (sola lettura) | ses_e2a3f8899ffeByf9A3u0owMYwX | repo principale, nessuna modifica |
In corso in parallelo: fix Home (ses_e2a5432ffffe2x0p59F9I4SeSV). Dopo: integrazione, install in-place, prova su Mamma, tag; poi lotti di miglioramento UX scelti dall'utente.

- Fix Home piano non aggiornato: subagente cancellato (ses_e2a5432ffffe2x0p59F9I4SeSV) DOPO gate BUILD SUCCESSFUL 18m (958 test, 0 falliti, DashboardNumbersTest eseguito 17:28); commit 6a0d0d3 fatto da me dal suo worktree, merge 21615a2, tag release-2026-11-13-home-piano. Installato in-place. Verificato su Mamma: con Molto attivo + Non ora, chip 'piano da aggiornare' (niente -41%), riga 'Target del piano precedente: i nuovi dati danno 1.985 kcal', card con Rigenera subito dopo la card calorie. Audit del subagente perso (cancellato prima dell'output). Mamma ripristinata: Sedentario, flag pending a false, Home 1.625/1.381/-15%. Riesame UX: docs/UX_RIESAME_2026-11-13.md (ses_e2a3f8899ffeByf9A3u0owMYwX).

PIANO UX (utente: 'fai tutti e proviamo alla fine'): ipotesi dichiarate e reversibili: sgarro (come mock) al posto di Extra; messaggio motivazionale condizionale; Riserva recupero a 0 compatta (valore mantenuto); Totale di Cibo con C e F (nel menu). Lotti: UX1 Home quick win ∥ UX2 Cibo ordine/totale (avviati); poi UX3 vocabolario target+audit livello (dopo UX1/UX2), UX4 registrazione pasto (MealDetail + righe, dopo UX2), UX5 Home prima card semplificata + BMR/TDEE fuori da Cibo (dopo UX3). Prova a schermo alla fine (font 1,3x e 2x inclusi).
| senior-developer | UX1 Home quick win | (vedi esito) | feat/ux1-home-quickwin (da develop ebcdb4c) |
| senior-developer | UX2 Cibo ordine, totale con C/F, stati | (vedi esito) | feat/ux2-cibo-ordine (da develop ebcdb4c) |

- UX1 FERMATO (ses_e2a294480ffeuLQd4Gzj4d7GW8): commit 8645d96 su feat/ux1-home-quickwin, unito in develop. Gate del subagente BUILD SUCCESSFUL 18m39s, 962 test 0 falliti (XML riletti, HomeProgressHintTest eseguito). Non fatto: etichette 1W/1M/3M/1Y (test device), token alpha per secondo pasto (non adatto). Non verificato a schermo: riga recupero compatta, titolo secondo pasto, header +16dp. androidTest HomeMealCardsLayoutDeviceTest e HomeRecoveryExpectationLayoutDeviceTest da eseguire.

- UX2 FERMATO (ses_e2a2910c5ffeTuQ9V3JJ5PubXk): commit a1ae1fe, unito in develop (conflitto solo in strings.xml tra blocchi UX1/UX2, risolto tenendo entrambi, XML valido, 494 stringhe senza duplicati) = fe66896. Gate del subagente BUILD SUCCESSFUL 27m51s, 966 test 0 falliti (XML riletti). Non verificato a schermo: pulsante Genera/Rigenera con stile letto a runtime, spostamento del pulsante tra card vuota e fondo schermata, C/F nel Totale.

| senior-developer | UX3 vocabolario unico (Obiettivo di oggi, TDEE/BMR in italiano, Extra->Sgarro) + audit livello | (vedi esito) | feat/ux3-vocabolario (da develop fe66896) |
| senior-developer | UX4 registrazione pasto: barra azioni fissa + azione inline con Annulla | (vedi esito) | feat/ux4-registra-pasto (da develop fe66896) |
Dopo: UX5 Home prima card semplificata + BMR/TDEE fuori da Cibo; poi integrazione, install in-place e prova a schermo (font 1,3x/2x).

- UX4 FERMATO PARZIALE (ses_e29fc0c0bffe4NZ5wH4wL8XqX5): commit cc4b091, unito in develop = 17d3695 (nessun conflitto). Gate del subagente BUILD SUCCESSFUL 13m53s, 983 test 0 falliti (XML riletti, MealStatusRecorderTest 13). Fatto: barra azioni fissa nel dettaglio pasto (consumato/saltato + stato + Annulla), azione 1 tocco sulle righe di Cibo con Annulla. NON fatto: azione rapida sulla card Home (11 file > 9): confluisce in UX5. Non verificato a schermo: sovrapposizione snackbar/barra, font grande, riga Cibo con colonna 48dp in piu. Decisione aperta: segnare consumato giorni futuri ammesso come nel dettaglio.

- UX3 FERMATO (ses_e29fc39bfffeGncMLp36RroGhw): commit c0cd7a6, unito in develop = 1703840 (conflitto solo strings.xml con UX4, tenuti entrambi, XML valido, 511 stringhe, nessun duplicato, nessun carattere rovinato). Gate del subagente BUILD SUCCESSFUL 14m49s, 973 test 0 falliti (XML riletti). GLOSSARIO_UI.md creato. Casi dubbi: prompt IA continua a dire 'target abituale' (non toccato); etichette BIA 'BMR/Metabolismo basale' non allineate; Cibo mostra 'Obiettivo del giorno' per giorni diversi da oggi. AUDIT piano stale: (1) bilancio settimanale Home confronta TDEE live con kcal del piano vecchio senza flag [HomeViewModel:266-272] (2) legenda Cibo senza 'piano precedente' [FoodPlanActivity:490-514] (3) weekly review mostra targetKcal del piano vecchio [WeeklyReviewService:119] (4) PDF mostra obiettivo da profilo live e obiettivo del piano salvato senza avviso [ProfileExportService:111,140] (5) BIA Coach/Progresso usano numeri live del profilo. NB: la combinazione UX1-UX4 non ha ancora un gate (lo fanno UX5a/UX5b).

| senior-developer | UX5a Home: card calorie con Dettagli collassati, azione rapida sul prossimo pasto (solo oggi/passati), nota piano da aggiornare sul bilancio settimanale, fusione azioni Rilevazioni se possibile | (vedi esito) | feat/ux5a-home-card (da develop 1703840) |
| senior-developer | UX5b Cibo/review/PDF: Totale senza BMR/TDEE, piano precedente, tocco rapido solo oggi/passati | (vedi esito) | feat/ux5b-cibo-totale (da develop 1703840) |
Decisioni mie (reversibili): tocco rapido solo oggi/passati; Dettagli del calcolo collassati; gate di combinazione UX1-UX4 fatto da UX5a/UX5b.

- UX5b FERMATO (ses_e29df9262ffeVO9N576qaGRaTX): commit 5395844, unito in develop. Gate BUILD SUCCESSFUL 14m13s su base UX1-UX4 = prima verifica della combinazione: 993 test 0 falliti (XML riletti), nessuna correzione di merge necessaria. Non verificato a schermo: card Totale, note PDF (coordinate a vista), nota review globale per profilo.

- UX5a FERMATO (ses_e29dfc0ceffeTsckc49rPtrQ7F): commit aee82c1, unito in develop = ea86adf (nessun conflitto; strings.xml 524 voci valido senza duplicati). Gate del subagente BUILD SUCCESSFUL 16m57s, 991 test 0 falliti (XML riletti). Fatto: Dettagli del calcolo collassati (stato solo in memoria), azione rapida prossimo pasto (solo oggi/passati), nota piano da aggiornare su bilancio settimanale, card Rilevazioni unica (Nuova BIA/Nuove misure/Storico), Apri analisi dati -> tab Progresso. RISCHIO: la Home non apre piu l'hub MeasurementsActivity (Analisi BIA con Coach, impatto sul piano): resta in Altro > Rilevazioni; da decidere se serve un link in Home.
Gate finale combinazione UX1-UX5 in corso: worktree ux-final (integrazione/ux-final = develop ea86adf), log Temp\opencode\gate-uxfinal.log.

- GATE FINALE UX1-UX5 (worktree ux-final = develop ea86adf): BUILD SUCCESSFUL 7m03s, 998 test unitari 0 falliti (XML riletti, eseguiti 19:48 dopo l'avvio del gate), lint ok, assembleDebugAndroidTest compila. Tag release-2026-11-13-ux-candidata su ea86adf (candidata, NON verificata a schermo). APK: ux-final\android\app\build\outputs\apk\debug\app-debug.apk (19:47). Installazione sul Samsung BLOCCATA: adb non vede il device (devices vuoto dopo kill-server). In attesa che l'utente riconnetta il telefono.

- PROVA A SCHERMO UX1-UX5 (APK da ux-final = develop ea86adf, profilo Mamma, font 1,1/1,3/2,0; font ripristinato a 1,1). OK: Home messaggio condizionale, Obiettivo di oggi, Dettagli del calcolo si apre/chiude, recupero compatto, pasto di domani senza controllo rapido, tocco rapido registra (consumate 150, rimangono 1231, anelli aggiornati), Cibo ordine nuovo con Totale dopo i pasti e C/F, dettaglio pasto con barra fissa e Annulla (snackbar sopra la barra), nessun testo fuori schermo o sovrapposto a 1,3x e 2,0x (Home/Cibo/Progresso/Altro). DIFETTI: D1 snackbar Home e Cibo ancorato a bottomNav nascosto dal TabHost (TabHostActivity:125 lo mette GONE) -> compare in alto (y~118-224) sotto la barra profilo e il tocco su Annulla apre il consulente IA: l'annulla dalla Home non funziona; D2 plurale 'Totale di 1 elementi' (strings home_ux5a_note_all_consumed, FoodPlanActivity ~605-610); D3 barra azioni del dettaglio pasto alta ~443px (~158dp), due pulsanti impilati; D4 Cibo mostra il banner 'dati cambiati' (meccanismo diverso dal flag pending) mentre Home no, dopo aver ripristinato il livello: due fonti di 'piano non aggiornato'. NON verificato: dettaglio pasto a 2,0x, TalkBack, landscape, contrasto, androidTest. Consumo di prova su Mamma 'Spuntino della sera' rimosso dal dettaglio (Stato: da registrare).

| senior-developer | UX6 correzioni dalla prova: snackbar ancorato male (D1), plurale (D2), barra dettaglio pasto alta (D3), indagine D4 | (vedi esito) | fix/ux6-correzioni-prova (da develop 31d2bda) |

- UX6 FERMATO (ses_e299dfc36ffe0fTZACyo09ZkRN): commit e9f92da, unito in develop = e1f9a97. Gate: BUILD SUCCESSFUL 10m33s ma la ripresa dopo riavvio server aveva quasi tutto UP-TO-DATE; XML test del 20:37 (dopo l'ultimo sorgente 20:33): 1004 test 0 falliti (riletti io). D4 (indagine): Cibo confronta timestamp profilo/misure con createdAt del piano (FoodPlanViewModel:179-180 + flag), Home solo flag isPending (HomeActivity:419-421): regola unica proposta = impronta degli input nutrizionali salvata con il piano (richiede Room, non fatto). PROVA A SCHERMO UX6 (APK e1f9a97 su Samsung, Mamma): D1 OK in Home (snackbar y 1865-2002 sopra la nav host da 2031, Annulla ripristina senza aprire il consulente IA); D3 OK (barra dettaglio 105 dp, pulsanti affiancati); D2 plurale 'Totale di 2 elementi' OK (caso 1 non riprovato); Cibo: snackbar in basso (y 1866) ma il tocco su Annulla e arrivato in ritardo (due dump uiautomator) e ha aperto il dettaglio: Annulla in Cibo NON verificato. NOTA: dopo l'installazione risulta un piano Mamma v29 generato da Gemini alle 20:42 (3 chiamate IA, ~7k token in) e consumi su Mamma di cui non conosco con certezza l'origine (ipotesi: uso del telefono da parte dell'utente; non provato). Tag release-2026-11-13-ux su e1f9a97 con limiti sopra.

- VERIFICA FINALE UX6 su device (13/11 ~21:10, Mamma): Annulla in Cibo OK (tocco ravvicinato: torna 'Segna come consumato', resta in TabHost); plurale singolare OK ('Totale di 1 elemento segnato come consumato'). Consumi di prova rimossi dall'interfaccia. RESTA 1 riga food_consumptions per Mamma: id 6, mealId 685, planVersionId 28 (piano precedente), 200 kcal, creata alle 20:41:19 prima dei miei test di quell'ora; non ha controllo in UI perche il pasto non e nel piano v29, ma Home la conta (Consumate oggi 200). Da decidere: i consumi legati a una versione di piano precedente devono contare nel totale di oggi? Piano v29 (20:42) di origine non provata. Tag release-2026-11-13-ux confermato.

- Utente: 'si' (alle 3 opzioni). Avviati: UX7 impronta unica 'piano non aggiornato' con colonna nullable inputsFingerprint su meal_plan_versions (Room 21, MIGRATION_20_21) in feat/ux7-impronta-piano da develop 98e01ff; explore (sola lettura) che classifica gli androidTest SICURI/RISCHIO/DISTRUTTIVO per eseguirli sul telefono senza toccare i dati reali (connectedAndroidTest disinstalla l'app: VIETATO; alternativa am instrument). Opzione Grok L3 in attesa della chiave xAI dell'utente.

- Classificazione androidTest (explore ses_e29534e3affecBsyBoc5XOcv79): 60 file; SICURI 6 (WeeklyReviewContractTest, ProfileCalculationServiceWeightTest, PersonalResponseServiceProfileScopeTest, BiaAnalysisAiJobHandlerTest, PdfExportRendererTest, CheatPhotoFlowTest) + 1 con riserva (AiAutomationPreferencesTest); RISCHIO 46 (PTR 7, PREF 2, W 14, R 23); DISTRUTTIVI 7 (DatabaseMigrationTest su myfitai.db reale, QaSeeder e e2e che cliccano SEED_6_MONTHS/clearAllTables, ExportFormatsE2ETest). I test girano nel processo/UID dell'app reale. VIETATO connectedAndroidTest (disinstalla). Comando: adb install -r dei due APK + am instrument -e class <6 classi> com.myfitai.app.test/androidx.test.runner.AndroidJUnitRunner. Esecuzione RINVIATA: adb non vede il device (devices vuoto dopo kill-server); nessuna modifica fatta sul telefono. Prima dell'esecuzione: copia DB+wal+shm+prefs fuori repo in backup-myfitai\pre-androidtest-sicuri.

- UX7 FERMATO (ses_e29536bd2ffeqsnTn5lrjwMlyY): commit 5f97998, unito in develop. Gate BUILD SUCCESSFUL 16m26s (88 eseguiti), 1015 test 0 falliti (XML riletti, PlanFingerprintTest eseguito 22:22). Room 21: colonna nullable meal_plan_versions.inputsFingerprint, MIGRATION_20_21 (ALTER TABLE ADD COLUMN). Verifica OFFLINE sulla copia del DB reale (db3, v20): ALTER applicato su copia -> schema = 21.json (14 tabelle, 0 differenze), conteggi invariati, 28/28 versioni con impronta null. NON ancora installato sul telefono (adb non vede il device) e migrazione Room NON eseguita su device. Rischio dichiarato: review di settimane passate puo mostrare quasi sempre la nota 'piano non aggiornato' (impronta include peso risolto): valutare limite alla settimana corrente. Regola: impronta piano non null => decide il confronto; null => flag isPending; timestamp eliminati.

- UX7 VERIFICATO SU DEVICE (14/11 ~00:30, APK develop 63a6421 da worktree ux7, installazione in-place): backup prima in backup-myfitai\pre-ux7-21 (DB+wal+shm+prefs; include secure_ai_credentials.xml = solo ciphertext, fuori repo). Migrazione Room 20->21 eseguita dal sistema: user_version 21, 15 tabelle con righe identiche, colonna inputsFingerprint presente, 28 versioni con impronta nulla, nessun crash. Piano Mamma rigenerato (Gemini, v30 23:52, impronta e8fdd41860544796, 2 chiamate IA). Scenario livello: Molto attivo + 'Non ora' -> Home 'piano da aggiornare' + card e Cibo banner 'dati cambiati' (entrambi); ripristino Sedentario -> entrambi spariti (Home -15%, Cibo senza banner) = difetto D4 risolto. Mamma ripristinata a Sedentario. TEST STRUMENTATI SICURI eseguiti con am instrument (6 classi, 22 test): OK (22 tests), 0 falliti; dopo: sessione profilo identica, DB versione 21 con conteggi identici al backup pre-test (backup in backup-myfitai\pre-androidtest-sicuri). NON eseguiti: 46 test RISCHIO e 7 DISTRUTTIVI (vedi classificazione). Tag release-2026-11-14-ux7 su 63a6421.

- UX8 (fatto direttamente dall'orchestratore, 3 file): nota 'piano non aggiornato' della Weekly review solo per la settimana corrente (FoodTotalsPresentation.planUpdateNoteVisible con weekStart/today; test planUpdateNote_onlyForTheCurrentWeek). Commit b26308e, develop = 565037b, tag release-2026-11-14-ux8. Gate BUILD SUCCESSFUL 16m27s, 1016 test 0 falliti (XML riletti, test nuovo presente, eseguito 08:16). Telefono: installato. Non verificato a schermo (nessuna review da mostrare provata).

- UX9 (richiesta utente: 'in home non vedo il fabbisogno base'): BMR/BMR BIA/TDEE erano nascosti in 'Dettagli del calcolo' chiuso di default per mia scelta UX5a; ora aperto di default (HomeActivity calorieDetailsExpanded=true, comprimibile), test strumentato HomeCaloriesLayoutDeviceTest adattato. Commit c3f0c3b, develop = 26a0220, tag release-2026-11-14-ux9. Gate BUILD SUCCESSFUL 6m48s, 1016 test 0 falliti (XML 08:47 dopo la modifica 08:44). Installazione sul telefono e verifica a schermo IN ATTESA: adb non vede il device.

- CORREZIONE DATI su richiesta utente (14/11 ~09:12): l'import foto Fitdays di Luca (BIA id 19: 91,3 kg, grasso 21,7%, acqua 52,4 kg, proteine 14,3 kg, BMR 1914, 14/11 08:01) era finito sul profilo attivo Mamma (id 3); alle 08:39/08:40 erano nate due versioni di piano (v31 target 1952, v32 target 2806) e il livello di Mamma era 'Molto attivo'. Backup completo prima in backup-myfitai\pre-correzione-bia-luca (DB+WAL+SHM+prefs). Operazioni su copia locale con WAL unificato (integrity ok, user_version 21): UPDATE bia_measurements SET profileId=1 WHERE id=19; DELETE meal_plan_versions id 31,32 (FK cascade, 0 consumi/sgarri collegati, 0 orfani); UPDATE user_profile SET activityLevel='Sedentario' WHERE id=3. File riscritto sul telefono con app ferma e WAL/SHM rimossi. Verifica a schermo: Mamma BMR 1.354 / TDEE 1.625 / obiettivo 1.381 / -15% (come prima), nessun crash; Luca BMR BIA 1.914, TDEE 2.297 (Sedentario x1,20), obiettivo 2.242 dal piano, 1.952 da profilo. Luca: BIA 18 (era 17), Mamma BIA 1. Costi IA delle 2 generazioni restano registrati (reali). Aperto: livello di attivita di Luca (Sedentario dal 13/11 16:36) e piano di Luca non rigenerato.

- UX10 (Home: Dettagli del calcolo subito sotto 'Obiettivo di oggi', prima degli anelli): commit 53d281a, develop = 96256d0, tag release-2026-11-14-ux10. Gate BUILD SUCCESSFUL 8m30s; testDebugUnitTest UP-TO-DATE (modifica solo layout, XML invariato: 1016 test 0 falliti del gate UX9), assembleDebug/lint rieseguiti, androidTest compila (UP-TO-DATE). Installato in-place; verificato a schermo su Luca: Target y1196, Dettagli y1516, BMR y1674, BMR BIA y1807, TDEE y1941 = tutto nella prima schermata (nav da y2025); nessun crash.

- Luca (14/11 ~09:25, su richiesta utente 'si'): livello impostato a 'Molto attivo' dal profilo e piano rigenerato con Gemini (v33, 3 chiamate IA 09:23; target 2806 kcal, proteine 182,6 g, C 378,7 g, F 62,4 g; impronta 675a56a4dc96ec07; ATT=2 TOL=4). Home Luca: BMR 1.914, BMR BIA 1.914, TDEE 3.302 (x1,725), obiettivo di oggi 2.806 (da profilo 2.807), -15% sul consumo, nessun avviso 'piano non aggiornato'. Mamma resta Sedentario (piano v30, 1.381). Nessun backup nuovo (dati modificati solo da UI app). Non verificato: qualita dei pasti del nuovo piano di Luca (macro dichiarati dal modello, solo avviso locale).

- UX11 (utente: 'manca dove mettere la chiave per grok'): il campo 'Chiave API Grok (xAI)' esisteva (Altro > Impostazioni IA > card Chiavi API, sotto Gemini/OpenAI, ~1600 px dall'interruttore) ma nulla lo indicava. Aggiunti avviso + pulsante 'Vai al campo della chiave' sotto l'interruttore quando Grok e acceso e senza chiave (aiGrokMissingKeyGroup, scrollToGrokKey). Commit ed3211a, develop = 3b359a7, tag release-2026-11-14-ux11. Gate BUILD SUCCESSFUL 7m24s, 1016 test 0 falliti (testDebugUnitTest eseguito), androidTest compila. Telefono: grok_enabled=true gia nelle preferenze, nessuna chiave. Installazione e verifica a schermo IN ATTESA: adb non vede il device.

- L'utente ha incollato in chat una chiave con prefisso gsk_ (Groq, NON xAI) dicendo 'io ho quella per grok': NON usata, NON verificata, NON salvata in nessun file/log (ne qui); consigliata revoca e rigenerazione perche esposta in chiaro in chat. Il provider Grok attuale parla con api.x.ai: la chiave Groq darebbe 401 e la invierebbe a terzi. Decisione (ipotesi reversibile): nuovo provider Groq sperimentale e spento. | senior-developer | Groq L1 provider backend (chat completions OpenAI-compatibile, strict su gpt-oss ecc.) | (vedi esito) | feat/groq-l1-provider (da develop) |

- Groq L1 FERMATO (ses_e26b9e5c0ffe3WxDGoiOjBv7jq): commit f8b6af3, develop = aae9ed5. 1048 test 0 falliti (XML riletti, GroqProviderTest 19 + GroqRuntimeTest 13). Modelli: gpt-oss-20b (default, 0.075/0.30, solo testo), gpt-oss-120b (0.15/0.60, solo testo), qwen3.8-27b (preview, immagini, 0.80/4.00). Foto con gpt-oss: errore locale MODEL_UNAVAILABLE senza rete. Nessuna chiamata reale. Nessuna chiave in file/log (verificato). Gap noto: contratti nativi con campi opzionali (weekly-meal-plan, plan-adjustment) potrebbero tornare null con strict; oggi i contratti registrati usano la busta compatta {data:string}.
| senior-developer | Groq L2 UI: interruttore, chiave con avviso e pulsante, modello, costi, avviso foto | (vedi esito) | feat/groq-l2-ui (da develop aae9ed5) |

- UX12 (richiesta utente: 'lasciamo solo gemini e openai, il resto togliamo' + 'in home ancora non vedo il basale delle calorie senza sport'): RIMOSSI Grok (xAI) e Groq: revert dei merge d3d0a43, 9460efd, f6deaee, 3b359a7 (UX11), aae9ed5 con -X ours + pulizia di 41 stringhe ai_*grok*; nessun riferimento residuo in src (kt/xml); cartelle 06-ai/providers/grok e groq eliminate. Groq L2 (ses_e26a172ddffesLAt4fq3TEONQJ) fermato senza gate/commit. Errore mio durante il revert: risoluzione conflitto che toglieva anche stringhe UX, annullata e rifatta in modo selettivo. NUOVO: Home 'Dettagli del calcolo' con riga 'Fabbisogno base, senza sport (BMR x 1,20)' = BaseCalories.withoutSport (test BaseCaloriesTest 2 casi). Interpretazione mia di 'senza sport' = fattore Sedentario 1,20 (reversibile). Commit f7df212 + 7543aae, develop = e2b7fb7, tag release-2026-11-14-ux12. Gate BUILD SUCCESSFUL 8m11s, 986 test 0 falliti (testDebugUnitTest eseguito, BaseCaloriesTest presente). Installato in-place: Luca BMR 1.914, BMR BIA 1.914, Fabbisogno base senza sport 2.297 (y1971-2025, in fondo alla prima schermata), TDEE sotto il bordo; Impostazioni IA: solo Gemini e OpenAI, Gemini attivo (selected_provider tornato GEMINI), nessun crash. La chiave gsk_ incollata in chat era stata inviata ad api.x.ai dalla verifica del campo Grok (non salvata): revoca consigliata. Backup pre-install in backup-myfitai\pre-install-groq-l1.

- UX13 (procedi): Home nasconde la riga 'Metabolismo a riposo da BIA' quando coincide col BMR locale (BiaBmrRowRule.visible, 4 test). Commit su fix/ux13-bmr-duplicato, develop = 9a2e48e, tag release-2026-11-14-ux13. Gate BUILD SUCCESSFUL 14m16s, 990 test 0 falliti (BiaBmrRowRuleTest eseguito). Installato in-place; verificato su Luca: Obiettivo y1196, BMR y1674, Fabbisogno base senza sport y1807, TDEE y1941 (< nav y2025) = tutto nella prima schermata; nessun crash.

- VERIFICA FINALE SEMPLIFICAZIONE (14/11): ProfileBackupRestoreTest eseguito su device con am instrument (3 test: restoreV3WithoutLegacyKeys, restoreUnknownSchema_isRejected, restoreV2WithWorkouts_importsEverythingElseAndWritesNoWorkouts) = OK (3 tests). Backup prima in backup-myfitai\pre-restore-test; il test svuota i puntatori del profilo attivo (file myfitai_profile_session.xml): ripristinato dal backup (Luca id 1); DB reale: 15 tabelle con righe identiche prima/dopo; app riaperta senza crash. DEVELOPMENT_PLAN.md: voce Semplificazione attivita segnata [x] con elenco di verificato/non verificato. Il test APK com.myfitai.app.test resta installato (innocuo; non disinstallato per vincolo).

- BRAINSTORMING (utente, 14/11): dieta redatta/validata da specialisti, revisione efficiente di IA e misure, individuazione di situazioni critiche (grasso addominale/viscerale, muscolo scheletrico poco sviluppato). Delegato a prodotto/ricercatore-prodotto (sola lettura, background): ses vedi esito. Nessuna modifica al repository.

- BRAINSTORMING (14/11) CAMBIO: l'utente non vuole specialisti umani ma che lo 'specialista' sia l'IA stessa (pannello di agenti specializzati che scrivono la dieta, valutano IA e misure, individuano situazioni critiche). Ricerca (ses_e25d6d4d5ffeZki7e224GuQ6xb) ri-orientata con follow-up: architettura pannello IA, ancoraggio a linee guida, motore flag deterministico, valutazione/golden set, quadro normativo senza professionista.

- Ricerca specialista=IA CONCLUSA (ses_e25d6d4d5ffeZki7e224GuQ6xb): sintesi in docs/RICERCA_SPECIALISTA_IA_2026-11-14.md (fonti del ricercatore, non riverificate). Raccomandazione: MVP opzione B (macro da database, motore flag deterministico, KB come dati, revisore di famiglia diversa, arbitro in codice), claim 'assistente'. Nessun lavoro di sviluppo avviato: attese le decisioni dell'utente.

- DECISIONI (ipotesi dichiarate, reversibili, utente 'come procediamo'): claim 'assistente' (non 'specialista') nei testi in un lotto successivo; perimetro adulti sani con blocchi duri; pavimento calorico = quello gia in SafetyCalorieFloor (nessuna modifica); revisore di famiglia diversa e macro da database in lotti successivi; dati e consenso della madre ancora da raccogliere. AVVIATO Flag L1 (motore deterministico di segnalazioni corporee, solo dominio+test, niente UI).
| senior-developer | Flag L1 motore segnalazioni corporee | (vedi esito) | feat/flag-l1-motore (da develop) |

- Flag L1 FERMATO (ses_e25cd32ecffedwQXovEZhKSoxu): commit 2853eaf, develop = 705c5c0. 1047 test 0 falliti (XML riletti: BodyFlagEngineTest 48, Golden 4, Vocabulary 5, eseguiti 14:31). Regole a-h implementate (WHtR 0,5/0,6, vita U94/102 D80/88, BMI info, viscerale in salita+WHtR, calo muscolare >=1,5 kg con eta>=60 o BMI<18,5, calo peso >1 kg/sett x2, calo massa magra, %grasso info). Soglie interne mie da validare (ATTENZIONE vs INFO, 1,5 kg, finestre recenza). Claim 'nutrizionista/dietista' da cambiare in 'assistente': BiaAnalysisService:57, BiaAnalysisAiJobHandler:130 (DEFAULT_SAFETY_NOTE), BiaCoachT4Test:290-291, BiaAnalysisContractTest:627, NUTRITION_AGENT.md:56, NUTRITION_AGENT_GEMINI.md:63, FOOD_CONSTRAINTS_LIMITS.md:4. DATI REALI (backup, 14/11): Mamma vita 99 cm/164 cm = WHtR 0,60 (scatterebbe WHTR_HIGH + vita D>=88), 1 BIA senza composizione; Luca vita 90 cm/186 cm = 0,48 (nessun flag), 18 BIA con condizioni di misura tutte non dichiarate (qualita LOW: nessun flag di trend BIA).
| senior-developer | Flag L2 card 'Segnali da approfondire' in Progresso + BodyFlagService | (vedi esito) | feat/flag-l2-ui (da develop 705c5c0) |
Dopo: Flag L3 = BIA Coach spiega i flag (non li decide) + claim assistente.

- Flag L2 FERMATO (ses_e25b9bb8dffekfppYY7YSNOQqJ): commit 6bceb13, develop = 41c8237, tag release-2026-11-14-flag-l2. Gate BUILD SUCCESSFUL 9m35s (primo giro fallito per import fuso, corretto), 1066 test 0 falliti (XML 15:00 dopo ultimo sorgente 14:56; BodyFlagServiceTest 11, BodyFlagTextsTest 8). Card 'Segnali da approfondire' in Progresso (BodyFlagService, BodyFlagTexts, item_body_flag.xml, blocco strings FLAG L2). VERIFICATO A SCHERMO (APK installato in-place): Luca = BMI 26,4 info + riga 'tendenze BIA richiedono condizioni dichiarate'; Mamma = WHtR alto (vita 99/164 = 0,60) DA APPROFONDIRE + vita alta (88 cm D) DA APPROFONDIRE + BMI 29,7 info; dialog 'Perche questo segnale' (soglia e fonte, limiti, cosa fare) aperto sulla vita. Profilo attivo ripristinato a Luca (id 1). NON verificato: font 1,3x/2,0x, TalkBack, fonti non riverificate sui testi primari (NICE/OMS citate in modo generico), dialog del rapporto vita/altezza. Nota: sul profilo Luca risulta 'Analisi progressi IA ultima esecuzione 14 Nov 11:06' non avviata dall'orchestratore (origine non provata).

- Flag L3 FERMATO (ses_e25962c8bffeOLViQdZehm80eF): commit a9a34d4, develop = 1ac726a, tag release-2026-11-14-flag-l3. Gate BUILD SUCCESSFUL 6m26s, 1080 test 0 falliti (XML 15:29 dopo ultimo sorgente 15:25; BodyFlagCoachTest 14). Nessun cambio ai contratti JSON; validatore locale (flag inventati, termini diagnostici, numeri cm estranei); claim 'assistente' nei 7 punti; DEFAULT_SAFETY_NOTE nuova (la vecchia resta LEGACY per risultati salvati). NON fatto: analisi progressi (PA1). PROVA REALE (APK installata, Gemini): Luca BIA Coach OK in 8 s (Da monitorare, nessun segnale inventato, 'Obiettivo da profilo' 2807); Mamma 1o tentativo respinto dalla regola ESISTENTE INSUFFICIENT_DATA_MAX_TWO_ACTIONS (non il nuovo validatore, outcome BUSINESS_VALIDATION_FAILED), Riprova OK in 8 s: 'Dati insufficienti' con una sola BIA, il coach NON menziona il flag vita/WHtR (nessun obbligo di spiegarlo: i flag restano nella card di Progresso). 4 chiamate Gemini di prova (costo trascurabile). Profilo attivo ripristinato a Luca. Non verificato: il blocco FLAG_APP nel prompt reale (nessun log del prompt), validatore su una risposta che spiega un flag.

- Utente (14/11): 'non ha blocchi' = la madre non ha patologie/farmaci da dichiarare: nessun blocco specifico per lei; resta rete di sicurezza generale (eta<18, gravidanza, DCA) da fare dopo. Passo 2 (macro dal database alimentare): avviato progetto di design (architetto-software, sola lettura, background): ses vedi esito.

- Progetto macro da database CONCLUSO (ses_e25801ecfffeWeVWZk4ga8gVad): docs/PROGETTO_MACRO_DA_DATABASE_2026-11-14.md. Lotti L0-L8, prima spike S1 (JVM, sola lettura). Nessuna modifica al codice. Decisioni utente in attesa (validatore B consigliata, fonte USDA+CREA, ecc.).

| senior-developer | Spike S1: macro dichiarati vs calcolati (USDA mini-catalogo, backup v2 reali in sola lettura, riparabilita), solo tools/spike-s1 + docs | (vedi esito) | spike/s1-macro-da-database (da develop) |

- Spike S1 CONCLUSO (ses_e25775ac4ffeBQ20KGDInu03vk): commit eb34487 unito in develop (solo tools/spike-s1 + docs/SPIKE_S1_RISULTATI.md; nessun dato personale nei file: verificato con git grep). Esiti: copertura righe 94,1% (voci USDA dirette) / 99,2% con segnaposto bresaola; deviazione dichiarato-calcolato: giorno kcal mediana +2,6% p90 +22,1% max +32,3% (profilo P2 +17,3% sistematico), grassi giorno p10 -20,4%; riparazione: kcal entro +-3% 35,4% -> 100%, +-1% 98,7%; proteine +-3% 89,9% (FAIL soglia 98% con limiti del progetto; 96,2% con limiti 0,5x-2x); tempi 6-11 ms/giorno (Python). Raccomandazione: blocca solo kcal calcolate +-3% dopo riparazione; proteine obiettivo di riparazione non bloccante; carbo/grassi informativi; shadow mode 2-4 settimane + fonte italiana per salumi, pane, latticini, proteine in polvere, frutti di bosco.

- DECISIONI utente (14/11) sul progetto macro: (1) validatore = consigliato dallo spike: blocca SOLO sulle kcal calcolate +-3% (+-4% retry) dopo riparazione; proteine = obiettivo di riparazione non bloccante; carbo/grassi informativi; (2) fonte = USDA (nessuna richiesta a CREA per ora; Ciqual ripiego futuro); (3) piani esistenti: ipotesi mia reversibile = restano con i numeri dichiarati marcati 'stima del modello', nessun ricalcolo. AVVIATI in parallelo L1 catalogo, L2 nucleo di calcolo, L4 persistenza v22 (3 worktree).

| senior-developer | Macro L1 catalogo alimenti (USDA CC0, 250-350 voci, loader, test integrita) | ses_e25635b61ffekIDgLIvEmlJwJS | feat/macro-l1-catalogo |
| senior-developer | Macro L2 nucleo di calcolo e riparazione (policy: blocca solo kcal) | ses_e25631ac4ffekIskzFWCPjJocI | feat/macro-l2-calcolo |
| senior-developer | Macro L4 persistenza Room v22 + mapper unico | ses_e2562dde1ffemODQWLC9crAEem | feat/macro-l4-persistenza |
Dopo: L3 contratto MFP2 e integrazione (richiede L1+L2+L4 uniti).

- Macro L4 FERMATO (ses_e2562dde1ffemODQWLC9crAEem): commit 17fffeb, develop = 26d4510. Gate BUILD SUCCESSFUL 9m33s, 1084 test 0 falliti. Room 22 additiva; verifica OFFLINE su copia del DB reale (v21): schema = 22.json (15 tabelle, 0 differenze), righe invariate, 732 pasti DECLARED, 2247 ingredienti foodId null. Migrazione Room NON eseguita su device (si fara con la build combinata). Limiti: backup/restore non conserva i nuovi campi; scaleMeal e alternative dalla cronologia lasciano il pasto DECLARED con totali non allineati allo snapshot (L5a/L5b); custom_foods senza FK (convenzione).
- Macro L2 FERMATO (ses_e25631ac4ffekIskzFWCPjJocI): commit 8946e5e, merge in develop. Gate BUILD SUCCESSFUL 14m31s, 1131 test 0 falliti (51 nuovi). Golden 60 giorni: kcal +-3% e +-1% 100% sui fattibili, proteine +-3% 94% (100% se target coerenti), infeasible 100% riconosciuti; tempi mediana 0,16 ms. Pesi 4/2/1/1 con riprese a caldo; stato NOT_CONFIGURED/NOT_CONVERGED extra. Pesi non calibrati su dati reali.

- Macro L1 FERMATO (ses_e25635b61ffekIDgLIvEmlJwJS): commit 57ad8d8, develop = 94429fe. Gate BUILD SUCCESSFUL (secondo giro; il primo FAILED per 2 controlli sui dati: tag e note), 1108 test 0 falliti sul suo ramo (FoodCatalogTest 14 + FoodCatalogAssetTest 14, eseguiti 16:43, skipped 0, campione 20% vs CSV USDA eseguito con MYFITAI_USDA_DIR). Catalogo 374 voci (A 290, B 78 proxy, C 6 = bresaola, prosciutto_crudo, speck, pane_carasau, stracchino, mascarpone con nota 'da verificare con etichetta'), asset 198 KB, USDA SR Legacy 2018-04 + Foundation 2025-12-18, CC0, registrato in ASSET_SOURCES.md; tutte le 80 voci dello spike presenti. Limiti: resa cotto/crudo solo cereali/legumi; proxy e stagionalita curati a mano; bicchiere=200 ml convenzione. develop = L1+L2+L4: combinazione ancora SENZA gate (lo fara L3). File vuoti nella radice del repo principale (FoodPlanActivity.kt, MealDetailViewModel.kt: residui di sessioni precedenti, non tracciati).

- Rimossi 2 file vuoti non tracciati nella radice (FoodPlanActivity.kt, MealDetailViewModel.kt).
| senior-developer | Macro L3 contratto MFP2 + integrazione (dietro interruttore useFoodCatalogPlan default false; primo gate della combinazione L1+L2+L4) | (vedi esito) | feat/macro-l3-contratto (da develop 7fded47) |

- Macro L3 FERMATO (ses_e254210bcffe8dZvStJUGaP8ZA): commit 9697583, develop = 1037050, tag release-2026-11-14-macro-l3. Gate (2o giro; 1o FAILED per un test sbagliato sul latte in cucchiai) BUILD SUCCESSFUL, 1193 test 0 falliti, 1 saltato (FoodCatalogAssetTest campione USDA: richiede MYFITAI_USDA_DIR), XML 17:33 = ultimo sorgente 17:33. PRIMA verifica della combinazione L1+L2+L4+L3: compila e passa senza correzioni di merge. MFP2 dietro useFoodCatalogPlan (ai_settings.xml, chiave booleana use_food_catalog_plan, default false; MFP1 invariato). Prompt MFP2: sistema 6.629 car + CAT 12,7-22,3k car (P1 senza vincoli 337 voci ~7,2-9,7k token totali). Non fatto: custom_foods nel prompt, pasti consumati bloccati, test servizio flag off (nessun Robolectric). Rischi: displayDose latte in cucchiai, id fuori stagione non rifiutati, +5-10k token per generazione, comportamento Gemini/OpenAI con MFP2 NON verificato. Installazione sul telefono e prova reale (migrazione 21->22, flag acceso, generazione Gemini) IN ATTESA: adb non vede il device; backup pre-room22 fallito (file vuoti) e cancellato.

- PROVA REALE MFP2 su telefono (14/11, Gemini gemini-3.5-flash-lite, profilo Luca, flag use_food_catalog_plan=true): migrazione Room 21->22 OK (versione 22, righe invariate, integrity ok). Rigenerato piano v6 (id 36): 3 tentativi (ATT=3, TOL=4), source GEMINI, catalogVersion=1, nutritionSource COMPUTED, 2 giorni (oggi e domani). Kcal giorni 2806 e 2805 (target 2806,6: scarto 0%), proteine 181,5 e 184,0 (target 182,6), carbo 366,8/374,3 (target 378,7), grassi 72,9/68,1 (target 62,4: +16,9% e +9,2%, informativo). Riparazione: il modello aveva proposto 3213 kcal/222,9 g P (giorno 1) e 2971/206,5 (giorno 2); l'app ha ritoccato 17 e 13 voci (es. pollo 200->120 g, salmone 200->120, patate 250->370). Ingredienti con snapshot (foodId, kcal, macro), dosi pratiche (3 fette, 1/4 cucchiaio cannella, 3/4 cucchiaio olio), creatina come integratore a 0 kcal. Costo: 3 chiamate ~18,5k token input + ~1,4k output (~0,0024 USD ciascuna) vs MFP1 3,3k/1,9k: ~5x ma centesimi. Osservazioni: titolo pasto non sempre coerente con ingredienti dopo riparazione (titolo 'zucchine' ma ingrediente zucca; 'riso basmati' ma nome catalogo 'Riso bianco'); dosi 3/4 cucchiaio di olio meno naturali di 2 cucchiaini; motivi dei 2 tentativi respinti non persistiti. adb ha perso il device dopo la lettura DB.

- 15/11 verifica schermate piano calcolato su telefono: Cibo e dettaglio pasto mostrano macro calcolati (589 kcal, 34,1 P, 67,5 C, 20,5 G; ingredienti con '2 uova', 87 ml). Difetti: card pasto con testo spezzato a meta' parola (font_scale 1,1: 'Colaz/ione'); immagini ingrediente tutte uguali (placeholder riso, preesistente); dettaglio senza kcal per ingrediente.
| senior-developer | Macro L3b rifiniture (titoli coerenti, dosi naturali, log tentativi rifiutati) | ses_e238c8725ffevzTYzbJBl2duG1 | feat/macro-l3b-rifiniture |
| senior-developer | UX card pasto testo spezzato | ses_e238c64b2ffeT727Ac20bZluds | fix/ux-card-pasto |
