# Step 19 — Weekly Review reale

## Obiettivo
Produrre una review settimanale persistente usando esclusivamente dati reali del profilo attivo, separando fatti locali deterministici da sintesi IA.

## Dati utilizzati
- ultima versione del piano della settimana;
- target del piano e medie pianificate della settimana;
- sgarri registrati;
- allenamenti e rest day;
- BIA della settimana;
- misure corporee della settimana;
- contesto sintetico del Personal Response Engine.

## Regole
- la settimana deve essere conclusa prima della generazione;
- l'aderenza non viene inventata: resta `null`/`n.d.` finché non esisterà un dato persistente sui pasti realmente consumati;
- calorie e macro mostrate nella review sono valori del piano, non consumo reale;
- i delta corporei richiedono almeno due valori reali della stessa metrica nella settimana;
- nessuna correlazione viene trasformata in causalità;
- nessun claim medico o diagnostico;
- il provider IA produce JSON strutturato, poi validato localmente;
- `agentValidation` non è autorevole;
- la review viene persistita in `WeeklyReviewEntity`, una sola per profilo/settimana tramite upsert;
- la rigenerazione sostituisce la review della stessa settimana, non modifica piani, BIA, misure, sgarri o allenamenti.

## UI
`WeeklyReviewActivity` apre per default l'ultima settimana completamente conclusa, consente navigazione verso settimane precedenti e mostra dati locali anche prima della generazione IA. La review può essere aperta da `Analisi IA` tramite `Approfondisci con IA`.

## Trigger
La review diventa eleggibile solo dopo la chiusura della settimana. La generazione resta esplicita per evitare chiamate IA/costi non richiesti; l'eventuale reminder schedulato automatico appartiene allo Step 21 (notifiche locali).

## QA ancora da eseguire
Build Gradle, instrumented test e prova reale provider/device restano nel controllo finale concordato.
