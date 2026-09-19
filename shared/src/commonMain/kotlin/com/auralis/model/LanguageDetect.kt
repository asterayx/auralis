package com.auralis.model

object LanguageDetect {
    fun detect(text: String): String {
        val chars = text.filter { it.isLetter() || isCjk(it) }
        if (chars.isEmpty()) return "und"
        val cjk = chars.count { isCjk(it) }
        return if (cjk * 2 >= chars.length) "zh" else "en"
    }

    fun sameFamily(a: String?, b: String): Boolean {
        val left = normalize(a) ?: return false
        return left == normalize(b)
    }

    fun counterpart(detected: String?, localLanguage: String, remoteLanguage: String): String =
        if (sameFamily(detected, localLanguage)) remoteLanguage else localLanguage

    fun normalize(code: String?): String? =
        code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')?.takeIf { it.isNotBlank() }

    fun isCjk(ch: Char): Boolean = ch in '\u4e00'..'\u9fff'
}
