package com.myfitai.app.ui.progress

import com.myfitai.app.data.local.entity.BiaMeasurementEntity
import com.myfitai.app.data.local.entity.BodyMeasurementEntity
import com.myfitai.app.domain.body.BodyFlag
import com.myfitai.app.domain.body.BodyFlagCode
import com.myfitai.app.domain.body.BodyFlagHint
import com.myfitai.app.domain.body.BodyFlagProfile
import com.myfitai.app.domain.body.BodyFlagReliability
import com.myfitai.app.domain.body.BodyFlagSeverity
import com.myfitai.app.domain.body.FlagFixtures
import com.myfitai.app.domain.body.FlagFixtures.FEMALE
import com.myfitai.app.domain.body.FlagFixtures.MALE
import com.myfitai.app.domain.body.FlagFixtures.bia
import com.myfitai.app.domain.body.FlagFixtures.body
import com.myfitai.app.domain.body.FlagFixtures.profile
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FLAG L2: ogni chiave `body_flag_*` del motore ha le sue stringhe, la mappa messageKey -> stringhe e in un solo
 * punto ([BodyFlagTexts]) e il linguaggio resta prudente (nessuna diagnosi). Legge le stringhe reali di strings.xml.
 */
class BodyFlagTextsTest {
    private val resDir: File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private val strings: Map<String, String> by lazy {
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File(resDir, "values/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Id risorsa -> nome, letto dalla classe R generata: prova che la mappa punta alla stringa giusta. */
    private val resourceNames: Map<Int, String> by lazy {
        Class.forName("com.myfitai.app.R\$string").fields
            .filter { it.type == Int::class.javaPrimitiveType }
            .associate { it.getInt(null) to it.name }
    }

    private val forbidden = listOf(
        "diagnos", "sarcopen", "malatt", "obesit", "obesity", "patolog", "pathol", "disease", "disturb",
        "anoress", "bulim", "ipertension", "diabet", "cura", "terapi", "prescri",
    )

    private fun flagStrings() = strings.filterKeys { it.startsWith("body_flag_") }

    @Test
    fun everyEngineMessageKey_hasATextEntry_andAllFourStrings() {
        assertEquals(BodyFlagCode.entries.toSet(), BodyFlagTexts.entries.keys)
        BodyFlagCode.entries.forEach { code ->
            val key = code.messageKey
            val entry = BodyFlagTexts.entries.getValue(code)
            listOf(
                "title" to entry.title, "context" to entry.context, "why" to entry.why, "limit" to entry.limit,
            ).forEach { (suffix, id) ->
                val name = "${key}_$suffix"
                assertTrue("manca la stringa $name", !strings[name].isNullOrBlank())
                assertEquals("la mappa di $code punta alla stringa sbagliata per $suffix", name, resourceNames[id])
            }
            assertTrue("azione mancante per $code", resourceNames.getValue(entry.action).startsWith("body_flag_action_"))
        }
    }

    @Test
    fun noNewFlagStringContainsForbiddenTerms() {
        val offenders = flagStrings().flatMap { (name, value) ->
            forbidden.filter { value.lowercase().contains(it) }.map { "$name -> $it" }
        }
        assertTrue("Termini vietati nei testi FLAG L2: $offenders", offenders.isEmpty())
    }

    @Test
    fun prudentWording_skeletalMuscleInvitesAStrengthTest_andNothingPromisesAnOutcome() {
        val skeletal = BodyFlagTexts.entries.getValue(BodyFlagCode.SKELETAL_MUSCLE_DECLINE)
        assertTrue(strings.getValue("body_flag_action_strength_test").contains("test di forza"))
        assertEquals("body_flag_action_strength_test", resourceNames[skeletal.action])
        assertTrue(strings.getValue("body_flag_card_subtitle").contains("medico"))
        // Ogni azione rimanda al medico (non e una diagnosi) e nessun testo usa il condizionale di certezza.
        strings.filterKeys { it.startsWith("body_flag_action_") }.forEach { (name, value) ->
            assertTrue("$name deve rimandare al medico", value.contains("medico"))
        }
    }

    @Test
    fun contextPlaceholders_matchTheEvidenceKeysDeclaredForEachCode() {
        BodyFlagCode.entries.forEach { code ->
            val entry = BodyFlagTexts.entries.getValue(code)
            val text = strings.getValue("${code.messageKey}_context")
            val indices = Regex("%(\\d+)[\$]s").findAll(text).map { it.groupValues[1].toInt() }.toSet()
            assertEquals("segnaposto di $code", (1..entry.contextKeys.size).toSet(), indices)
            entry.contextKeys.forEach { key ->
                assertTrue("chiave di evidenza sconosciuta $key in $code", key == BodyFlagTexts.HEIGHT_KEY || key in ENGINE_EVIDENCE_KEYS)
            }
        }
    }

    @Test
    fun severityReliabilityAndHintLabels_coverEveryValue() {
        assertEquals(BodyFlagSeverity.entries.toSet(), BodyFlagTexts.severityLabels.keys)
        assertEquals(BodyFlagReliability.entries.toSet(), BodyFlagTexts.reliabilityLabels.keys)
        assertEquals(BodyFlagHint.entries.toSet(), BodyFlagTexts.hintLabels.keys)
        // La gravita si legge anche senza colore: le due etichette sono parole diverse.
        assertEquals("Info", strings.getValue("body_flag_severity_info"))
        assertEquals("Da approfondire", strings.getValue("body_flag_severity_attention"))
        assertEquals("Misura la vita per valutare il rapporto vita/altezza", strings.getValue("body_flag_hint_measure_waist"))
        assertEquals("Registra almeno 3 BIA nelle stesse condizioni", strings.getValue("body_flag_hint_add_bia"))
        assertEquals("Nessun segnale con i dati disponibili", strings.getValue("body_flag_empty"))
        assertEquals("Le tendenze BIA richiedono misure con le stesse condizioni dichiarate", strings.getValue("body_flag_bia_same_conditions_note"))
    }

    @Test
    fun formatValue_usesItalianNumbers() {
        assertEquals("0,60", BodyFlagTexts.formatValue("whtr", 0.604))
        assertEquals("99", BodyFlagTexts.formatValue("waistCm", 99.0))
        assertEquals("88,2", BodyFlagTexts.formatValue("weightKg", 88.2))
        assertEquals("1,2", BodyFlagTexts.formatValue("weeklyRateSecondKg", 1.2))
    }

    @Test
    fun realEvidenceFillsTheSentence_withTheValuesOfTheEngine() {
        val flags = sampleFlags()
        val waist = flags.first { it.code == BodyFlagCode.WHTR_HIGH }
        val args = BodyFlagTexts.contextArgs(waist, heightCm = 180.0)
        val sentence = String.format(
            Locale.ITALIAN,
            strings.getValue("body_flag_whtr_high_context").replace("%%", "%"),
            *args.toTypedArray(),
        )
        assertEquals("Vita 110 cm su altezza 180 cm: rapporto 0,61", sentence)
    }

    @Test
    fun everyCode_isProducedBySomeSample_andHasCompleteContextArguments() {
        val flags = sampleFlags()
        assertEquals("campioni che non producono tutti i codici", BodyFlagCode.entries.toSet(), flags.map { it.code }.toSet())
        flags.forEach { flag ->
            val args = BodyFlagTexts.contextArgs(flag, heightCm = 180.0)
            val keys = BodyFlagTexts.entries.getValue(flag.code).contextKeys
            assertEquals("argomenti di ${flag.code}", keys.size, args.size)
            args.forEachIndexed { i, value -> assertFalse("valore mancante ${keys[i]} in ${flag.code}", value == "-") }
        }
    }

    /** Dati sintetici (nessun dato reale) scelti per far produrre al motore ogni codice. */
    private fun sampleFlags(): List<BodyFlag> {
        val scenarios: List<Triple<BodyFlagProfile, List<BiaMeasurementEntity>, List<BodyMeasurementEntity>>> = listOf(
            Triple(profile(MALE, 40, 180.0), emptyList(), listOf(body(4, waist = 110f))),
            Triple(profile(MALE, 40, 180.0), emptyList(), listOf(body(4, waist = 95f))),
            Triple(profile(MALE, 45, 180.0), emptyList(), listOf(body(4, waist = 104f, weight = 100f))),
            Triple(profile(MALE, 40, 180.0), emptyList(), listOf(body(4, weight = 55f))),
            Triple(
                profile(FEMALE, 52, 160.0),
                listOf(bia(56, weight = 78f, visceral = 8f), bia(28, weight = 78f, visceral = 9f), bia(0, weight = 78f, visceral = 10f)),
                listOf(body(4, waist = 90f)),
            ),
            Triple(
                profile(FEMALE, 62, 158.0),
                listOf(bia(56, weight = 55f, skeletal = 20.0f), bia(28, weight = 55f, skeletal = 19.0f), bia(0, weight = 55f, skeletal = 18.2f)),
                listOf(body(4, waist = 74f)),
            ),
            Triple(
                profile(MALE, 30, 178.0),
                emptyList(),
                listOf(body(14, waist = 85f, weight = 90f), body(7, weight = 88.8f), body(0, weight = 87.6f)),
            ),
            Triple(
                profile(FEMALE, 35, 170.0),
                listOf(
                    bia(56, weight = 70f, lean = 50f, fat = 20.0f), bia(28, weight = 67f, lean = 48.5f, fat = 20.4f),
                    bia(0, weight = 64f, lean = 47f, fat = 20.6f),
                ),
                listOf(body(4, waist = 70f)),
            ),
            Triple(
                profile(MALE, 40, 180.0),
                listOf(bia(60, fat = 25f), bia(40, fat = 24f), bia(20, fat = 23f), bia(0, fat = 22f)),
                listOf(body(3, waist = 70f)),
            ),
            Triple(
                profile(MALE, 40, 180.0),
                listOf(bia(60, fat = 22f), bia(40, fat = 23f), bia(20, fat = 24f), bia(0, fat = 25f)),
                listOf(body(3, waist = 70f)),
            ),
        )
        return scenarios.flatMap { (p, b, m) -> FlagFixtures.eval(p, b, m).flags }
    }

    private companion object {
        /** Chiavi di evidenza prodotte da BodyFlagEngine (ev("...")). */
        val ENGINE_EVIDENCE_KEYS = setOf(
            "whtr", "waistCm", "bmi", "weightKg", "visceralFatLevelFirst", "visceralFatLevelLast",
            "skeletalMuscleKgFirst", "skeletalMuscleKgLast", "ageYears", "weightKgStart", "weightKgMiddle",
            "weightKgLatest", "weeklyRateFirstKg", "weeklyRateSecondKg", "leanMassKgFirst", "leanMassKgLast",
            "weightKgFirst", "weightKgLast", "leanShareOfWeightLossPercent", "bodyFatPercentFirst", "bodyFatPercentLast",
        )
    }
}
