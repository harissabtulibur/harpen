package com.harpen.studio

import android.app.Application
import com.harpen.studio.data.format.HarpenArchiveManager
import com.harpen.studio.data.local.HarpenDatabase
import com.harpen.studio.data.settings.SettingsRepository
import com.harpen.studio.data.storage.SafStorageManager
import com.harpen.studio.input.TouchDebuggerController
import com.harpen.studio.ocr.OfflineOcrManager
import com.harpen.studio.pdf.PdfEngine

class HarpenApplication : Application() {

    lateinit var database: HarpenDatabase
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var archiveManager: HarpenArchiveManager
        private set
    lateinit var safStorageManager: SafStorageManager
        private set
    lateinit var touchDebugger: TouchDebuggerController
        private set
    lateinit var ocrManager: OfflineOcrManager
        private set
    lateinit var pdfEngine: PdfEngine
        private set

    override fun onCreate() {
        super.onCreate()
        database = HarpenDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        archiveManager = HarpenArchiveManager(this)
        safStorageManager = SafStorageManager(this, archiveManager)
        touchDebugger = TouchDebuggerController()
        ocrManager = OfflineOcrManager()
        pdfEngine = PdfEngine(this, archiveManager)
    }
}
