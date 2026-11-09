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
 * Visual language is taken from the supplied reference screenshot: near-black
 * canvas, numbered outlined cards, one green accent, a hero page-count badge and
 * a fixed bottom dock. Green is reserved for selection, the primary action,
 * live progress and the few numbers that must be found at a glance.
 */
object Palette {
    val background = Color(0xFF080B0A)
    val card = Color(0xFF121614)
    val inner = Color(0xFF191F1B)
    val surface = Color(0xFF0E1210)
    val surfaceRaised = Color(0xFF1A201C)
    val cardStroke = Color(0xFF252D28)
    val stroke = Color(0xFF202722)
    val strokeStrong = Color(0xFF334038)
    val textPrimary = Color(0xFFF1F6F2)
    val textSecondary = Color(0xFFA2B0A7)
    val textTertiary = Color(0xFF6F7D74)
    val accent = Color(0xFF4BE3A0)
    val accentDim = Color(0xFF2BA575)
    val onAccent = Color(0xFF052316)
    val warning = Color(0xFFE5B563)
    val danger = Color(0xFFF0736F)
    val track = Color(0xFF1E2621)
}

object Dimens {
    val pagePadding = 18.dp
    val sectionGap = 18.dp
    val cardGap = 14.dp
    val cardRadius = 16.dp
    val innerRadius = 12.dp
    val controlRadius = 10.dp
    val touch = 48.dp
}

@Composable
fun PdfSplitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Palette.onAccent,
            background = Palette.background,
            onBackground = Palette.textPrimary,
            surface = Palette.card,
            onSurface = Palette.textPrimary,
            surfaceVariant = Palette.inner,
            onSurfaceVariant = Palette.textSecondary,
            error = Palette.danger,
        ),
        content = content,
    )
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
            .border(1.dp, Palette.cardStroke, RoundedCornerShape(Dimens.cardRadius))
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
            .border(1.dp, Palette.cardStroke, RoundedCornerShape(Dimens.innerRadius))
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
                    Palette.accent.copy(alpha = 0.20f),
                    Palette.accent.copy(alpha = 0.015f),
                ),
                start = Offset(w, 0f),
                end = Offset(w * 0.52f, h),
            ),
        )
        drawLine(
            color = Palette.accent.copy(alpha = 0.30f),
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
            .height(42.dp)
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
            .height(54.dp),
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
