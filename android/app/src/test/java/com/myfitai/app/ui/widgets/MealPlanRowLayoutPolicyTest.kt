package com.myfitai.app.ui.widgets

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Riga pasto del Piano alimentare: nessuna parola spezzata e azioni sempre da 48dp, anche con font 1,3x e 2x. */
class MealPlanRowLayoutPolicyTest {
    private val resDir = listOf("src/main/res", "app/src/main/res", "android/app/src/main/res")
        .map(::File).first { File(it, "values/dimens.xml").exists() }

    private fun dimenDp(name: String): Float =
        Regex("""<dimen name="$name">([0-9.]+)dp</dimen>""").find(File(resDir, "values/dimens.xml").readText())
            ?.groupValues?.get(1)?.toFloat() ?: error("Token dimen mancante o non in dp: $name")

    @Test
    fun actionsStayOnTheSideWhenTextHasRoom() {
        assertFalse(MealRowLayoutPolicy.actionsBelow(rowContentWidthPx = 900, reservedSideActionsPx = 300, longestWordPx = 150f, minTextWidthPx = 400f))
    }

    @Test
    fun actionsMoveBelowWhenLongestWordWouldBreak() {
        assertTrue(MealRowLayoutPolicy.actionsBelow(rowContentWidthPx = 900, reservedSideActionsPx = 300, longestWordPx = 650f, minTextWidthPx = 400f))
    }

    @Test
    fun actionsMoveBelowWhenTextColumnIsBelowTheMinimumWidth() {
        assertTrue(MealRowLayoutPolicy.actionsBelow(rowContentWidthPx = 600, reservedSideActionsPx = 300, longestWordPx = 100f, minTextWidthPx = 400f))
    }

    @Test
    fun minimumTextWidthGrowsWithFontScaleButNeverShrinks() {
        assertEquals(400f, MealRowLayoutPolicy.minTextWidthPx(400f, 0.85f), 0.001f)
        assertEquals(520f, MealRowLayoutPolicy.minTextWidthPx(400f, 1.3f), 0.001f)
        assertEquals(800f, MealRowLayoutPolicy.minTextWidthPx(400f, 2.0f), 0.001f)
    }

    @Test
    fun largeFontOnNarrowPhoneAlwaysFallsBackBelow() {
        // ~320dp di riga a 2x: la colonna laterale non puo' arrivare a 144dp * 2.
        val density = 2.8125f
        val min = MealRowLayoutPolicy.minTextWidthPx(dimenDp("food_meal_row_text_min_width") * density, 2.0f)
        val reserved = ((dimenDp("dashboard_thumbnail_size") + 12f + dimenDp("icon_button_size") + 4f) * density).toInt()
        assertTrue(MealRowLayoutPolicy.actionsBelow((300 * density).toInt(), reserved, longestWordPx = 100f, minTextWidthPx = min))
    }

    @Test
    fun rowActionTokensKeepA48dpTouchTarget() {
        assertTrue(dimenDp("icon_button_size") >= 48f)
        assertTrue(dimenDp("food_meal_row_text_min_width") >= 120f)
    }
}