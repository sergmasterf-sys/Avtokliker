package com.example.autoclicker.data

import android.content.Context
import com.example.autoclicker.engine.RunCoordinator

/**
 * Сбросы настроек. Перед любым сбросом останавливаются все работающие режимы.
 */
object ResetManager {

    private fun repo(context: Context) = SettingsRepository.getInstance(context.applicationContext)

    /** Умный режим: флаги, режим конфигов и выбор активного конфига. Файлы загруженных конфигов остаются. */
    fun resetSmartMode(context: Context) {
        RunCoordinator.stopAll(context, "сброс умного режима")
        repo(context).resetSmartFlags()
        val cm = SmartConfigManager.getInstance(context.applicationContext)
        cm.setMode(SmartConfigMode.DEFAULT_ONLY)
        cm.setActiveName("")
        SmartLearnedStore.clear()
        com.example.autoclicker.engine.FastPipeline.FingerprintStore(context).clearAll()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: умный режим сброшен (встроенный конфиг)")
    }

    fun resetNeonBrightness(context: Context) {
        repo(context).resetNeonBrightness()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: яркость неона сброшена")
    }

    /** Сбросить всё: умный режим, яркость. Конфиги и логи не удаляются. */
    fun resetAll(context: Context) {
        RunCoordinator.stopAll(context, "сброс всех настроек")
        val r = repo(context)
        r.resetSmartFlags()
        r.resetNeonBrightness()
        val cm = SmartConfigManager.getInstance(context.applicationContext)
        cm.setMode(SmartConfigMode.DEFAULT_ONLY)
        cm.setActiveName("")
        SmartLearnedStore.clear()
        com.example.autoclicker.engine.FastPipeline.FingerprintStore(context).clearAll()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RESET: сброшены все настройки")
    }

}
