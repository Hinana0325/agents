package agent.protocol

/**
 * Shared intent envelope for all nodes (phone, watch, PC, earphones, cloud).
 *
 * Mirrors protocol/envelope.schema.json, which is authoritative.
 * This file is dependency-free on purpose: it defines the data and the
 * validation rules only. JSON (de)serialisation is left to the host app's
 * JSON library, so this file does not force a JSON dependency on any node.
 *
 * Rules enforced by [validate]:
 * - task_id, device_id, agent_id must be non-blank.
 * - intent_type must be one of the nine known types; anything else is
 *   rejected before execution, never guessed or downgraded.
 * - params must contain only keys allowed for that intent type.
 * - confirmation_tier must be 1, 2 or 3.
 * - status must be one of the five lifecycle states.
 * - created_at must look like an ISO 8601 UTC timestamp.
 *
 * Agents exchange only these envelopes, never free-form execution
 * instructions. A sender cannot lower its own confirmation tier: the
 * receiving agent applies its own tier policy on top of this field.
 */
enum class IntentType {
    OPEN_APP,
    SET_ALARM,
    CREATE_CALENDAR_EVENT,
    SET_TIMER,
    WEB_SEARCH,
    SAVE_NOTE,
    CHAT_ONLY,
    HANDOFF_TASK,
    VOICE_COMMAND,
}

enum class TaskStatus {
    PENDING,
    CONFIRMED,
    EXECUTING,
    COMPLETED,
    CANCELLED,
}

enum class ConfirmationTier(val value: Int) {
    TIER_1(1),
    TIER_2(2),
    TIER_3(3),
}

data class AgentEnvelope(
    val taskId: String,
    val deviceId: String,
    val agentId: String,
    val intentType: IntentType,
    val params: Map<String, String>,
    val confirmationTier: ConfirmationTier,
    val status: TaskStatus,
    val createdAt: String,
    val targetDeviceId: String? = null,
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (taskId.isBlank()) errors += "task_id must not be blank"
        if (deviceId.isBlank()) errors += "device_id must not be blank"
        if (agentId.isBlank()) errors += "agent_id must not be blank"
        val allowedKeys = ALLOWED_PARAMS[intentType] ?: emptySet()
        val unknownKeys = params.keys - allowedKeys
        if (unknownKeys.isNotEmpty()) errors += "unknown params for $intentType: $unknownKeys"
        val requiredKeys = REQUIRED_PARAMS[intentType] ?: emptySet()
        val missingKeys = requiredKeys - params.keys
        if (missingKeys.isNotEmpty()) errors += "missing required params for $intentType: $missingKeys"
        if (intentType == IntentType.HANDOFF_TASK && targetDeviceId.isNullOrBlank()) {
            errors += "HANDOFF_TASK requires target_device_id"
        }
        if (!ISO_UTC_REGEX.matches(createdAt)) {
            errors += "created_at must be an ISO 8601 UTC timestamp"
        }
        return errors
    }

    fun isValid(): Boolean = validate().isEmpty()

    companion object {
        private val ISO_UTC_REGEX =
            Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")

        // Mirrors the phone MVP IntentValidator for the original seven types.
        // HANDOFF_TASK and VOICE_COMMAND are cross-device only: they carry
        // routing/transcript data and must be converted to a concrete
        // intent (and confirmed) by the receiving agent before execution.
        val ALLOWED_PARAMS: Map<IntentType, Set<String>> = mapOf(
            IntentType.OPEN_APP to setOf("appName"),
            IntentType.SET_ALARM to setOf("hour", "minute", "label"),
            IntentType.CREATE_CALENDAR_EVENT to setOf(
                "title", "date", "startTime", "durationMinutes", "description", "location",
            ),
            IntentType.SET_TIMER to setOf("seconds"),
            IntentType.WEB_SEARCH to setOf("query"),
            IntentType.SAVE_NOTE to setOf("content", "title"),
            IntentType.CHAT_ONLY to emptySet(),
            IntentType.HANDOFF_TASK to setOf("target_device_id", "original_task_id", "summary"),
            IntentType.VOICE_COMMAND to setOf("transcript"),
        )

        val REQUIRED_PARAMS: Map<IntentType, Set<String>> = mapOf(
            IntentType.OPEN_APP to setOf("appName"),
            IntentType.SET_ALARM to setOf("hour", "minute"),
            IntentType.CREATE_CALENDAR_EVENT to setOf("title", "date", "startTime"),
            IntentType.SET_TIMER to setOf("seconds"),
            IntentType.WEB_SEARCH to setOf("query"),
            IntentType.SAVE_NOTE to setOf("content"),
            IntentType.CHAT_ONLY to emptySet(),
            IntentType.HANDOFF_TASK to setOf("target_device_id", "original_task_id"),
            IntentType.VOICE_COMMAND to setOf("transcript"),
        )
    }
}
