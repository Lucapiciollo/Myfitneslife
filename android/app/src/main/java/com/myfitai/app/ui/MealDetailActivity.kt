package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.IngredientRowView
import com.myfitai.app.ui.widgets.SelectableSegmentView

class MealDetailActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_meal_detail)
        bindBack()
        bindBottom(BottomNavBinder.Tab.FOOD)

        val ingredients = listOf(
            R.id.ingredientRice to Triple("Riso basmati", "80 g", R.drawable.img_food_carbs),
            R.id.ingredientChicken to Triple("Petto di pollo", "200 g", R.drawable.img_food_protein),
            R.id.ingredientZucchini to Triple("Zucchine", "150 g", R.drawable.img_food_vegetables),
            R.id.ingredientCarrot to Triple("Carote", "100 g", R.drawable.img_food_vegetables),
            R.id.ingredientOil to Triple("Olio extravergine", "10 g", R.drawable.img_food_fats),
            R.id.ingredientSalt to Triple("Sale, spezie", "q.b.", R.drawable.img_food_condiments),
        )
        ingredients.forEach { (id, data) ->
            findViewById<IngredientRowView>(id).apply {
                setName(data.first)
                setQuantity(data.second)
                setIcon(data.third)
            }
        }

        val ingredientsContainer = findViewById<android.view.View>(R.id.ingredientsContainer)
        val preparationContainer = findViewById<android.view.View>(R.id.preparationContainer)
        findViewById<SelectableSegmentView>(R.id.detailSegment).apply {
            setSegments(listOf("Ingredienti", "Preparazione"), selectedIndex = 0)
            setOnSegmentSelectedListener { index ->
                ingredientsContainer.visibility = if (index == 0) android.view.View.VISIBLE else android.view.View.GONE
                preparationContainer.visibility = if (index == 1) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        findViewById<android.view.View>(R.id.consumedButton).setOnClickListener { finish() }
    }
}
