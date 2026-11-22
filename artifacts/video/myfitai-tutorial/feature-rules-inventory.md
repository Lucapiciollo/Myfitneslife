# MyFitAI — copertura della guida documentario

Stato del copione: allineato al `DEVELOPMENT_PLAN.md` consultato il 22/11/2026. La guida descrive le funzioni V1 implementate e rende espliciti i limiti del profilo QA. Non presenta come live le funzioni IA che non possono essere provate con il provider disattivato.

## Funzioni coperte

| Area | Scena | Funzione documentata |
|---|---:|---|
| Avvio e profilo QA | 01–03 | Onboarding, profilo attivo, dati sintetici e dati personali modificabili |
| Navigazione | 02 | Home, Cibo, Progresso, Altro e selezione del profilo |
| Calcoli locali | 04–05 | BMI, BMR, TDEE, target/macronutrienti, coefficienti attivita, consumo e valori non disponibili |
| Attività | 06 | Livello di attività nel profilo; programma e diario allenamenti rimossi |
| Piano alimentare | 07–10 | Settimane/giorni, generazione esplicita, JSON strutturato, validator locale, dettaglio e preparazione |
| Target e ingredienti | 08–10 | Tolleranza ±3%, retry ±4%, quantità/unità/displayDose, condimenti e bevande conteggiati |
| Preferenze e timing | 10–11 | Priorità sicurezza/target/timing/preferenze/stagionalità; vincoli rigidi distinti dai gusti |
| Consumi | 12 | Stati consumato/saltato, totali reali e preservazione degli snapshot tra versioni |
| Cambio pasto | 13 | Ricerca da storico stesso tipo/orario compatibile e ±10%; cinque alternative IA a calorie uguali; conferma esplicita |
| Extra e adattamento | 14 | Modalità rapida/dettagliata, stima prudente e modifica dei soli pasti futuri |
| Spesa | 15 | Aggregazione deterministica dal piano, senza IA |
| BIA e misure | 16–17 | Inserimento/import BIA con preview, misure corporee, comparabilità e storico |
| Progressi e analisi | 18–19 | Metriche/intervalli, trend, analisi strutturate e review di settimana completata |
| Consiglio IA | 20 | Richiesta one-shot, proposte e accettazione separata |
| Configurazione | 21 | Percorso alimentare, numero pasti, orari e automazioni facoltative |
| Notifiche | 22 | Promemoria locali, anteprima menu e preferenze |
| IA e credenziali | 23 | Gemini/OpenAI, provider attivo, Keystore, AES-GCM e funzioni disabilitate senza provider |
| Storico ed export | 24 | Versioni, consumi, sgarri, BIA, misure, review, JSON/CSV/PDF e backup |
| Privacy e limiti | 25–26 | Conferme per azioni distruttive, nessuna cancellazione in ripresa, non-diagnosi e non-punizione |

## Regole di prodotto narrate

1. I calcoli numerici (BMI, BMR, TDEE, target, macro, medie, delta e trend) sono responsabilità locale dell'app, non dell'LLM.
2. I target sono dinamici. La validazione nutrizionale mira a ±3%; i retry di generazione possono usare ±4%; la validazione finale `appValidation` è autorevole.
3. I provider restituiscono JSON strutturato, validato per schema e regole di business prima della persistenza. Il testo libero dell'IA non è fonte di verità per la UI.
4. Ingredienti con impatto calorico o sul sodio richiedono quantità numerica, unità e `displayDose`. Condimenti e bevande caloriche non si omettono; `q.b.`, un filo e un po' non sono dosi accettabili.
5. Priorità di scelta: sicurezza e dati utente, target, timing/comfort, preferenze/storico, stagionalità e praticità. La stagionalità è una preferenza, non un vincolo superiore.
6. Non si impongono regole arbitrarie su frutta al mattino o carboidrati la sera. Sodio, fibre, grassi, volume, timing e sensibilità dichiarata sono fattori contestuali, non diagnosi.
7. I livelli di attivita quotidiana sono Sedentario ×1,20, Moderato ×1,55 e Molto attivo ×1,725; il TDEE e calcolato dall'app a partire dal BMR.
8. Consumi già registrati e piani trascorsi restano storico. Una nuova generazione crea una nuova versione; non riscrive il passato.
9. Le alternative da storico rispettano tipo, slot e orario compatibile e una tolleranza calorie ±10%; le alternative IA sono esattamente cinque, nello stesso slot e con calorie uguali al pasto sorgente. Nessuna sostituzione si applica senza conferma.
10. Uno sgarro non porta a compensazioni punitive né modifica automaticamente i macro-target. L'adattamento riguarda solo pasti futuri e richiede conferma.
11. La lista della spesa è aggregata localmente dagli ingredienti del piano; i totali non vengono delegati all'IA.
12. BIA, misure e trend includono peso, massa grassa, massa muscolare, vita e proporzioni; sono informativi e non diagnostici. Un dato assente resta tale; una variazione idrica non equivale automaticamente a variazione di grasso.
13. La review settimanale richiede un periodo completato. Il consiglio nutrizionale è one-shot, restituisce esattamente cinque proposte ordinate, non salva una conversazione e richiede accettazione esplicita.
14. Gemini e OpenAI sono selezionabili a runtime, con un solo provider attivo. Le chiavi sono cifrate con Android Keystore/AES-GCM, protette da screenshot e mai esportate, loggate o incluse nei backup.
15. Senza provider IA configurato, generazioni, analisi e automazioni IA non partono; le schermate e i calcoli locali rimangono disponibili.
16. La versione corrente non include un programma o diario allenamenti. Il livello di attività quotidiana resta nel profilo.

## Limiti della dimostrazione

- Il profilo `QA Home Live` contiene dati sintetici persistenti nella variante QA.
- Il provider IA è disattivato: le scene che spiegano generazione, review, consiglio e adattamento sono narrazione funzionale, non risultati live.
- La guida non conferma import, cancellazioni, salvataggi aggiuntivi o consumi durante le riprese.
- Il ripristino di un backup viene presentato come flusso che richiede conferma; la guida non avvia il ripristino.
- La voce è sintetica Piper Serena High, non una registrazione umana; ritmo impostato lento e pause tra frasi.

## Fonti

- `DEVELOPMENT_PLAN.md` — stato di implementazione e verifiche V1.
- `AGENTS.md` — vincoli di QA, privacy, nutrizione e fedeltà dei contenuti.
- `05-domain/01-local-engine.md` — calcoli deterministici.
- `06-ai/01-ai-boundaries.md` — confini dell'IA.
- `06-ai/02-food-adjustment-flow.md` — adattamento alimentare.
- `06-ai/05-openai-key-security.md` — sicurezza delle credenziali.
- `06-ai/providers/shared/CONDIMENTS_BEVERAGES_RULES.md` — quantità e condimenti.
- `06-ai/providers/shared/SEASONALITY_TIMING_RULES.md` — timing e stagionalità.
- `07-export/01-json-contract.md` — export e restore.