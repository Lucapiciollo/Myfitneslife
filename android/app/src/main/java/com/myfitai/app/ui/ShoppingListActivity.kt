package com.myfitai.app.ui

import android.os.Bundle
import com.myfitai.app.R
import com.myfitai.app.navigation.BottomNavBinder
import com.myfitai.app.ui.widgets.SelectableSegmentView
import com.myfitai.app.ui.widgets.ShoppingItemRowView

class ShoppingListActivity : BaseShellActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shopping_list)
        bindBack()
        bindBottom(BottomNavBinder.Tab.FOOD)

        findViewById<SelectableSegmentView>(R.id.viewModeSegment).setSegments(listOf("Settimana", "Categorie"), selectedIndex = 0)
        findViewById<SelectableSegmentView>(R.id.filterSegment).setSegments(listOf("Tutte", "Da comprare", "Presi"), selectedIndex = 0)

        val items = listOf(
            Triple(R.id.itemChicken, listOf("Petto di pollo", "1,4 kg", R.drawable.img_food_protein), false),
            Triple(R.id.itemSalmon, listOf("Salmone", "600 g", R.drawable.img_food_protein), true),
            Triple(R.id.itemEggs, listOf("Uova", "12 pz", R.drawable.img_food_protein), true),
            Triple(R.id.itemYogurt, listOf("Yogurt greco", "1,8 kg", R.drawable.img_food_dairy), false),
            Triple(R.id.itemRice, listOf("Riso basmati", "1,2 kg", R.drawable.img_food_carbs), false),
            Triple(R.id.itemPasta, listOf("Pasta integrale", "500 g", R.drawable.img_food_carbs), false),
            Triple(R.id.itemOats, listOf("Avena", "700 g", R.drawable.img_food_carbs), false),
            Triple(R.id.itemBanana, listOf("Banane", "7 pz", R.drawable.img_food_fruits), false),
            Triple(R.id.itemApple, listOf("Mele", "5 pz", R.drawable.img_food_fruits), false),
            Triple(R.id.itemZucchini, listOf("Zucchine", "1,5 kg", R.drawable.img_food_vegetables), false),
            Triple(R.id.itemCarrot, listOf("Carote", "1 kg", R.drawable.img_food_vegetables), false),
        )
        items.forEach { (id, data, checked) ->
            findViewById<ShoppingItemRowView>(id).apply {
                setName(data[0] as String)
                setQuantity(data[1] as String)
                setIcon(data[2] as Int)
                setChecked(checked)
            }
        }

        findViewById<android.view.View>(R.id.resetButton).setOnClickListener {
            items.forEach { (id, _, _) -> findViewById<ShoppingItemRowView>(id).setChecked(false) }
        }
        findViewById<android.view.View>(R.id.exportButton).setOnClickListener { finish() }
    }
}
