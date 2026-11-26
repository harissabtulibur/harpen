package com.harpen.studio.ocr

import com.harpen.studio.model.OcrCorrectionMode
import com.harpen.studio.model.OcrLanguageMode

data class TextCorrectionChange(
    val originalToken: String,
    val suggestedToken: String,
    val reason: String,
    val accepted: Boolean = true
)

data class OcrCorrectionResult(
    val rawText: String,
    val correctedText: String,
    val detectedLanguage: String,
    val changes: List<TextCorrectionChange>,
    val note: String
)

/**
 * Offline Indonesian & English OCR post-processor.
 *
 * Design guarantees:
 * - Zero internet required.
 * - Never silently alters numbers, dates, formulas, abbreviations, or uppercase technical terms.
 * - Clearly separates basic cleanup & dictionary spelling from actual deep grammar parsing.
 */
object IndonesianEnglishCorrector {

    private val indonesianCommonFixes = mapOf(
        "yng" to "yang",
        "yg" to "yang",
        "dgn" to "dengan",
        "tdk" to "tidak",
        "utk" to "untuk",
        "krn" to "karena",
        "dr" to "dari",
        "pd" to "pada",
        "dlm" to "dalam",
        "tsb" to "tersebut",
        "sbgi" to "sebagai",
        "analisa" to "analisis",
        "aktifitas" to "aktivitas",
        "praktek" to "praktik",
        "resiko" to "risiko",
        "sistim" to "sistem",
        "tehnik" to "teknik",
        "kwitansi" to "kuitansi",
        "jadual" to "jadwal",
        "nasehat" to "nasihat",
        "apotik" to "apotek",
        "atlit" to "atlet",
        "ijin" to "izin",
        "paham" to "paham"
    )

    private val englishCommonFixes = mapOf(
        "teh" to "the",
        "adn" to "and",
        "taht" to "that",
        "wiht" to "with",
        "recieve" to "receive",
        "seperate" to "separate",
        "occured" to "occurred",
        "definately" to "definitely",
        "acommodate" to "accommodate",
        "enviroment" to "environment",
        "goverment" to "government",
        "independant" to "independent",
        "mispell" to "misspell",
        "untill" to "until",
        "wich" to "which",
        "becuase" to "because",
        "thier" to "their"
    )

    private val indonesianMarkers = setOf(
        "yang", "dan", "di", "ke", "dari", "untuk", "dengan", "pada", "adalah", "dalam", "tidak", "ini", "itu"
    )

    private val englishMarkers = setOf(
        "the", "and", "of", "to", "in", "for", "with", "that", "this", "is", "are", "from"
    )

    fun process(
        rawText: String,
        languageMode: OcrLanguageMode,
        correctionMode: OcrCorrectionMode,
        preserveTechnicalTerms: Boolean = true
    ): OcrCorrectionResult {
        if (rawText.isBlank() || correctionMode == OcrCorrectionMode.NONE) {
            return OcrCorrectionResult(
                rawText = rawText,
                correctedText = rawText,
                detectedLanguage = detectLanguageLabel(rawText, languageMode),
                changes = emptyList(),
                note = "No correction applied."
            )
        }

        val detected = detectLanguageLabel(rawText, languageMode)
        var working = cleanWhitespaceAndLineWraps(rawText)
        val changes = mutableListOf<TextCorrectionChange>()

        if (correctionMode == OcrCorrectionMode.PUNCTUATION ||
            correctionMode == OcrCorrectionMode.GRAMMAR ||
            correctionMode == OcrCorrectionMode.SPELLING
        ) {
            working = normalizePunctuation(working)
        }

        if (correctionMode == OcrCorrectionMode.SPELLING || correctionMode == OcrCorrectionMode.GRAMMAR) {
            val activeDict = buildMap {
                if (languageMode == OcrLanguageMode.INDONESIAN || languageMode == OcrLanguageMode.MIXED || languageMode == OcrLanguageMode.AUTO) {
                    putAll(indonesianCommonFixes)
                }
                if (languageMode == OcrLanguageMode.ENGLISH || languageMode == OcrLanguageMode.MIXED || languageMode == OcrLanguageMode.AUTO) {
                    putAll(englishCommonFixes)
                }
            }

            val tokenRegex = Regex("""(\b[A-Za-z0-9_./:-]+\b)""")
            working = tokenRegex.replace(working) { match ->
                val token = match.value
                if (preserveTechnicalTerms && isProtectedToken(token)) {
                    token
                } else {
                    val lower = token.lowercase()
                    val replacement = activeDict[lower]
                    if (replacement != null && replacement != lower) {
                        val cased = if (token.first().isUpperCase()) {
                            replacement.replaceFirstChar { it.uppercase() }
                        } else {
                            replacement
                        }
                        changes.add(
                            TextCorrectionChange(
                                originalToken = token,
                                suggestedToken = cased,
                                reason = "Offline ID/EN dictionary match"
                            )
                        )
                        cased
                    } else {
                        token
                    }
                }
            }
        }

        if (correctionMode == OcrCorrectionMode.GRAMMAR) {
            working = applySentenceCapitalization(working)
        }

        val note = when (correctionMode) {
            OcrCorrectionMode.BASIC_CLEANUP -> "Applied offline whitespace, line-wrap, and hyphenation cleanup."
            OcrCorrectionMode.PUNCTUATION -> "Applied offline punctuation spacing and basic cleanup."
            OcrCorrectionMode.SPELLING -> "Applied offline dictionary spelling check; protected formulas, numbers, dates, and technical terms."
            OcrCorrectionMode.GRAMMAR -> "Applied offline rule-based capitalization & spelling cleanup (full neural grammar parsing requires an external offline LLM/grammar pack)."
            OcrCorrectionMode.NONE -> ""
        }

        return OcrCorrectionResult(
            rawText = rawText,
            correctedText = working,
            detectedLanguage = detected,
            changes = changes,
            note = note
        )
    }

    fun applySelectedChanges(rawText: String, changes: List<TextCorrectionChange>): String {
        var result = cleanWhitespaceAndLineWraps(rawText)
        for (change in changes) {
            if (change.accepted) {
                result = result.replaceFirst(change.originalToken, change.suggestedToken)
            }
        }
        return result
    }

    private fun isProtectedToken(token: String): Boolean {
        if (token.any { it.isDigit() }) return true
        if (token.contains('/') || token.contains(':') || token.contains('=') || token.contains('_') || token.contains('.')) return true
        // All-caps acronyms (e.g., PDF, OCR, RAM, API, USB, KTP, BPJS)
        if (token.length >= 2 && token.all { it.isUpperCase() }) return true
        // CamelCase / mixed technical identifiers (e.g., MotionEvent, HarpenCanvas)
        if (token.drop(1).any { it.isUpperCase() }) return true
        return false
    }

    private fun cleanWhitespaceAndLineWraps(input: String): String {
        return input
            .replace("\r\n", "\n")
            .replace(Regex("-\n([a-zA-Z])"), "$1")
            .replace(Regex("[ \t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun normalizePunctuation(input: String): String {
        return input
            .replace(Regex("\\s+([,.;:!?])"), "$1")
            .replace(Regex("([,.;:!?])([A-Za-z])"), "$1 $2")
    }

    private fun applySentenceCapitalization(input: String): String {
        val sb = StringBuilder(input.length)
        var capitalizeNext = true
        for (ch in input) {
            if (capitalizeNext && ch.isLetter()) {
                sb.append(ch.uppercaseChar())
                capitalizeNext = false
            } else {
                sb.append(ch)
                if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                    capitalizeNext = true
                }
            }
        }
        return sb.toString()
    }

    private fun detectLanguageLabel(text: String, mode: OcrLanguageMode): String {
        if (mode != OcrLanguageMode.AUTO) return mode.displayName
        val words = text.lowercase().split(Regex("\\W+")).filter { it.isNotBlank() }
        val idHits = words.count { it in indonesianMarkers }
        val enHits = words.count { it in englishMarkers }
        return when {
            idHits > 0 && enHits > 0 -> "Mixed (Indonesian + English)"
            idHits > enHits -> "Bahasa Indonesia"
            enHits > idHits -> "English"
            else -> "Latin (Auto)"
        }
    }
}
