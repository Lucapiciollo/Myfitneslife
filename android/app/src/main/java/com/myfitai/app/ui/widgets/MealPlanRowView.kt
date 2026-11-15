package com.myfitai.app.ui.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.myfitai.app.R

/**
 * Regola pura di adattamento della riga pasto: i due pulsanti azione stanno a destra, impilati, finche' la colonna di testo
 * resta larga abbastanza da non spezzare nessuna parola; altrimenti passano sotto il testo, affiancati.
 */
object MealRowLayoutPolicy {
    /** Larghezza minima desiderata della colonna di testo, in px: cresce con la scala del font ma non scende sotto il valore base. */
    fun minTextWidthPx(baseMinPx: Float, fontScale: Float): Float = baseMinPx * maxOf(1f, fontScale)

    /** True se a destra non resta spazio per la parola piu' lunga (o per la larghezza minima): azioni sotto il testo. */
    fun actionsBelow(rowContentWidthPx: Int, reservedSideActionsPx: Int, longestWordPx: Float, minTextWidthPx: Float): Boolean =
        rowContentWidthPx - reservedSideActionsPx < maxOf(longestWordPx, minTextWidthPx)
}

/** Riga pasto riutilizzabile per il Piano alimentare: slot, tipo, kcal, descrizione e azioni (consumato, alternative). */
class MealPlanRowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val topRow: LinearLayout
    private val thumbnail: ShapeableImageView
    private val textColumn: LinearLayout
    private val titleView: TextView
    private val kcalView: TextView
    private val descriptionView: TextView
    private val statusView: TextView
    private val actions: LinearLayout
    private val changeButton: ImageView
    private val quickButton: ImageView
    private var actionsBelow = false

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        minimumHeight = resources.getDimensionPixelSize(R.dimen.food_meal_row_min_height)
        setPadding(
            resources.getDimensionPixelSize(R.dimen.card_content_padding_compact),
            resources.getDimensionPixelSize(R.dimen.space_12),
            resources.getDimensionPixelSize(R.dimen.space_8),
            resources.getDimensionPixelSize(R.dimen.space_12),
        )
        background = context.getDrawable(R.drawable.bg_card)

        topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addView(topRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        thumbnail = ShapeableImageView(context).apply {
            val size = resources.getDimensionPixelSize(R.dimen.dashboard_thumbnail_size)
            layoutParams = LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = ShapeAppearanceModel.builder(context, 0, R.style.ShapeAppearance_MyFitAI_Thumbnail).build()
        }
        topRow.addView(thumbnail)

        textColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.space_12)
            }
        }
        // Tipo pasto e kcal su righe proprie, a larghezza piena della colonna: vanno a capo solo tra parole.
        titleView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setTextAppearance(R.style.Text_MyFitAI_SettingsLabel)
            gravity = Gravity.START
            textAlignment = TEXT_ALIGNMENT_TEXT_START
        }
        textColumn.addView(titleView)
        kcalView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.space_2)
            }
            setTextAppearance(R.style.Text_MyFitAI_KeyValueValue)
            gravity = Gravity.START
            textAlignment = TEXT_ALIGNMENT_TEXT_START
        }
        textColumn.addView(kcalView)

        descriptionView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.space_4)
            }
            setTextAppearance(R.style.Text_MyFitAI_Body)
            gravity = Gravity.START
            textAlignment = TEXT_ALIGNMENT_TEXT_START
        }
        textColumn.addView(descriptionView)
        statusView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.space_4)
            }
            setTextAppearance(R.style.Text_MyFitAI_StatusValue)
            setTextColor(context.getColor(R.color.accent_green_dark))
        }
        textColumn.addView(statusView)
        topRow.addView(textColumn)

        val buttonSize = resources.getDimensionPixelSize(R.dimen.icon_button_size)
        val iconPadding = resources.getDimensionPixelSize(R.dimen.space_8)
        // UX4: azione rapida "Segna come consumato" (area 48dp) accanto a "Cerca alternative" (area 48dp).
        quickButton = ImageView(context).apply {
            layoutParams = LayoutParams(buttonSize, buttonSize)
            setImageResource(R.drawable.ic_check)
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            val ripple = android.util.TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            isClickable = true
            isFocusable = true
            visibility = GONE
        }
        changeButton = ImageView(context).apply {
            layoutParams = LayoutParams(buttonSize, buttonSize)
            setImageResource(R.drawable.ic_refresh)
            setColorFilter(context.getColor(R.color.accent_green_dark))
            setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            contentDescription = "Cerca alternative per il pasto"
            isClickable = true
            isFocusable = true
        }
        actions = LinearLayout(context)
        actions.addView(quickButton)
        actions.addView(changeButton)
        placeActions(below = false)
    }

    /** Sposta il blocco azioni a destra del testo (impilato) o sotto il testo (affiancato, allineato a fine riga). */
    private fun placeActions(below: Boolean) {
        actionsBelow = below
        (actions.parent as? android.view.ViewGroup)?.removeView(actions)
        if (below) {
            actions.orientation = HORIZONTAL
            addView(actions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        } else {
            actions.orientation = VERTICAL
            topRow.addView(actions, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.space_4)
            })
        }
    }

    private fun longestWordPx(): Float = listOf(titleView, kcalView, descriptionView, statusView).maxOf { view ->
        if (view.visibility == GONE) 0f else view.text.split(Regex("\\s+")).maxOfOrNull { view.paint.measureText(it) } ?: 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val contentWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val reserved = resources.getDimensionPixelSize(R.dimen.dashboard_thumbnail_size) +
                resources.getDimensionPixelSize(R.dimen.space_12) +
                resources.getDimensionPixelSize(R.dimen.icon_button_size) +
                resources.getDimensionPixelSize(R.dimen.space_4)
            val below = MealRowLayoutPolicy.actionsBelow(
                rowContentWidthPx = contentWidth,
                reservedSideActionsPx = reserved,
                longestWordPx = longestWordPx(),
                minTextWidthPx = MealRowLayoutPolicy.minTextWidthPx(
                    resources.getDimension(R.dimen.food_meal_row_text_min_width),
                    resources.configuration.fontScale,
                ),
            )
            if (below != actionsBelow) placeActions(below)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    fun setTitle(title: String) {
        titleView.text = title
    }

    fun setKcal(kcal: String) {
        kcalView.text = kcal
    }

    fun setDescription(description: String) {
        descriptionView.text = description
    }

    fun setStatus(status: String?) {
        statusView.text = status.orEmpty()
        statusView.visibility = if (status.isNullOrBlank()) GONE else VISIBLE
    }

    /** Shows the icon of the moment of the day (breakfast, lunch, dinner, snacks) instead of a photo. */
    fun setMeal(type: String?, timeMinutes: Int?, describe: Boolean) {
        thumbnail.setBackgroundResource(R.drawable.bg_meal_icon_tile)
        thumbnail.scaleType = ImageView.ScaleType.CENTER
        thumbnail.setImageResource(MealSlotIcons.iconRes(type, timeMinutes))
        if (describe) {
            thumbnail.contentDescription = MealSlotIcons.label(type, timeMinutes)
            thumbnail.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            thumbnail.contentDescription = null
            thumbnail.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    fun setImage(resId: Int) {
        thumbnail.setImageResource(resId)
    }

    fun setOnChangeClickListener(listener: (() -> Unit)?) {
        changeButton.setOnClickListener { listener?.invoke() }
    }

    /**
     * One-tap registration of the meal as consumed. [consumed] only changes how the control reads (state + description):
     * the tap is always forwarded to [listener], which decides what to do (never a silent destructive action).
     */
    fun setQuickConsume(mealTitle: String, consumed: Boolean, listener: (() -> Unit)?) {
        quickButton.visibility = if (listener == null) GONE else VISIBLE
        quickButton.setColorFilter(context.getColor(if (consumed) R.color.accent_green_dark else R.color.text_muted))
        quickButton.alpha = 1f
        quickButton.contentDescription = context.getString(
            if (consumed) R.string.meal_ux4_quick_consumed_cd else R.string.meal_ux4_quick_consume_cd,
            mealTitle,
        )
        quickButton.setOnClickListener { listener?.invoke() }
    }

    fun setChangeEnabled(enabled: Boolean) {
        changeButton.isEnabled = enabled
        changeButton.importantForAccessibility = if (enabled) IMPORTANT_FOR_ACCESSIBILITY_AUTO else IMPORTANT_FOR_ACCESSIBILITY_NO
        changeButton.alpha = if (enabled) 1f else resources.getFraction(R.fraction.alpha_component_disabled, 1, 1)
    }
}
