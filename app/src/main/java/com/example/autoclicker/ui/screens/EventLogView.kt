package com.example.autoclicker.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autoclicker.data.LogExporter
import com.example.autoclicker.data.LogFileManager
import com.example.autoclicker.ui.theme.AccentAmber
import com.example.autoclicker.ui.theme.AccentGreen
import com.example.autoclicker.ui.theme.AccentRed
import com.example.autoclicker.ui.theme.GlassButton
import com.example.autoclicker.ui.theme.GlassDialog
import com.example.autoclicker.ui.theme.GlassLabel
import com.example.autoclicker.ui.theme.GlassPanel
import com.example.autoclicker.ui.theme.TextMuted
import com.example.autoclicker.ui.theme.TextPrimary
import com.example.autoclicker.ui.theme.TextSecondary

@Composable
fun EventLogView(
    logs: List<String>,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }
    var showRecentLinesDialog by remember { mutableStateOf(false) }
    var recentLinesText by remember { mutableStateOf("") }

    val logFiles = remember(logs) { LogFileManager.listLogFiles() }
    val totalSize = remember(logs) { LogFileManager.totalSizeBytes() }
    val sizeKb = totalSize / 1024

    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .testTag("event_log_container")
    ) {
        GlassLabel("ЖУРНАЛ")

        // Строка с количеством файлов и размером (хранятся 2 суток)
        Text(
            text = "Файлов: ${logFiles.size}, размер $sizeKb КБ, хранятся 2 суток",
            color = TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        // Первый ряд кнопок: «Скопировать логи», «Поделиться», «Сохранить файл»
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GlassButton(
                onClick = {
                    val ok = LogExporter.copyToClipboard(context)
                    if (!ok) {
                        Toast.makeText(context, "Логов нет", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Логи скопированы", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1.2f),
                height = 36.dp,
                cornerRadius = 16.dp,
                hueOffset = 10f
            ) {
                Text("Скопировать логи", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            GlassButton(
                onClick = {
                    val shareIntent = LogExporter.createShareIntent(context)
                    if (shareIntent != null) {
                        context.startActivity(shareIntent)
                    } else {
                        Toast.makeText(context, "Логов нет", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
                height = 36.dp,
                cornerRadius = 16.dp,
                hueOffset = 25f
            ) {
                Text("Поделиться", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            GlassButton(
                onClick = {
                    val fileName = LogExporter.saveToDownloads(context)
                    if (fileName != null) {
                        Toast.makeText(context, "Сохранено в Загрузки: $fileName", Toast.LENGTH_LONG).show()
                    } else {
                        val shareIntent = LogExporter.createShareIntent(context)
                        if (shareIntent != null) {
                            context.startActivity(shareIntent)
                        } else {
                            Toast.makeText(context, "Логов нет", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.weight(1.1f),
                height = 36.dp,
                cornerRadius = 16.dp,
                hueOffset = 40f
            ) {
                Text("Сохранить файл", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Второй ряд кнопок: «Показать 100 строк» и «Очистить логи»
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            GlassButton(
                onClick = {
                    val lines = LogFileManager.readLastLines(100)
                    if (lines.isBlank()) {
                        Toast.makeText(context, "Журнал пуст", Toast.LENGTH_SHORT).show()
                    } else {
                        recentLinesText = lines
                        showRecentLinesDialog = true
                    }
                },
                modifier = Modifier.weight(1.5f),
                height = 36.dp,
                cornerRadius = 16.dp,
                hueOffset = 55f
            ) {
                Text("Показать 100 строк", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            GlassButton(
                onClick = { showClearConfirm = true },
                modifier = Modifier.weight(1f),
                height = 36.dp,
                cornerRadius = 16.dp,
                hueOffset = -60f
            ) {
                Text("Очистить логи", color = AccentRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0F101A).copy(alpha = 0.90f), Color(0xFF06070B).copy(alpha = 0.98f))
                    )
                )
                .padding(10.dp)
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "События пока отсутствуют. Нажмите START или выполните действие.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(logs) { logEntry ->
                        val textColor = when {
                            logEntry.contains("ERROR", ignoreCase = true) || logEntry.contains("ОШИБКА", ignoreCase = true) -> AccentRed
                            logEntry.contains("TAP", ignoreCase = true) || logEntry.contains("НАЖАТИЕ", ignoreCase = true) -> AccentGreen
                            logEntry.contains("WAIT", ignoreCase = true) || logEntry.contains("ОЖИДАНИЕ", ignoreCase = true) -> AccentAmber
                            logEntry.contains("START", ignoreCase = true) || logEntry.contains("ЗАПУСК", ignoreCase = true) -> Color(0xFF80D8FF)
                            else -> TextPrimary
                        }
                        Text(
                            text = logEntry,
                            color = textColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.5.dp)
                        )
                    }
                }
            }
        }

        // Диалог подтверждения очистки логов
        if (showClearConfirm) {
            GlassDialog(
                title = "Очистить логи?",
                onDismissRequest = { showClearConfirm = false },
                onConfirm = {
                    showClearConfirm = false
                    onClearLogs()
                    Toast.makeText(context, "Журнал очищен", Toast.LENGTH_SHORT).show()
                },
                confirmText = "Очистить",
                cancelText = "Отмена"
            ) {
                Text(
                    text = "Все события и файлы логов за 2 суток будут безвозвратно удалены.",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }

        // Диалог просмотра последних 100 строк логов с выделением и кнопкой копирования
        if (showRecentLinesDialog) {
            GlassDialog(
                title = "Последние 100 строк",
                onDismissRequest = { showRecentLinesDialog = false },
                onConfirm = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("AutoClicker recent logs", recentLinesText))
                    Toast.makeText(context, "Строки скопированы", Toast.LENGTH_SHORT).show()
                    showRecentLinesDialog = false
                },
                confirmText = "Копировать",
                cancelText = "Закрыть"
            ) {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF070810))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = recentLinesText,
                            color = Color(0xFFD6DBE7),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}
