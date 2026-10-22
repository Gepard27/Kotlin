package ru.campus.equipment.http

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ru.campus.equipment.booking.*
import ru.campus.equipment.common.*
import ru.campus.equipment.identity.User
import ru.campus.equipment.inventory.Equipment
import java.time.Instant

internal val json = ObjectMapper()
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

internal fun User.dto() = mapOf("id" to id.toString(), "name" to name, "email" to email, "role" to role.name)
internal fun Equipment.dto() = mapOf(
    "id" to id.toString(), "inventoryNumber" to inventoryNumber, "name" to name,
    "category" to category, "location" to location, "description" to description, "status" to status.name,
)
internal fun Booking.dto(now: Instant) = mapOf(
    "id" to id.toString(), "userId" to userId.toString(), "equipmentId" to equipmentId.toString(),
    "start" to period.start.toString(), "end" to period.end.toString(), "purpose" to purpose,
    "createdAt" to createdAt.toString(), "status" to status.name, "reason" to reason,
    "issuedAt" to issuedAt?.toString(), "issuedBy" to issuedBy?.toString(), "issueCondition" to issueCondition,
    "returnedAt" to returnedAt?.toString(), "returnedBy" to returnedBy?.toString(), "returnCondition" to returnCondition,
    "overdue" to isOverdue(now),
)
internal fun BookingEvent.dto() = mapOf(
    "id" to id.toString(), "bookingId" to bookingId.toString(), "actorId" to actorId.toString(),
    "status" to status.name, "at" to at.toString(), "note" to note,
)

internal fun JsonNode.text(field: String): String {
    val value = get(field)
    if (value == null || !value.isTextual) invalid("$field must be a string")
    return value.textValue()
}

internal fun JsonNode.boolean(field: String): Boolean {
    val value = get(field)
    if (value == null || !value.isBoolean) invalid("$field must be a boolean")
    return value.booleanValue()
}

internal inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
    enumValues<T>().firstOrNull { it.name == value } ?: invalid("$field must be one of ${enumValues<T>().joinToString { it.name }}")
