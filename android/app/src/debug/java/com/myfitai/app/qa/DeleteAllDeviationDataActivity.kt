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

/** QA cleanup: removes deviations and recovery ledger, preserving plans and profile history. */
class DeleteAllDeviationDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "Pulizia sgarri e recovery..."; setPadding(32, 32, 32, 32) }
        setContentView(status)
        CoroutineScope(Dispatchers.Main).launch {
            status.text = withContext(Dispatchers.IO) {
                val data = AppDataContainer.get(this@DeleteAllDeviationDataActivity)
                val profileId = data.activeProfileStore.currentIdOrNull() ?: return@withContext "Nessun profilo attivo"
                val before = data.cheatEntryRepository.all(profileId).first().size
                data.dataDeletionService.deleteCheatEntries()
                data.nutritionRecoveryRepository.deleteByProfile(profileId)
                val after = data.cheatEntryRepository.all(profileId).first().size
                "Sgarri rimossi=$before; rimanenti=$after; recovery=0"
            }
        }
    }
}
