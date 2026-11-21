package io.github.jellyforgedev.widgora.widget

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether the periodic refresh is really in the queue, and really gone again.
 *
 * ### Why this needs a device at all
 *
 * The decisions themselves are unit-tested without one. What cannot be checked that way is the
 * step in between: that an enqueue under a unique name produces exactly one request a query by
 * that name finds, and that a cancellation reaches it. Both go through a database and a scheduler
 * that only exist on a device.
 *
 * No test dependency was added for this. `work-testing` would offer a synchronous executor and a
 * driver for constraints, and neither is needed to ask what is in the queue: the query is part of
 * the runtime artefact.
 *
 * ### Why it does not place a widget
 *
 * It calls the provider's callbacks directly. Placing a real widget needs a host and the
 * permission to bind one, and it would be testing the platform's delivery of a broadcast rather
 * than what this change decides. That delivery is confirmed separately, by hand, with a widget in
 * the debug host.
 */
@RunWith(AndroidJUnit4::class)
class WidgetRefreshDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * Leaves no periodic work behind on the device.
     *
     * Without it the first run of this test would schedule a refresh every thirty minutes on
     * whatever device it ran on, for as long as the test application stayed installed.
     */
    @After
    fun cancelWhateverTheTestQueued() {
        WidgetRefreshScheduler.cancel(context)
    }

    @Test
    fun theFirstWidgetPutsTheRefreshInTheQueue() {
        WidgetRefreshScheduler.cancel(context)

        CountsWidgetProvider().onEnabled(context)

        val queued = infos().filterNot { it.state.isFinished }
        assertEquals("expected exactly one request, states were ${describe()}", 1, queued.size)
    }

    /** Asking twice leaves one request, which is what the unique name is for. */
    @Test
    fun askingTwiceDoesNotProduceTwoRequests() {
        WidgetRefreshScheduler.cancel(context)

        CountsWidgetProvider().onEnabled(context)
        CountsWidgetProvider().onEnabled(context)

        val queued = infos().filterNot { it.state.isFinished }
        assertEquals("expected exactly one request, states were ${describe()}", 1, queued.size)
    }

    /**
     * The last widget going takes the refresh with it.
     *
     * No widget is placed in this test, which is exactly the situation `onDisabled` reports, so
     * the count the provider consults is zero and the rule says cancel.
     */
    @Test
    fun losingTheLastWidgetTakesTheRefreshOutOfTheQueue() {
        CountsWidgetProvider().onEnabled(context)
        assertTrue("nothing was queued to cancel", infos().any { !it.state.isFinished })

        CountsWidgetProvider().onDisabled(context)

        // A cancelled request is not necessarily removed from the database straight away; it is
        // recorded as cancelled. Either is correct, and asserting absence alone would be
        // asserting the timing of a pruning this change has no say over.
        assertTrue("the refresh is still queued: ${describe()}", infos().none { !it.state.isFinished })
        assertTrue(
            "nothing was recorded as cancelled: ${describe()}",
            infos().isEmpty() || infos().any { it.state == WorkInfo.State.CANCELLED },
        )
    }

    private fun infos(): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(WidgetRefreshScheduler.WORK_NAME).get()

    /** The states in the queue, for a failure message. It names no data, only states. */
    private fun describe(): String = infos().map { it.state }.toString()
}
