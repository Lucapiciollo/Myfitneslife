# Modelli dati V1

## Profilo e misure
- UserProfile
- DailySchedule
- FoodPreferences
- BiaMeasurement
- BiaMeasurementConditions
- BodyMeasurement
- WorkoutEntry

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
