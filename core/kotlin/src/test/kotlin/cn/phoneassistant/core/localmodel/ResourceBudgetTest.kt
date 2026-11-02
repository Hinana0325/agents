package cn.phoneassistant.core.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResourceBudgetTest {

    @Test
    fun validBudgetIsAccepted() {
        val budget = ResourceBudget(maxMemoryMb = 512, tier = "flagship")
        assertEquals(512, budget.maxMemoryMb)
        assertEquals("flagship", budget.tier)
    }

    @Test
    fun zeroMemoryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ResourceBudget(maxMemoryMb = 0, tier = "entry")
        }
    }

    @Test
    fun negativeMemoryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ResourceBudget(maxMemoryMb = -1, tier = "pc_mid")
        }
    }

    @Test
    fun everyDocumentedTierCanBeConstructed() {
        for (tier in listOf("flagship", "mid", "entry", "pc_high", "pc_mid", "pc_entry")) {
            assertEquals(tier, ResourceBudget(maxMemoryMb = 1, tier = tier).tier)
        }
    }
}
