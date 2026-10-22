package ru.campus.equipment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.*
import org.junit.Test
import ru.campus.equipment.booking.*
import ru.campus.equipment.common.*
import ru.campus.equipment.common.Period
import ru.campus.equipment.http.HttpApi
import ru.campus.equipment.identity.*
import ru.campus.equipment.inventory.*
import ru.campus.equipment.postgres.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.nio.file.Files
import java.nio.file.Path
import java.time.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ServerIntegrationTest {
    companion object {
        private lateinit var postgres: EmbeddedPostgres
        private lateinit var pool: HikariDataSource
        private val mapper = ObjectMapper()
        private val initialTime = Instant.parse("2030-01-01T09:00:00Z")
        private const val adminKey = "integration-test-admin-key-not-a-production-secret"

        @JvmStatic @BeforeClass fun startPostgres() {
            postgres = EmbeddedPostgres.builder().setPort(0).start()
            pool = HikariDataSource(HikariConfig().apply {
                dataSource = postgres.postgresDatabase
                maximumPoolSize = 8
            })
            PgDatabase(pool).migrate()
        }

        @JvmStatic @AfterClass fun stopPostgres() {
            if (::pool.isInitialized) pool.close()
            if (::postgres.isInitialized) postgres.close()
        }
    }

    private class MutableClock(var time: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = time
    }

    private lateinit var clock: MutableClock
    private lateinit var database: PgDatabase
    private lateinit var identities: IdentityService
    private lateinit var inventory: InventoryService
    private lateinit var repository: PgBookings
    private lateinit var equipmentRepository: PgEquipment
    private lateinit var bookings: BookingService
    private lateinit var admin: User
    private lateinit var student: CreatedUser
    private lateinit var staff: CreatedUser
    private var api: HttpApi? = null

    @Before fun prepare() {
        pool.connection.use { it.createStatement().use { statement ->
            statement.execute("TRUNCATE booking_events, bookings, equipment, app_users RESTART IDENTITY")
        } }
        clock = MutableClock(initialTime)
        database = PgDatabase(pool)
        identities = IdentityService(database, PgUsers(database))
        identities.bootstrap("Admin", "admin@test.local", adminKey)
        admin = identities.authenticate(adminKey)
        student = identities.create(admin, "Student", "student@test.local", Role.USER)
        staff = identities.create(admin, "Staff", "staff@test.local", Role.STAFF)
        equipmentRepository = PgEquipment(database)
        repository = PgBookings(database)
        inventory = InventoryService(database, equipmentRepository)
        bookings = BookingService(database, repository, equipmentRepository, clock)
    }

    @After fun cleanup() { api?.close() }

    private fun equipment(): Equipment = inventory.create(admin, UUID.randomUUID().toString(), "Laptop", "LAPTOP", "Room 101", "16 GB RAM")
    private fun period(startOffset: Long = 3600, endOffset: Long = 7200) = Period(initialTime.plusSeconds(startOffset), initialTime.plusSeconds(endOffset))
    private fun book(item: Equipment, period: Period = period()) = bookings.create(student.user, item.id, period, "Laboratory work")
    private fun expect(code: String, action: () -> Unit) {
        assertEquals(code, assertFailsWith<BusinessException> { action() }.code)
    }

    private fun api(): HttpApi = api ?: HttpApi(InetSocketAddress("127.0.0.1", 0), identities, inventory, bookings,
        database::healthy, clock, 4).also { it.start(); api = it }

    private data class Result(val status: Int, val body: JsonNode, val headers: java.net.http.HttpHeaders)
    private fun request(method: String, path: String, key: String? = adminKey, body: Any? = null, raw: String? = null,
                        contentType: String = "application/json"): Result {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${api().port}$path")).timeout(Duration.ofSeconds(20))
        if (key != null) builder.header("Authorization", "Bearer $key")
        val payload = raw ?: body?.let(mapper::writeValueAsString)
        if (payload != null) builder.header("Content-Type", contentType)
        val response = HttpClient.newHttpClient().send(builder.method(method,
            payload?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString())
        return Result(response.statusCode(), mapper.readTree(response.body()), response.headers())
    }

    @Test fun `REST full booking lifecycle persists state and ordered history`() {
        val item = request("POST", "/api/equipment", staff.apiKey, mapOf(
            "inventoryNumber" to "NB-001", "name" to "Laptop", "category" to "LAPTOP", "location" to "Room 101"))
        assertEquals(201, item.status)
        assertTrue(item.headers.firstValue("Location").isPresent)
        val booking = request("POST", "/api/bookings", student.apiKey, mapOf(
            "equipmentId" to item.body["id"].asText(), "start" to period().start.toString(), "end" to period().end.toString(), "purpose" to "Study"))
        assertEquals(201, booking.status)
        val id = booking.body["id"].asText()
        assertEquals(200, request("POST", "/api/bookings/$id/approve", staff.apiKey).status)
        clock.time = period().start
        val issue = request("POST", "/api/bookings/$id/issue", staff.apiKey, mapOf("condition" to "Good"))
        assertEquals(200, issue.status)
        assertEquals("ISSUED", issue.body["status"].asText())
        val returned = request("POST", "/api/bookings/$id/return", staff.apiKey, mapOf("condition" to "Good", "damaged" to false))
        assertEquals(200, returned.status)
        assertEquals("RETURNED", returned.body["status"].asText())
        assertEquals(listOf("PENDING", "APPROVED", "ISSUED", "RETURNED"),
            request("GET", "/api/bookings/$id/events", student.apiKey).body["items"].map { it["status"].asText() })
        api?.close(); api = null
        database = PgDatabase(pool)
        bookings = BookingService(database, PgBookings(database), PgEquipment(database), clock)
        assertEquals("RETURNED", request("GET", "/api/bookings/$id", student.apiKey).body["status"].asText())
    }

    @Test fun `simultaneous approvals reserve equipment exactly once`() {
        val item = equipment()
        val first = book(item)
        val second = book(item)
        val outcomes = race {
            val id = if (it == 0) first.id else second.id
            runCatching { bookings.approve(staff.user, id) }
        }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals("BOOKING_CONFLICT", (outcomes.single { it.isFailure }.exceptionOrNull() as BusinessException).code)
        assertEquals(1, bookings.list(admin, BookingStatus.APPROVED, false, Page()).size)
        assertEquals(1, bookings.list(admin, BookingStatus.PENDING, false, Page()).size)
        assertEquals(3, bookings.history(admin, first.id).size + bookings.history(admin, second.id).size)
    }

    @Test fun `simultaneous issue of one booking has one winner`() {
        val booking = book(equipment())
        bookings.approve(admin, booking.id)
        clock.time = booking.period.start
        val outcomes = race { runCatching { bookings.issue(staff.user, booking.id, "Good") } }
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals("INVALID_TRANSITION", (outcomes.single { it.isFailure }.exceptionOrNull() as BusinessException).code)
    }

    private fun <T> race(action: (Int) -> T): List<T> {
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        try {
            val jobs = (0..1).map { index -> executor.submit(Callable { ready.countDown(); start.await(); action(index) }) }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            return jobs.map { it.get(20, TimeUnit.SECONDS) }
        } finally { start.countDown(); executor.shutdownNow() }
    }

    @Test fun `adjacent reservations are allowed and overlapping create is rejected`() {
        val item = equipment()
        val first = book(item)
        bookings.approve(admin, first.id)
        assertFalse(bookings.available(item.id, first.period))
        expect("BOOKING_CONFLICT") { book(item, period(5400, 9000)) }
        val next = book(item, period(7200, 10800))
        assertEquals(BookingStatus.APPROVED, bookings.approve(admin, next.id).status)
    }

    @Test fun `overdue possession prevents next issue until actual return`() {
        val item = equipment()
        val first = book(item)
        val next = book(item, period(7200, 10800))
        bookings.approve(admin, first.id)
        bookings.approve(admin, next.id)
        clock.time = first.period.start
        bookings.issue(admin, first.id, "Good")
        clock.time = next.period.start
        assertEquals(listOf(first.id), bookings.list(admin, null, true, Page()).map { it.id })
        expect("EQUIPMENT_NOT_RETURNED") { bookings.issue(admin, next.id, "Good") }
        bookings.returnEquipment(admin, first.id, "Good", false)
        assertEquals(BookingStatus.ISSUED, bookings.issue(admin, next.id, "Good").status)
    }

    @Test fun `damaged return closes loan cancels future reservations and marks maintenance`() {
        val item = equipment()
        val first = book(item)
        val next = book(item, period(7200, 10800))
        bookings.approve(admin, first.id)
        bookings.approve(admin, next.id)
        clock.time = first.period.start
        bookings.issue(admin, first.id, "Good")
        bookings.returnEquipment(admin, first.id, "Broken screen", true)
        assertEquals(BookingStatus.RETURNED, bookings.get(admin, first.id).status)
        assertEquals(BookingStatus.CANCELLED, bookings.get(student.user, next.id).status)
        assertEquals(EquipmentStatus.MAINTENANCE, inventory.get(item.id).status)
        expect("EQUIPMENT_UNAVAILABLE") { book(item, period(10800, 14400)) }
        bookings.changeEquipmentStatus(admin, item.id, EquipmentStatus.ACTIVE, "Repaired")
        assertEquals(BookingStatus.PENDING, book(item, period(10800, 14400)).status)
    }

    @Test fun `failure during return rolls back equipment booking cancellations and audit`() {
        val item = equipment()
        val first = book(item)
        val next = book(item, period(7200, 10800))
        bookings.approve(admin, first.id)
        bookings.approve(admin, next.id)
        clock.time = first.period.start
        bookings.issue(admin, first.id, "Good")
        val failingRepository = object : BookingRepository by repository {
            override fun addEvent(event: BookingEvent) {
                if (event.status == BookingStatus.RETURNED) error("Simulated audit failure")
                repository.addEvent(event)
            }
        }
        val faulty = BookingService(database, failingRepository, equipmentRepository, clock)
        assertFailsWith<IllegalStateException> { faulty.returnEquipment(admin, first.id, "Broken", true) }
        assertEquals(BookingStatus.ISSUED, bookings.get(admin, first.id).status)
        assertEquals(BookingStatus.APPROVED, bookings.get(admin, next.id).status)
        assertEquals(EquipmentStatus.ACTIVE, inventory.get(item.id).status)
        assertEquals(2, bookings.history(admin, next.id).size)
    }

    @Test fun `database itself rejects overlapping reservations bypassing service`() {
        val item = equipment()
        val first = book(item)
        val second = book(item)
        bookings.approve(admin, first.id)
        val error = assertFailsWith<SQLException> {
            pool.connection.use { connection -> connection.prepareStatement("UPDATE bookings SET status = 'APPROVED' WHERE id = ?").use {
                it.setObject(1, second.id); it.executeUpdate()
            } }
        }
        assertEquals("23P01", error.sqlState)
    }

    @Test fun `cancel releases reservation and repeated terminal transitions fail`() {
        val item = equipment()
        val first = book(item)
        bookings.approve(admin, first.id)
        bookings.cancel(student.user, first.id, "Plans changed")
        assertTrue(bookings.available(item.id, first.period))
        expect("INVALID_TRANSITION") { bookings.approve(admin, first.id) }
        expect("INVALID_TRANSITION") { bookings.cancel(student.user, first.id, "Again") }
        val replacement = book(item)
        bookings.reject(staff.user, replacement.id, "Not for classes")
        assertEquals(BookingStatus.REJECTED, bookings.get(student.user, replacement.id).status)
    }

    @Test fun `issue requires confirmation and current booking window`() {
        val booking = book(equipment())
        expect("INVALID_TRANSITION") { bookings.issue(admin, booking.id, "Good") }
        bookings.approve(admin, booking.id)
        expect("OUTSIDE_BOOKING_PERIOD") { bookings.issue(admin, booking.id, "Good") }
        clock.time = booking.period.end
        expect("OUTSIDE_BOOKING_PERIOD") { bookings.issue(admin, booking.id, "Good") }
        expect("INVALID_TRANSITION") { bookings.returnEquipment(admin, booking.id, "Good", false) }
    }

    @Test fun `expired pending requests cannot be approved`() {
        val booking = book(equipment())
        clock.time = booking.period.start
        expect("BOOKING_EXPIRED") { bookings.approve(admin, booking.id) }
    }

    @Test fun `issued equipment can be retired but must still be returned`() {
        val item = equipment()
        val booking = book(item)
        bookings.approve(admin, booking.id)
        clock.time = booking.period.start
        bookings.issue(admin, booking.id, "Good")
        bookings.changeEquipmentStatus(admin, item.id, EquipmentStatus.RETIRED, "Decommissioned")
        bookings.returnEquipment(admin, booking.id, "Returned damaged", true)
        assertEquals(EquipmentStatus.RETIRED, inventory.get(item.id).status)
        expect("EQUIPMENT_RETIRED") { bookings.changeEquipmentStatus(admin, item.id, EquipmentStatus.ACTIVE, "Restore") }
    }

    @Test fun `users cannot act as staff or access other users bookings`() {
        val other = identities.create(admin, "Other", "other@test.local", Role.USER)
        val booking = book(equipment())
        expect("FORBIDDEN") { bookings.approve(student.user, booking.id) }
        expect("FORBIDDEN") { bookings.get(other.user, booking.id) }
        expect("FORBIDDEN") { bookings.history(other.user, booking.id) }
        expect("FORBIDDEN") { bookings.cancel(other.user, booking.id, "No") }
        expect("FORBIDDEN") { identities.create(staff.user, "Admin", "evil@test.local", Role.ADMIN) }
        expect("FORBIDDEN") { inventory.create(student.user, "NB-X", "Test", "LAPTOP", "101", "") }
        assertTrue(bookings.list(other.user, null, false, Page()).isEmpty())
    }

    @Test fun `REST rejects malformed inputs and reports appropriate statuses`() {
        assertEquals(200, request("GET", "/health", key = null).status)
        assertEquals(401, request("GET", "/api/me", key = null).status)
        assertEquals(401, request("GET", "/api/me", key = "wrong").status)
        assertEquals(403, request("GET", "/api/users", student.apiKey).status)
        assertEquals(404, request("GET", "/api/equipment/${UUID.randomUUID()}").status)
        assertEquals(400, request("GET", "/api/equipment/not-a-uuid").status)
        val method = request("DELETE", "/api/equipment")
        assertEquals(405, method.status)
        assertTrue(method.headers.firstValue("Allow").orElse("").contains("GET"))
        assertEquals(400, request("GET", "/api/equipment?limit=0").status)
        assertEquals(400, request("GET", "/api/equipment?limit=20&limit=30").status)
        assertEquals(400, request("GET", "/api/bookings?overdue=yes").status)
        assertEquals(400, request("POST", "/api/bookings", raw = "{").status)
        assertEquals(400, request("POST", "/api/bookings", raw = "{} {}").status)
        assertEquals(400, request("POST", "/api/bookings", raw = "{\"purpose\":\"one\",\"purpose\":\"two\"}").status)
        assertEquals(400, request("POST", "/api/bookings", body = mapOf("unexpected" to true)).status)
        assertEquals(415, request("POST", "/api/bookings", raw = "{}", contentType = "text/plain").status)
        val oversized = request("POST", "/api/bookings", raw = "x".repeat(65537))
        assertEquals(413, oversized.status)
        assertTrue(oversized.body["error"]["requestId"].asText().isNotBlank())
    }

    @Test fun `REST validates dates types and denies forged owner`() {
        val item = equipment()
        val valid = mapOf("equipmentId" to item.id.toString(), "start" to period().start.toString(), "end" to period().end.toString(), "purpose" to "Study")
        assertEquals(400, request("POST", "/api/bookings", student.apiKey, valid + ("userId" to admin.id.toString())).status)
        assertEquals(400, request("POST", "/api/bookings", student.apiKey, valid + ("start" to "tomorrow")).status)
        assertEquals(400, request("POST", "/api/bookings", student.apiKey, valid + ("start" to initialTime.toString())).status)
        assertEquals(400, request("POST", "/api/bookings", student.apiKey, valid + ("purpose" to 123)).status)
        assertEquals(400, request("POST", "/api/bookings", student.apiKey, valid + ("purpose" to " ")).status)
        assertEquals(400, request("POST", "/api/bookings", student.apiKey, valid + ("end" to period().start.toString())).status)
        val created = request("POST", "/api/bookings", student.apiKey, valid)
        assertEquals(201, created.status)
        val id = created.body["id"].asText()
        assertEquals(403, request("POST", "/api/bookings/$id/approve", student.apiKey).status)
        assertEquals(409, request("POST", "/api/bookings/$id/issue", staff.apiKey, mapOf("condition" to "Good")).status)
    }

    @Test fun `user provisioning hashes keys and handles normalized email duplicates`() {
        val response = request("POST", "/api/users", body = mapOf("name" to "New User", "email" to "NEW@test.local", "role" to "USER"))
        assertEquals(201, response.status)
        val key = response.body["apiKey"].asText()
        assertEquals("new@test.local", request("GET", "/api/me", key).body["email"].asText())
        assertFalse(request("GET", "/api/users").body.toString().contains(key))
        assertEquals(409, request("POST", "/api/users", body = mapOf("name" to "Duplicate", "email" to "new@test.local", "role" to "USER")).status)
        pool.connection.use { c -> c.prepareStatement("SELECT api_key_hash FROM app_users WHERE email = 'new@test.local'").use {
            it.executeQuery().use { rs -> assertTrue(rs.next()); assertEquals(IdentityService.hash(key), rs.getString(1)); assertNotEquals(key, rs.getString(1)) }
        } }
    }

    @Test fun `bootstrap and migrations are repeatable without erasing data`() {
        val item = equipment()
        database.migrate()
        identities.bootstrap("Admin", "admin@test.local", adminKey)
        assertEquals(item, inventory.get(item.id))
        assertEquals(3, identities.list(admin, Page()).size)
        assertFailsWith<IllegalStateException> { identities.bootstrap("Admin", "admin@test.local", "different-admin-key-with-at-least-32-characters") }
    }

    @Test fun `inventory uniqueness pagination and maintenance are enforced`() {
        val item = equipment()
        expect("DUPLICATE_RESOURCE") { inventory.create(admin, item.inventoryNumber, "Duplicate", "LAPTOP", "101", "") }
        equipment()
        assertEquals(1, inventory.list(Page(limit = 1), "LAPTOP").size)
        assertEquals(1, inventory.list(Page(limit = 1, offset = 1), "LAPTOP").size)
        val pending = book(item)
        bookings.changeEquipmentStatus(admin, item.id, EquipmentStatus.MAINTENANCE, "Repair")
        expect("EQUIPMENT_UNAVAILABLE") { bookings.approve(admin, pending.id) }
    }

    @Test fun `packaged application starts from environment and survives process restart`() {
        val port = ServerSocket(0).use { it.localPort }
        val log = Files.createTempFile("campus-startup-", ".log")
        val installation = Path.of(System.getProperty("campus.installDir"))
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java")
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()

        fun start(): Process {
            val builder = ProcessBuilder(java.toString(), "-cp", installation.resolve("lib").toString() + "/*", "ru.campus.equipment.ApplicationKt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
            builder.environment().putAll(mapOf(
                "DATABASE_URL" to "jdbc:postgresql://127.0.0.1:${postgres.port}/postgres",
                "DATABASE_USER" to "postgres", "DATABASE_PASSWORD" to "test-only",
                "BOOTSTRAP_ADMIN_EMAIL" to "admin@test.local", "BOOTSTRAP_ADMIN_KEY" to adminKey,
                "HTTP_HOST" to "127.0.0.1", "HTTP_PORT" to port.toString(), "LOG_LEVEL" to "info",
            ))
            val process = builder.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline && process.isAlive) {
                val status = runCatching {
                    client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/health")).timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()
                }.getOrNull()
                if (status == 200) return process
                Thread.sleep(100)
            }
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            fail("Packaged server did not start: ${Files.readString(log)}")
        }

        fun stop(process: Process) {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }

        try {
            val first = start()
            val id = try {
                val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/equipment"))
                    .header("Authorization", "Bearer $adminKey").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"inventoryNumber":"RESTART-TEST","name":"Laptop","category":"LAPTOP","location":"101"}"""))
                    .build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(201, response.statusCode())
                mapper.readTree(response.body())["id"].asText()
            } finally { stop(first) }
            val second = start()
            try {
                val response = client.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/equipment/$id"))
                    .header("Authorization", "Bearer $adminKey").GET().build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode())
                assertEquals("RESTART-TEST", mapper.readTree(response.body())["inventoryNumber"].asText())
            } finally { stop(second) }
            assertFalse(Files.readString(log).contains(adminKey), "Credentials must not appear in server logs")
        } finally { log.toFile().deleteOnExit() }
    }
}
