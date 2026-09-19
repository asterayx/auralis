package com.auralis.model

import kotlinx.serialization.Serializable

@Serializable
data class LanguageHint(
    val codes: List<String>,
    val autoDetect: Boolean = true,
) {
    val label: String
        get() = when (codes) {
            listOf("zh", "en") -> "中+英"
            listOf("en") -> "English"
            listOf("pt", "en") -> "PT+EN"
            else -> codes.joinToString("+")
        }

    companion object {
        val ChineseEnglish = LanguageHint(listOf("zh", "en"), autoDetect = true)
        val English = LanguageHint(listOf("en"), autoDetect = false)
        val PortugueseEnglish = LanguageHint(listOf("pt", "en"), autoDetect = true)
        val presets: List<LanguageHint> = listOf(ChineseEnglish, English, PortugueseEnglish)
    }
}
