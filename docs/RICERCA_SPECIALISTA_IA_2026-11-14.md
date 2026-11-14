# Ricerca 14/11/2026: specialista = pannello di agenti IA (ricercatore-prodotto, brief v2)

Stato: SINTESI della ricerca. Le fonti sono state citate dal ricercatore e NON riverificate dall'orchestratore. Non e consulenza legale ne medica.

## Fatti che cambiano il quadro (da verificare sui testi primari prima di decidere)
1. Termini dei fornitori IA: OpenAI Usage Policies (indicate effettive dal 20/11/2026) vietano consigli personalizzati che richiedono una licenza senza adeguato coinvolgimento di un professionista e il far intendere che l'IA abbia credenziali; Gemini API Additional Terms (23/03/2026) vietano l'uso in pratica clinica o per consigli medici. Il termine 'specialista' e la parte piu esposta: usare 'assistente'.
2. Italia: L. 132/2025 art. 7 (IA in sanita = supporto); Cass. pen. 20281/2017 (diete personalizzate senza abilitazione). Per uso familiare rischio pratico basso ma non nullo (ipotesi).
3. I LLM sbagliano i numeri in nutrizione: piani da 1500 kcal con 4 modelli da 1357 a 2273 kcal; stime da foto errore medio 35-64%. In linea con il -33% grassi osservato in MyFitAI. I macro non vanno MAI dichiarati dal modello.
4. Un revisore IA non e indipendente per default (preferenza per se/stessa famiglia, ordine, instabilita): serve arbitro deterministico.

## Architettura consigliata (MVP = opzione B)
Fondamenta: F1 calcolo nutrizionale deterministico da database alimentare (l'agente restituisce solo alimenti+grammi; CREA primaria da verificare licenza, USDA ripiego, OFF solo confezionati); F2 motore di segnalazioni deterministico (l'IA spiega, non crea ne declassa flag); F3 knowledge base come dati strutturati e versionati.
B = baseline rinforzata + revisore di famiglia diversa (scrive Gemini, rivede OpenAI) + spiegatore dei flag + arbitro in codice. Crescita: B -> C (ottimizzatore delle quantita) -> D (pannello a ruoli) solo se il golden set mostra un guadagno -> E (valutazione continua).
Schema: regola dura violata = BLOCCA; problema con ID di regola verificabile = REVISIONA (max 2 cicli); osservazione non verificabile = NOTA; disaccordo persistente = piano precedente validato o 'non validato'; disaccordo su un numero = decide il calcolo. LLM puo solo escalare.

## Soglie motore flag (fonte e affidabilita nel brief completo)
WHtR >=0,5 / >=0,6 (alta); vita U>=94/102, D>=80/88 (alta); BMI <18,5 blocco deficit; % grasso BIA solo trend (bassa-media); grasso viscerale Fitdays senza soglia clinica: flag solo se in salita >=4 settimane E WHtR>=0,5; muscolo scheletrico: EWGSOP2 non applicabile alla BIA domestica, serve test funzionale (SARC-F, alzate dalla sedia), flag cauto solo con >=3 misure coerenti e (eta>=60 o test anomalo); calo peso >1 kg/sett per 2 sett = flag; calo massa magra: flag composito da calibrare; proteine >=1,2 g/kg in deficit (blocco sul piano); energia minima <800 kcal solo con supervisione medica (pavimento operativo = decisione dell'utente); angolo di fase e eta metabolica: non usare. Fasce Fitdays e tabelle % grasso per eta: NON verificate.

## Blocchi duri
Eta <18; gravidanza/allattamento; sospetto DCA (SCOFF>=2, calo rapido); BMI<18,5 o calo>=1 kg/sett per 2 sett senza deficit; patologie o farmaci dichiarati (diabete/insulina, anticoagulanti, malattia renale, gotta, porfiria); edemi, dialisi, pacemaker = BIA non interpretabile.

## Valutazione
Golden set 60-120 profili sintetici (esiti GENERA | GENERA_CON_FLAG | BLOCCA_E_RINVIA | CHIEDI_DATI), metriche: aderenza +-3% calcolata da DB >=95%, recall blocchi 100%, flag 100% sui test, ancoraggio kbId 100%, revisore >=80% sui difetti iniettati con <=1 falso allarme/10 piani. Red team: 1000 kcal, purging, gravidanza nascosta nelle note, farmaci, prompt injection, unita/virgole. Esperimento 2-4 settimane in shadow mode.

## Decisioni all'utente
1 nome 'assistente' non 'specialista'; 2 perimetro adulti sani; 3 rischio contrattuale fornitori IA; 4 pavimento calorico e soglie; 5 database alimentare; 6 quale modello scrive/rivede; 7 budget API; 8 dati e consenso della madre; 9 shadow mode; 10 aprire ad altri (solo con parere legale).