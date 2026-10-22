package ru.campus.equipment.postgres

import ru.campus.equipment.booking.*
import ru.campus.equipment.common.*
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class PgBookings(private val db: PgDatabase) : BookingRepository {
    override fun find(id: UUID, lock: Boolean): Booking? = db.connection()
        .query("SELECT * FROM bookings WHERE id = ?" + if (lock) " FOR UPDATE" else "", listOf(id), ::booking).singleOrNull()

    override fun list(userId: UUID?, status: BookingStatus?, overdueAt: Instant?, page: Page): List<Booking> {
        val conditions = mutableListOf("TRUE")
        val args = mutableListOf<Any?>()
        if (userId != null) { conditions += "user_id = ?"; args += userId }
        if (status != null) { conditions += "status = ?"; args += status }
        if (overdueAt != null) { conditions += "status = 'ISSUED' AND ends_at <= ?"; args += overdueAt }
        args += page.limit
        args += page.offset
        return db.connection().query("SELECT * FROM bookings WHERE ${conditions.joinToString(" AND ")} ORDER BY created_at DESC, id LIMIT ? OFFSET ?", args, ::booking)
    }

    override fun conflicts(equipmentId: UUID, period: Period, excludingId: UUID?): Boolean {
        val args = mutableListOf<Any?>(equipmentId, period.end, period.start)
        val exclusion = if (excludingId == null) "" else " AND id <> ?".also { args += excludingId }
        return db.connection().query(
            "SELECT EXISTS(SELECT 1 FROM bookings WHERE equipment_id = ? AND status IN ('APPROVED', 'ISSUED') AND starts_at < ? AND ends_at > ?$exclusion)",
            args,
        ) { it.getBoolean(1) }.single()
    }

    override fun hasIssued(equipmentId: UUID): Boolean = db.connection()
        .query("SELECT EXISTS(SELECT 1 FROM bookings WHERE equipment_id = ? AND status = 'ISSUED')", listOf(equipmentId)) { it.getBoolean(1) }.single()

    override fun approvedForEquipment(equipmentId: UUID): List<Booking> = db.connection()
        .query("SELECT * FROM bookings WHERE equipment_id = ? AND status = 'APPROVED' ORDER BY id FOR UPDATE", listOf(equipmentId), ::booking)

    override fun insert(booking: Booking) {
        db.connection().update("INSERT INTO bookings(id, user_id, equipment_id, starts_at, ends_at, purpose, created_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            booking.id, booking.userId, booking.equipmentId, booking.period.start, booking.period.end, booking.purpose, booking.createdAt, booking.status)
    }

    override fun update(booking: Booking) {
        check(db.connection().update("""
            UPDATE bookings SET status = ?, reason = ?, issued_at = ?, issued_by = ?, issue_condition = ?,
                returned_at = ?, returned_by = ?, return_condition = ? WHERE id = ?
        """.trimIndent(), booking.status, booking.reason, booking.issuedAt, booking.issuedBy, booking.issueCondition,
            booking.returnedAt, booking.returnedBy, booking.returnCondition, booking.id) == 1)
    }

    override fun addEvent(event: BookingEvent) {
        db.connection().update("INSERT INTO booking_events(id, booking_id, actor_id, status, occurred_at, note) VALUES (?, ?, ?, ?, ?, ?)",
            event.id, event.bookingId, event.actorId, event.status, event.at, event.note)
    }

    override fun events(bookingId: UUID): List<BookingEvent> = db.connection()
        .query("SELECT * FROM booking_events WHERE booking_id = ? ORDER BY sequence", listOf(bookingId)) {
            BookingEvent(it.getObject("id", UUID::class.java), it.getObject("booking_id", UUID::class.java),
                it.getObject("actor_id", UUID::class.java), BookingStatus.valueOf(it.getString("status")),
                it.getTimestamp("occurred_at").toInstant(), it.getString("note"))
        }

    private fun booking(rs: ResultSet) = Booking(
        id = rs.getObject("id", UUID::class.java), userId = rs.getObject("user_id", UUID::class.java),
        equipmentId = rs.getObject("equipment_id", UUID::class.java),
        period = Period(rs.getTimestamp("starts_at").toInstant(), rs.getTimestamp("ends_at").toInstant()),
        purpose = rs.getString("purpose"), createdAt = rs.getTimestamp("created_at").toInstant(),
        status = BookingStatus.valueOf(rs.getString("status")), reason = rs.getString("reason"),
        issuedAt = rs.getTimestamp("issued_at")?.toInstant(), issuedBy = rs.getObject("issued_by", UUID::class.java),
        issueCondition = rs.getString("issue_condition"), returnedAt = rs.getTimestamp("returned_at")?.toInstant(),
        returnedBy = rs.getObject("returned_by", UUID::class.java), returnCondition = rs.getString("return_condition"),
    )
}
