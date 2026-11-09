package com.melhive.zenith.tools

import com.melhive.zenith.agents.ConfirmationBroker
import com.melhive.zenith.core.security.ConfirmationPolicy
import com.melhive.zenith.core.skills.SkillRegistry
import com.melhive.zenith.core.tools.Capability
import com.melhive.zenith.core.tools.RiskLevel
import com.melhive.zenith.core.tools.ToolCatalog
import com.melhive.zenith.core.tools.ToolRequest
import com.melhive.zenith.core.tools.ToolSpec
import com.melhive.zenith.core.tools.ToolValidator
import com.melhive.zenith.core.tools.ValidatedArgs
import com.melhive.zenith.core.tools.ValidationResult
import com.melhive.zenith.files.FileOpException
import com.melhive.zenith.security.AuditLogger
import com.melhive.zenith.settings.SettingsRepository
import kotlinx.coroutines.CancellationException

/** Result returned by an executor. [message] is shown to the user and fed back to models. */
data class ToolResult(val ok: Boolean, val message: String, val taskId: Long? = null)

fun interface ToolExecutor {
    suspend fun execute(args: ValidatedArgs, request: ToolRequest): ToolResult
}

/** Describes what the confirmation dialog should say for a validated call. */
fun interface ConfirmationText {
    fun describe(spec: ToolSpec, args: ValidatedArgs): String
}

sealed interface ToolOutcome {
    val message: String
    data class Executed(val spec: ToolSpec, val result: ToolResult) : ToolOutcome { override val message get() = result.message }
    data class Invalid(val errors: List<String>) : ToolOutcome { override val message get() = "Invalid request: " + errors.joinToString("; ") }
    data class Denied(val reason: String) : ToolOutcome { override val message get() = reason }
    data class Declined(val spec: ToolSpec) : ToolOutcome { override val message get() = "Cancelled — you declined “${spec.title}”." }
    data class Failed(val spec: ToolSpec, val error: String) : ToolOutcome { override val message get() = "${spec.title} failed: $error" }
}

/**
 * The only path from a request (native command, local model, online model,
 * workflow) to an Android operation:
 *
 *   validate schema → check skill enabled → check capabilities →
 *   ask confirmation if required → execute → audit.
 *
 * Model output never reaches an executor without passing every step.
 */
class ToolManager(
    private val catalog: ToolCatalog,
    private val skills: SkillRegistry,
    private val settings: SettingsRepository,
    private val capabilities: CapabilityChecker,
    private val broker: ConfirmationBroker,
    private val audit: AuditLogger,
    private val confirmationText: ConfirmationText,
) {
    private val validator = ToolValidator(catalog)
    private val executors = mutableMapOf<String, ToolExecutor>()

    fun register(name: String, executor: ToolExecutor) {
        require(catalog.find(name) != null) { "No spec for tool $name" }
        executors[name] = executor
    }

    fun registered(): Set<String> = executors.keys

    suspend fun execute(request: ToolRequest): ToolOutcome {
        val origin = request.origin.name
        val validation = validator.validate(request)
        if (validation is ValidationResult.Invalid) {
            audit.record(request.tool, origin, request.args, "invalid", validation.errors.joinToString("; "))
            return ToolOutcome.Invalid(validation.errors)
        }
        val (spec, args) = validation as ValidationResult.Valid
        val s = settings.current()

        val fromModel = request.origin == ToolRequest.Origin.LOCAL_MODEL || request.origin == ToolRequest.Origin.ONLINE_MODEL
        val skill = skills.skillForTool(spec.name)
        if (fromModel && skill != null && skill.id !in s.enabledSkills) {
            val reason = "The ${skill.name} skill is disabled, so this action is not available to the AI."
            audit.record(spec.name, origin, request.args, "denied", reason)
            return ToolOutcome.Denied(reason)
        }

        val missing = spec.capabilities.filterNot { capabilities.isAvailable(it) }
        if (missing.isNotEmpty()) {
            val reason = "${spec.title} needs: " + missing.joinToString(", ") { capabilities.explain(it) }
            audit.record(spec.name, origin, request.args, "denied", reason)
            return ToolOutcome.Denied(reason)
        }

        val executor = executors[spec.name] ?: return ToolOutcome.Denied("${spec.title} is not implemented on this device").also {
            audit.record(spec.name, origin, request.args, "denied", "no executor")
        }

        if (ConfirmationPolicy.requiresConfirmation(spec, request, s.confirmAllChanges)) {
            val approved = broker.confirm(
                title = spec.title,
                message = confirmationText.describe(spec, args) + if (fromModel) "\n\nRequested by the AI model." else "",
                destructive = spec.risk == RiskLevel.DESTRUCTIVE,
            )
            if (!approved) {
                audit.record(spec.name, origin, request.args, "cancelled", "user declined")
                return ToolOutcome.Declined(spec)
            }
        }

        return try {
            val result = executor.execute(args, request)
            audit.record(spec.name, origin, request.args, if (result.ok) "executed" else "failed", result.message)
            ToolOutcome.Executed(spec, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileOpException) {
            audit.record(spec.name, origin, request.args, "failed", e.message ?: "")
            ToolOutcome.Failed(spec, e.message ?: "file operation failed")
        } catch (e: SecurityException) {
            audit.record(spec.name, origin, request.args, "failed", "security: ${e.message}")
            ToolOutcome.Failed(spec, "Android denied access (${e.message}). The permission may have been revoked.")
        } catch (e: Exception) {
            com.melhive.zenith.android.CrashReporter.recordNonFatal("tool ${spec.name}", e)
            audit.record(spec.name, origin, request.args, "failed", e.toString())
            ToolOutcome.Failed(spec, e.message ?: e.javaClass.simpleName)
        }
    }
}

interface CapabilityChecker {
    suspend fun isAvailable(c: Capability): Boolean
    fun explain(c: Capability): String
}
