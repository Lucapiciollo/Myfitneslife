package com.myfitai.app.qa

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QaSeederActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val seeder = SixMonthQaDataSeeder(this)
        status = TextView(this).apply { text = "QA seeder pronto"; contentDescription = "qa_status"; textSize = 16f; setPadding(32, 32, 32, 32) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(button("RESET") { run("Reset") { seeder.reset(); "Database QA resettato" } })
            addView(button("SEED_6_MONTHS") { run("Seed 6 mesi") { seeder.seedSixMonths() } })
            addView(button("SEED_DEMO_12_MONTHS") { run("Seed demo 12 mesi") { seeder.seedDemo12Months() } })
            addView(button("SEED_STRESS") { run("Seed stress") { seeder.seedStress() } })
        })
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }

    private fun run(label: String, action: suspend () -> String) {
            status.text = "$label in corso…"
            status.contentDescription = "qa_status"
        scope.launch {
            status.text = runCatching { withContext(Dispatchers.IO) { action() } }
                .onFailure { Log.e("MyFitAI.QA", "$label failed", it) }
                .getOrElse { "ERRORE: ${it.javaClass.simpleName}: ${it.message}" }
        }
    }
}
