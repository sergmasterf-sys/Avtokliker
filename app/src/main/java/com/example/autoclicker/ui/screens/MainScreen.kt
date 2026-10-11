package com.example.autoclicker.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.ui.components.PermissionButtonsWebView
import com.example.autoclicker.ui.theme.LocalNeonHue
import com.example.autoclicker.ui.theme.NeonFrame
import com.example.autoclicker.ui.theme.NeonTheme
import com.example.autoclicker.ui.theme.ProvideNeon

/**
 * Главный экран: полупрозрачная стеклянная карточка с неоновой рамкой
 * на тёмном фоне со светящимися пятнами в цвет текущего неона.
 */
@Composable
fun MainScreen(
    settings: ClickerSettings,
    isAccessibilityConnected: Boolean,
    isOverlayGranted: Boolean,
    isAccessibilityEnabledInSystem: Boolean = false,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onLaunchOverlayService: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    remember { NeonTheme.init(context); true }
    val neonBriFraction = settings.neonBrightness / 100f

    ProvideNeon(brightness = neonBriFraction) {
        val currentHue = LocalNeonHue.current
        val scrollState = rememberScrollState()

        Box(
            modifier = modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(Color(0xFF080A18))

                    // Мягкие светящиеся пятна в цвет текущего неона
                    val hh = currentHue.value
                    val neonColor1 = Color(NeonTheme.hsl(hh, 1f, 0.55f, 0.35f))
                    val neonColor2 = Color(NeonTheme.hsl(hh + 40f, 1f, 0.50f, 0.25f))

                    // Верхнее-левое пятно
                    val rx1 = size.width * 0.80f
                    val ry1 = size.height * 0.40f
                    val cx1 = size.width * 0.20f
                    val cy1 = size.height * 0.25f
                    withTransform({ scale(1f, ry1 / rx1, Offset(cx1, cy1)) }) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to neonColor1,
                                0.65f to neonColor1.copy(alpha = 0f),
                                1f to Color.Transparent,
                                center = Offset(cx1, cy1),
                                radius = rx1
                            ),
                            radius = rx1,
                            center = Offset(cx1, cy1)
                        )
                    }

                    // Нижнее-правое пятно
                    val rx2 = size.width * 0.85f
                    val ry2 = size.height * 0.45f
                    val cx2 = size.width * 0.82f
                    val cy2 = size.height * 0.78f
                    withTransform({ scale(1f, ry2 / rx2, Offset(cx2, cy2)) }) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to neonColor2,
                                0.65f to neonColor2.copy(alpha = 0f),
                                1f to Color.Transparent,
                                center = Offset(cx2, cy2),
                                radius = rx2
                            ),
                            radius = rx2,
                            center = Offset(cx2, cy2)
                        )
                    }
                }
                .statusBarsPadding()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            val cardRadius = 32.dp
            val cardShape = RoundedCornerShape(cardRadius)

            // Стеклянная полупрозрачная карточка с неоновой рамкой
            Box(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(cardShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF0A0C1E).copy(alpha = 0.52f),
                                    Color(0xFF060712).copy(alpha = 0.60f)
                                )
                            )
                        )
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Автокликер",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF1F4FB)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Умный режим",
                        fontSize = 14.sp,
                        color = Color(0xFF80D8FF)
                    )
                    Spacer(Modifier.height(24.dp))

                    // Accessibility + Overlay (исчезают после выдачи)
                    if (!isOverlayGranted || !isAccessibilityEnabledInSystem) {
                        PermissionButtonsWebView(
                            isOverlayGranted = isOverlayGranted,
                            isAccessibilityGranted = isAccessibilityEnabledInSystem,
                            showLaunchButton = false,
                            onRequestOverlay = onOpenOverlaySettings,
                            onRequestAccessibility = onOpenAccessibilitySettings,
                            onLaunchOverlay = {},
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (isAccessibilityEnabledInSystem && !isAccessibilityConnected) {
                            Text(
                                text = "Accessibility включён, подключаюсь…",
                                color = Color(0xFF80D8FF),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                    }

                    // Постоянная кнопка «Вызов плавающей кнопки»
                    PermissionButtonsWebView(
                        isOverlayGranted = true,
                        isAccessibilityGranted = true,
                        showLaunchButton = true,
                        launchOnly = true,
                        onRequestOverlay = {},
                        onRequestAccessibility = {},
                        onLaunchOverlay = {
                            if (!isAccessibilityEnabledInSystem || !isOverlayGranted) {
                                val missing = buildList {
                                    if (!isAccessibilityEnabledInSystem) add("Accessibility")
                                    if (!isOverlayGranted) add("Overlay")
                                }.joinToString(", ")
                                Toast.makeText(
                                    context,
                                    "Нужны разрешения: $missing",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                SettingsRepository.getInstance(context).setOverlayWanted(true)
                                onLaunchOverlayService()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Неоновая рамка поверх карточки
                NeonFrame(
                    cornerRadius = cardRadius,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }
}

