package com.myfitai.app.qa

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.myfitai.app.data.AppDataContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Read-only QA inspection for consumption, deviations and recovery ledger. */
class InspectRecoveryDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "Ispezione..."; setPadding(24, 24, 24, 24) }
        setContentView(status)
        CoroutineScope(Dispatchers.Main).launch {
            status.text = withContext(Dispatchers.IO) {
                val data = AppDataContainer.get(this@InspectRecoveryDataActivity)
                val profileId = data.activeProfileStore.currentIdOrNull() ?: return@withContext "Nessun profilo"
                val db = com.myfitai.app.data.local.MyFitAiDatabase.getInstance(applicationContext)
                val consumed = db.foodConsumptionDao().observeAll(profileId).first().filter { it.status == "CONSUMED" }
                val cheats = db.cheatEntryDao().observeAll(profileId).first()
                val events = db.nutritionRecoveryDao().observeEvents(profileId).first()
                val withdrawals = db.nutritionRecoveryDao().observeWithdrawals(profileId).first()
                "profile=$profileId consumed=${consumed.size} consumedKcal=${consumed.sumOf { it.kcal ?: 0 }} " +
                    "cheats=${cheats.size} cheatKcal=${cheats.sumOf { it.estimatedKcal ?: 0 }} " +
                    "recoveryEvents=${events.size} activeRecovery=${events.filter { it.status == "ACTIVE" }.sumOf { it.remainingKcal }} " +
                    "withdrawals=${withdrawals.size}"
            }
        }
    }
}
