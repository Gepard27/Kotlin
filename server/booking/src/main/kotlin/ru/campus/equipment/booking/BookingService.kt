package ru.campus.equipment.booking

import ru.campus.equipment.common.*
import ru.campus.equipment.identity.Role
import ru.campus.equipment.identity.User
import ru.campus.equipment.inventory.*
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class BookingService(
    private val transactions: Transactions,
    private val bookings: BookingRepository,
    private val equipment: EquipmentRepository,
    private val clock: Clock,
) {
    fun create(actor: User, equipmentId: UUID, period: Period, purpose: String): Booking {
        val text = requiredText(purpose, "purpose", 500)
        return transactions.execute {
            val item = equipment.find(equipmentId, lock = true) ?: missing("Equipment")
            item.requireActive()
            val now = now()
            if (period.start <= now) invalid("Booking must start in the future")
            checkAvailability(equipmentId, period)
            val booking = Booking(UUID.randomUUID(), actor.id, equipmentId, period, text, now)
            bookings.insert(booking)
            event(booking, actor, now)
            booking
        }
    }

    fun get(actor: User, id: UUID): Booking = transactions.execute {
        (bookings.find(id) ?: missing("Booking")).also { requireAccess(actor, it) }
    }

    fun list(actor: User, status: BookingStatus?, overdue: Boolean, page: Page): List<Booking> = transactions.execute {
        bookings.list(if (actor.role == Role.USER) actor.id else null, status, if (overdue) now() else null, page)
    }

    fun history(actor: User, id: UUID): List<BookingEvent> = transactions.execute {
        requireAccess(actor, bookings.find(id) ?: missing("Booking"))
        bookings.events(id)
    }

    fun available(equipmentId: UUID, period: Period): Boolean = transactions.execute {
        val item = equipment.find(equipmentId) ?: missing("Equipment")
        item.status == EquipmentStatus.ACTIVE && !bookings.conflicts(equipmentId, period)
    }

    fun approve(actor: User, id: UUID): Booking {
        actor.requireStaff()
        return change(id, actor) { booking, item, now ->
            booking.requireStatus(BookingStatus.PENDING)
            item.requireActive()
            if (booking.period.start <= now) conflict("BOOKING_EXPIRED", "Cannot approve a booking whose start has passed")
            checkAvailability(item.id, booking.period, booking.id)
            booking.copy(status = BookingStatus.APPROVED)
        }
    }

    fun reject(actor: User, id: UUID, reason: String): Booking {
        actor.requireStaff()
        val note = requiredText(reason, "reason", 500)
        return change(id, actor) { booking, _, _ ->
            booking.requireStatus(BookingStatus.PENDING)
            booking.copy(status = BookingStatus.REJECTED, reason = note)
        }
    }

    fun cancel(actor: User, id: UUID, reason: String): Booking {
        val note = requiredText(reason, "reason", 500)
        return change(id, actor) { booking, _, _ ->
            requireAccess(actor, booking)
            booking.requireStatus(BookingStatus.PENDING, BookingStatus.APPROVED)
            booking.copy(status = BookingStatus.CANCELLED, reason = note)
        }
    }

    fun issue(actor: User, id: UUID, condition: String): Booking {
        actor.requireStaff()
        val text = requiredText(condition, "condition", 500)
        return change(id, actor) { booking, item, now ->
            booking.requireStatus(BookingStatus.APPROVED)
            item.requireActive()
            if (!booking.period.contains(now)) conflict("OUTSIDE_BOOKING_PERIOD", "Issue is allowed only within the reserved period")
            if (bookings.hasIssued(item.id)) conflict("EQUIPMENT_NOT_RETURNED", "Previous equipment loan has not been returned")
            booking.copy(status = BookingStatus.ISSUED, issuedAt = now, issuedBy = actor.id, issueCondition = text)
        }
    }

    fun returnEquipment(actor: User, id: UUID, condition: String, damaged: Boolean): Booking {
        actor.requireStaff()
        val text = requiredText(condition, "condition", 500)
        return change(id, actor) { booking, item, now ->
            booking.requireStatus(BookingStatus.ISSUED)
            if (damaged && item.status != EquipmentStatus.RETIRED) {
                equipment.updateStatus(item.id, EquipmentStatus.MAINTENANCE)
                cancelApproved(item.id, actor, "Equipment damaged on return: $text".take(500), now)
            }
            booking.copy(status = BookingStatus.RETURNED, returnedAt = now, returnedBy = actor.id, returnCondition = text)
        }
    }

    fun changeEquipmentStatus(actor: User, id: UUID, status: EquipmentStatus, reason: String): Equipment {
        actor.requireStaff()
        val note = requiredText(reason, "reason", 500)
        return transactions.execute {
            val item = equipment.find(id, lock = true) ?: missing("Equipment")
            if (item.status == EquipmentStatus.RETIRED) conflict("EQUIPMENT_RETIRED", "Retired equipment cannot change status")
            if (status != EquipmentStatus.ACTIVE) cancelApproved(id, actor, note, now())
            equipment.updateStatus(id, status)
            item.copy(status = status)
        }
    }

    private fun change(id: UUID, actor: User, action: (Booking, Equipment, Instant) -> Booking): Booking = transactions.execute {
        val initial = bookings.find(id) ?: missing("Booking")
        val item = equipment.find(initial.equipmentId, lock = true) ?: missing("Equipment")
        val booking = bookings.find(id, lock = true) ?: missing("Booking")
        val now = now()
        val updated = action(booking, item, now)
        bookings.update(updated)
        event(updated, actor, now)
        updated
    }

    private fun cancelApproved(equipmentId: UUID, actor: User, reason: String, now: Instant) {
        bookings.approvedForEquipment(equipmentId).forEach {
            val cancelled = it.copy(status = BookingStatus.CANCELLED, reason = reason)
            bookings.update(cancelled)
            event(cancelled, actor, now)
        }
    }

    private fun checkAvailability(equipmentId: UUID, period: Period, excludingId: UUID? = null) {
        if (bookings.conflicts(equipmentId, period, excludingId)) conflict("BOOKING_CONFLICT", "Equipment is already reserved for this period")
    }

    private fun requireAccess(actor: User, booking: Booking) {
        if (actor.role == Role.USER && actor.id != booking.userId) forbidden()
    }

    private fun event(booking: Booking, actor: User, now: Instant) {
        bookings.addEvent(BookingEvent(UUID.randomUUID(), booking.id, actor.id, booking.status, now, booking.reason))
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
