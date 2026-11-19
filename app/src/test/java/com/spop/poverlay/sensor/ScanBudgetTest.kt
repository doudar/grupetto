package com.spop.poverlay.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanBudgetTest {
    private val minute = 60_000L

    @Test
    fun `scanning is allowed before anything is reported`() {
        assertTrue(ScanBudget().allows(10 * minute))
    }

    @Test
    fun `start-up scans for five minutes while the bike is idle`() {
        val budget = ScanBudget()
        budget.arm(0)
        assertTrue(budget.allows(4 * minute))
        assertFalse(budget.allows(5 * minute))
    }

    @Test
    fun `scanning never stops while the bike keeps moving`() {
        val budget = ScanBudget()
        budget.arm(0)
        budget.onMovement(true, 1 * minute)
        assertTrue(budget.allows(90 * minute))
    }

    @Test
    fun `scanning continues for five minutes after the bike stops`() {
        val budget = ScanBudget()
        budget.onMovement(true, 0)
        budget.onMovement(false, 30 * minute)
        assertTrue(budget.allows(34 * minute))
        assertFalse(budget.allows(35 * minute))
    }

    @Test
    fun `moving again after a long idle resumes scanning`() {
        val budget = ScanBudget()
        budget.onMovement(true, 0)
        budget.onMovement(false, 10 * minute)
        assertFalse(budget.allows(20 * minute))
        assertTrue(budget.onMovement(true, 20 * minute))
        assertTrue(budget.allows(21 * minute))
    }

    @Test
    fun `a short pause does not count as resuming`() {
        val budget = ScanBudget()
        budget.onMovement(true, 0)
        budget.onMovement(false, 10 * minute)
        assertFalse(budget.onMovement(true, 12 * minute))
        assertTrue(budget.allows(12 * minute))
    }
}
