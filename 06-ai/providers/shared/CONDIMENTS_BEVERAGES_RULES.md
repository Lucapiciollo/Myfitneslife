# Condiments & Beverages Rules — VINCOLANTE

Queste regole sono condivise da Gemini e OpenAI e si applicano a generazione, adattamento e revisione dei piani.

## Principio
Condimenti e bevande non sono mai implicitamente “gratis”. Se apportano energia, zuccheri, grassi, sodio o altri nutrienti quantitativamente rilevanti devono comparire negli ingredienti ed essere conteggiati nei totali del pasto e della giornata.

## Dose numerica + dose comprensibile
Ogni ingrediente deve mantenere `quantity` numerica e `unit` separata, usate dal motore di calcolo. Deve inoltre avere `displayDose`, una descrizione pratica per l’utente.

Esempi ammessi:
- olio EVO: `quantity: 5`, `unit: "ml"`, `displayDose: "1 cucchiaino"`;
- olio EVO: `quantity: 2.5`, `unit: "ml"`, `displayDose: "1/2 cucchiaino"`;
- zucchero: `quantity: 5`, `unit: "g"`, `displayDose: "1 bustina da 5 g"` SOLO se il peso della bustina è noto;
- miele: `quantity: 5`, `unit: "g"`, `displayDose: "circa 1 cucchiaino raso"`;
- latte: `quantity: 200`, `unit: "ml"`, `displayDose: "1 bicchiere da 200 ml"`.

`displayDose` è solo una rappresentazione pratica: non sostituisce mai `quantity` + `unit` e non è usato per i calcoli.

## Conversioni domestiche
Usare equivalenze domestiche solo quando sufficientemente standard o definite nella knowledge base dell’app.
- 1 cucchiaino di liquido = 5 ml;
- 1/2 cucchiaino = 2.5 ml;
- 1 cucchiaio = 15 ml.

Non inventare il peso di bustine, confezioni, cucchiai colmi/rasi o porzioni di marca. Se il peso reale non è noto, usare grammi/ml come dose principale e una descrizione prudente (es. `"5 g (~1 cucchiaino raso)"`) oppure `NEEDS_INPUT` quando la precisione è necessaria.

## Condimenti
Devono essere espliciti e conteggiati quando presenti: olio, burro, margarina, maionese, ketchup, pesto, salse, salsa di soia, formaggi/grana usati come condimento, zucchero, miele, sciroppi e analoghi.

È vietato usare quantità vaghe come “un filo d’olio”, “q.b.”, “un po’ di salsa”, “una manciata” quando il condimento influenza calorie, macro o sodio.

Sale, spezie, erbe, aceto e succo di limone possono essere indicati in dose pratica; se il sodio è rilevante, la quantità di sale/salsa salata deve essere esplicita e considerata nel bilanciamento della giornata.

## Bevande
- acqua: non calorica, non entra nei macro;
- caffè/tè senza zucchero o latte: energia trascurabile, ma eventuali aggiunte devono essere ingredienti separati;
- latte, bevande vegetali, succhi, bibite zuccherate, sport drink e bevande caloriche: devono essere conteggiati;
- bevande zero/non caloriche: non richiedono compensazione calorica e non devono essere presentate come strumento dimagrante;
- alcol: se presente, va conteggiato energeticamente e trattato come alimento flessibile/occasionale, mai come idratazione;
- caffeina: considerare timing e quantità quando forniti, soprattutto vicino al sonno, senza prescrizioni cliniche;
- non inventare target idrici: usare quelli dell’app se disponibili.

## Bilanciamento giornaliero
Se un pasto è più ricco di sodio, grassi, fibre o volume, non vietare automaticamente l’alimento: riequilibrare i pasti successivi evitando di concentrare nuovamente lo stesso fattore, mantenendo i target dinamici entro ±3%.

## Controlli obbligatori del PlanReviewAgent
Il revisore deve rifiutare/segnalare come hard violation quando:
- un condimento calorico rilevante è presente ma non quantificato;
- una bevanda calorica non è conteggiata;
- `displayDose` manca su un ingrediente;
- `displayDose` è incompatibile con `quantity`/`unit`;
- vengono usate quantità vaghe (`q.b.`, `filo`, `un po’`) per ingredienti con impatto nutrizionale;
- una dose domestica è presentata come esatta senza equivalenza nota.

La validazione finale resta dell’app (`appValidation`), che deve verificare schema, quantità numeriche, unità, presenza di `displayDose` e coerenza dei totali.