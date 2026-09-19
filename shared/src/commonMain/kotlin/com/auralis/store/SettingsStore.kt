package com.auralis.store

import com.auralis.model.GlossaryEntry
import com.auralis.model.PromptTemplate
import com.auralis.provider.Presets
import com.auralis.provider.ProviderEndpoint
import com.auralis.provider.ProviderProfile
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val endpoints: List<ProviderEndpoint> = Presets.allEndpoints,
    val profiles: List<ProviderProfile> = Presets.defaultProfiles,
    val activeProfileId: String = Presets.demo.id,
    val templates: List<PromptTemplate> = com.auralis.model.BuiltInTemplates.all,
    val vocabulary: List<String> = emptyList(),
    val glossary: List<GlossaryEntry> = emptyList(),
    val keepAudioDefault: Boolean = true,
    val crashReporting: Boolean = false,
    val recordingConsent: Boolean = true,
    val fontScale: Float = 1.0f,
    val darkMode: Boolean = true,
    val highContrast: Boolean = false,
    val uiLanguage: String = "zh",
    val monthlyBudgetUsd: Double? = null,
)

interface SettingsStore {
    suspend fun load(): AppSettings
    suspend fun save(settings: AppSettings)
}

class InMemorySettingsStore(
    initial: AppSettings = AppSettings(),
) : SettingsStore {
    private var value = initial
    override suspend fun load(): AppSettings = value
    override suspend fun save(settings: AppSettings) {
        value = settings
    }
}
