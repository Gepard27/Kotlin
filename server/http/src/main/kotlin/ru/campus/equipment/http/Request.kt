package ru.campus.equipment.http

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpExchange
import ru.campus.equipment.common.*
import java.net.URLDecoder
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

internal class HttpFailure(val status: Int, val code: String, message: String) : RuntimeException(message)

internal class Request(val exchange: HttpExchange, private val parameters: Map<String, String>) {
    fun id(): UUID = uuid(parameters.getValue("id"), "id")

    fun body(vararg allowed: String, emptyAllowed: Boolean = false): JsonNode {
        val bytes = exchange.requestBody.readNBytes(65537)
        if (bytes.size > 65536) throw HttpFailure(413, "BODY_TOO_LARGE", "Body must not exceed 64 KiB")
        if (bytes.isEmpty() && emptyAllowed) return json.createObjectNode()
        val contentType = exchange.requestHeaders.getFirst("Content-Type")?.substringBefore(';')?.trim()
        if (!contentType.equals("application/json", ignoreCase = true)) throw HttpFailure(415, "UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json")
        val body = try { json.readTree(bytes) } catch (_: JsonProcessingException) { invalid("Malformed JSON body") }
        if (body == null || !body.isObject) invalid("Body must be a JSON object")
        val unknown = body.fieldNames().asSequence().filter { it !in allowed }.toList()
        if (unknown.isNotEmpty()) invalid("Unknown JSON fields: ${unknown.joinToString()}")
        return body
    }

    fun query(vararg allowed: String): Map<String, String> {
        val raw = exchange.requestURI.rawQuery ?: return emptyMap()
        if (raw.length > 2048) invalid("Query is too long")
        val result = mutableMapOf<String, String>()
        for (part in raw.split('&')) {
            val pair = part.split('=', limit = 2)
            val key = decode(pair[0])
            if (key !in allowed || result.containsKey(key)) invalid("Unknown or repeated query parameter")
            result[key] = decode(pair.getOrElse(1) { "" })
        }
        return result
    }

    private fun decode(value: String): String = try { URLDecoder.decode(value, Charsets.UTF_8) } catch (_: IllegalArgumentException) { invalid("Invalid URL encoding") }
}

internal fun uuid(value: String, field: String): UUID {
    if (!Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value)) invalid("$field must be a UUID")
    return UUID.fromString(value)
}

internal fun instant(value: String, field: String): Instant = try { Instant.parse(value) } catch (_: DateTimeParseException) { invalid("$field must be an ISO-8601 timestamp with timezone, for example 2026-10-22T10:00:00Z") }

internal fun Map<String, String>.page(): Page = Page(
    get("limit")?.let { it.toIntOrNull() ?: invalid("limit must be an integer") } ?: 20,
    get("offset")?.let { it.toIntOrNull() ?: invalid("offset must be an integer") } ?: 0,
)
