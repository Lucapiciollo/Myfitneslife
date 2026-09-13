package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.MealPlanRowView
import com.myfitai.app.ui.widgets.WeekDaySelectorView

class FoodPlanActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_food_plan)
        bindBottom(BottomNavBinder.Tab.FOOD)

        findViewById<WeekDaySelectorView>(R.id.weekDaySelector).setDays(
            listOf(
                WeekDaySelectorView.Day("Lun", "14"),
                WeekDaySelectorView.Day("Mar", "15"),
                WeekDaySelectorView.Day("Mer", "16"),
                WeekDaySelectorView.Day("Gio", "17"),
                WeekDaySelectorView.Day("Ven", "18"),
                WeekDaySelectorView.Day("Sab", "19"),
                WeekDaySelectorView.Day("Dom", "20"),
            ),
            selectedIndex = 0,
        )

        findViewById<MealPlanRowView>(R.id.mealBreakfast).apply {
            setTitle("Colazione")
            setKcal("520 kcal")
            setDescription("Yogurt greco, avena e banana")
            setImage(R.drawable.img_meal_breakfast)
            setOnClickListener { go(MealDetailActivity::class.java) }
        }
        findViewById<MealPlanRowView>(R.id.mealSnack).apply {
            setTitle("Spuntino")
            setKcal("280 kcal")
            setDescription("Frutta secca e mela")
            setImage(R.drawable.img_meal_snack)
            setOnClickListener { go(MealDetailActivity::class.java) }
        }
        findViewById<MealPlanRowView>(R.id.mealLunch).apply {
            setTitle("Pranzo")
            setKcal("710 kcal")
            setDescription("Riso basmati, pollo e verdure")
            setImage(R.drawable.img_meal_lunch)
            setOnClickListener { go(MealDetailActivity::class.java) }
        }
        findViewById<MealPlanRowView>(R.id.mealPreworkout).apply {
            setTitle("Pre-workout")
            setKcal("250 kcal")
            setDescription("Yogurt e frutta")
            setImage(R.drawable.img_meal_preworkout)
            setOnClickListener { go(MealDetailActivity::class.java) }
        }
        findViewById<MealPlanRowView>(R.id.mealDinner).apply {
            setTitle("Cena")
            setKcal("640 kcal")
            setDescription("Salmone, patate e verdure")
            setImage(R.drawable.img_meal_dinner)
            setOnClickListener { go(MealDetailActivity::class.java) }
        }

        findViewById<android.view.View>(R.id.shoppingButton).setOnClickListener { go(ShoppingListActivity::class.java) }
        findViewById<android.view.View>(R.id.cheatButton).setOnClickListener { go(CheatEntryActivity::class.java) }
    }
}
