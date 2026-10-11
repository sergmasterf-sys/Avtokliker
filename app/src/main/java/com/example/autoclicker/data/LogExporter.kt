package com.example.autoclicker.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Копирование, отправка и сохранение логов (txt за последние 2 суток).
 * Вызывается из интерфейса: кнопки «Скопировать», «Скачать / Поделиться», «Очистить».
 */
object LogExporter {

    private const val MAX_CLIPBOARD_CHARS = 200_000

    private fun exportFile(context: Context): File? {
        val text = LogFileManager.readAllText()
        if (text.isBlank()) return null
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val file = File(dir, "autoclicker_logs_$stamp.txt")
        file.writeText(text)
        return file
    }

    /** Копирует последние ~200 тыс. символов логов в буфер обмена. Возвращает false, если логов нет. */
    fun copyToClipboard(context: Context): Boolean {
        val text = LogFileManager.readAllText(MAX_CLIPBOARD_CHARS)
        if (text.isBlank()) return false
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AutoClicker logs", text))
        return true
    }

    /** Intent выбора «Поделиться» с txt-файлом логов. null, если логов нет. Запускать через startActivity. */
    fun createShareIntent(context: Context): Intent? {
        val file = exportFile(context) ?: return null
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Логи AutoClicker").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Сохраняет txt в папку «Загрузки» (Android 10+). Возвращает имя файла или null
     * (нет логов или старый Android: тогда используйте createShareIntent).
     */
    @android.annotation.SuppressLint("NewApi")
    fun saveToDownloads(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val text = LogFileManager.readAllText()
        if (text.isBlank()) return null
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val name = "autoclicker_logs_$stamp.txt"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download")
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: return null
        return name
    }
}
