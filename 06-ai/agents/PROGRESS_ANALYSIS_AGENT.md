# ProgressAnalysisAgent — runtime V2

## Ruolo
Interpreta i trend corporei multi-settimana già calcolati dall'app. È separato dalla Weekly Review: la review descrive la singola settimana, mentre questo agente valuta l'evoluzione storica.

Non genera diete, non cambia target calorici o macro e non decide il deficit. Le decisioni quantitative restano nel motore locale.

## Attivazione
- Manualmente dal tab Progressi, dopo conferma esplicita del costo/quota IA.
- Automaticamente dopo una precedente esecuzione riuscita, con intervallo configurabile in Impostazioni (1–12 settimane; default 4).
- Una nuova esecuzione manuale riuscita diventa il nuovo riferimento temporale e riavvia il conto alla rovescia automatico.
- Se non esiste ancora un'esecuzione riuscita, il ciclo automatico non parte: il tab mostra che serve la prima analisi.

## Input runtime compatto
L'app passa solo dati sintetici, non lo storico riga per riga:
- `P`: obiettivo e livello attività.
- `B0/B/BT`: BIA baseline, corrente e delta trend recente nell'ordine peso, body fat, massa muscolare, muscolo scheletrico, acqua, grasso viscerale.
- `BM0/BM/BMT`: baseline, corrente e trend delle 11 circonferenze nell'ordine petto, vita, addome, spalle, glutei, braccio sx/dx, coscia sx/dx, polpaccio sx/dx.
- `A`: conteggi sintetici di allenamenti, riposi, deviazioni registrate e finestra temporale.
- `RS`: classificazione locale di ricomposizione come solo contesto.

`?` significa dato non disponibile.

## Output runtime compatto
Protocollo `PA1` dentro il piccolo envelope JSON del provider:

```text
PA1
C|PR_ST_WL_MR_NT_ID|L_M_H
P|WT_BF_MU_WA_AB_LM_TR_DV_BC|F_U_X|L_M_H
S|summary
V|1_or_0|notes
```

Classificazioni:
- `PR`: ricomposizione positiva.
- `ST`: stabile.
- `WL`: dimagrimento senza chiaro segnale di rischio muscolare.
- `MR`: dimagrimento con possibile segnale muscolare da monitorare.
- `NT`: trend sfavorevole.
- `ID`: dati insufficienti/incoerenti.

Pattern: peso, body fat, muscolo, vita, addome, arti, training, deviazioni registrate e coerenza complessiva dei segnali. Massimo 6 pattern. `F/U/X` = favorevole/sfavorevole/incerto. La sintesi deve restare <=18 parole.

## Regole
- Valuta segnali congiunti; il peso da solo non basta.
- BIA e circonferenze sono stime/osservazioni, non prove causali.
- Abbassa la confidenza se i segnali sono discordanti o poco comparabili.
- Non inventare aderenza o consumo: i pasti pianificati non provano che siano stati consumati.
- Una deviazione registrata non descrive l'intera aderenza.
- Non diagnosticare malattie, disidratazione, edema o perdita muscolare.
- Non attribuire causalità certa a un alimento, piano o allenamento.
- Non ricalcolare KPI già forniti dall'app.
- Non modificare direttamente piano, calorie, macro o deficit.

## Token policy
Input posizionale e output a codici sono vincolanti per ridurre il costo. Non ripetere dati, non produrre spiegazioni discorsive, non restituire JSON verboso oltre all'envelope richiesto.

## Persistenza e scheduling
Solo un'analisi validata aggiorna `lastSuccess`, classificazione, confidenza, sintesi, provider e modello. `lastSuccess + intervallo configurato` determina la prossima esecuzione mostrata nel tab. Errori o dati insufficienti non spostano l'ultima esecuzione valida.
