package io.github.jellyforgedev.widgora.model

import io.github.jellyforgedev.widgora.model.IntegrationInstanceState.CONFIGURED
import io.github.jellyforgedev.widgora.model.IntegrationInstanceState.ENABLED
import io.github.jellyforgedev.widgora.model.IntegrationInstanceState.FAILING
import io.github.jellyforgedev.widgora.model.IntegrationInstanceState.FETCHING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every ordered pair of states is covered: eight transitions are legal, the remaining eight are
 * not. The pairs are written out one by one instead of looped so that a failure names the exact
 * transition that broke.
 */
class IntegrationInstanceStateTest {

    @Test
    fun configuredToEnabledIsAllowed() = assertAllowed(CONFIGURED, ENABLED)

    @Test
    fun enabledToFetchingIsAllowed() = assertAllowed(ENABLED, FETCHING)

    @Test
    fun fetchingToEnabledIsAllowed() = assertAllowed(FETCHING, ENABLED)

    @Test
    fun fetchingToFailingIsAllowed() = assertAllowed(FETCHING, FAILING)

    @Test
    fun failingToFetchingIsAllowed() = assertAllowed(FAILING, FETCHING)

    @Test
    fun enabledToConfiguredIsAllowed() = assertAllowed(ENABLED, CONFIGURED)

    @Test
    fun fetchingToConfiguredIsAllowed() = assertAllowed(FETCHING, CONFIGURED)

    @Test
    fun failingToConfiguredIsAllowed() = assertAllowed(FAILING, CONFIGURED)

    @Test
    fun configuredToFetchingIsRejected() = assertRejected(CONFIGURED, FETCHING)

    @Test
    fun configuredToFailingIsRejected() = assertRejected(CONFIGURED, FAILING)

    @Test
    fun enabledToFailingIsRejected() = assertRejected(ENABLED, FAILING)

    @Test
    fun failingToEnabledIsRejected() = assertRejected(FAILING, ENABLED)

    @Test
    fun configuredToItselfIsRejected() = assertRejected(CONFIGURED, CONFIGURED)

    @Test
    fun enabledToItselfIsRejected() = assertRejected(ENABLED, ENABLED)

    @Test
    fun fetchingToItselfIsRejected() = assertRejected(FETCHING, FETCHING)

    @Test
    fun failingToItselfIsRejected() = assertRejected(FAILING, FAILING)

    /**
     * Guards the two numbers above. A fifth state would otherwise arrive with nine untested
     * pairs and no test failure to point at them.
     */
    @Test
    fun exactlyEightOfSixteenPairsAreAllowed() {
        val states = IntegrationInstanceState.entries
        assertEquals(4, states.size)
        val allowed = states.sumOf { from -> states.count { to -> from.canTransitionTo(to) } }
        assertEquals(8, allowed)
    }

    private fun assertAllowed(from: IntegrationInstanceState, to: IntegrationInstanceState) {
        assertTrue(from.canTransitionTo(to), from.name + " -> " + to.name)
        assertEquals(to, from.transitionTo(to))
    }

    private fun assertRejected(from: IntegrationInstanceState, to: IntegrationInstanceState) {
        assertFalse(from.canTransitionTo(to), from.name + " -> " + to.name)
        assertFailsWith<IllegalStateException> { from.transitionTo(to) }
    }
}
