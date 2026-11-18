# Rete di sicurezza generale per il piano alimentare (architetto-software, 15/11/2026)

Stato: ANALISI SOLA LETTURA + PROPOSTA. Nessuna modifica al codice. Non è consulenza medica né legale. Le fonti web sono state consultate il 15/11/2026 solo come riferimenti pubblici. Le soglie senza fonte verificata sono marcate DA CONFERMARE. Perimetro dichiarato dall'utente (14/11): la madre non ha patologie o farmaci da dichiarare (`docs/prodotto/stato.md:448`). Questa rete serve a proteggere chiunque usi l'app oggi o in futuro.

I percorsi sono sotto `android/app/src/main/java/com/myfitai/app/`, abbreviati `app/`.

Nota di processo: il subagente non aveva uno strumento di scrittura locale; il testo è stato salvato dall'orchestratore senza modifiche di contenuto. Due punti sono dedotti dal codice e non verificati con test: il piano sotto il pavimento per la tolleranza e il pavimento sopra il TDEE.

## 0. Sintesi
- **Cosa protegge oggi.** Quattro cose:
  - il pavimento calorico 1200/1500 e il BMR;
  - un deficit limitato per costruzione al 12,5-17,5% del TDEE;
  - il controllo di allergie e intolleranze su elenco;
  - un `SAFETY_FLAG` che però vale SOLO per l'analisi BIA e NON blocca la generazione del piano.
- **Cosa manca nel profilo.** Non ci sono dichiarazioni di gravidanza, patologie, farmaci o DCA.
- **Effetto concreto.** Oggi un profilo di 14-17 anni, o con BMI < 18,5, riceve un piano dimagrante normale. Una gravidanza non è rilevabile.
- **Lacune critiche:**
  - C1 minorenni (14-17 ammessi);
  - C2 gravidanza e allattamento (nessun dato);
  - C3 patologie e farmaci (nessun campo; proteine sempre ≥ 1,2 g/kg anche con malattia renale);
  - C4 BMI basso con obiettivo di dimagrimento (solo nota al coach BIA);
  - C5 DCA (nessuno screening; il recupero sgarro riduce le calorie dei giorni dopo);
  - C6 tre definizioni diverse di "calo rapido";
  - C7 nessun gate unico prima della chiamata IA.
- **Lotto consigliato per primo:** S1, un gate deterministico puro (dominio e test, senza UI né Room). Non richiede decisioni irreversibili ed è la base dei lotti successivi.

## 1. Inventario VERIFICATO di ciò che l'app fa oggi
Legenda: IMPLEMENTATO = codice che blocca o limita in modo deterministico. SOLO PROMPT = istruzione al modello, non verificata dal codice. PARZIALE. MANCA.

| Area | Stato | Evidenza (file:riga) | Note |
|---|---|---|---|
| Età valida per i calcoli | IMPLEMENTATO (solo range) | `app/domain/calculation/LocalCalculationEngine.kt:13-14` (MIN 14, MAX 120), `:164` (Mifflin solo nel range); `app/domain/profile/ProfileAgeRule.kt:15-16,34-42`; `app/domain/food/PlanPrerequisites.kt:43-53,60-65`; `NutritionPlanGenerationService.kt:111-113` | È un gate di AFFIDABILITÀ della formula, NON di sicurezza: 14-17 anni passa. |
| Minorenni (< 18) | MANCA per il piano; solo flag BIA | `app/domain/ai/AiUserContext.kt:32,54` (`AGE_UNDER_18`, solo contesto BIA); `app/domain/body/BodyFlagEngine.kt:24` (motore flag solo adulti, `NOT_ADULT`) | Piano, sgarro e creatina in SPORT mode non controllano l'età ≥ 18 (`NutritionPlanContract.kt:208-211`, `SportsNutritionClassifier.kt:13-18`). Nessuna schermata "solo adulti" trovata. |
| Anziani | PARZIALE | `AiUserContext.kt:33,55` (`AGE_75_PLUS`, solo BIA, soglia 75 senza fonte); `app/domain/calculation/MacroPolicy.kt:33,65-66` (proteine sempre ≥ 1,2 g/kg) | Nessuna regola per ≥ 65 sul deficit. Le proteine ≥ 1,2 g/kg coprono già PROT-AGE (sez. 2). |
| Gravidanza / allattamento | MANCA | nessun campo in `app/data/local/entity/Entities.kt:10-27` (`UserProfileEntity`); `AiUserContext.kt:25` lo dichiara ("non sono dati noti all'app") | SOLO PROMPT per la BIA: `app/domain/body/BiaAnalysisService.kt:59`. Nel prompt del piano (`NutritionPlanGenerationService.kt:579-587`) non c'è nulla. |
| Patologie e farmaci dichiarati | MANCA | `UserProfileEntity` senza campi; `app/domain/food/DietaryProfile.kt:9-16` ha solo cibi, allergie, intolleranze, stile, note libere | Le `notes` vanno nel prompt come preferenza SOFT (`DietaryProfile.kt:83-91`, prompt `NutritionPlanGenerationService.kt:584`). Una dichiarazione scritta lì non produce blocchi. |
| Allergie / intolleranze / esclusioni | IMPLEMENTATO (su elenco) | `DietaryProfile.kt:166-229` (`FoodConstraintValidator`), usato in `NutritionPlanContract.kt:229`, `CheatAdjustmentContract.kt:136`, `MealAlternativeService.kt:114,121,158,207,264`, `NutritionAdviceContract.kt:71`, `app/domain/food/catalog/FoodCatalog.kt:119-127`. Profilo illeggibile = blocco fail-closed (`NutritionPlanGenerationService.kt:108`, `DietaryProfile.kt:95-100`) | Dichiarato "controllo su elenco, non garanzia medica" (`DietaryProfile.kt:103`, `06-ai/providers/shared/FOOD_CONSTRAINTS_LIMITS.md`). Limiti noti: nichel, solfiti, voci fuori elenco solo per nome. |
| Soglia kcal minima | IMPLEMENTATO | `app/domain/calculation/SafetyCalorieFloor.kt:15-16,23-24,27-34` (max(BMR, 1200 donne / 1500 altri e sesso mancante)); applicata in `LocalCalculationEngine.kt:119-123`, `AdaptiveNutritionTargetEngine.kt:100`, `CalorieRecoveryEngine.kt:56,71,113`, `NutritionPlanGenerationService.kt:136-137,149,199`; test `SafetyCalorieFloorTest.kt:42-48` | Vale solo se il fattore obiettivo è < 1 (`SafetyCalorieFloor.kt:27-28`: dimagrimento e ricomposizione). Il commento la dichiara "IPOTESI REVERSIBILE da confermare" (`:9-12`). |
| Massimo deficit | IMPLEMENTATO per costruzione, NON come validatore | fattori `LocalCalculationEngine.kt:174-180` (dimagrimento 0,85; ricomposizione 0,95); adattamento 0,825-0,875 e 0,925-0,975 (`AdaptiveNutritionTargetEngine.kt:136-146`, passo 0,025 `:150`); recupero sgarro max -10%/giorno (`CalorieRecoveryEngine.kt:19,70,112`); tetto "mai raggiungere il mantenimento" (`NutritionPlanContract.kt:176-178,196-201`, `NutritionPlanGenerationService.kt:207-210`) | Deficit massimo dichiarato in `06-ai/agents/BODY_CONTEXT_AND_ADAPTIVE_TARGETS.md:11`: 12,5-17,5%. Col recupero sgarro il giorno può scendere a circa 0,85 × 0,9 = 0,765 del TDEE (deduzione dal codice, non testata). Nessun cap assoluto in kcal/giorno. |
| Tolleranza sotto il pavimento | LACUNA DEDOTTA | tolleranza ±3% primo tentativo, ±4% retry (`NutritionPlanGenerationService.kt:204-205,300`, `NutritionPlanAssessment`) | Il pavimento vale sul TARGET. Un piano accettato a -3/-4% può stare sotto 1200/1500 (calcolo: 0,96 × 1200 = 1152). DA VERIFICARE con un test. |
| Pavimento sopra il TDEE | POSSIBILE CONFLITTO | `SafetyCalorieFloor.kt:23-24` + `NutritionPlanContract.kt:196-201` | Se TDEE < pavimento, il target sale oltre il TDEE ma ogni giorno deve restare < TDEE. La generazione può fallire con `DEFICIT_PLAN_REACHES_MAINTENANCE`. DA VERIFICARE con un caso sintetico. |
| BMI basso con obiettivo dimagrimento | PARZIALE (solo BIA) | `AiUserContext.kt:34,57` (`LOW_BMI_WEIGHT_LOSS` < 18,5, solo se obiettivo WEIGHT_LOSS); uso in `app/domain/body/BiaAnalysisReport.kt:179-186,199`; validatore coach `app/domain/body/BiaAnalysisContract.kt:280-284`; test `BiaAnalysisReportTest.kt:303,341`. Flag informativo BMI `app/domain/body/BodyFlagThresholds.kt:49` | Il piano alimentare NON legge il flag: nessun blocco. RECOMPOSITION non è coperta dal flag. |
| Perdita di peso troppo rapida | PARZIALE, 3 DEFINIZIONI | (a) `AiUserContext.kt:35-37,65-74`: > 1,0% del peso/settimana, span ≥ 14 gg in finestra 28 gg, solo BIA; (b) `AdaptiveNutritionTargetEngine.kt:71-75,89`: ≤ -1,0%/settimana con evidenza ≥ 21 gg, effetto = `REDUCE_DEFICIT` di soli 2,5 punti; (c) `BodyFlagThresholds` (`docs/MOTORE_SEGNALAZIONI_CORPOREE.md:39`): > 1,0 kg/sett per 2 sett consecutive | In nessun caso il piano si ferma o rinvia al medico. Con l'adattamento il fattore resta fra 0,825 e 0,875. |
| Segnali DCA | MANCA (nessuno screening, nessun gate) | `app/domain/body/BodyFlagCoach.kt:11-25` (vocabolario: i DCA si nominano solo per rimandare al medico); `BiaAnalysisService.kt:59` (solo prompt BIA); test `BodyFlagCoachTest.kt:259-260` | Nessuna domanda sul rapporto con il cibo. Il recupero sgarro (`CalorieRecoveryEngine.kt:18-19`) riduce le kcal dei giorni successivi: meccanismo compensatorio da non offrire a chi è a rischio DCA. Il prompt dice "No punitive compensation" (`NutritionPlanGenerationService.kt:585`) ma è SOLO PROMPT. |
| Obiettivi incompatibili col profilo | MANCA | obiettivo scelto liberamente (`LocalCalculationEngine.kt:26-32`, `ProfileCalculationMapper.goal :279-286`) | Nessun controllo obiettivo × BMI/età/dichiarazioni. |
| Proteine | IMPLEMENTATO | `MacroPolicy.kt:31-36,52-55,65-66`: ≥ 1,2 g/kg, cap 2,0 (dimagrimento/ricomposizione/massa) o 1,8 g/kg, e 30% kcal | Nessuna differenza per età o malattia renale (sez. 2: KDIGO 0,8 g/kg). |
| Ambito dell'IA | SOLO PROMPT | `app/ai/AiRuntimeService.kt:77` (`SCOPE:NUTRITION_ONLY`, "No medical..."), `06-ai/agents/NUTRITION_AGENT.md:6`; coach BIA `BiaAnalysisService.kt:55` | Nessun controllo locale che il modello rispetti l'ambito sul piano. |
| Avvertenze UI | PARZIALE | `app/ui/SettingsActivity.kt:332` (nota generica); `app/ui/PhysicalEvolutionActivity.kt:239`; coach BIA `BiaAnalysisAiJobHandler.kt:150`, `strings.xml:575,593-596` | Nessun consenso o dichiarazione al primo avvio, nessun avviso sul piano alimentare. |
| Validatore finale | IMPLEMENTATO ma non clinico | `NutritionPlanContract.kt:158-232`; `NutritionIntegrityValidator.kt:134` (informativo, mai bloccante: `NutritionPlanGenerationService.kt:307`) | Valida struttura, kcal entro tolleranza, vincoli cibo. Non valida pavimenti per giorno, cap deficit o condizioni di salute. |

Lo stato è già noto al prodotto: `docs/prodotto/stato.md:102` lascia aperta la domanda "minorenni/gravidanza/disturbi alimentari" e `:519` la riporta tra le cose da fare.

## 2. GAP rispetto a una rete di sicurezza ragionevole (app consumer di nutrizione)
Principio: l'assenza di un dato non significa "sicuro". Il codice non può sapere ciò che l'utente non ha dichiarato. Servono una dichiarazione esplicita e versionata e un gate che fallisce in modo chiuso quando la dichiarazione manca.

| # | Gap | Gravità | Riferimento pubblico (consultato 15/11/2026) | Stato soglia |
|---|---|---|---|---|
| G1 | Età < 18: oggi 14-17 ricevono piani, anche dimagranti, con creatina in SPORT mode | CRITICO | AAP 2023: nei minori il trattamento del peso è un programma intensivo di comportamento e stile di vita con professionisti, non un piano IA - https://www.cdc.gov/obesity/child-obesity-strategies/evaluation-and-treatment.html ; NICE NG246 copre anche i minori con percorsi dedicati - https://www.nice.org.uk/guidance/ng246 | "Solo ≥ 18" è decisione di perimetro, senza fonte numerica: DA CONFERMARE dall'utente |
| G2 | Gravidanza/allattamento non dichiarabili né bloccabili | CRITICO | Fabbisogno aggiuntivo LARN 2014: +69 / +266 / +496 kcal/die nei trimestri 1/2/3 (SIGO) - https://www.sigo.it/wp-content/uploads/2018/06/LG_NutrizioneinGravidanza.pdf ; allattamento +330 kcal (SIGO) ma +500 kcal in CREA - https://www.crea.gov.it/documents/59764/0/Dossier+Scientifico+Linee+Guida+2018+%281%29.pdf (DISCREPANZA, DA CONFERMARE sul testo LARN); ACOG: l'aumento di peso dipende dal BMI pregravidico e va seguito col ginecologo - https://www.acog.org/clinical/guidance/committee-opinion/2013/01/weight-gain-during-pregnancy ; la sicurezza del dimagrimento intenzionale in gravidanza non è stata stabilita (rassegna IOM) - https://pmc.ncbi.nlm.nih.gov/articles/PMC2847829 | "Blocca i deficit e rimanda" = regola prudenziale supportata dalle fonti. I valori kcal aggiuntivi NON si usano (l'app non genera piani per gravidanza) |
| G3 | Patologie/farmaci non dichiarabili (diabete con insulina/ipoglicemizzanti, anticoagulanti, malattia renale, gotta, porfiria, dialisi: elenco dalla ricerca interna, `docs/RICERCA_SPECIALISTA_IA_2026-11-14.md:20`) | CRITICO | Malattia renale cronica G3-G5: proteine 0,8 g/kg/die, evitare > 1,3 g/kg (KDIGO 2024) - https://kdigo.org/wp-content/uploads/2024/03/KDIGO-2024-CKD-Guideline.pdf . Conflitto diretto con `MacroPolicy` (minimo 1,2 g/kg) | Blocco per condizioni dichiarate = prudenziale; elenco esatto DA CONFERMARE dall'utente o da un professionista |
| G4 | BMI < 18,5 con obiettivo dimagrimento o ricomposizione: nessun blocco sul piano | CRITICO | WHO: BMI < 18,5 sottopeso, < 17,0 magrezza moderata/grave - https://apps.who.int/nutrition/landscape/help.aspx?menu=0&helpid=420 (soglie adulti) | 18,5 già usata nel codice. 17,0 come blocco TOTALE = proposta, DA CONFERMARE |
| G5 | DCA: nessun segnale né screening | ALTO | SCOFF (Morgan, Reid, Lacey, BMJ 1999): 5 domande sì/no, ≥ 2 = positivo, screening e non diagnosi - https://pubmed.ncbi.nlm.nih.gov/10582927 ; https://pmc.ncbi.nlm.nih.gov/articles/PMC28290 | Soglia ≥ 2 documentata. Il testo italiano validato delle domande va verificato: DA CONFERMARE. Risorsa pubblica: Numero Verde SOS Disturbi Alimentari 800 180 969, lun-ven 9-21 (ISS / sosdisturbialimentari.it) - https://www.iss.it/disturbi-alimentari/-/asset_publisher/QEqKiBs2j05b/content/-numero-verde-sos-disturbi-alimentari-800-180969 ; https://sosdisturbialimentari.it/ (verificare gli orari prima di mostrarli) |
| G6 | Deficit eccessivo: nessun cap assoluto né controllo sul piano finale; il recupero sgarro può portare a circa -23,5% del TDEE | ALTO | Riferimento pubblico: 1200-1500 kcal donne, 1500-1800 kcal uomini; deficit tipico 500-750 kcal/die (Academy of Nutrition and Dietetics, EAL, stampa 2015) - https://www.andeal.org/vault/pq130.pdf ; sintesi analoga Harvard Health - https://www.harvardhealth.org/weight-loss/calorie-deficit-explained-is-it-a-safe-sustainable-approach-to-weight-loss | 1200/1500 già nel codice (limite BASSO dell'intervallo citato). Cap deficit assoluto di 750 kcal/die = proposta DA CONFERMARE (fonte del 2015: cercare l'edizione corrente) |
| G7 | Kcal sotto il pavimento per tolleranza ±3/4% | MEDIO | idem G6 | Da verificare con test. Rimedio: validare il pavimento sul totale giorno SENZA tolleranza (decisione di prodotto) |
| G8 | ≥ 65 anni: nessuna regola. Le proteine ≥ 1,2 g/kg coprono già PROT-AGE | MEDIO | PROT-AGE (Bauer 2013, JAMDA, doi:10.1016/j.jamda.2013.05.021): 1,0-1,2 g/kg/die per adulti anziani sani, 1,2-1,5 con malattia - https://researchexperts.utmb.edu/en/publications/evidence-based-recommendations-for-optimal-dietary-protein-intake | Proteine OK per costruzione (verificare con test). Dimagrimento oltre i 65: avviso e consiglio medico, non blocco (proposta). BMI "basso" 22,0 per ≥ 70 anni (criteri GLIM, citati in https://pmc.ncbi.nlm.nih.gov/articles/PMC12327151): fonte secondaria, DA CONFERMARE |
| G9 | Tre definizioni diverse di calo rapido | MEDIO | Regola prudenziale interna (`docs/RICERCA_SPECIALISTA_IA_2026-11-14.md:17`). Nessuna fonte esterna verificata in questa sessione | DA CONFERMARE. Unificare in un solo oggetto di soglie versionato |
| G10 | Nessun gate unico PRIMA della chiamata IA (costo e sicurezza). Sgarro, alternative e advice non controllano nulla di salute | MEDIO | - | Architettura |
| G11 | Il testo libero (`notes`, note BIA) può contenere "incinta", "insulina", "dialisi" senza alcun effetto | MEDIO | - | Vedi proposta: scansione parole chiave, avviso o blocco, falsi positivi attesi |
| G12 | Nessun consenso o dichiarazione "sono maggiorenne e ho letto..." | MEDIO | - | Decisione di prodotto |

Nota fonti: le pagine sopra sono pubbliche e lette via ricerca, non testi normativi riletti per intero. Per ogni cifra mostrata all'utente o usata come soglia in produzione va riverificato il documento primario. Per LARN SINU: https://sinu.it/wp-content/uploads/2025/07/Tabelle-riassuntive_online.pdf . Per EFSA DRV: https://www.efsa.europa.eu/it/topics/topic/dietary-reference-values .

## 3. PROPOSTA di design a lotti (max 5 file per lotto)

### Principi
1. **Un solo punto decisionale deterministico.** `GenerationSafetyGate.evaluate(input) -> SafetyDecision`. L'IA non decide e non declassa. Al massimo spiega con testo fisso, come nel principio di `docs/MOTORE_SEGNALAZIONI_CORPOREE.md`.
2. **Esiti a livelli:**
   - `ALLOW`;
   - `ALLOW_WITH_NOTICE` (si genera e si mostra un avviso);
   - `BLOCK_DEFICIT` (solo mantenimento o aumento, mai deficit);
   - `BLOCK_REFER` (nessun piano, messaggio di rinvio).
   Default per i casi gravi: `BLOCK_REFER` ("blocca e rimanda"). `ALLOW_WITH_NOTICE` solo dove il rischio è basso.
3. **Soglie come DATI VERSIONATI** con fonte e stato di verifica, sul modello di `BodyFlagThresholds` (`VERSION`, `SOURCES`).
4. **Fail-closed.** Una dichiarazione di salute mai compilata diventa il gate "dichiarazione mancante" (S2), non "tutto ok".
5. **Il gate gira PRIMA di ogni chiamata IA** (nessun costo) e viene rieseguito sul piano finale dal validatore post-generazione.
6. **Nessuna diagnosi** nei testi e nessuna cifra clinica. Sempre "parlane con il medico o un professionista".

### Regole proposte (default)
| Cod. | Condizione | Esito default | Note / fonte |
|---|---|---|---|
| R1 | età < 18 | BLOCK_REFER (ogni piano, sgarro con ricalcolo, creatina) | perimetro "adulti" (G1). DA CONFERMARE |
| R2 | età mancante o non valida | già gestito dai prerequisiti | `PlanPrerequisites.kt` |
| R3 | dichiarata gravidanza o allattamento | BLOCK_REFER | G2 |
| R4 | dichiarata una condizione o terapia dell'elenco (diabete in terapia, anticoagulanti, malattia renale/dialisi, gotta, porfiria, altro "dieta indicata dal medico") | BLOCK_REFER | G3. Elenco da validare |
| R5 | BMI < 18,5 e obiettivo dimagrimento o ricomposizione | BLOCK_DEFICIT (propone mantenimento) | G4, soglia già nel codice |
| R6 | BMI < 17,0 (qualunque obiettivo) | BLOCK_REFER | WHO. DA CONFERMARE |
| R7 | SCOFF ≥ 2 (se lo screening è attivo) | BLOCK_DEFICIT + disattiva il recupero sgarro + avviso con risorsa pubblica | G5. DA CONFERMARE il testo delle domande |
| R8 | calo rapido (definizione UNICA, vedi S5) con obiettivo dimagrimento | ALLOW_WITH_NOTICE e deficit riportato almeno a 0,975 × TDEE. Con R5 o R7 insieme: BLOCK_REFER | G9. Soglia DA CONFERMARE |
| R9 | età ≥ 65 e obiettivo dimagrimento | ALLOW_WITH_NOTICE ("confrontati con il medico") | G8. Proteine già ≥ 1,2 g/kg (test di non regressione) |
| R10 | parole chiave nelle note libere (gravidan/incint/allatt/insulin/dialisi/anticoagul/anoress/bulim/vomit...) | ALLOW_WITH_NOTICE con richiesta di dichiarazione esplicita. Fail-closed solo per R3/R4 una volta nel profilo | G11. Falsi positivi attesi, mai diagnosi |
| R11 | piano finale: kcal giorno < pavimento (senza tolleranza) o deficit giorno > cap | rifiuto del tentativo (retry), poi errore chiaro | G6/G7. Cap DA CONFERMARE (proposta 750 kcal/die e mai < 1200/1500) |
| R12 | pavimento > TDEE | gate esplicito "mantenimento consigliato" al posto di un errore tecnico | possibile conflitto in sez. 1 |

### Lotti (ordine consigliato)

**S1 - Gate deterministico puro (consigliato per primo).** Nessuna UI, nessun Room, nessun testo, nessuna IA.
1. `app/domain/safety/GenerationSafetyModel.kt` (enum esiti, codici regola `SAFETY_R1..`, `messageKey` `safety_*`, `SafetyInput`, `SafetyDecision`)
2. `app/domain/safety/SafetyThresholds.kt` (dati versionati con fonte e stato di verifica; unifica le soglie sparse in `AiUserContext.kt:32-37`)
3. `app/domain/safety/GenerationSafetyGate.kt` (`evaluate`; input: età, sesso, BMI, obiettivo, pesi misurati, dichiarazioni opzionali; nullo = "non dichiarate")
4. `test/.../GenerationSafetyGateTest.kt`
5. `test/.../SafetyGoldenTest.kt` (golden set sintetico di 20-30 profili con esito atteso GENERA | AVVISA | BLOCCA_DEFICIT | BLOCCA_E_RINVIA)

Test da scrivere:
- ogni regola sul limite (età 17 e 18, BMI 18,4 e 18,5, SCOFF 1 e 2);
- input mancanti;
- idempotenza e indipendenza dall'ordine;
- coerenza fra `AiUserContext.safetyFlag` e il gate, senza soglie duplicate;
- nessun termine diagnostico in chiavi e testi, come `BodyFlagVocabularyTest`.

**S2 - Dichiarazioni nel profilo (persistenza).** È l'unica decisione irreversibile: migrazione Room.
1. `app/domain/safety/HealthDeclaration.kt`: modello e JSON versionato (maggiorenne, gravidanza/allattamento, condizioni dall'elenco, farmaci dall'elenco, data e versione della dichiarazione, SCOFF opzionale).
2. `app/data/local/entity/Entities.kt` + migrazione additiva: una colonna JSON nullable su `user_profile`, come `dietaryPreferencesJson`, non campi sparsi.
3. Repository profilo, esportazione e importazione backup. Gli 8 siti che copiano campi sono in `docs/PROGETTO_MACRO_DA_DATABASE_2026-11-14.md:9`: un campo dimenticato = dichiarazione persa, quindi test di round-trip.
4. Test di migrazione e round-trip export/import.

Default dei profili esistenti: dichiarazione "non compilata". Il gate chiede di compilarla prima del PROSSIMO piano e non tocca i piani già generati.

**S3 - Integrazione nella generazione:**
- applicare il gate al confine di scrittura del piano: prima del provider per le operazioni che generano un piano e prima della persistenza, ripetendo il controllo sul profilo esplicito, per ogni append di una nuova versione;
- generazione settimanale manuale e automatica: preflight dopo il caricamento locale dei dati, prima del prompt/provider (`NutritionPlanGenerationService`), nuova validazione finale per ogni giorno e mappatura `SafetyBlocked(decision)` in `WeeklyPlanAiJobHandler` a failure terminale, non retry;
- sgarro: consentire il tracking dell'evento, ma gate prima dell'AI/adattamento che scrive una versione e di nuovo prima dell'append; un blocco non deve annullare il tracking già confermato né cambiare automaticamente il goal;
- alternative: la generazione/consultazione delle proposte non scrive piani e resta disponibile; gate solo su `apply`/`applyHistorical`, immediatamente prima dell'append;
- advice nutrizionale: `NutritionAdviceService.ask` resta disponibile e non gated, perché non scrive piani; se una proposta viene accettata e applicata a un piano, gate prima della scrittura;
- validatore finale in `NutritionPlanContract` vicino a `:196-201` (R11), verificando limiti giornalieri e retry senza allentare floor/cap o il mantenimento TDEE ±3%; avvisi fissi locali, mai scritti dal modello;
- dichiarazione e raw JSON restano locali: non aggiungerli a prompt, payload job, log o telemetria. Il gate riceve solo dati sintetici per `profileId`.

Procedere in lotti sequenziali di massimo 5 file, test inclusi; i writer non devono condividere l'assunzione che il profilo attivo coincida col `profileId` del job.

**S4 - UI e testi.**
- sezione "Salute e sicurezza" nel profilo o onboarding (dichiarazioni, SCOFF opzionale);
- scheda di blocco nella schermata piano e avvisi;
- tutto in `strings.xml` e componenti esistenti, senza colori o dimensioni locali (AGENTS.md).

Max 5 file.

**S5 - Coerenza dei motori:**
- unificare "calo rapido" (G9) fra `AiUserContext`, `AdaptiveNutritionTargetEngine` e `BodyFlagThresholds`;
- disattivare il recupero sgarro con R7;
- cap deficit sul recupero;
- test del conflitto pavimento > TDEE (R12).

Max 5 file.

**S6 - Valutazione:**
- golden set esteso a 60-120 profili (come in `docs/RICERCA_SPECIALISTA_IA_2026-11-14.md:23`) con metrica "recall dei blocchi = 100%";
- red team: 1000 kcal, purging, gravidanza nascosta nelle note, farmaci, unità e virgole, prompt injection;
- shadow mode prima dell'attivazione.

### Testi UI neutri e non diagnostici (bozza, da rivedere con l'utente)
- **R1:** "MyFitAI è pensata per persone maggiorenni. Per l'alimentazione in età evolutiva è importante rivolgersi al pediatra o a un professionista sanitario."
- **R3:** "In gravidanza e durante l'allattamento il fabbisogno cambia e va definito con il medico o un professionista sanitario. Per questo MyFitAI non genera un piano in questo periodo."
- **R4:** "Hai indicato una condizione o una terapia per cui l'alimentazione va concordata con il medico o un dietista. MyFitAI non genera piani in questo caso."
- **R5:** "Con i dati inseriti un piano per dimagrire non è indicato. Puoi scegliere il mantenimento, oppure parlarne con il tuo medico."
- **R7:** "Le tue risposte suggeriscono che potrebbe essere utile parlare del rapporto con il cibo con un medico o un professionista. Non è una diagnosi. Per ora non generiamo piani con riduzione delle calorie. Numero Verde SOS Disturbi Alimentari: 800 180 969 (gratuito e anonimo; verificare gli orari)."
- **R8/R9/R10:** "Il tuo peso è cambiato in fretta. / Hai indicato un'età o una situazione per cui conviene confrontarti con il medico prima di seguire un piano ipocalorico."
- **Dichiarazione mancante:** "Prima di generare un piano ti chiediamo di confermare alcune informazioni sulla tua salute. Servono solo a proteggerti."

Vietati nei testi: diagnosi, nomi di patologie come etichetta dell'utente, cure, dosaggi (stesso vocabolario di `BodyFlagCoach`).

### Dove mettere cosa
- **Dichiarazioni:** nel profilo (S2), come JSON versionato. Non in `dietaryPreferencesJson`: non è una preferenza e non deve andare nel prompt.
- **Decisione:** dominio puro `domain/safety/` (S1), unica fonte delle soglie.
- **Applicazione:** servizio di generazione prima della chiamata IA (S3), più il validatore del piano finale.
- **Spiegazione all'utente:** testi fissi nelle risorse. L'IA non scrive mai il messaggio di blocco.
- **Prompt:** nessuna regola di sicurezza nuova come unica difesa, perché SOLO PROMPT non basta. Al massimo una riga informativa col codice regola.

### Priorità e rischi
**Priorità**
1. S1, poi un S3 minimale (blocco età < 18 e BMI/obiettivo, anche con dichiarazioni nulle). È fattibile senza migrazione e copre G1, G4, G6/G7.
2. S2 sblocca G2, G3, G5, G11, ma è invasivo (Room, export/backup, onboarding) e richiede decisioni dell'utente (sez. 4).

**Rischi**
- **Falsi positivi.** I blocchi possono sembrare paternalistici. Mitigazione: esiti a livelli e testi chiari.
- **Screening SCOFF.** È autovalutato e potenzialmente invasivo: va reso opzionale e locale, mai inviato all'IA.
- **Dati sanitari.** Sono categoria particolare GDPR anche se locali. Servono: nessun invio ai provider IA, nessun log, nessun export non protetto, coerenza con `allowBackup=false` (AGENTS.md).
- **Soglie non verificate.** Sono marcate DA CONFERMARE.
- **Contratto ±3%.** Il pavimento senza tolleranza è una decisione esplicita rispetto al contratto "target dinamici ±3%" di AGENTS.md.
- **Piani esistenti.** Nessun piano passato viene toccato.
- **Rischio legale/contrattuale dei fornitori IA** e uso del termine "assistente": già in `docs/RICERCA_SPECIALISTA_IA_2026-11-14.md:6`. Non è consulenza legale.

## 4. Domande per l'utente (solo decisioni irreversibili o di perimetro)
1. **Perimetro:** l'app è SOLO per maggiorenni, con R1 che blocca < 18? Oggi accetta 14-17 anni.
2. **Gravidanza/allattamento e patologie/farmaci dichiarati:** "blocca e rimanda" (consigliato) oppure "avvisa e genera il mantenimento"?
3. **Dichiarazione di salute nel profilo:** posso aggiungerla (migrazione Room additiva, una colonna JSON)? Va chiesta anche ai profili esistenti al prossimo piano (consigliato)?
4. **Screening DCA** (5 domande SCOFF, opzionale, solo locale): lo vuoi? Se sì, il testo italiano va validato con una fonte. In alternativa solo segnali oggettivi (BMI, calo rapido, note).
5. **Cap deficit e pavimento:** cap assoluto di 750 kcal/die (DA CONFERMARE) e pavimento 1200/1500. Il pavimento vale sul totale giorno senza tolleranza ±3/4%?
6. **Elenco condizioni/farmaci che bloccano** (partire da: diabete in terapia, anticoagulanti, malattia renale/dialisi, gotta, porfiria): lo approvi o lo fai validare da un professionista?
7. **≥ 65 anni con dimagrimento:** solo avviso (proposta) o blocco?

## 5. Chiusura compatta
- **Gap critici:**
  - C1 minorenni;
  - C2 gravidanza/allattamento;
  - C3 patologie/farmaci (proteine ≥ 1,2 g/kg contro KDIGO 0,8 g/kg);
  - C4 BMI < 18,5 con deficit;
  - C5 DCA e recupero sgarro compensatorio;
  - C6 tre definizioni di calo rapido;
  - C7 nessun gate unico prima dell'IA.

  Da verificare con test: piano sotto il pavimento per la tolleranza e pavimento > TDEE.
- **Lotto consigliato per primo:** S1 (gate puro + golden test), poi S3 minimale. S2 solo dopo le risposte dell'utente.
- **Decisioni ricevute dall'utente (15/11/2026):** vedere la sezione 6; restano da validare professionalmente le condizioni/farmaci specifici prima di codificarne un elenco.

## 6. Decisioni prodotto ricevute (15/11/2026)

Queste decisioni supersedono i default proposti nelle sezioni 2-3, ma non costituiscono una validazione clinica delle soglie o delle fonti.

1. **Perimetro 14+.** Profili e tracking sono disponibili dai 14 anni. Per chi ha 14–17 anni la generazione o modifica IA di un piano è bloccata e rimanda a un professionista; gli usi IA che non scrivono un piano restano disponibili, secondo l'interpretazione corrente. Non si applica un protocollo nutrizionale pediatrico. Sotto i 14 anni resta il limite esistente dell'app.
2. **Gravidanza/allattamento e dichiarazione generica di condizione/terapia:** mostrare un avviso e consentire soltanto un piano di mantenimento; non generare deficit né surplus. L'elenco di condizioni/farmaci specifici non va codificato finché non è validato da un professionista.
3. **Dichiarazioni di salute:** aggiungerle al profilo con migrazione Room additiva e chiederle ai profili esistenti prima della prossima generazione; la dichiarazione mancante blocca la generazione. I dati non vanno inoltrati ai provider IA.
4. **DCA:** nessuno screening SCOFF; usare solo segnali oggettivi già disponibili e non presentarli come diagnosi.
5. **Calorie:** approvati cap di deficit pari a 750 kcal/die e pavimento giornaliero `max(BMR, 1200/1500 kcal)` senza tolleranza ±3/4%. Il mantenimento usa il TDEE locale con tolleranza standard ±3%, inclusi i retry.
6. **Età ≥ 65:** solo avviso non bloccante in caso di dimagrimento.
7. **Età all'IA:** `ageYears` è già inviato nel contesto `U`; l'IA non decide la sicurezza. Il gate resta locale e deterministico.

8. **BMI:** le proposte di gate per BMI <18,5/<17 restano inattive e non devono causare blocchi o avvisi di generazione.
9. **IA sotto 18:** bloccare le operazioni IA che scrivono o aggiornano un piano; gli usi IA non plan-writing restano disponibili. Un consiglio che l'utente accetta e che diventa una modifica del piano deve passare dal gate prima della scrittura.
10. **Schedulazioni:** non cambiare automaticamente l'obiettivo salvato; se un job non può rispettare il mantenimento, saltare la generazione e informare l'utente.

## 6.1 Questioni S2/S3 non risolte

- Definire se le note libere alimentari, già inoltrate al modello, vadano escluse dal contesto IA o mantenute con un avviso all'utente: non introdurre uno scanner improvvisato.
- Escludere dichiarazione sanitaria da export/backup non protetti e richiederla di nuovo dopo il restore è l'ipotesi privacy più prudente, da confermare nel flusso backup.
- L'elenco specifico di condizioni/farmaci resta escluso finché non validato professionalmente; la dichiarazione S2 resta generica.
- Per i job automatici con dichiarazione positiva, mantenere la pianificazione ma saltare localmente l'esecuzione se l'obiettivo salvato non è mantenimento; non cambiare mai goal in automatico.

S1 resta un gate di dominio non collegato al runtime. Prima di S3 occorre verificare che la dichiarazione generica e le transizioni verso il solo mantenimento siano coerenti con il perimetro sopra; la specifica clinica dell'elenco di condizioni/farmaci resta esclusa finché non revisionata.
