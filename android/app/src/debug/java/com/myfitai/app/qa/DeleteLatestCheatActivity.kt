package com.myfitai.app.qa

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.myfitai.app.data.local.MyFitAiDatabase
import com.myfitai.app.data.profile.ActiveProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-shot QA cleanup for the most recently entered cheat only. */
class DeleteLatestCheatActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "Pulizia sgarro in corso..."; setPadding(32, 32, 32, 32) }
        setContentView(status)
        CoroutineScope(Dispatchers.Main).launch {
            status.text = withContext(Dispatchers.IO) {
                val profileId = ActiveProfileStore(this@DeleteLatestCheatActivity).currentIdOrNull()
                    ?: return@withContext "Nessun profilo attivo"
                val db = MyFitAiDatabase.getInstance(applicationContext)
                val entries = db.cheatEntryDao().observeAll(profileId).first()
                val latest = entries.firstOrNull() ?: return@withContext "Nessuno sgarro da eliminare"
                db.cheatEntryDao().delete(latest)
                "Eliminato ultimo sgarro: ${latest.description}; rimanenti=${entries.size - 1}"
            }
        }
    }
}
