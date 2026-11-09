package com.melhive.zenith.core.tools

import com.melhive.zenith.core.security.PathPolicy
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/** Arguments that passed schema validation, with typed accessors. */
class ValidatedArgs internal constructor(private val values: Map<String, Any>) {
    fun string(name: String): String = values[name] as String
    fun stringOrNull(name: String): String? = values[name] as String?
    fun int(name: String): Long = values[name] as Long
    fun intOrNull(name: String): Long? = values[name] as Long?
    fun bool(name: String): Boolean = values[name] as Boolean
    fun boolOrNull(name: String): Boolean? = values[name] as Boolean?
    fun dateTime(name: String): LocalDateTime = values[name] as LocalDateTime
    fun dateTimeOrNull(name: String): LocalDateTime? = values[name] as LocalDateTime?
    fun has(name: String): Boolean = values.containsKey(name)
    val raw: Map<String, Any> get() = values
}

sealed interface ValidationResult {
    data class Valid(val spec: ToolSpec, val args: ValidatedArgs) : ValidationResult
    data class Invalid(val errors: List<String>) : ValidationResult
}

/**
 * Strict schema validation. Unknown tools and unknown arguments are rejected,
 * so model output can never smuggle extra parameters into an executor.
 */
class ToolValidator(private val catalog: ToolCatalog) {

    fun validate(request: ToolRequest): ValidationResult {
        val spec = catalog.find(request.tool)
            ?: return ValidationResult.Invalid(listOf("Unknown tool '${request.tool}'"))
        val errors = mutableListOf<String>()
        val unknown = request.args.keys - spec.params.map { it.name }.toSet()
        if (unknown.isNotEmpty()) errors += "Unknown argument(s): ${unknown.sorted().joinToString()}"

        val values = LinkedHashMap<String, Any>()
        for (p in spec.params) {
            val rawValue = request.args[p.name]?.trim()
            if (rawValue.isNullOrEmpty()) {
                if (p.required) errors += "Missing required argument '${p.name}'"
                continue
            }
            when (val converted = convert(p, rawValue)) {
                is Conversion.Ok -> values[p.name] = converted.value
                is Conversion.Error -> errors += "Argument '${p.name}': ${converted.message}"
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid(spec, ValidatedArgs(values))
        else ValidationResult.Invalid(errors)
    }

    private sealed interface Conversion {
        data class Ok(val value: Any) : Conversion
        data class Error(val message: String) : Conversion
    }

    private fun convert(p: ParamSpec, raw: String): Conversion {
        val limit = if (p.type == ParamType.TEXT) maxOf(p.maxLength, 100_000) else p.maxLength
        if (raw.length > limit) return Conversion.Error("too long (max $limit characters)")
        if (p.type != ParamType.TEXT && raw.any { it.isISOControl() }) {
            return Conversion.Error("contains control characters")
        }
        return when (p.type) {
            ParamType.STRING, ParamType.TEXT -> Conversion.Ok(raw)
            ParamType.INT -> {
                val n = raw.toLongOrNull() ?: return Conversion.Error("not an integer")
                if (p.min != null && n < p.min) return Conversion.Error("must be ≥ ${p.min}")
                if (p.max != null && n > p.max) return Conversion.Error("must be ≤ ${p.max}")
                Conversion.Ok(n)
            }
            ParamType.BOOLEAN -> when (raw.lowercase()) {
                "true", "yes", "on", "1" -> Conversion.Ok(true)
                "false", "no", "off", "0" -> Conversion.Ok(false)
                else -> Conversion.Error("not a boolean")
            }
            ParamType.ENUM -> {
                val match = p.enumValues.firstOrNull { it.equals(raw, ignoreCase = true) }
                    ?: return Conversion.Error("must be one of ${p.enumValues.joinToString()}")
                Conversion.Ok(match)
            }
            ParamType.DATETIME -> try {
                Conversion.Ok(LocalDateTime.parse(raw))
            } catch (e: DateTimeParseException) {
                Conversion.Error("not an ISO-8601 local date-time (e.g. 2026-01-31T09:30)")
            }
            ParamType.PATH -> when (val r = PathPolicy.normalizeRelative(raw)) {
                is PathPolicy.Result.Ok -> Conversion.Ok(r.path)
                is PathPolicy.Result.Rejected -> Conversion.Error(r.reason)
            }
            ParamType.FILE_NAME -> when (val r = PathPolicy.validateFileName(raw)) {
                is PathPolicy.Result.Ok -> Conversion.Ok(r.path)
                is PathPolicy.Result.Rejected -> Conversion.Error(r.reason)
            }
        }
    }
}
