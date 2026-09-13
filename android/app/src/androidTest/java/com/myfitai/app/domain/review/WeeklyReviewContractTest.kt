package com.myfitai.app.domain.review

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeeklyReviewContractTest {

    @Test
    fun validReview_isAccepted() {
        val response = WeeklyReviewContract.parse(validJson(100L))
        assertTrue(WeeklyReviewContract.validateBusiness(response, 100L).isSuccess)
    }

    @Test
    fun wrongWeek_isRejected() {
        val response = WeeklyReviewContract.parse(validJson(99L))
        assertFalse(WeeklyReviewContract.validateBusiness(response, 100L).isSuccess)
    }

    @Test
    fun causalClaim_isRejected() {
        val root = JSONObject(validJson(100L)).put("summary", "Lo sgarro ha causato il cambiamento del peso")
        val response = WeeklyReviewContract.parse(root.toString())
        assertFalse(WeeklyReviewContract.validateBusiness(response, 100L).isSuccess)
    }

    private fun validJson(weekStart: Long): String = JSONObject()
        .put("weekStartEpochDay", weekStart)
        .put("summary", "Settimana descritta usando solo dati registrati.")
        .put("observations", JSONArray().put("Sono stati registrati tre allenamenti."))
        .put("nextWeekGuidance", JSONArray().put("Mantieni una pianificazione pratica."))
        .put("agentValidation", JSONObject().put("valid", true).put("notes", "ok"))
        .toString()
}
