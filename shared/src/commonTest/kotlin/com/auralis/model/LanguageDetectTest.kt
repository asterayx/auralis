package com.auralis.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageDetectTest {
    @Test
    fun detectsChineseAndEnglish() {
        assertEquals("zh", LanguageDetect.detect("大家好，我们开始今天的供应商对齐会。"))
        assertEquals("en", LanguageDetect.detect("Hello everyone, thanks for joining."))
        assertEquals("und", LanguageDetect.detect("12 / 8"))
    }

    @Test
    fun sameFamilyIgnoresRegion() {
        assertTrue(LanguageDetect.sameFamily("zh-CN", "zh"))
        assertTrue(LanguageDetect.sameFamily("en_US", "en"))
        assertFalse(LanguageDetect.sameFamily("zh", "en"))
    }

    @Test
    fun counterpartFlipsThePair() {
        assertEquals("en", LanguageDetect.counterpart("zh", "zh", "en"))
        assertEquals("zh", LanguageDetect.counterpart("en", "zh", "en"))
    }
}
