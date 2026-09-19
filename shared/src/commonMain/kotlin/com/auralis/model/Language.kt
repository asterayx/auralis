package com.auralis.model

import kotlinx.serialization.Serializable

@Serializable
data class LanguageHint(
    val codes: List<String>,
    val autoDetect: Boolean = true,
) {
    companion object {
        val ChineseEnglish = LanguageHint(listOf("zh", "en"), autoDetect = true)
        val English = LanguageHint(listOf("en"), autoDetect = false)
        val PortugueseEnglish = LanguageHint(listOf("pt", "en"), autoDetect = true)
    }
}
