# Controllo vincoli alimentari — limiti dichiarati

**Il controllo è basato su elenchi curati, non è una garanzia medica.** Non sostituisce il parere di medico,
allergologo o dietista. L'app non può garantire l'assenza di un allergene o di un ingrediente vietato.

Implementazione: `FoodConstraintValidator` e `FoodSynonyms`
(`android/app/src/main/java/com/myfitai/app/domain/food/`). Test: `FoodSynonymsGoldenTest`,
`DietaryProfileTest`, `NutritionGuardrailsIntegrationTest`.

## Cosa viene controllato

- Nome di ogni ingrediente, **titolo del pasto**, nome e **nota** degli integratori (piano, alternative e pasti
  dello storico proposti come alternativa).
- Normalizzazione senza accenti e senza maiuscole, parole intere (mai sottostringhe: «grano» non intercetta
  «grana padano»), singolare/plurale italiano semplice (pomodoro/pomodori, uovo/uova, noce/noci,
  pistacchio/pistacchi, mollusco/molluschi).
- Famiglie curate: lattosio, latte e latticini, formaggi, burro, glutine, frutta a guscio, noce, arachidi, soia,
  sesamo, sedano, senape, lupini, pesce, crostacei, molluschi, uova, riso, più gli stili vegetariano, vegano e
  pescetariano (carne, salumi, selvaggina, gelatina, pesce, latticini, uova, miele secondo lo stile).
- Vincoli scritti come frase («allergia al glutine», «senza glutine», «intolleranza al lattosio»,
  «allergia alle noci») e due vincoli uniti da «e» se entrambi sono famiglie note.
- Eccezioni note: «senza lattosio»/«delattosato» non violano il lattosio (ma violano un'allergia al latte);
  «senza glutine», «pasta di riso/mais/legumi», «grano saraceno» non violano il glutine;
  «burro di arachidi/cacao», «latte di mandorla/cocco/soia/avena» non sono burro/latte;
  «noce di cocco», «noce moscata», «noce di vitello» non sono noci; «wurstel vegetale» non è carne.
  «Risotto» viola un vincolo sul riso ma non sul glutine.

## Scelte conservative documentate

- L'avena conta come glutine (contaminazione); il grano saraceno no.
- Un'allergia o intolleranza chiamata «latte» significa tutti i latticini; un alimento escluso chiamato «latte»
  significa solo il latte.
- Un'etichetta «vegetale/vegano» rende innocuo l'intero ingrediente per le famiglie animali (anche «brodo vegetale
  e pollo» sarebbe accettato).
- Il pescetariano esclude solo carne (e salumi): la gelatina non è esclusa.
- La preparazione (testo libero) non è controllata, per non bloccare frasi come «cuocere senza burro».

## Cosa NON viene garantito

- Ingredienti nascosti (salse, dadi, ripieni, additivi), tracce, contaminazioni crociate, caseina o lattosio
  nei prodotti confezionati, caglio animale nei formaggi, chiarificanti nel vino.
- Nomi non italiani, errori di battitura, abbreviazioni, nomi commerciali non presenti negli elenchi.
- Alimenti che non compaiono negli elenchi: se manca, non viene intercettato. Per aggiungere un termine
  modificare `FoodSynonyms.kt` e la tabella di `FoodSynonymsGoldenTest`.
- Quantità minime o dosi: un vincolo è binario, non valuta la soglia di tolleranza individuale.

## Preferenze illeggibili

Se `dietaryPreferencesJson` è corrotto o ha una forma non valida, `DietaryProfile.parse` restituisce
`status = UNREADABLE` (il testo grezzo resta in `notes`). Non è mai equivalente a «nessun vincolo»:
generazione del piano, alternative e consigli si fermano con il codice stabile
`PROFILE_CONSTRAINTS_UNREADABLE` e il messaggio che chiede di riaprire e salvare di nuovo le preferenze
alimentari. Anche `FoodConstraintValidator` fallisce in modo chiuso con lo stesso codice.
