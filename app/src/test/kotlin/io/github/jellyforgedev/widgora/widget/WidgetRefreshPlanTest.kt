package io.github.jellyforgedev.widgora.widget

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The two decisions behind the periodic refresh, exercised without a device.
 *
 * Both have a wrong answer that would be invisible on a television: work queued for a widget
 * nobody placed retrieves from a server every half hour for nothing, and a retry reported for an
 * unreachable server retries with backoff instead of letting the widget say what happened.
 */
class WidgetRefreshPlanTest {

    // --- Whether the work belongs in the queue -------------------------------------------------

    @Test
    fun oneWidgetIsEnoughToWantTheWork() {
        assertEquals(RefreshSchedule.ENQUEUE, WidgetRefreshPlan.scheduleFor(1))
    }

    @Test
    fun severalWidgetsStillWantOneRequest() {
        assertEquals(RefreshSchedule.ENQUEUE, WidgetRefreshPlan.scheduleFor(7))
    }

    /** The whole reason the count is consulted at all. */
    @Test
    fun noWidgetMeansTheWorkIsCancelled() {
        assertEquals(RefreshSchedule.CANCEL, WidgetRefreshPlan.scheduleFor(0))
    }

    /**
     * A negative count is a defect in the caller, not an input to interpret.
     *
     * It cannot arise from the platform — a number of placed widgets is the size of an array — so
     * reading it as "no widgets" would mean quietly accepting something that can only be a
     * programming error, and the accepting branch is the one that cancels.
     */
    @Test
    fun aNegativeCountIsRefusedRatherThanInterpreted() {
        assertFailsWith<IllegalArgumentException> { WidgetRefreshPlan.scheduleFor(-1) }
    }

    // --- What a finished run reports -----------------------------------------------------------

    /**
     * The case the whole split exists for.
     *
     * A run that pushed has done its job whatever the retrieval found. The type carries no notion
     * of the retrieval having succeeded, so there is deliberately no way for this decision to
     * come to depend on one — which is why this test can state the rule for every failure the
     * application knows without enumerating any of them.
     */
    @Test
    fun aRunThatPushedSucceedsWhateverItRetrieved() {
        assertEquals(RefreshOutcome.SUCCESS, WidgetRefreshPlan.outcomeOf(RefreshRun.Pushed))
    }

    @Test
    fun aRunWithNothingToPushToSucceedsBecauseThereIsNothingToRetry() {
        assertEquals(
            RefreshOutcome.SUCCESS,
            WidgetRefreshPlan.outcomeOf(RefreshRun.NothingToPushTo),
        )
    }

    @Test
    fun aRunThatBrokeOffBeforeThePushAsksToBeRetried() {
        assertEquals(
            RefreshOutcome.RETRY,
            WidgetRefreshPlan.outcomeOf(
                RefreshRun.BrokeOffBeforePush(IllegalStateException("storage went away")),
            ),
        )
    }

    /**
     * Every kind of run is one this test actually exercises.
     *
     * The expectation is read from the type rather than written down as a number. A hardcoded
     * count would be a test that asks to be bumped: adding a kind of run and forgetting to try it
     * would fail here, and the quickest way back to green would be to change the number — which
     * is the outcome this exists to prevent.
     *
     * Java reflection on the nested types, not `sealedSubclasses`, which needs a reflection
     * library this project does not depend on. The `when` in the production code is exhaustive by
     * the compiler's doing; this is the braces to that belt, and it covers the part the compiler
     * cannot see — whether the three answers were ever checked.
     */
    @Test
    fun everyKindOfRunIsExercisedHere() {
        val exercised = listOf(
            RefreshRun.Pushed,
            RefreshRun.NothingToPushTo,
            RefreshRun.BrokeOffBeforePush(RuntimeException("whatever")),
        )
        val declared = RefreshRun::class.java.declaredClasses
            .filter { nested -> RefreshRun::class.java.isAssignableFrom(nested) }
            .map { nested -> nested.simpleName }
            .toSet()

        assertTrue(declared.isNotEmpty(), "the hierarchy reported no nested kinds at all")
        assertEquals(declared, exercised.map { it.javaClass.simpleName }.toSet())
    }

    // --- The two figures that only mean anything together --------------------------------------

    /**
     * The staleness threshold is one and a half runs.
     *
     * Held because the two constants are a pair: raising the interval without raising the
     * threshold would leave every widget reading as outdated for most of the time between two
     * ordinary, successful runs, and neither figure on its own would look wrong.
     */
    @Test
    fun theStalenessThresholdIsOneAndAHalfIntervals() {
        val intervalMs = TimeUnit.MINUTES.toMillis(WidgetTiming.REFRESH_INTERVAL_MINUTES)

        assertEquals(intervalMs * 3 / 2, WidgetTiming.STALE_AFTER_MS)
    }

    /** Below the platform's floor a periodic request is silently raised to it. */
    @Test
    fun theIntervalClearsThePlatformFloorForPeriodicWork() {
        assertTrue(
            WidgetTiming.REFRESH_INTERVAL_MINUTES >= PLATFORM_MINIMUM_PERIOD_MINUTES,
            "an interval of ${WidgetTiming.REFRESH_INTERVAL_MINUTES} minutes is below the floor",
        )
    }

    private companion object {

        /** `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` is fifteen minutes. */
        const val PLATFORM_MINIMUM_PERIOD_MINUTES = 15L
    }
}
