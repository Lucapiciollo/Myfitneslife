package com.myfitai.app.ui.theme

import androidx.annotation.DimenRes
import androidx.annotation.IntegerRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.myfitai.app.R

/** Adapta le risorse Android MyFitAI a Material 3; le risorse restano la sola sorgente dei token. */
@Composable
fun MyFitAiTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = colorResource(R.color.accent_green),
        onPrimary = colorResource(R.color.white),
        primaryContainer = colorResource(R.color.surface_positive_soft),
        onPrimaryContainer = colorResource(R.color.text_primary),
        secondary = colorResource(R.color.accent_blue),
        onSecondary = colorResource(R.color.white),
        secondaryContainer = colorResource(R.color.surface_info_soft),
        onSecondaryContainer = colorResource(R.color.text_primary),
        tertiary = colorResource(R.color.accent_orange),
        onTertiary = colorResource(R.color.text_primary),
        tertiaryContainer = colorResource(R.color.surface_warning_soft),
        onTertiaryContainer = colorResource(R.color.text_primary),
        error = colorResource(R.color.semantic_error),
        onError = colorResource(R.color.white),
        errorContainer = colorResource(R.color.surface_error_soft),
        onErrorContainer = colorResource(R.color.text_primary),
        background = colorResource(R.color.bg_primary),
        onBackground = colorResource(R.color.text_primary),
        surface = colorResource(R.color.surface_primary),
        onSurface = colorResource(R.color.text_primary),
        surfaceVariant = colorResource(R.color.surface_secondary),
        onSurfaceVariant = colorResource(R.color.text_secondary),
        outline = colorResource(R.color.divider),
        outlineVariant = colorResource(R.color.surface_soft),
        scrim = colorResource(R.color.scrim_black_60),
    )
    val typography = myFitAiTypography()

    MaterialTheme(
        colorScheme = colors,
        typography = typography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(dimensionResource(R.dimen.radius_small)),
            small = RoundedCornerShape(dimensionResource(R.dimen.radius_field)),
            medium = RoundedCornerShape(dimensionResource(R.dimen.radius_card)),
            large = RoundedCornerShape(dimensionResource(R.dimen.radius_large)),
            extraLarge = RoundedCornerShape(dimensionResource(R.dimen.radius_dialog)),
        ),
        content = content,
    )
}

@Composable
@ReadOnlyComposable
private fun myFitAiTypography(): Typography {
    val regular = FontWeight.Normal
    val medium = FontWeight.SemiBold
    val bold = FontWeight.Bold
    return Typography(
        displaySmall = TextStyle(fontSize = textToken(R.dimen.text_display_small), fontWeight = bold),
        headlineSmall = TextStyle(fontSize = textToken(R.dimen.text_hero), fontWeight = bold),
        titleLarge = TextStyle(fontSize = textToken(R.dimen.text_screen_title), fontWeight = bold),
        titleMedium = TextStyle(fontSize = textToken(R.dimen.text_section_title), fontWeight = bold),
        titleSmall = TextStyle(fontSize = textToken(R.dimen.text_card_title), fontWeight = medium),
        bodyLarge = TextStyle(fontSize = textToken(R.dimen.text_body_large), fontWeight = regular),
        bodyMedium = TextStyle(fontSize = textToken(R.dimen.text_body), fontWeight = regular),
        bodySmall = TextStyle(fontSize = textToken(R.dimen.text_body_compact), fontWeight = regular),
        labelLarge = TextStyle(fontSize = textToken(R.dimen.text_label), fontWeight = medium),
        labelMedium = TextStyle(fontSize = textToken(R.dimen.text_caption), fontWeight = medium),
        labelSmall = TextStyle(fontSize = textToken(R.dimen.text_micro), fontWeight = medium),
    )
}

/** Returns the authored sp token; Resources scales it with fontScale exactly once. */
@Composable
@ReadOnlyComposable
private fun textToken(@DimenRes id: Int): TextUnit {
    val context = LocalContext.current
    val rawPixels = context.resources.getDimension(id)
    val fontScale = context.resources.configuration.fontScale
    val densityScale = context.resources.displayMetrics.density * fontScale
    return (rawPixels / densityScale).sp
}

/** Semantic extension colors not represented by Material 3's base ColorScheme roles. */
object MyFitAiSemanticColors {
    val positive: Color
        @Composable @ReadOnlyComposable get() = colorResource(R.color.semantic_positive)
    val warning: Color
        @Composable @ReadOnlyComposable get() = colorResource(R.color.semantic_warning)
    val critical: Color
        @Composable @ReadOnlyComposable get() = colorResource(R.color.semantic_error)
    val information: Color
        @Composable @ReadOnlyComposable get() = colorResource(R.color.accent_blue)
    val chartPalette: List<Color>
        @Composable @ReadOnlyComposable get() = listOf(
            colorResource(R.color.accent_green_dark),
            colorResource(R.color.accent_orange),
            colorResource(R.color.accent_blue),
            colorResource(R.color.semantic_positive),
            colorResource(R.color.text_primary),
        )
}

object MyFitAiSurfaces {
    val aiContainer: Color
        @Composable @ReadOnlyComposable get() = colorResource(R.color.surface_ai)
    val onAiContainer: Color
        @Composable @ReadOnlyComposable get() = colorResource(R.color.text_primary)
}

/** Accesso nominativo alla scala dimensionale Android condivisa. */
object MyFitAiDimensions {
    val space2: Dp @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.space_2)
    val space4: Dp @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.space_4)
    val space8: Dp @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.space_8)
    val space12: Dp @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.space_12)
    val space16: Dp @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.space_16)
    val space24: Dp @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.space_24)
    val compactGutter: Dp @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.page_gutter_compact)
    val mediumGutter: Dp @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.page_gutter_medium)
    val expandedGutter: Dp @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.page_gutter_expanded)
    val minimumTouchTarget: Dp @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.control_min_height)
}

/** Motion timings are read from the same integer resources used by the Views motion adapter. */
object MyFitAiMotionTokens {
    @Composable
    @ReadOnlyComposable
    fun durationMillis(@IntegerRes id: Int): Int = integerResource(id)
}

/** State-less responsive spacing variants sourced from shared Android dimens. */
object MyFitAiSpacing {
    val compact: Dp
        @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.page_gutter_compact)
    val medium: Dp
        @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.page_gutter_medium)
    val expanded: Dp
        @Composable @ReadOnlyComposable get() = dimensionResource(R.dimen.page_gutter_expanded)
}
