package io.github.jellyforgedev.widgora.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves that the test chain for :model is wired up and actually executes.
 * It makes no statement about the product and must not grow into a product test.
 */
class SmokeTest {

    @Test
    fun testChainRuns() {
        assertEquals(2, 1 + 1)
    }
}
