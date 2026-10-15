package com.example.autoclicker.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Неоновые киберпанк HUD-кнопки (нативный Jetpack Compose):
 * - Базовый цвет: яркий салатово-голубой неон (Lime + Cyan).
 * - Во время нажатия (touch down): кнопка мгновенно меняет цвет на ярко-красный неон.
 * - Мгновенная прогрузка (0 мс) без задержек и рывков.
 */
@Composable
fun PermissionButtonsWebView(
    isOverlayGranted: Boolean,
    isAccessibilityGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onLaunchOverlay: () -> Unit = {},
    showLaunchButton: Boolean = true,
    launchOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (launchOnly) {
        // Постоянная кнопка «Вызов плавающей кнопки»
        NeonHudButton(
            title = "Плавающая кнопка",
            subtitle = "▸ вызвать",
            icon = Icons.Filled.PlayArrow,
            onClick = onLaunchOverlay,
            modifier = modifier
                .fillMaxWidth()
                .height(68.dp)
        )
    } else {
        // Ряд кнопок разрешений: Accessibility и Поверх экрана (Overlay)
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Кнопка Accessibility
            AnimatedVisibility(
                visible = !isAccessibilityGranted,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier.weight(1f)
            ) {
                NeonHudButton(
                    title = "Accessibility",
                    subtitle = "▸ нажми",
                    icon = Icons.Filled.AccessibilityNew,
                    onClick = onRequestAccessibility,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                )
            }

            // Кнопка Поверх экрана
            AnimatedVisibility(
                visible = !isOverlayGranted,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier.weight(1f)
            ) {
                NeonHudButton(
                    title = "Поверх экрана",
                    subtitle = "▸ нажми",
                    icon = Icons.Filled.Layers,
                    onClick = onRequestOverlay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                )
            }
        }
    }
}

/**
 * Неоновая киберпанк HUD-кнопка:
 * В покое: салатово-голубой неон.
 * При нажатии: ярко-красный неон.
 */
@Composable
private fun NeonHudButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = tween(90),
        label = "hud_scale"
    )

    // Неоновые цвета:
    // Покой: Салатово-голубой неон
    val idleCyan = Color(0xFF00E5FF)    // Ярко-голубой неон
    val idleLime = Color(0xFF39FF14)    // Ярко-салатовый неон
    val idleBlend = Color(0xFF00FFA6)   // Салатово-голубой бленд
    // Нажатие: Ярко-красный неон
    val pressedRed = Color(0xFFFF1A35)  // Неоново-красный
    val pressedRedGlow = Color(0xFFFF385C)

    val primaryColor by animateColorAsState(
        targetValue = if (isPressed) pressedRed else idleCyan,
        animationSpec = tween(80),
        label = "hud_primary_color"
    )

    val secondaryColor by animateColorAsState(
        targetValue = if (isPressed) pressedRedGlow else idleLime,
        animationSpec = tween(80),
        label = "hud_secondary_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "hud_neon")

    // Плавная пульсация рамок
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.70f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hud_pulse"
    )

    // Лазерная полоса сканирования
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "hud_scan"
    )

    // Мерцание статуса («▸ нажми»)
    val blinkAlpha by infiniteTransition.animateFloat(
        initialValue = 0.50f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hud_blink"
    )

    val buttonShape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(buttonShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .drawBehind {
                val w = size.width
                val h = size.height
                val cornerLen = 14.dp.toPx()
                val strokeW = 2.5.dp.toPx()

                // 1. Полупрозрачный стеклянный фон с неоновым отблеском
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            secondaryColor.copy(alpha = if (isPressed) 0.30f else 0.14f),
                            primaryColor.copy(alpha = if (isPressed) 0.22f else 0.08f),
                            Color(0xFF090B16).copy(alpha = 0.70f)
                        )
                    ),
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                )

                // 2. Тонкая светящаяся рамка периметра
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            secondaryColor.copy(alpha = 0.40f * pulseAlpha),
                            primaryColor.copy(alpha = 0.50f * pulseAlpha)
                        )
                    ),
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx())
                )

                // 3. Бегущая полоса сканирования (лазер)
                val scanY = h * scanProgress
                val scanAlpha = when {
                    scanProgress < 0.1f -> scanProgress * 10f
                    scanProgress > 0.9f -> (1f - scanProgress) * 10f
                    else -> 1f
                }
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            secondaryColor.copy(alpha = 0.60f * scanAlpha),
                            primaryColor.copy(alpha = 0.90f * scanAlpha),
                            secondaryColor.copy(alpha = 0.60f * scanAlpha),
                            Color.Transparent
                        )
                    ),
                    start = Offset(4.dp.toPx(), scanY),
                    end = Offset(w - 4.dp.toPx(), scanY),
                    strokeWidth = 1.6.dp.toPx()
                )

                // 4. Кибернетические L-скобы по 4 углам:
                // Верхние углы: салатовый неон (при нажатии — красный)
                // Нижние углы: голубой неон (при нажатии — красный)
                val topCornerColor = secondaryColor.copy(alpha = if (isPressed) 1f else pulseAlpha)
                val bottomCornerColor = primaryColor.copy(alpha = if (isPressed) 1f else pulseAlpha)

                // Верхний-левый угол (салатовый / красный)
                drawLine(topCornerColor, Offset(0f, 0f), Offset(cornerLen, 0f), strokeW, StrokeCap.Square)
                drawLine(topCornerColor, Offset(0f, 0f), Offset(0f, cornerLen), strokeW, StrokeCap.Square)

                // Верхний-правый угол (салатовый / красный)
                drawLine(topCornerColor, Offset(w - cornerLen, 0f), Offset(w, 0f), strokeW, StrokeCap.Square)
                drawLine(topCornerColor, Offset(w, 0f), Offset(w, cornerLen), strokeW, StrokeCap.Square)

                // Нижний-левый угол (голубой / красный)
                drawLine(bottomCornerColor, Offset(0f, h - cornerLen), Offset(0f, h), strokeW, StrokeCap.Square)
                drawLine(bottomCornerColor, Offset(0f, h), Offset(cornerLen, h), strokeW, StrokeCap.Square)

                // Нижний-правый угол (голубой / красный)
                drawLine(bottomCornerColor, Offset(w - cornerLen, h), Offset(w, h), strokeW, StrokeCap.Square)
                drawLine(bottomCornerColor, Offset(w, h - cornerLen), Offset(w, h), strokeW, StrokeCap.Square)
            }
            .semantics {
                contentDescription = "$title, $subtitle"
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isPressed) pressedRed else idleBlend,
                modifier = Modifier.size(20.dp)
            )

            Spacer(Modifier.height(3.dp))

            Text(
                text = title,
                color = if (isPressed) Color(0xFFFFD2D8) else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.08.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            Text(
                text = subtitle,
                color = if (isPressed) pressedRed else secondaryColor.copy(alpha = blinkAlpha),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
