# Controllo vincoli alimentari — limiti dichiarati

**Controllo su elenco, non garanzia medica.** Il controllo è basato su elenchi curati. Non sostituisce il parere di
medico o allergologo. L'app non può garantire l'assenza di un allergene o di un ingrediente vietato.

Implementazione: `FoodConstraintValidator`, `FoodSynonyms`, `DietaryProfile`
(`android/app/src/main/java/com/myfitai/app/domain/food/`). Test: `FoodSynonymsGoldenTest`,
`DietaryProfileTest`, `NutritionGuardrailsIntegrationTest`, `AiWorkflowIntegrationTest` (strumentato).

## Cosa viene controllato

- Nome di ogni ingrediente, nome e nota degli integratori, **titolo del pasto** (piano, alternative, pasti dello
  storico proposti come alternativa). Non la preparazione (testo libero).
- Normalizzazione senza accenti e maiuscole, parole intere (mai sottostringhe: «grano» non intercetta «grana padano»),
  singolare/plurale italiano semplice (pomodoro/pomodori, uovo/uova, noce/noci, mollusco/molluschi).
- Famiglie curate: lattosio, latte e latticini, formaggi, burro, glutine, frutta a guscio (e singole noci), arachidi,
  soia, sesamo, sedano, senape, lupini, pesce, crostacei, molluschi, uova, riso, carne, gelatina, miele; stili
  vegetariano, vegano, pescetariano. L'etichetta `onnivoro`/`nessuno` non è una restrizione.
- Vincoli scritti come frase («allergia al glutine», «senza glutine», «intolleranza al lattosio»).

## Vincoli non riconosciuti (ripiego prudente)

Un vincolo che non coincide con un nome in elenco non viene mai ignorato:

1. i nomi noti contenuti nel testo vengono riconosciuti («latticini e derivati», «frutta con guscio», «pesce azzurro»);
2. «derivati» allarga un alimento al suo gruppo («latte e derivati» = tutti i latticini);
3. ogni altra parola significativa (≥ 4 lettere, non stopword) viene cercata da sola nei nomi («kiwi giallo» →
   «kiwi»).

Questi vincoli sono **controllati solo per nome**: `DietaryProfile.constraintNotices` li elenca con motivo
`NAME_ONLY`, così la schermata preferenze potrà dirlo all'utente. Lo stesso elenco segnala `LIMITED` per **nichel** e
**solfiti** (limiti dichiarati: si trova solo la parola stessa, non gli alimenti che li contengono) e `UNKNOWN_STYLE`
per stili di dieta non riconosciuti (es. «paleo», non applicati).

## Etichette («senza lattosio», «vegetale»…)

- «senza X», «privo di X», «non contiene X» tolgono X dal testo («Burro senza lattosio»).
- Le etichette «senza lattosio/delattosato», «senza glutine», «vegetale/vegano» esentano **solo la parola adiacente**
  (a non più di due parole, senza «con», «e», «in»… in mezzo). «Burger vegetale con formaggio» viola i latticini;
  «Insalata di vegetali con tonno» viola l'allergia al pesce; «Formaggio vegetale» no.
- «senza lattosio» non esenta un'allergia al latte.
- Prodotti noti: «burro di arachidi/cacao», «latte di mandorla/cocco/soia/avena», «noce di cocco», «noce moscata»,
  «pasta/pane/cracker/gallette/biscotti/farina di riso, mais, legumi…» (non glutine), «polpette/carpaccio di pesce o
  verdure» (non carne).

## Titolo del pasto

Nel titolo contano solo i termini **forti e non ambigui** (seitan, orzo, grano, mozzarella, pollo, tonno, uova…). I
termini generici che hanno varianti senza il problema (pasta, pane, latte, yogurt, hamburger, salame…) nel titolo
non producono violazioni: decide l'elenco ingredienti, che è autorevole. «Pasta al pomodoro» con ingrediente «Pasta
di riso» è accettato per il glutine; con «Pasta integrale» è rifiutato.

## Scelte conservative

- L'avena conta come glutine (contaminazione); il grano saraceno no. «Risotto» viola un vincolo sul riso, non sul glutine.
- Un'allergia/intolleranza chiamata «latte» significa tutti i latticini; un alimento escluso «latte» solo il latte
  (salvo «latte e derivati»).
- Il pescetariano esclude solo la carne (e salumi): gelatina non esclusa.

## Casi NON coperti (falsi negativi noti)

Piatti il cui nome non rivela gli ingredienti: **tortilla, wrap, pancake**, e in generale pizza/focaccia per il
lattosio, ragù/polpette/cotoletta/panko per le uova o il latte, gelati e dolci per i frutti a guscio, «salsa di soia»
per chi evita la sola soia in forma di parola isolata (la salsa di soia è invece coperta per soia e glutine).
Coperti perché certi e a basso rischio: polipo, cotoletta, ragù, polpette, carpaccio (carne), cappuccino, tiramisù
(latte, uova, glutine), bagel, panko (glutine), salsa di soia (soia, glutine).
Mancano inoltre: ingredienti nascosti (salse, dadi, ripieni, additivi), tracce e contaminazioni, caglio animale,
chiarificanti del vino, nomi non italiani, errori di battitura, nomi commerciali e alimenti non presenti negli
elenchi. Per aggiungere un termine: `FoodSynonyms.kt` e la tabella di `FoodSynonymsGoldenTest`.

## Preferenze illeggibili

Se `dietaryPreferencesJson` è JSON corrotto, ha una forma non valida o è **testo libero legacy** (es. «Allergico alle
noci, niente lattosio»), `DietaryProfile.parse` restituisce `status = UNREADABLE`:

- non equivale a «nessun vincolo»: piano, alternative, applicazione di un'alternativa e consigli si fermano;
  il piano settimanale in background termina con un fallimento definitivo (non ritentabile) e un messaggio chiaro;
- il testo originale resta disponibile in `rawLegacyText` (e in `notes`) per mostrarlo nella schermata preferenze;
  non viene mai convertito in allergie e non viene salvato da `toJson`;
- il messaggio per l'utente non contiene codici; il codice stabile `PROFILE_CONSTRAINTS_UNREADABLE` è nel campo
  `code` delle eccezioni `ConstraintsUnreadable` e in `DietaryProfile.UNREADABLE_CODE`.

La chiave legacy `style` è letta come `dietStyle` (salvo «onnivoro»/«nessuno»).
