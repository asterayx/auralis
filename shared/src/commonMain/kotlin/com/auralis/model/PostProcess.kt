package com.auralis.model

import kotlinx.serialization.Serializable

@Serializable
data class PromptTemplate(
    val id: String,
    val name: String,
    val description: String,
    val prompt: String,
    val isBuiltIn: Boolean = false,
    val isDefault: Boolean = false,
)

@Serializable
data class PostProcessResult(
    val id: String,
    val templateId: String,
    val templateName: String,
    val content: String,
    val createdAtMs: Long,
    val providerId: String,
    val model: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
)

object BuiltInTemplates {
    val meetingNotes = PromptTemplate(
        id = "tpl-meeting-notes",
        name = "Meeting notes",
        description = "Summary, key points, action items, open questions",
        prompt = """
            You are a meeting secretary. From the transcript below, produce:
            1. A 5–8 sentence summary
            2. Key points (bullet list)
            3. Action items as "- [ ] owner: task (due if mentioned)"
            4. Open questions
            Write in the same language as the majority of the transcript.
            If a term appears in the glossary, keep that wording.
        """.trimIndent(),
        isBuiltIn = true,
        isDefault = true,
    )

    val actionItems = PromptTemplate(
        id = "tpl-actions",
        name = "Action items",
        description = "Extract only action items",
        prompt = """
            Extract action items from the transcript.
            Format each as "- [ ] owner: task (due if mentioned)".
            If the owner is unclear, use "TBD".
            Use the transcript language.
        """.trimIndent(),
        isBuiltIn = true,
    )

    val polish = PromptTemplate(
        id = "tpl-polish",
        name = "Polish transcript",
        description = "Fix punctuation, drop fillers, keep meaning",
        prompt = """
            Clean the transcript: fix punctuation, remove fillers (um/uh/那个/就是),
            and paragraph it. Do not invent content. Keep speaker labels if present.
            Return only the polished transcript.
        """.trimIndent(),
        isBuiltIn = true,
    )

    val all: List<PromptTemplate> = listOf(meetingNotes, actionItems, polish)
}
