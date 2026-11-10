package com.melhive.zenith.core.tools

/** How dangerous executing a tool is. Drives confirmation policy. */
enum class RiskLevel {
    /** Reads data only. Never needs confirmation. */
    READ_ONLY,
    /** Creates or changes data in a recoverable way. */
    MODIFY,
    /** Deletes data or performs an irreversible action. Always needs confirmation. */
    DESTRUCTIVE,
}

/**
 * Abstract capabilities a tool needs. The Android layer maps these to runtime
 * permissions, SAF grants or configuration checks. Keeping them abstract lets
 * the policy logic stay testable on the JVM.
 */
enum class Capability(val label: String) {
    STORAGE_ACCESS("Access to a folder you selected"),
    NOTIFICATIONS("Post notifications"),
    MICROPHONE("Microphone"),
    NETWORK("Internet access"),
    ONLINE_AI("A configured online AI provider"),
    CLOCK_APP("A clock app that handles alarm intents"),
    CALENDAR_APP("A calendar app that handles insert intents"),
    CONTACTS("Contacts permission"),
    NOTIFICATION_ACCESS("Notification access"),
    DND_ACCESS("Do Not Disturb access"),
    CALENDAR_READ("Calendar read permission"),
}

enum class ParamType { STRING, TEXT, INT, BOOLEAN, ENUM, DATETIME, PATH, FILE_NAME }

data class ParamSpec(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String> = emptyList(),
    val min: Long? = null,
    val max: Long? = null,
    val maxLength: Int = 512,
)

data class ToolSpec(
    val name: String,
    val title: String,
    val description: String,
    val params: List<ParamSpec>,
    val risk: RiskLevel,
    val capabilities: Set<Capability> = emptySet(),
    /** True when the tool starts a background task instead of returning immediately. */
    val background: Boolean = false,
    /** Harmless and instantly reversible (flashlight, media keys, volume): AI requests run without asking. */
    val lowRisk: Boolean = false,
    /** Always ask, even for commands the user typed or said (e.g. placing a phone call). */
    val alwaysConfirm: Boolean = false,
) {
    init {
        require(NAME_REGEX.matches(name)) { "Invalid tool name: $name" }
        require(params.map { it.name }.toSet().size == params.size) { "Duplicate params in $name" }
    }

    fun param(name: String): ParamSpec? = params.firstOrNull { it.name == name }

    companion object {
        val NAME_REGEX = Regex("^[a-z]+(\\.[a-z_]+)+$")
    }
}

/** A request to execute a tool. Arguments are untrusted until validated. */
data class ToolRequest(
    val tool: String,
    val args: Map<String, String> = emptyMap(),
    val origin: Origin = Origin.NATIVE,
) {
    enum class Origin { NATIVE, LOCAL_MODEL, ONLINE_MODEL, WORKFLOW, USER_UI }
}
