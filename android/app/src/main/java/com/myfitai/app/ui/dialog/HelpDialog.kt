package com.myfitai.app.ui.dialog

import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myfitai.app.R
import com.myfitai.app.ui.BaseShellActivity

/** One titled block of a help dialog, rendered as a card (title, optional accent subtitle, body). */
data class HelpSection(val title: String, val subtitle: String? = null, val body: String)

/**
 * Everything the shared help modal needs. All parameters are plain data, so any screen can describe its help
 * without building views.
 *
 * @param message paragraphs separated by a blank line. A paragraph written as `Heading: text` shows the heading in
 *   bold with the dialog accent colour (unless [highlightHeadings] is false).
 * @param sections optional cards, shown after [message]; use them for long structured guides.
 * @param confirmLabel label of the single action; defaults to the shared "Ho capito".
 */
data class HelpDialogSpec(
    val title: String,
    val message: String? = null,
    val sections: List<HelpSection> = emptyList(),
    val confirmLabel: String? = null,
    val highlightHeadings: Boolean = true,
)

internal data class HelpParagraph(val heading: String?, val value: String?, val text: String)

/** Splits a help message into paragraphs and recognises the `Heading: text` form. Pure, so it is unit-testable. */
internal fun parseHelpParagraphs(message: String, highlightHeadings: Boolean = true): List<HelpParagraph> =
    message.trim().split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }.map { raw ->
        val lines = raw.trim().lines()
        val firstLine = lines.firstOrNull().orEmpty().trim().removePrefix("\u2022 ").trim()
        val remaining = lines.drop(1).joinToString(" ") { it.trim() }.trim()
        val separator = if (highlightHeadings) firstLine.indexOf(": ") else -1
        if (separator > 0) {
            val valueOrText = firstLine.substring(separator + 2).trim()
            HelpParagraph(
                heading = firstLine.substring(0, separator).trim(),
                value = valueOrText.takeIf { remaining.isNotBlank() },
                text = listOfNotNull(valueOrText.takeIf { remaining.isBlank() }, remaining.takeIf { it.isNotBlank() }).joinToString(" "),
            )
        } else {
            val clean = (listOf(firstLine) + remaining).filter { it.isNotBlank() }.joinToString(" ")
            HelpParagraph(null, null, clean)
        }
    }

/** The single help modal of the app: same theme, layout, scrolling and action bar everywhere. */
object HelpDialog {

    fun show(activity: BaseShellActivity, spec: HelpDialogSpec) {
        runCatching { create(activity, spec).show() }.onFailure { createFallback(activity, spec).show() }
    }

    private fun confirmLabel(activity: BaseShellActivity, spec: HelpDialogSpec) =
        spec.confirmLabel ?: activity.getString(R.string.help_dialog_confirm)

    private fun create(activity: BaseShellActivity, spec: HelpDialogSpec): MaterialAlertDialogBuilder {
        val content = activity.layoutInflater.inflate(R.layout.dialog_help, null, false)
        content.findViewById<TextView>(R.id.helpTitle).text = spec.title
        val messageView = content.findViewById<LinearLayout>(R.id.helpMessage)
        val sectionsView = content.findViewById<LinearLayout>(R.id.helpSections)

        if (spec.message.isNullOrBlank()) {
            messageView.visibility = View.GONE
        } else {
            renderMessage(activity, messageView, parseHelpParagraphs(spec.message, spec.highlightHeadings))
        }
        spec.sections.forEachIndexed { index, section ->
            val card = activity.layoutInflater.inflate(R.layout.item_help_section, sectionsView, false)
            card.findViewById<TextView>(R.id.helpSectionTitle).text = section.title
            card.findViewById<TextView>(R.id.helpSectionSubtitle).apply {
                if (section.subtitle.isNullOrBlank()) visibility = View.GONE else text = section.subtitle
            }
            card.findViewById<TextView>(R.id.helpSectionBody).apply {
                text = section.body
                setLineSpacing(
                    resources.getDimension(R.dimen.line_spacing_zero_extra),
                    resources.getFraction(R.fraction.text_line_spacing_comfortable, 1, 1),
                )
            }
            if (index == spec.sections.lastIndex) card.findViewById<View>(R.id.helpSectionDivider).visibility = View.GONE
            sectionsView.addView(card)
        }
        return MaterialAlertDialogBuilder(activity)
            .setTitle(null as CharSequence?)
            .setView(activity.normalizeRuntimeDialogContent(content))
            .setPositiveButton(confirmLabel(activity, spec), null)
    }

    /** Plain Material message dialog, used only if the custom layout cannot be built. */
    private fun createFallback(activity: BaseShellActivity, spec: HelpDialogSpec): MaterialAlertDialogBuilder {
        val text = listOfNotNull(spec.message?.takeIf { it.isNotBlank() }) +
            spec.sections.map { section -> "${section.title}\n${section.body}" }
        return MaterialAlertDialogBuilder(activity)
            .setTitle(spec.title)
            .setMessage(text.joinToString("\n\n"))
            .setPositiveButton(confirmLabel(activity, spec), null)
    }

    private fun renderMessage(
        activity: BaseShellActivity,
        container: LinearLayout,
        paragraphs: List<HelpParagraph>,
    ) {
        paragraphs.forEachIndexed { index, paragraph ->
            if (paragraph.heading != null) {
                val block = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, 0, activity.resources.getDimensionPixelSize(R.dimen.space_16))
                }
                block.addView(TextView(activity).apply {
                    text = paragraph.heading
                    setTextAppearance(R.style.Text_MyFitAI_HelpSectionTitle)
                })
                paragraph.value?.takeIf { it.isNotBlank() }?.let { value ->
                    block.addView(TextView(activity).apply {
                        text = value
                        setTextAppearance(R.style.Text_MyFitAI_HelpSectionValue)
                    })
                }
                paragraph.text.takeIf { it.isNotBlank() }?.let { bodyText ->
                    block.addView(TextView(activity).apply {
                        text = bodyText
                        setTextAppearance(R.style.Text_MyFitAI_HelpSectionBody)
                    })
                }
                container.addView(block, LinearLayout.LayoutParams(-1, -2))
            } else {
                container.addView(TextView(activity).apply {
                    text = paragraph.text
                    setTextAppearance(R.style.Text_MyFitAI_HelpMessage)
                    setPadding(0, 0, 0, if (index < paragraphs.lastIndex) activity.resources.getDimensionPixelSize(R.dimen.space_16) else 0)
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }
    }
}
