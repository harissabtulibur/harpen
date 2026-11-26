package com.harpen.studio

import com.harpen.studio.model.OcrCorrectionMode
import com.harpen.studio.model.OcrLanguageMode
import com.harpen.studio.ocr.IndonesianEnglishCorrector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndonesianEnglishCorrectorTest {

    @Test
    fun preservesNumbersFormulasAndTechnicalAcronyms() {
        val raw = "Analisa RAM 4GB pd 25/11/2026 utk API OCR = 100% tehnik."
        val res = IndonesianEnglishCorrector.process(
            rawText = raw,
            languageMode = OcrLanguageMode.MIXED,
            correctionMode = OcrCorrectionMode.SPELLING,
            preserveTechnicalTerms = true
        )

        assertTrue(res.correctedText.contains("RAM"))
        assertTrue(res.correctedText.contains("4GB"))
        assertTrue(res.correctedText.contains("25/11/2026"))
        assertTrue(res.correctedText.contains("API"))
        assertTrue(res.correctedText.contains("OCR"))
        assertTrue(res.correctedText.contains("Analisis"))
        assertTrue(res.correctedText.contains("pada"))
        assertTrue(res.correctedText.contains("untuk"))
        assertTrue(res.correctedText.contains("teknik"))
    }

    @Test
    fun noneModeLeavesTextUntouched() {
        val raw = "teh analisa 123"
        val res = IndonesianEnglishCorrector.process(
            rawText = raw,
            languageMode = OcrLanguageMode.AUTO,
            correctionMode = OcrCorrectionMode.NONE
        )
        assertEquals(raw, res.correctedText)
        assertTrue(res.changes.isEmpty())
    }
}
