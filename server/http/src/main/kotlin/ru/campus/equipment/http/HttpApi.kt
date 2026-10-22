package ru.campus.equipment.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.slf4j.LoggerFactory
import ru.campus.equipment.booking.*
import ru.campus.equipment.common.*
import ru.campus.equipment.identity.*
import ru.campus.equipment.inventory.*
import java.net.InetSocketAddress
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpApi(
    address: InetSocketAddress,
    private val identity: IdentityService,
    private val inventory: InventoryService,
    private val bookings: BookingService,
    private val healthy: () -> Boolean,
    private val clock: Clock,
    threads: Int = 16,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(HttpApi::class.java)
    private val executor = Executors.newFixedThreadPool(threads)
    private val server = HttpServer.create(address, 128)
    val port: Int get() = server.address.port
    private data class Response(val status: Int, val body: Any, val location: String? = null)
    private data class Route(val method: String, val path: String, val handler: (Request, User) -> Response) {
        fun match(path: String): Map<String, String>? {
            val expected = this.path.split('/')
            val actual = path.split('/')
            if (actual.size != expected.size) return null
            val values = mutableMapOf<String, String>()
            expected.zip(actual).forEach { (pattern, part) ->
                if (pattern == "{id}") values["id"] = part else if (pattern != part) return null
            }
            return values
        }
    }

    private val routes = listOf(
        Route("GET", "/api/me") { request, actor -> request.query(); Response(200, actor.dto()) },
        Route("POST", "/api/users") { request, actor ->
            request.query()
            val body = request.body("name", "email", "role")
            val created = identity.create(actor, body.text("name"), body.text("email"), enumValue(body.text("role"), "role"))
            Response(201, mapOf("user" to created.user.dto(), "apiKey" to created.apiKey))
        },
        Route("GET", "/api/users") { request, actor ->
            val query = request.query("limit", "offset")
            Response(200, mapOf("items" to identity.list(actor, query.page()).map { it.dto() }))
        },
        Route("POST", "/api/equipment") { request, actor ->
            request.query()
            val body = request.body("inventoryNumber", "name", "category", "location", "description")
            val item = inventory.create(actor, body.text("inventoryNumber"), body.text("name"), body.text("category"), body.text("location"), body.get("description")?.let { body.text("description") } ?: "")
            Response(201, item.dto(), "/api/equipment/${item.id}")
        },
        Route("GET", "/api/equipment") { request, _ ->
            val query = request.query("limit", "offset", "category")
            Response(200, mapOf("items" to inventory.list(query.page(), query["category"]).map { it.dto() }))
        },
        Route("GET", "/api/equipment/{id}") { request, _ -> request.query(); Response(200, inventory.get(request.id()).dto()) },
        Route("GET", "/api/equipment/{id}/availability") { request, _ ->
            val query = request.query("start", "end")
            val period = Period(instant(query["start"] ?: invalid("start is required"), "start"), instant(query["end"] ?: invalid("end is required"), "end"))
            Response(200, mapOf("available" to bookings.available(request.id(), period)))
        },
        Route("PATCH", "/api/equipment/{id}/status") { request, actor ->
            request.query()
            val body = request.body("status", "reason")
            Response(200, bookings.changeEquipmentStatus(actor, request.id(), enumValue(body.text("status"), "status"), body.text("reason")).dto())
        },
        Route("POST", "/api/bookings") { request, actor ->
            request.query()
            val body = request.body("equipmentId", "start", "end", "purpose")
            val booking = bookings.create(actor, uuid(body.text("equipmentId"), "equipmentId"),
                Period(instant(body.text("start"), "start"), instant(body.text("end"), "end")), body.text("purpose"))
            Response(201, booking.dto(clock.instant()), "/api/bookings/${booking.id}")
        },
        Route("GET", "/api/bookings") { request, actor ->
            val query = request.query("status", "overdue", "limit", "offset")
            val overdue = query["overdue"]?.let { it.toBooleanStrictOrNull() ?: invalid("overdue must be true or false") } ?: false
            Response(200, mapOf("items" to bookings.list(actor, query["status"]?.let { enumValue(it, "status") }, overdue, query.page()).map { it.dto(clock.instant()) }))
        },
        Route("GET", "/api/bookings/{id}") { request, actor -> request.query(); Response(200, bookings.get(actor, request.id()).dto(clock.instant())) },
        Route("GET", "/api/bookings/{id}/events") { request, actor -> request.query(); Response(200, mapOf("items" to bookings.history(actor, request.id()).map { it.dto() })) },
        Route("POST", "/api/bookings/{id}/approve") { request, actor ->
            request.query(); request.body(emptyAllowed = true)
            Response(200, bookings.approve(actor, request.id()).dto(clock.instant()))
        },
        Route("POST", "/api/bookings/{id}/reject") { request, actor ->
            request.query()
            Response(200, bookings.reject(actor, request.id(), request.body("reason").text("reason")).dto(clock.instant()))
        },
        Route("POST", "/api/bookings/{id}/cancel") { request, actor ->
            request.query()
            Response(200, bookings.cancel(actor, request.id(), request.body("reason").text("reason")).dto(clock.instant()))
        },
        Route("POST", "/api/bookings/{id}/issue") { request, actor ->
            request.query()
            Response(200, bookings.issue(actor, request.id(), request.body("condition").text("condition")).dto(clock.instant()))
        },
        Route("POST", "/api/bookings/{id}/return") { request, actor ->
            request.query()
            val body = request.body("condition", "damaged")
            Response(200, bookings.returnEquipment(actor, request.id(), body.text("condition"), body.boolean("damaged")).dto(clock.instant()))
        },
    )

    init {
        require(threads in 1..128)
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange) }
    }

    fun start() { server.start() }

    private fun handle(exchange: HttpExchange) {
        val requestId = UUID.randomUUID().toString()
        val started = System.nanoTime()
        var status = 500
        var actorId: UUID? = null
        var routeName = "unmatched"
        try {
            val path = exchange.requestURI.path
            val response = if (path == "/health" || path == "/health/live") {
                routeName = path
                if (exchange.requestMethod != "GET") {
                    exchange.responseHeaders.set("Allow", "GET")
                    throw HttpFailure(405, "METHOD_NOT_ALLOWED", "Use GET")
                }
                val up = path == "/health/live" || healthy()
                Response(if (up) 200 else 503, mapOf("status" to if (up) "UP" else "DOWN"))
            } else {
                val matching = routes.mapNotNull { route -> route.match(path)?.let { route to it } }
                if (matching.isEmpty()) throw HttpFailure(404, "NOT_FOUND", "Route not found")
                val (route, parameters) = matching.firstOrNull { it.first.method == exchange.requestMethod } ?: run {
                    exchange.responseHeaders.set("Allow", matching.joinToString(", ") { it.first.method })
                    throw HttpFailure(405, "METHOD_NOT_ALLOWED", "Method is not allowed for this route")
                }
                routeName = route.path
                val headers = exchange.requestHeaders["Authorization"]
                val header = headers?.singleOrNull()
                val apiKey = header?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)
                val actor = identity.authenticate(apiKey)
                actorId = actor.id
                route.handler(Request(exchange, parameters), actor)
            }
            status = response.status
            response.location?.let { exchange.responseHeaders.set("Location", it) }
            send(exchange, status, response.body, requestId)
        } catch (failure: BusinessException) {
            status = when (failure.kind) {
                ErrorKind.VALIDATION -> 400
                ErrorKind.UNAUTHENTICATED -> 401
                ErrorKind.FORBIDDEN -> 403
                ErrorKind.NOT_FOUND -> 404
                ErrorKind.CONFLICT -> 409
                ErrorKind.UNAVAILABLE -> 503
            }
            if (status == 503) logger.error("requestId={} database operation failed", requestId, failure)
            if (status == 401) exchange.responseHeaders.set("WWW-Authenticate", "Bearer")
            sendError(exchange, status, failure.code, failure.message ?: "Request failed", requestId)
        } catch (failure: HttpFailure) {
            status = failure.status
            sendError(exchange, status, failure.code, failure.message ?: "Request failed", requestId)
        } catch (failure: Exception) {
            logger.error("requestId={} unexpected request failure", requestId, failure)
            sendError(exchange, 500, "INTERNAL_ERROR", "Unexpected server error", requestId)
        } finally {
            logger.info("requestId={} method={} route={} actor={} status={} durationMs={}", requestId,
                exchange.requestMethod, routeName, actorId ?: "anonymous", status, (System.nanoTime() - started) / 1_000_000)
            exchange.close()
        }
    }

    private fun sendError(exchange: HttpExchange, status: Int, code: String, message: String, requestId: String) {
        try { send(exchange, status, mapOf("error" to mapOf("code" to code, "message" to message, "requestId" to requestId)), requestId) }
        catch (_: java.io.IOException) { }
    }

    private fun send(exchange: HttpExchange, status: Int, body: Any, requestId: String) {
        val bytes = json.writeValueAsBytes(body)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("X-Request-Id", requestId)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(1)
        executor.shutdown()
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
    }
}
