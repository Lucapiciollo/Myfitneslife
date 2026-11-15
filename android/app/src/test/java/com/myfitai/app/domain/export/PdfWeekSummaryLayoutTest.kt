package com.myfitai.app.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfWeekSummaryLayoutTest {
    private val top = 450f
    private val bottom = 785f
    private val left = 58f
    private val right = 535f

    private fun layout(rows: Int) = PdfWeekSummaryLayout.layout(rows, top, bottom, left, right)

    @Test
    fun singleDayBoxShrinksToContent() {
        val result = layout(1)
        assertEquals(1, result.columns)
        assertEquals(95f, result.height, 0.001f)
        assertEquals(65f, result.cells.single().baselineOffset, 0.001f)
    }

    @Test
    fun sevenDaysKeepPreviousHeightAndRowPositions() {
        val result = layout(7)
        assertEquals(1, result.columns)
        assertEquals(275f, result.height, 0.001f)
        assertEquals(listOf(65f, 95f, 125f, 155f, 185f, 215f, 245f), result.cells.map { it.baselineOffset })
        assertTrue(result.cells.all { it.left == left && it.right == right })
    }

    @Test
    fun heightGrowsOnlyByRowPitch() {
        assertEquals(30f, layout(4).height - layout(3).height, 0.001f)
    }

    @Test
    fun emptyPlanHasOnlyTitleArea() {
        val result = layout(0)
        assertEquals(65f, result.height, 0.001f)
        assertTrue(result.cells.isEmpty())
    }

    @Test
    fun boxNeverExceedsPageLimitEvenForLongPeriods() {
        listOf(1, 7, 9, 10, 14, 21, 28, 31, 60).forEach { rows ->
            val result = layout(rows)
            assertTrue("rows=$rows", top + result.height <= bottom)
            assertEquals("rows=$rows", rows, result.cells.size)
        }
    }

    @Test
    fun longPeriodsUseMoreColumnsWithoutOverlap() {
        val result = layout(14)
        assertEquals(2, result.columns)
        assertEquals(7, result.rowsPerColumn)
        val first = result.cells.first { it.column == 0 }
        val second = result.cells.first { it.column == 1 }
        assertTrue(first.right < second.left)
        assertEquals(right, second.right, 0.001f)
        assertEquals(0, second.row)
    }
}
