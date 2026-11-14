# Spike S1: macro dichiarati dal modello vs macro calcolati da catalogo alimentare

Data: 14/11/2026. Branch `spike/s1-macro-da-database`. Riferimento: `docs/PROGETTO_MACRO_DA_DATABASE_2026-11-14.md` (Design, Spike S1).
Nessun codice di produzione modificato: script Python in `tools/spike-s1/` (vedi README). Nessun dato personale nel repo: solo aggregati e nomi di alimenti. Profili anonimizzati P1 e P2.

## 1. Esito in breve

| Soglia di progetto (non vincolante) | Esito | Valore misurato |
|---|---|---|
| Copertura alias >= 90% sulle voci piu' frequenti | PASS, con riserva (vedi limiti) | 94,1% delle righe con 73 voci USDA dirette; 96,9% con 6 proxy USDA; 99,2% con 1 placeholder non USDA |
| Riparabilita' >= 98% dei giorni entro +-3% (solo kcal) | PASS | 100% (79/79 giorni; 43/43 con soli alimenti USDA) |
| Riparabilita' >= 98% dei giorni entro +-3% (kcal e proteine) | FAIL | 89,9% con i limiti del progetto; 96,2% con limiti piu' larghi (0,5x-2,0x, +-60% pasto) |
| < 50 ms per giorno | PASS sulla mediana e sul p90 (in Python, tempi rumorosi) | mediana 6-11 ms, p90 14-20 ms, max 25-51 ms a seconda dell'esecuzione (il massimo sfiora 50 ms in Python su un singolo giorno) |

Risultato centrale: i macro dichiarati dal modello si discostano molto dai macro calcolati (mediana del valore assoluto 12-13% per pasto e 5-6% per giorno sulle kcal), ma il ciclo di riparazione porta il 100% dei giorni risolti entro +-3% sulle kcal. Le proteine entro +-3% sono raggiungibili nel 90% dei giorni; il 10% mancante e' infattibile entro i limiti, non un limite del solutore, e si concentra in un solo profilo.

## 2. Metodo

**Dati in ingresso (sola lettura, fuori dal repo).** Due backup `myfitai_profile_export_v2`. Tutte le versioni di tutti i piani. Righe ingrediente grezze: 2.088 in 672 pasti e 112 giorni. Dopo la deduplica dei pasti identici (stessi macro dichiarati e stessi ingredienti/quantita'): 394 pasti unici (P1 287, P2 107), 1.187 righe, 82 giorni unici (P1 63, P2 19). Dove non e' indicato diversamente le statistiche usano i pasti e i giorni deduplicati; la copertura e' riportata anche pesata su tutte le righe grezze.

**Catalogo.** USDA FoodData Central (CC0): SR Legacy 2018-04 (7.793 alimenti, macro sempre completi, porzioni presenti per 7.533) e Foundation 2025-12-18 (436 alimenti). Mini-catalogo di 80 voci scelte a mano sulle frequenze dei due backup (74 da SR Legacy, 5 da Foundation, 1 senza fdcId).
- Tier A (73): alimento USDA equivalente. Tier B (6): proxy USDA dichiarato (orata -> branzino, nasello -> whiting, yogurt di soia, grano saraceno, proteine in polvere -> whey generica, marmellata senza zuccheri). Tier C (1): bresaola, valori tipici di etichetta NON USDA e NON verificati, solo segnaposto per misurare l'effetto sui giorni.
- Alias normalizzati: minuscolo, senza accenti, punteggiatura e articoli/preposizioni; match per sequenza di token, vince l'alias piu' lungo.
- Foundation usa quasi sempre l'energia "Atwater" (id 2047/2048) al posto di 1008: solo 31 alimenti su 436 hanno 1008; 376 su 436 hanno macro completi; 116 su 436 hanno porzioni. Per questo il catalogo e' costruito quasi tutto su SR Legacy.
- Verifiche sullo zip: ID nutrienti confermati (energia 1008, proteine 1003, grassi 1004, carboidrati 1005, fibra 1079, sodio 1093, acqua 1051). Lo zip Foundation `2026-10-29` pubblicato come release piu' recente NON contiene `food.csv`, `food_nutrient.csv` ne' `food_portion.csv` (14 file, senza valori nutrizionali): inutilizzabile, usato il `2025-12-18`. Crudo/cotto e' marcato solo nel testo della descrizione ("raw", "cooked, boiled"), non da un campo strutturato; esiste `retention_factor.csv` ma per codici di metodo di cottura, non per fdcId.

**Calcolo.** kcal/P/C/F = somma di grammi x per100 / 100. `ml` convertiti con densita' per voce (olio 0,91; latte e kefir 1,03; albume 1,03; acqua e te 1,0).

**Deviazione.** (dichiarato - calcolato) / calcolato, sui pasti e sui giorni interamente risolti. Soglie minime del denominatore per evitare rapporti degenerati: pasto 20 kcal / 3 g per macro, giorno 100 kcal / 10 g. Il dichiarato giornaliero e' la somma dei pasti.

**Riparazione (simulazione del ciclo di progetto).** Discesa a coordinate con proiezione. Obiettivo quadratico sugli errori relativi dei quattro totali giornalieri rispetto ai target del giorno, con pesi 1000/100/10/1 per kcal/proteine/grassi/carboidrati (priorita' kcal > proteine > grassi > carboidrati) e una piccola penalita' sulla distanza dai grammi del modello. Vincoli: ogni ingrediente in [0,6x; 1,5x] del peso del modello, kcal di ogni pasto entro +-40% di quelle iniziali, voci fisse (sale, integratori e proteine in polvere, acqua/te, cannella, voci sotto 2 g). Arrotondamento finale a passi di 5 g (1 g per le voci sotto i 20 g) con rifinitura locale. Arresto quando nessun ingrediente si sposta piu' di 0,5 g in una passata, massimo 200 passate. Target kcal/proteine/carboidrati/grassi presi dal giorno del piano.

## 3. Copertura

| Catalogo | Voci | Righe risolte (dedup, 1.187) | Righe risolte (tutte le versioni, 2.088) | Pasti interamente risolti (394) | Giorni interamente risolti (82) |
|---|---|---|---|---|---|
| A: solo USDA diretto | 73 | 95,1% | 94,1% | 337 (85,5%) | 28 (34,1%) |
| A+B: con proxy USDA | 79 | 97,6% | 96,9% | 366 (92,9%) | 43 (52,4%) |
| A+B+C: con placeholder bresaola | 80 | 99,7% | 99,2% | 390 (99,0%) | 79 (96,3%) |

Nomi distinti normalizzati: 157 stringhe su 2.088 righe, che raggruppate per alimento diventano 81 concetti. Con l'alias del catalogo completo bastano 30 voci per l'80% delle righe, 39 per il 90%, 48 per il 95%, 62 per il 98%. Le prime 50 voci coprono il 95,7%.

**Righe non risolte** (conteggio su righe dedup; stato catalogo A, solo 22 nomi distinti, quindi l'elenco dei 30 e' completo):

| # | Ingrediente normalizzato | Righe |
|---|---|---|
| 1 | bresaola | 14 |
| 2 | filetto orata | 6 |
| 3 | bresaola tacchino | 5 |
| 4 | proteine in polvere sapore vaniglia | 4 |
| 5 | orata | 4 |
| 6 | bresaola tacchino o manzo | 3 |
| 7 | yogurt soia naturale | 3 |
| 8 | proteine in polvere al cioccolato (testo troncato nei dati) | 2 |
| 9 | frutti di bosco | 2 |
| 10 | proteine in polvere "alcol o neutre" (testo corrotto) | 2 |
| 11 | proteine in polvere whey | 2 |
| 12 | proteine in polvere sapore neutro | 1 |
| 13 | pane carasau | 1 |
| 14 | fiocchi di grano saraceno | 1 |
| 15 | proteine in polvere neutre | 1 |
| 16 | filetto di nasello | 1 |
| 17 | prosciutto crudo sgrassato | 1 |
| 18 | proteine in polvere "alcoliche o neutre" | 1 |
| 19 | bresaola di manzo | 1 |
| 20 | bresaola "di avasini" (testo corrotto) | 1 |
| 21 | proteine in polvere gusto vaniglia | 1 |
| 22 | proteine in polvere alla vaniglia | 1 |

Con i proxy B restano irrisolti solo: bresaola (24 righe in tutte le varianti), frutti di bosco (2), pane carasau (1), prosciutto crudo sgrassato (1). Il vero vuoto USDA per un'alimentazione italiana: bresaola, prosciutto crudo, pane carasau, skyr (mappato a yogurt greco 0%), ricotta e fiocchi di latte (USDA ha equivalenti vicini ma non uguali). Servono tabelle italiane (CREA) o dati di etichetta.

**Righe con nome corrotto.** Almeno tre nomi hanno testo troncato o con caratteri errati ("proteine in polvere alcol..." , "bresaola di avasini", "T" per te). Il match per alias li assorbe solo grazie al prefisso: con foodId esplicito nel protocollo MFP2 il problema sparirebbe.

## 4. Unita' e dosi

- Unita' (`unit`): solo `g` (1.927 righe grezze su 2.088) e `ml` (161); nessuna riga usa cucchiaio, fetta o pezzo come unita'. Convertibili in grammi: 100% delle righe risolte (99,7% di tutte le righe con il tier A+B+C; 95,1% con il solo tier A perche' le righe non risolte non hanno densita'). I `ml` sono solo olio, latte, kefir, albume, acqua e te: densita' nota.
- `displayDose` (1.187 righe dedup): solo numero senza unita' 806 (67,9%), "N g" 271 (22,8%), "Ng" 42, "N ml" 29, "Nml" 13, "ml" senza numero 13 (1,1%), cucchiaio/cucchiaino/"mezzo" 12 (1,0%), altro 1. Il campo e' quindi inaffidabile come fonte e superfluo: la quantita' esiste gia' in `quantity`+`unit`.
- Cucchiaio: 11 righe di olio extravergine dichiarano "1 cucchiaio" = 10 g (mediana), mentre USDA riporta 13,5 g per cucchiaio (-26%). Una dose calcolata da porzioni USDA non coinciderebbe con le dosi che il modello scrive oggi.
- `weightState`: "fresco" 703 (59,2%), "secco" 231, "liquido" 117, sporco/non valido 98 (8,3%: valori come "g", "ml" o numeri), "crudo" 20, "grezzo" 9, "surgelato" 7, "peso a crudo" 2. Nessuna riga e' marcata "cotto".

## 5. Deviazione dichiarato vs calcolato

Variante principale: catalogo A+B (solo USDA). Valori in %, segno = dichiarato rispetto a calcolato (positivo: il modello dichiara di piu').

**Per pasto (n = 366)**

| Macro | Mediana | p10 | p90 | Max (con segno) | Mediana |abs| | Entro +-3% | Entro +-10% |
|---|---|---|---|---|---|---|---|
| kcal | +3,0 | -15,3 | +38,7 | -99,5 | 13,2 | 15,3% | 39,9% |
| Proteine | -1,3 | -19,3 | +38,6 | +204,6 | 12,8 | 9,0% | 41,3% |
| Carboidrati | -1,6 | -30,7 | +78,5 | +391,5 | 18,6 | 9,1% | 31,6% |
| Grassi | -3,8 | -43,0 | +42,1 | +128,9 | 24,3 | 6,3% | 21,3% |

In assoluto: scarto mediano per pasto 42 kcal (p90 115 kcal), 3,4 g proteine (p90 9,5), 5,7 g carboidrati (p90 20,7), 2,6 g grassi (p90 7,0).

**Per giorno completo (n = 43)**

| Macro | Mediana | p10 | p90 | Max (con segno) | Mediana |abs| | Entro +-3% |
|---|---|---|---|---|---|---|
| kcal | +2,6 | -5,3 | +22,1 | +32,3 | 5,4 | 30,2% |
| Proteine | +3,9 | -3,1 | +20,5 | +44,2 | 4,9 | 34,9% |
| Carboidrati | -1,2 | -18,6 | +17,5 | +62,5 | 10,2 | 16,3% |
| Grassi | -1,7 | -20,4 | +17,7 | +43,8 | 12,5 | 20,9% |

**Sensibilita' con il placeholder bresaola (A+B+C, pasti n = 390, giorni n = 79).** Pasto kcal: mediana +2,0, p10 -15,8, p90 +37,5, max -99,5. Pasto proteine: -2,1 / -20,2 / +34,9 / +204,6. Giorno kcal: +2,5 / -5,5 / +17,4 / +32,3. Giorno proteine: -1,2 / -12,5 / +17,0 / +44,2. Giorno carboidrati: -1,1 / -15,4 / +16,3 / +62,5. Giorno grassi: +1,1 / -19,5 / +17,0 / +43,8. Pasti entro +-3% sulle kcal: 15,4%; giorni: 35,4%.

**Per profilo (A+B+C).**

| Profilo | Pasti kcal: mediana / p10 / p90 / max | Giorni kcal: mediana / p10 / p90 / max | Giorni proteine: mediana / p10 / p90 / max |
|---|---|---|---|
| P1 (284 pasti, 61 giorni) | +0,5 / -15,8 / +29,0 / -99,5 | +0,9 / -6,2 / +6,4 / +12,0 | -3,3 / -12,9 / +3,9 / -19,8 |
| P2 (106 pasti, 18 giorni) | +16,5 / -14,2 / +49,6 / +95,9 | +17,3 / +1,7 / +29,7 / +32,3 | +14,6 / +4,0 / +27,5 / +44,2 |

Letture:
- Per P1 il modello e' mediamente centrato e il giorno e' entro +-6% nell'80% dei casi; per P2 il modello sovrastima in modo sistematico (+17% kcal giornaliere): pasti leggeri come yogurt, spuntini e porzioni con olio dichiarati con kcal molto piu' alte di quelle del catalogo.
- Il valore estremo -99,5% e' un pasto dichiarato "1 kcal" per 186 kcal calcolate; un altro dichiara 32 kcal per 231 calcolate. Sono errori aritmetici reali nel dichiarato, non del catalogo.
- I macro dichiarati sono internamente quasi coerenti: kcal contro 4P+4C+9F ha mediana +2,5%, mediana |abs| 3,0%, 90% dei pasti entro 10%. Il modello rispetta l'aritmetica che il validatore controlla ma non la composizione reale degli alimenti: e' il difetto che il progetto vuole eliminare.
- Il dichiarato giornaliero coincide con il target kcal (scarto mediano 0,0%) e sta entro 0,8% sulle proteine (ABC), ma sui carboidrati e' lontano 3,7% e sui grassi 9,4% dai target di macro del giorno: gia' oggi i target +-3% sui quattro macro (AGENTS.md) non sono rispettati dal modello nemmeno sulla propria aritmetica.
- Confrontabilita': con il catalogo A+B l'analisi per pasto copre il 92,9% dei pasti unici e quella per giorno il 52,4% dei giorni unici (43 su 82). Con A+B+C 99,0% e 96,3%.

## 6. Riparabilita'

Giorni interamente risolti, target dal piano. Catalogo A+B+C (n = 79) e A+B (n = 43).

| Metrica | A+B+C (79) | A+B (43) |
|---|---|---|
| kcal entro +-3% PRIMA | 35,4% | 30,2% |
| kcal entro +-1% PRIMA | 10,1% | 9,3% |
| proteine entro +-3% PRIMA | 19,0% | 18,6% |
| kcal e proteine entro +-3% PRIMA | 7,6% | 7,0% |
| kcal entro +-1% DOPO | 98,7% | 97,7% |
| kcal entro +-3% DOPO | 100% (max 1,8%) | 100% (max 1,8%) |
| proteine entro +-1% DOPO | 84,8% | 72,1% |
| proteine entro +-3% DOPO | 89,9% | 81,4% |
| kcal e proteine entro +-3% DOPO | 89,9% | 81,4% |
| carboidrati entro +-3% DOPO | 38,0% | 34,9% |
| grassi entro +-3% DOPO | 75,9% | 69,8% |
| tutti e quattro entro +-3% DOPO | 35,4% | 30,2% |
| errore kcal mediano PRIMA -> DOPO | 4,9% -> 0,02% | 5,6% -> 0,03% |
| errore proteine mediano PRIMA -> DOPO | 9,7% -> 0,17% | 10,7% -> 0,17% |
| passate (mediana / p90 / max) | 47 / 103 / 200 | 54 / 119 / 200 |
| tempo per giorno in Python (mediana / p90 / max), due esecuzioni consecutive | 5,9-6,5 / 13,6-16,0 / 24,8-50,8 ms | 6,0-10,8 / 17,9-20,2 / 29,5-48,9 ms |

Per profilo (A+B+C): P1 (61 giorni) 100% entro +-3% sia su kcal sia su proteine; P2 (18 giorni) 100% su kcal, 55,6% su proteine (10 giorni su 18). In P2 le proteine del modello sono sovrastimate di mediana 21,7% prima della riparazione: il target proteico non e' raggiungibile entro i limiti con le sole kcal vincolate. Sui giorni falliti e' il vincolo, non il solutore: con pesi invertiti (proteine prima delle kcal) i giorni che mancano kcal o proteine entro +-3% salgono a 19 su 79 invece di 9 (prova esplorativa fuori dallo script).

Sensibilita' ai limiti (A+B+C, kcal +-3% / kcal e proteine +-3%): limiti di progetto [0,6x-1,5x], +-40% pasto: 100% / 89,9%; larghi [0,5x-2,0x], +-60%: 100% / 96,2%; stretti [0,8x-1,25x], +-25%: 94,9% / 75,9%. (A+B: 100/81,4, 100/93,0, 90,7/65,1.)

Nota sulle passate: la percentuale di giorni riusciti e' quasi invariata gia' a 10 passate rispetto a 30, 200 e 1000 (prova esplorativa fuori dallo script: kcal entro +-1% 99% a 10 passate, proteine entro +-3% 90% a 10 e 200 passate); le 200 passate raggiunte da un solo giorno indicano solo la tolleranza 0,5 g non raggiunta. Sono tempi Python senza ottimizzazione e variano tra esecuzioni; su JVM sono attesi inferiori, non misurato.

## 7. Rischi crudo/cotto

- Nessuna riga e' marcata cotta; il 22% delle righe e' marcato esplicitamente crudo/secco ("secco", "crudo", "grezzo", "peso a crudo"); il resto e' "fresco", "liquido", "surgelato" o non valido. "Fresco" in italiano indica il prodotto non cotto, quindi la lettura piu' probabile e' crudo, ma non e' verificabile dal dato.
- Stima dell'errore se i pesi degli alimenti per cui il catalogo ha una controparte cotta (riso, pasta, quinoa, cous cous, farro, orzo, patate, patate dolci, zucchine, spinaci, broccoli, fagiolini, carote, asparagi, melanzane, pollo, merluzzo, salmone, fiocchi d'avena) fossero in realta' pesi da cotto, usando le rese del catalogo (crudo vs cotto per 100 g):
  - tutte le voci con controparte cotta: scarto mediano 10,1% delle kcal giornaliere di target (p90 15,5%), limite superiore teorico;
  - solo quelle non marcate esplicitamente crude: mediana 3,6% (p90 6,9%).
  Rese (kcal/100 g crudo -> cotto): riso 365 -> 130, quinoa 368 -> 120 (il peso cotto vale 2,8-3x il crudo: errore di kcal fino a +180% per quella voce se si scambia lo stato), pollo 120 -> 165 e patate 69 -> 87 (errore di 26-38% in senso opposto). Il rischio e' concentrato su cereali, quinoa, riso e carne.
- Conclusione: con foodId esplicito lo stato (crudo/cotto) diventa proprieta' della voce di catalogo e il rischio sparisce; resta da fare per le voci cotte del catalogo (castagne bollite, broccoli bolliti).

## 8. Limiti dello spike

1. Due soli profili e una sola pipeline di generazione (lessico ripetitivo: 157 nomi, 81 alimenti). Il catalogo e' stato costruito guardando questi stessi dati: la copertura 94-99% e' un limite superiore ottimistico. Indizio sulla generalizzazione: le righe il cui nome compare una sola volta sono 1,4% (stima di massa non vista); non e' una stima della copertura su altri utenti.
2. Differenze di composizione USDA/Italia non separabili dalla deviazione del modello: yogurt greco 0% (USDA 59 kcal/100 g), kefir (USDA magro 43 kcal/100 g), latte parzialmente scremato mappato su 2% (50 kcal), fiocchi di latte, ricotta, pane, olio "solo evo", frutta (mela 52 kcal/100 g). Un errore di catalogo del 5-10% sposta le deviazioni per pasto. Le deviazioni vanno lette come "modello rispetto a catalogo USDA", non come verita' nutrizionale.
3. Proxy di tier B e placeholder C sono giudizi manuali non verificati; il valore bresaola (150 kcal, 32 g proteine, 2,5 g grassi) e' una stima tipica di etichetta, non una fonte.
4. Il solutore e' una versione semplice con pesi e penalita' scelti qui; non e' il design finale. Non e' stata testata la modalita' "infattibile -> intervallo kcal al modello", ne' alternative pasto, sgarri o piani adattati.
5. Selezione: la riparazione vale solo sui giorni risolti. Con A+B il 52,4% dei giorni; i giorni con alimenti italiani specifici (bresaola) sono esclusi o coperti dal segnaposto.
6. Deduplicazione di pasti e giorni identici: le distribuzioni sono sui pasti unici, non sulla frequenza con cui il modello li ha proposti. P1 contribuisce molto piu' di P2 a copertura e statistiche aggregate; P2 ha pochi giorni (18) e un bias sistematico.
7. I tempi sono Python, una sola macchina, senza JIT.
8. Non valutati: licenza CREA, Ciqual/ANSES, costi in token con catalogo nel prompt, qualita' del riconoscimento fuori da questi due profili.

## 9. Conclusioni e raccomandazioni

**Fattibilita'.** Il calcolo dei macro dal database e la riparazione a livello giorno funzionano per le kcal: 100% dei giorni risolti entro +-3% (98,7% entro +-1%), con grammi vicini a quelli del modello (limiti 0,6x-1,5x) e arrotondamento a 5 g. Senza riparazione solo il 35% dei giorni sarebbe entro +-3% sulle kcal calcolate: il dichiarato del modello non e' una misura affidabile.

**Quante voci servono.** Per questi due profili 39 alimenti coprono il 90% delle righe, 48 il 95%, 62 il 98%; il mini-catalogo da 80 voci copre 99,2% (94,1% con le sole voci USDA dirette). Per generalizzare ad altri utenti il target di 350-500 voci del progetto resta ragionevole ma non e' dimostrato da questo campione: va misurato con piu' profili, e con una fonte italiana per salumi, pane, latticini e piatti (CREA o etichette), dove USDA non ha equivalente.

**Politica del validatore (kcal / kcal+proteine / tutti).**
- Kcal: bloccante, +-3% calcolate dal catalogo dopo riparazione. Misurato 100% dei giorni.
- Proteine: obiettivo di riparazione con priorita' 2, non bloccante finche' non si decide un'altra soglia: +-3% e' raggiunto nell'89,9% dei giorni con i limiti del progetto e nel 96,2% con limiti piu' larghi; i giorni falliti non sono risolvibili senza cambiare le ricette (caso tipico: modello che sovrastima le proteine di ~20%). Opzione B (kcal+proteine >= 97%) non e' supportata con i limiti attuali; richiede limiti piu' larghi o il ritorno al modello dell'intervallo raggiungibile.
- Carboidrati e grassi: solo avvisi. Con i target di AGENTS.md (+-3% su tutti i macro) il 35% dei giorni li rispetta dopo riparazione e il dichiarato del modello oggi non li rispetta (grassi 9,4% mediano dai target). Il conflitto AGENTS.md (+-3% tutti) vs codice (+-3% solo kcal) va risolto in favore di "kcal bloccante, proteine obiettivo, C/F informativi" oppure con soglia piu' larga su C/F.
Raccomandazione: opzione A come regola bloccante, B come obiettivo di riparazione, C solo informativo; rivalutare dopo shadow mode di 2-4 settimane con piu' profili.

**Segnali per il design finale.**
1. Il protocollo MFP2 con foodId risolve nomi corrotti, stato crudo/cotto e ml/cucchiai in un colpo solo.
2. Le dosi pratiche devono derivare da porzioni del catalogo (olio 13,5 g/cucchiaio da USDA, non 10 g come oggi).
3. Il campo `weightState` va abbandonato come fonte (8% sporco, "fresco" ambiguo).
4. Servono voci italiane che USDA non ha: bresaola, prosciutto crudo, pane carasau, skyr, proteine in polvere, frutti di bosco.
5. Il caso "kcal dichiarate 1 o 32" mostra che un controllo di plausibilita' sul dichiarato continua a servire finche' i macro non sono tutti calcolati.
