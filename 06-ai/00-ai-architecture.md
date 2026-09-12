# MyFitAI — Architettura agenti V1

## Stato
Le definizioni degli agenti sono pronte ma **NON sono collegate al codice Android**.
L'app resta l'orchestratore. Gli agenti non comunicano direttamente fra loro e non scrivono nel database.

## Agenti V1
1. `NutritionAgent` — genera o modifica il menu alimentare settimanale.
2. `PlanReviewAgent` — valida un piano generato prima che venga accettato dall'app.
3. `ProgressAnalysisAgent` — interpreta trend storici già calcolati dall'app e produce una review strutturata.

La lista della spesa NON richiede un agente: viene aggregata localmente dagli ingredienti del piano. Eventuali normalizzazioni future sono opzionali.

## Pipeline obbligatoria
App -> calcoli locali -> richiesta JSON -> agente -> risposta JSON -> JSON Schema -> validazione business -> eventuale PlanReviewAgent -> persistenza -> UI.

Nessun testo libero prodotto dall'IA deve essere mostrato direttamente come fonte di verità nella UI.

## Trigger
- Nuova settimana -> `NutritionAgent.generate_weekly_plan`
- Sgarro -> `NutritionAgent.adjust_after_deviation`
- Fine settimana -> `ProgressAnalysisAgent.weekly_review`
- Nuova BIA/misure -> `ProgressAnalysisAgent.body_response_review`
- Piano generato/modificato -> `PlanReviewAgent.review_plan`

## Regole comuni
- Non inventare valori mancanti.
- Non fare diagnosi.
- Non prescrivere farmaci/integratori come terapia.
- Non modificare target numerici calcolati dall'app salvo richiesta esplicita con motivazione e segnalazione.
- Usare esclusivamente dati forniti dall'app e regole/documentazione controllata disponibile nel contesto.
- Se dati essenziali mancano: `status=NEEDS_INPUT`.
- Se il caso esce dal dominio: `status=OUT_OF_SCOPE`.
- Output sempre conforme allo schema previsto.
