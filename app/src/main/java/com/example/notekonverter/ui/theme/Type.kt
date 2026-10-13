package com.example.notekonverter.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Set of Material typography styles to start with
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
)

/** Thin strokes break up on e-paper, so text is one weight step heavier. */
val EinkTypography = Typography.run {
    fun TextStyle.heavier() = copy(fontWeight = if ((fontWeight ?: FontWeight.Normal) < FontWeight.Medium) FontWeight.Medium else FontWeight.Bold)
    copy(
        titleLarge = titleLarge.heavier(),
        titleMedium = titleMedium.heavier(),
        titleSmall = titleSmall.heavier(),
        bodyLarge = bodyLarge.heavier(),
        bodyMedium = bodyMedium.heavier(),
        bodySmall = bodySmall.heavier(),
        labelLarge = labelLarge.heavier(),
        labelMedium = labelMedium.heavier(),
    )
}
