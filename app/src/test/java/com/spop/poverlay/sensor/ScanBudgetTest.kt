package com.spop.poverlay.sensor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanBudgetTest {
    private val minute = 60_000L

    @Test
    fun `scanning is allowed until a window has been opened`() {
        assertTrue(ScanBudget().allows(10 * minute))
    }

    @Test
    fun `window closes after five minutes`() {
        val budget = ScanBudget()
        budget.arm(0)
        assertTrue(budget.allows(4 * minute))
        assertFalse(budget.allows(5 * minute))
    }

    @Test
    fun `first movement opens a fresh window`() {
        val budget = ScanBudget()
        budget.arm(0)
        assertTrue(budget.onMovement(true, 20 * minute))
        assertTrue(budget.allows(24 * minute))
        assertFalse(budget.allows(25 * minute))
    }

    @Test
    fun `a short pause while riding does not open a new window`() {
        val budget = ScanBudget()
        budget.onMovement(true, 0)
        assertFalse(budget.onMovement(false, 10 * minute))
        assertFalse(budget.onMovement(true, 12 * minute))
        assertFalse(budget.allows(12 * minute))
    }

    @Test
    fun `riding again after a long idle opens a new window`() {
        val budget = ScanBudget()
        budget.onMovement(true, 0)
        budget.onMovement(false, 10 * minute)
        assertTrue(budget.onMovement(true, 16 * minute))
        assertTrue(budget.allows(17 * minute))
    }

    @Test
    fun `repeated moving reports do not reopen the window`() {
        val budget = ScanBudget()
        assertTrue(budget.onMovement(true, 0))
        assertFalse(budget.onMovement(true, 6 * minute))
        assertFalse(budget.allows(6 * minute))
    }
}
