package ru.campus.equipment.common

import java.time.Instant
import java.time.temporal.ChronoUnit

enum class ErrorKind { VALIDATION, UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, UNAVAILABLE }

class BusinessException(val kind: ErrorKind, val code: String, message: String) : RuntimeException(message)

fun invalid(message: String): Nothing = throw BusinessException(ErrorKind.VALIDATION, "VALIDATION_ERROR", message)
fun missing(resource: String): Nothing = throw BusinessException(ErrorKind.NOT_FOUND, "NOT_FOUND", "$resource not found")
fun conflict(code: String, message: String): Nothing = throw BusinessException(ErrorKind.CONFLICT, code, message)
fun forbidden(): Nothing = throw BusinessException(ErrorKind.FORBIDDEN, "FORBIDDEN", "Operation is not allowed")

fun requiredText(value: String, field: String, max: Int): String = value.trim().also {
    if (it.isEmpty() || it.length > max || it.any { c -> c.isISOControl() }) {
        invalid("$field must contain 1..$max characters without control characters")
    }
}

interface Transactions {
    fun <T> execute(block: () -> T): T
}

data class Page(val limit: Int = 20, val offset: Int = 0) {
    init {
        if (limit !in 1..100 || offset !in 0..1_000_000) invalid("Invalid pagination: limit 1..100, offset 0..1000000")
    }
}

data class Period(val start: Instant, val end: Instant) {
    init {
        if (!start.isBefore(end)) invalid("start must be before end")
        if (start != start.truncatedTo(ChronoUnit.MICROS) || end != end.truncatedTo(ChronoUnit.MICROS)) {
            invalid("Dates support at most six fractional second digits")
        }
        if (start.isBefore(Instant.parse("2000-01-01T00:00:00Z")) ||
            end.isAfter(Instant.parse("2100-01-01T00:00:00Z"))) invalid("Dates must be between 2000 and 2100")
    }

    fun overlaps(other: Period): Boolean = start < other.end && other.start < end
    fun contains(instant: Instant): Boolean = instant >= start && instant < end
}
