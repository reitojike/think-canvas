package com.thinkcanvas.board

import android.content.res.Resources
import android.util.TypedValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 共有画像の世界座標 1 単位を、端末上の canvas の 1 px として組版する。 */
data class ExportTypography(
    val bodySize: Float = 14f,
    val titleSize: Float = 15f,
    val regionSize: Float = 12f,
    val textWidth: Int = 166,
    val bodyLineHeight: Float = 21f,
    val titleLineHeight: Float = 22.5f,
    val bodyLetterSpacingEm: Float = 0f,
    val titleLetterSpacingEm: Float = 0f,
    val regionLetterSpacingEm: Float = 0f,
) {
    companion object {
        fun from(density: Density, style: TextStyle): ExportTypography {
            val body = with(density) { 14.sp.toPx() }
            val title = with(density) { 15.sp.toPx() }
            val region = with(density) { 12.sp.toPx() }
            fun spacingEm(size: Float): Float = when {
                style.letterSpacing.isSp -> with(density) { style.letterSpacing.toPx() } / size
                style.letterSpacing.isEm -> style.letterSpacing.value
                else -> 0f
            }
            return ExportTypography(bodySize = body, titleSize = title, regionSize = region,
                textWidth = with(density) { 166.dp.roundToPx() },
                bodyLineHeight = with(density) { 21.sp.toPx() },
                titleLineHeight = with(density) { 22.5f.sp.toPx() },
                bodyLetterSpacingEm = spacingEm(body),
                titleLetterSpacingEm = spacingEm(title),
                regionLetterSpacingEm = spacingEm(region))
        }

        fun from(resources: Resources): ExportTypography {
            val metrics = resources.displayMetrics
            fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                value, metrics)
            return ExportTypography(bodySize = sp(14f), titleSize = sp(15f),
                regionSize = sp(12f), textWidth = (166f * metrics.density).roundToInt(),
                bodyLineHeight = sp(21f), titleLineHeight = sp(22.5f))
        }
    }
}
