package com.m1racle.reader.pdfsplit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * UI colors use role-based Radix Slate and Green scales. MaterialTheme and the
 * custom primitives agree on a single palette, following the system appearance
 * instead of forcing every user into the old black/green screenshot theme.
 */
data class PdfPalette(
    val background: Color,
    val card: Color,
    val inner: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val cardStroke: Color,
    val stroke: Color,
    val strokeStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentDim: Color,
    val onAccent: Color,
    val warning: Color,
    val danger: Color,
    val track: Color,
)

internal val lightPdfPalette = PdfPalette(
    background = Color(0xFFFCFCFD),  // slate 1
    card = Color(0xFFFFFFFF),
    inner = Color(0xFFF0F0F3),       // slate 3
    surface = Color(0xFFF9F9FB),     // slate 2
    surfaceRaised = Color(0xFFF0F0F3),
    cardStroke = Color(0xFFD1D1D6), // slate 7
    stroke = Color(0xFFB9BBC6),     // slate 8
    strokeStrong = Color(0xFF8B8D98), // slate 9
    textPrimary = Color(0xFF1C2024), // slate 12
    textSecondary = Color(0xFF45474E),
    textTertiary = Color(0xFF60646C), // slate 11
    accent = Color(0xFF218358),       // green 11: legible on white
    accentDim = Color(0xFF30A46C),    // green 9
    onAccent = Color(0xFFFFFFFF),
    warning = Color(0xFFAD5700),
    danger = Color(0xFFCE2C31),
    track = Color(0xFFDEDDE3),
)

internal val darkPdfPalette = PdfPalette(
    background = Color(0xFF111113),   // slate dark 1
    card = Color(0xFF1A1B1E),
    inner = Color(0xFF27282C),
    surface = Color(0xFF18191B),      // slate dark 2
    surfaceRaised = Color(0xFF212225),// slate dark 3
    cardStroke = Color(0xFF43444B),   // slate dark 6
    stroke = Color(0xFF5A5B63),
    strokeStrong = Color(0xFF777985),
    textPrimary = Color(0xFFEDEEF0),  // slate dark 12
    textSecondary = Color(0xFFB0B2BD),// slate dark 11
    textTertiary = Color(0xFFA3A5AF),
    accent = Color(0xFF50C58B),
    accentDim = Color(0xFF30A46C),
    onAccent = Color(0xFF082315),
    warning = Color(0xFFFFC53D),
    danger = Color(0xFFFF6369),
    track = Color(0xFF34353B),
)

private val LocalPdfPalette = staticCompositionLocalOf { lightPdfPalette }

val Palette: PdfPalette
    @Composable get() = LocalPdfPalette.current

object Dimens {
    val pagePadding = 18.dp
    val sectionGap = 14.dp
    val cardGap = 12.dp
    val cardRadius = 12.dp
    val innerRadius = 9.dp
    val controlRadius = 9.dp
    val touch = 48.dp
}

@Composable
fun PdfSplitTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkPdfPalette else lightPdfPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.card,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.inner,
            onSurfaceVariant = colors.textSecondary,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.card,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.inner,
            onSurfaceVariant = colors.textSecondary,
            error = colors.danger,
        )
    }
    CompositionLocalProvider(LocalPdfPalette provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Numbered, outlined card from the reference layout. */
@Composable
fun NumberedCard(
    number: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.cardRadius))
            .background(Palette.card)
.border(0.5.dp, Palette.cardStroke, RoundedCornerShape(Dimens.cardRadius))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepBadge(number)
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.textPrimary,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = subtitle,
                    modifier = Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = Palette.textTertiary,
                    maxLines = 1,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            trailing?.invoke()
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
fun StepBadge(number: String) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(Palette.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = Palette.onAccent,
        )
    }
}

/** A softer row inside a numbered card. */
@Composable
fun InnerRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.innerRadius))
            .background(Palette.inner)
.border(0.5.dp, Palette.cardStroke, RoundedCornerShape(Dimens.innerRadius))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun PageCountBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.accent.copy(alpha = 0.08f))
            .border(1.dp, Palette.accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = Palette.accent,
        )
    }
}

/** Angular green light in the top-right corner of the hero, as in the reference. */
@Composable
fun HeroDecoration(modifier: Modifier = Modifier) {
    val accent = Palette.accent
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val polygon = Path().apply {
            moveTo(w * 0.56f, 0f)
            lineTo(w, 0f)
            lineTo(w, h * 0.92f)
            lineTo(w * 0.78f, h * 0.34f)
            close()
        }
        drawPath(
            path = polygon,
            brush = Brush.linearGradient(
                colors = listOf(
                    accent.copy(alpha = 0.20f),
                    accent.copy(alpha = 0.015f),
                ),
                start = Offset(w, 0f),
                end = Offset(w * 0.52f, h),
            ),
        )
        drawLine(
            color = accent.copy(alpha = 0.30f),
            start = Offset(w * 0.56f, 0f),
            end = Offset(w * 0.78f, h * 0.34f),
            strokeWidth = 1.5f,
        )
    }
}

@Composable
fun OutlinedAction(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val borderColor = when {
        !enabled -> Palette.cardStroke
        selected -> Palette.accent
        else -> Palette.strokeStrong
    }
    val background = if (selected) Palette.accent.copy(alpha = 0.10f) else Palette.inner
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(Dimens.controlRadius))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(Dimens.controlRadius))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                !enabled -> Palette.textTertiary
                selected -> Palette.accent
                else -> Palette.textPrimary
            },
        )
    }
}

@Composable
fun DockNumberChip(value: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Palette.accent else Color.Transparent)
            .border(
                1.dp,
                if (selected) Palette.accent else Palette.strokeStrong,
                RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$value",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = if (selected) Palette.onAccent else Palette.textSecondary,
        )
    }
}

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    running: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (running) Palette.inner else Palette.accent,
            contentColor = if (running) Palette.textPrimary else Palette.onAccent,
            disabledContainerColor = Palette.inner,
            disabledContentColor = Palette.textTertiary,
        ),
    ) {
        if (!running) {
            Text("▶", fontSize = 12.sp, color = Palette.onAccent)
            Spacer(Modifier.width(9.dp))
        }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.cardStroke),
    )
}

/** Kept for compatibility with older section callers. */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.textSecondary,
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(10.dp))
        content()
        Spacer(Modifier.height(14.dp))
        Hairline()
    }
}

@Composable
fun FieldSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Palette.inner, RoundedCornerShape(Dimens.controlRadius))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        content = content,
    )
}
