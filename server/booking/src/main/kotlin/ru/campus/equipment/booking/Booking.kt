package ru.campus.equipment.booking

import ru.campus.equipment.common.*
import java.time.Instant
import java.util.UUID

enum class BookingStatus { PENDING, APPROVED, REJECTED, CANCELLED, ISSUED, RETURNED }

data class Booking(
    val id: UUID,
    val userId: UUID,
    val equipmentId: UUID,
    val period: Period,
    val purpose: String,
    val createdAt: Instant,
    val status: BookingStatus = BookingStatus.PENDING,
    val reason: String? = null,
    val issuedAt: Instant? = null,
    val issuedBy: UUID? = null,
    val issueCondition: String? = null,
    val returnedAt: Instant? = null,
    val returnedBy: UUID? = null,
    val returnCondition: String? = null,
) {
    fun requireStatus(vararg allowed: BookingStatus) {
        if (status !in allowed) conflict("INVALID_TRANSITION", "Cannot perform operation on a $status booking")
    }

    fun isOverdue(now: Instant): Boolean = status == BookingStatus.ISSUED && now >= period.end
}

data class BookingEvent(
    val id: UUID, val bookingId: UUID, val actorId: UUID,
    val status: BookingStatus, val at: Instant, val note: String?,
)

interface BookingRepository {
    fun find(id: UUID, lock: Boolean = false): Booking?
    fun list(userId: UUID?, status: BookingStatus?, overdueAt: Instant?, page: Page): List<Booking>
    fun conflicts(equipmentId: UUID, period: Period, excludingId: UUID? = null): Boolean
    fun hasIssued(equipmentId: UUID): Boolean
    fun approvedForEquipment(equipmentId: UUID): List<Booking>
    fun insert(booking: Booking)
    fun update(booking: Booking)
    fun addEvent(event: BookingEvent)
    fun events(bookingId: UUID): List<BookingEvent>
}
