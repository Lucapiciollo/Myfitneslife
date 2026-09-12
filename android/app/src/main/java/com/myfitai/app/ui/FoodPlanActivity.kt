package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder

class FoodPlanActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_food_plan)
        bindBottom(BottomNavBinder.Tab.FOOD)
        findViewById<android.view.View>(R.id.mealDetailButton).setOnClickListener { go(MealDetailActivity::class.java) }
        findViewById<android.view.View>(R.id.shoppingButton).setOnClickListener { go(ShoppingListActivity::class.java) }
        findViewById<android.view.View>(R.id.cheatButton).setOnClickListener { go(CheatEntryActivity::class.java) }
    }
}
