# Riesame UX 13/11/2026 (ux-reviewer, analisi statica: nessuna verifica a schermo)

Fonti: layout, Activity, token, mock V2, ANDROID_UI_DESIGN_SYSTEM.md. Non valutati: pixel, contrasto reale, font 1,3x/2x, TalkBack, Compose metriche, MealCardView.

## Interventi in ordine di valore
1. Quick win Home/accessibilita: avvisi in alto (H2), tocco profilo 48dp invece di 20dp (H3), secondo pasto a alpha 0,58 ma cliccabile (H8), niente gate IA sulla sola consultazione dei pasti (H7). S.
2. Cibo: Totale giornaliero subito dopo i pasti come nel mock, gestione piano sotto, stato vuoto con CTA, stile bottoni condiviso (C1, C5, C6, C7). S-M.
3. Vocabolario unico del target e glossario (Target / Target del giorno (piano) / Target teorico / Target abituale / TDEE; Extra vs sgarro). S-M. Prerequisito di 4 e 8.
4. Home: prima card in progressive disclosure (BMR/BMR BIA/TDEE nel dettaglio); card Totale di Cibo senza BMR/TDEE ripetuti (H1, C2). M.
5. Registrare un pasto piu in fretta: A barra azioni sticky nel dettaglio pasto (S); B azione inline su righe/card (M, decisione). Oggi 3 azioni da Home, 4 da Cibo.
6. Stati vuoti unificati con CTA in Home e Progresso (H4, P1, P2). M.
7. Feedback IA: verifica chiave con stato e errore inline senza svuotare il campo (A4), stato di generazione vicino al bottone. M.
8. Altro come hub: riga Profilo e preferenze (oggi il Profilo si apre solo dall'icona Home), gruppi Dati e backup / Zona di attenzione, meno ridondanza Impostazioni IA (A1, A2, A5, A3). M.

## Decisioni di prodotto necessarie
Ordine card Home rispetto al mock; dove vanno BMR/TDEE; C e F nel Totale (il mock li ha); messaggio motivazionale fisso (e testo del mock) o condizionale; nascondere Riserva di recupero a 0 (contraddice DEVELOPMENT_PLAN.md:332); fusione azioni di rilevazione e destinazione di Apri analisi dati; azione di consumo inline; riga Profilo in Altro; avviso IA fuori dalla Home; Extra o sgarro; CTA da Progresso verso Rilevazioni.

## Altro emerso
Apri analisi dati e Aggiungi rilevazione aprono entrambe l'hub Rilevazioni (5 CTA in 2 card); verifica chiave IA senza stato e con campo svuotato in errore; etichette bottom nav fino a 8sp; periodi grafico 1W/1M/3M/1Y senza contentDescription in Home.