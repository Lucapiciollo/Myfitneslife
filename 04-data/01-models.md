# Modelli dati V1

## Profilo e misure
- UserProfile
- DailySchedule
- FoodPreferences
- BiaMeasurement
- BiaMeasurementConditions
- BodyMeasurement
- WorkoutEntry (DEPRECATO dal 13/11/2026: tabella `workouts` eliminata con la migrazione 19->20 (Room v20); l'export v3 non la include; i backup v1/v2 restano importabili ignorandola)
- DailyActivityCheckIn (DEPRECATO dal 13/11/2026: tabella `daily_activity_checkins`, eliminata con la stessa migrazione)

## Alimentazione
- NutritionTarget
- WeeklyMealPlan
- MealPlanVersion
- MealPlanDay
- Meal
- Ingredient
- FoodDeviation
- PlanAdjustment
- MealCompletion
- ShoppingList
- ShoppingListItem

## Analisi/storico
- WeeklyReview
- TrendSnapshot
- PersonalResponseProfile
- HistoricalPattern
- AiAnalysis
- EvidenceReference

## Regola
`WeeklyMealPlan` e le sue versioni sono immutabili: una modifica crea sempre una nuova `MealPlanVersion`; non sovrascrivere lo storico.
