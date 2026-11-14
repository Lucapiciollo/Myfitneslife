# Body Flags Explain Rules - VINCOLANTE

Queste regole sono condivise da Gemini e OpenAI e si applicano al BIA Progress Coach (e a ogni agente che riceve la riga `FLAG_APP`).
La fonte unica del testo e `BodyFlagCoach.PROMPT_RULES` (`android/app/src/main/java/com/myfitai/app/domain/body/BodyFlagCoach.kt`): il prompt e provider-neutral, quindi Gemini e OpenAI ricevono le stesse identiche regole. Un test JVM verifica che questo documento le riporti alla lettera.

## Principio
Il CODICE decide se un flag esiste (`BodyFlagEngine`, vedi `docs/MOTORE_SEGNALAZIONI_CORPOREE.md`). L'IA puo solo SPIEGARE e contestualizzare i flag che l'app le passa. Non puo crearli, declassarli, cancellarli o reinterpretarli, ne fare diagnosi.

## Contesto fornito dall'app
Una riga `FLAG_APP`:
- con flag: `FLAG_APP:<CODICE>|gravita=<ATTENZIONE|INFO>|affidabilita=<ALTA|MEDIA|BASSA>|evidenza=<chiave>=<valore> <unita> (<data>),...;<altro flag>... (decisi dal codice dell'app: spiegali soltanto, non crearne altri)`;
- senza flag: `FLAG_APP:nessun flag: <motivo interno> (...)`. Il motivo interno (regola e motivo di salto) serve solo a capire il contesto: non va citato all'utente.

## Regole per il modello
1. Spiega SOLO i flag presenti nella riga FLAG_APP e non inventare altri segnali o soglie.
2. Non creare, declassare, cancellare o reinterpretare un flag: gravita e affidabilita le decide l'app.
3. Non fare diagnosi e non usare termini diagnostici (diagnosi, sarcopenia, obesita, malattia, patologia): usa la formula segnale da approfondire con un medico.
4. Cita solo valori e date dell'evidenza del flag e non commentare valori assenti o non forniti.
5. Se FLAG_APP indica nessun flag, scrivilo in una sola frase neutra senza aggiungere segnali e senza citare il motivo interno.
6. Non dare indicazioni terapeutiche ne dosaggi.
7. Per un flag sul muscolo invita a un test di forza e a un medico: la BIA domestica non misura la forza.
8. Non scrivere nei testi i codici dei flag (WHTR_HIGH e simili).

## Validazione locale (non delegata al modello)
`BiaAnalysisContract.validateBusinessWith` rifiuta la risposta e la fa rigenerare (stesso percorso dei rifiuti BIA: retry con suggerimento, poi `Riprova` manuale) se:
- usa un termine diagnostico o prescrittivo (`BodyFlagVocabulary.COACH_OUTPUT_STEMS`; le radici negate come "non e una diagnosi" sono tollerate, i nomi di condizioni mai): `BODY_FLAG_FORBIDDEN_TERM`;
- cita il codice di un flag non passato: `BODY_FLAG_CODE_NOT_PROVIDED`;
- presenta come segnale (frase con "segnale", "da approfondire", "rischio", "medico", ...) vita/addome, grasso viscerale, muscolo/massa magra, calo di peso rapido o BMI senza il flag corrispondente: `BODY_FLAG_SIGNAL_NOT_PROVIDED` (la nota di sicurezza D e esclusa da quest'ultimo controllo);
- scrive numeri con kg, %, kcal, g o cm non presenti nel contesto: `NUMBER_NOT_IN_CONTEXT` (controllo gia esistente, esteso ai cm).

## Contratto JSON
Nessun campo nuovo: il coach usa le sezioni testuali esistenti del protocollo BA2 (schema `myfitai_bia_progress_coach_pipe_v2`) e gli schemi in `06-ai/schemas/` restano invariati per entrambi i provider.
