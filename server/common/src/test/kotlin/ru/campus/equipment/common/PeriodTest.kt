package ru.campus.equipment.common

import org.junit.Test
import java.time.Instant
import kotlin.test.*

class PeriodTest {
    private val start = Instant.parse("2030-01-01T10:00:00Z")

    @Test fun `adjacent half open periods do not overlap`() {
        val first = Period(start, start.plusSeconds(3600))
        val adjacent = Period(first.end, first.end.plusSeconds(3600))
        assertFalse(first.overlaps(adjacent))
        assertFalse(adjacent.overlaps(first))
        assertTrue(first.contains(first.start))
        assertFalse(first.contains(first.end))
    }

    @Test fun `enclosing and partial periods overlap symmetrically`() {
        val first = Period(start, start.plusSeconds(3600))
        for (other in listOf(first, Period(start.minusSeconds(1), first.end.plusSeconds(1)),
            Period(start.plusSeconds(1), first.end.plusSeconds(1)))) {
            assertTrue(first.overlaps(other))
            assertTrue(other.overlaps(first))
        }
    }

    @Test fun `invalid ranges and submicrosecond precision are rejected`() {
        assertFailsWith<BusinessException> { Period(start, start) }
        assertFailsWith<BusinessException> { Period(start.plusSeconds(1), start) }
        assertFailsWith<BusinessException> { Period(start.plusNanos(1), start.plusSeconds(1)) }
    }
}
