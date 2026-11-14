# Motore di segnalazioni corporee deterministico (Lotto 1)

Stato: SOLO dominio puro + test. Nessuna UI, nessuna stringa, nessuna modifica Room, nessuna chiamata IA, nessun prompt.
Origine: `docs/RICERCA_SPECIALISTA_IA_2026-11-14.md` (fonti citate dal ricercatore, **non riverificate** sui testi primari).

## Principio
Il CODICE decide se un flag esiste. L'IA (lotti futuri) potra solo spiegarlo, mai crearlo, declassarlo o cambiarne la gravita.
Linguaggio: mai diagnosi; l'esito e un "segnale da approfondire con un medico". Nessun testo di UI nel dominio: solo codici e chiavi di messaggio (`body_flag_*`), le stringhe arriveranno in un lotto successivo.

## File
| File | Ruolo |
|---|---|
| `domain/body/BodyFlag.kt` | Modello: `BodyFlag(code, severity, reliability, evidence, sourceIds, messageKey)`, enum codici/regole/motivi di skip, `BodyFlagProfile`, `BodyFlagEvaluation` |
| `domain/body/BodyFlagThresholds.kt` | Soglie come DATI VERSIONATI (`VERSION`) + metadati fonte `{ente, documento, anno, applicabilita, affidabilita, verifica}` |
| `domain/body/BodyFlagEngine.kt` | `evaluate(profile, bia, measures, today, thresholds, biaZone) -> List<BodyFlag>` e `evaluateDetailed(...)` (flag + regole saltate con motivo) |
| `domain/body/BiaMeasurementQualityEngine.kt` | Aggiunta di `isDegraded(result)`: unica definizione di "comparabilita degradata", ora usata anche da `BiaAnalysisReport` |

Test: `BodyFlagEngineTest` (regole e limiti), `BodyFlagGoldenTest` (9 profili sintetici), `BodyFlagVocabularyTest` (nessun termine diagnostico), fixture in `FlagFixtures`.

## Input realmente disponibili
- Profilo: sesso biologico, eta, altezza (`BodyFlagProfile`, ricavabili da `UserProfileEntity`), obiettivo (solo registrato, non usato dalle regole).
- Misure corporee (`BodyMeasurementEntity`): **la circonferenza vita ESISTE** (`waistCm`; intervallo plausibile 40-200 cm da `BodyMeasurementRanges`), ultima riga con vita valorizzata; `weightKg` della riga entra nelle osservazioni di peso.
- BIA (`BiaMeasurementEntity`): peso, % grasso, grasso viscerale (livello), massa magra, muscolo scheletrico, condizioni di misura.
- Non esistono: circonferenza fianchi usata, angolo di fase, test di forza (alzate dalla sedia, SARC-F: non ancora in app).
- Peso: solo pesi MISURATI (`WeightResolver.observations`), mai il peso del profilo.
- Se un input manca la regola non genera flag e il motivo resta in `evaluateDetailed().skipped` (`BodyFlagSkipReason`, uso interno).

## Perimetro
Solo adulti (`adultMinAgeYears = 18`). Eta ignota o < 18: nessuna regola (`AGE_MISSING` / `NOT_ADULT`). Non copre gravidanza, patologie, farmaci, DCA, edemi, dialisi, pacemaker: sono blocchi/dichiarazioni del profilo che il motore non possiede (lotti futuri).

## Regole e soglie (versione `2026-11-14.1`)
| Regola / codice | Condizione | Gravita | Affidabilita | Fonte (non riverificata salvo nota) |
|---|---|---|---|---|
| WHtR `WHTR_ABOVE_HALF` / `WHTR_HIGH` | vita/altezza >= 0,5 / >= 0,6 (solo il livello piu alto) | ATTENZIONE | ALTA se vita <= 90 gg; MEDIA 91-180 gg; oltre = nessun flag | NICE NG246 (2025), adulti |
| Vita `WAIST_FIRST_LEVEL` / `WAIST_SECOND_LEVEL` | U >= 94 / >= 102 cm; D >= 80 / >= 88 cm | ATTENZIONE | come sopra | OMS 2008 (waist circumference), adulti, prevalentemente europei |
| BMI `BMI_BAND_UNDER` / `OVER` / `HIGH` | < 18,5 / >= 25 / >= 30 (BMI arrotondato a 1 decimale, ultimo peso misurato <= 90 gg) | INFO | ALTA se peso <= 30 gg, MEDIA fino a 90 gg | OMS (TRS 894, 2000); solo informativo e condizione di altre regole |
| Viscerale `VISCERAL_FAT_RISING` | >= 3 misure BIA coerenti, >= 28 gg, salita >= 1 livello, ultimo valore = massimo della serie, E WHtR >= 0,5 | INFO | BASSA | Fitdays: **nessuna soglia clinica**; solo trend |
| Muscolo scheletrico `SKELETAL_MUSCLE_DECLINE` | >= 3 misure BIA coerenti, >= 28 gg, calo >= 1,5 kg (> rumore 1,0 kg), ultimo valore non risalito, E (eta >= 60 OPPURE BMI < 18,5) | ATTENZIONE | BASSA | EWGSOP2 NON applicabile alla BIA domestica: il messaggio (chiave `..._check_strength`) invita a un test di forza non ancora in app |
| Calo peso `RAPID_WEIGHT_LOSS` | > 1,0 kg/settimana per 2 settimane consecutive (punti a ~7 gg di distanza, tolleranza +-1 gg, normalizzato sui giorni reali), ultimo punto <= 14 gg | ATTENZIONE | ALTA se tre punti della stessa fonte, altrimenti MEDIA | Regola prudenziale della ricerca, da calibrare |
| Massa magra `LEAN_MASS_LOSS_SHARE` | >= 3 misure BIA coerenti, >= 28 gg, calo massa magra >= 2 kg E quota > 40% del calo di peso (calo peso > 0,8 kg rumore) | ATTENZIONE | BASSA | Composito **da calibrare** (la massa magra include acqua) |
| % grasso `BODY_FAT_TREND_UP` / `DOWN` | >= 3 misure BIA coerenti, >= 28 gg, variazione > rumore (1,0 punti) | INFO | MEDIA se tutte le coppie di qualita ALTA, altrimenti BASSA | Solo trend, nessuna soglia clinica |

Tutte le soglie sono in `BodyFlagThresholds` (campi con default); `BodyFlagEngine` non contiene numeri di dominio tranne la tolleranza sui punti peso (`weekToleranceDays`, anch'essa in thresholds).

### Fonti NON verificate / non usate
- Fasce di riferimento Fitdays (viscerale, ecc.): non verificate, **non usate** (id `FITDAYS_BANDS`).
- Tabelle di % massa grassa per eta: non verificate, **non usate** (id `BODY_FAT_AGE_TABLES`).
- Tutti gli enti/titoli/anni in `SOURCES` sono quelli riportati dalla ricerca e vanno riverificati sui testi primari prima di mostrare la fonte all'utente.
- Ipotesi interne da calibrare: ritmo di calo peso, composito massa magra, calo muscolare minimo 1,5 kg, salita minima viscerale, finestre temporali, recenza (90/180 gg vita, 30/90 gg peso, 60 gg BIA, 90 gg finestra trend).

## Qualita input
- Non si duplicano le soglie esistenti: variazioni confrontate con `BiaNoiseThresholds.isReliableChange` (peso 0,8 kg, % grasso 1,0 punto, massa magra/muscolo 1,0 kg).
- Serie BIA: si parte dall'ultima misura e si va indietro; la serie si interrompe alla prima coppia consecutiva con qualita degradata (`BiaMeasurementQualityEngine.isDegraded`: livello LOW o "Condizione diversa") o fuori dalla finestra di trend. Quindi misure rumorose, condizioni diverse o solo parzialmente dichiarate non generano flag.
- Conseguenza voluta e prudente: chi non dichiara le condizioni di misura (digiuno, appena sveglio, ...) nelle BIA non ottiene flag di trend BIA (la qualita risulta LOW).
- Misure con data futura sono ignorate. Le BIA usano la zona oraria passata (`biaZone`), le misure corporee il giorno UTC (come `BodyWeightHistory`).

## Risultato
- Idempotente e indipendente dall'ordine degli input.
- Ordine: gravita (ATTENZIONE prima di INFO), poi priorita del codice (`BodyFlagCode.priority`), poi affidabilita, poi codice.
- Ogni flag porta `evidence` (valori, unita, data), `sourceIds` (in `BodyFlagThresholds.SOURCES`) e `messageKey`.

## Cosa NON fa
- Nessuna diagnosi, nessun testo, nessuna UI, nessuna persistenza dei flag, nessuna chiamata IA.
- Nessun angolo di fase, nessuna "eta metabolica".
- Nessuna soglia clinica sul grasso viscerale o sulla % di grasso.
- "Muscolo scheletrico basso" non ha una soglia assoluta applicabile alla BIA domestica: il motore segnala un CALO affidabile, non un livello basso.
- Non applica blocchi duri (gravidanza, DCA, farmaci, patologie): richiedono dati di profilo non ancora presenti.
- Non decide sulla dieta ne sui target (restano in `LocalCalculationEngine` e `SafetyCalorieFloor`).

## Come estendere
1. Nuova soglia: campo in `BodyFlagThresholds`, nuova `VERSION`, riga in questa tabella.
2. Nuova fonte: voce in `BodyFlagThresholds.SOURCES` con affidabilita e stato di verifica.
3. Nuova regola: valore in `BodyFlagRule` e `BodyFlagCode` (con `messageKey` e `priority`), funzione privata in `BodyFlagEngine` che ritorna con `skipOnly`/`skip` quando manca un input; test sul limite, input mancante, misure rumorose, sesso/eta; aggiornare il golden test.
4. Nuovi input (es. test di forza): aggiungerli a `BodyFlagProfile` o a un nuovo parametro, mai leggerli da Room dentro il motore.
5. Le stringhe UI/IA si agganciano alle `messageKey` in un lotto successivo; l'IA riceve flag e evidenza gia decisi e puo solo spiegarli.

## Lotto L3: il BIA Coach spiega i flag (nessun cambio Room, nessun campo JSON nuovo)
- Input: `BodyFlagService.load` (iniettato nell'handler come funzione, fakeable) alimenta la riga `FLAG_APP` del contesto del BIA Progress Coach (`BodyFlagCoach.contextLine`): per ogni flag codice, gravita, affidabilita ed evidenza (valori, unita, date); senza flag `nessun flag: <motivo interno>` (regole saltate e motivo, da `BodyFlagReport.skipped`). Se il servizio fallisce l'analisi parte lo stesso con `nessun flag: dati non disponibili`.
- Prompt: `BodyFlagCoach.PROMPT_RULES` (una sola fonte per Gemini e OpenAI, il prompt del coach e provider-neutral), riportate in `06-ai/providers/shared/BODY_FLAGS_EXPLAIN_RULES.md`. Il protocollo `PA1` dell'analisi progressi non riceve ancora la riga.
- Validatore locale (`BodyFlagCoach.violation`, dentro `BiaAnalysisContract.validateBusinessWith`): rifiuta termini diagnostici/prescrittivi (`BodyFlagVocabulary`, lista unica anche per `BodyFlagVocabularyTest`), codici di flag non passati e segnali (vita/addome, viscerale, muscolo, calo rapido, BMI) senza il flag corrispondente; i numeri restano controllati da `BiaNumberGuard` (ora anche i cm). Codici stabili `BODY_FLAG_*`: stesso percorso dei rifiuti BIA (retry con suggerimento, poi `Riprova`).
- Claim: niente "nutrizionista/dietista/specialista" nel prompt, nella nota fissa (`DEFAULT_SAFETY_NOTE`: "assistente automatico, non sostituisce il parere del medico"; la frase vecchia resta riconosciuta come "nessuna nota" in `LEGACY_SAFETY_NOTE`) e nei documenti degli agenti.
