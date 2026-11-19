package io.github.jellyforgedev.widgora.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a host is actually handed when it presses a widget.
 *
 * This cannot be a unit test. A `PendingIntent` only exists where there is a system to register
 * it with, and whether one is immutable is something the platform answers rather than the code
 * that asked for it. It is started by hand against a device and is deliberately not part of the
 * continuous integration run, like the instrumented tests in the data module.
 *
 * It is the one place the claim in `SECURITY.md` is checked rather than asserted: a host can send
 * this, and can do nothing else with it.
 */
@RunWith(AndroidJUnit4::class)
class WidgetLaunchDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theIntentNamesAWidgoraComponentAndNothingElse() {
        val intent = WidgetLaunch.intentFor(context, APP_WIDGET_ID)

        val component = intent.component
        assertNotNull("the intent is implicit", component)
        assertEquals(context.packageName, component!!.packageName)
        assertEquals(MAIN_ACTIVITY, component.className)
        assertEquals(context.packageName, intent.`package`)
    }

    /**
     * Nothing travels but the identifier.
     *
     * A widget carries no address, no credential and no monitor name, and the simplest way to
     * keep it that way is for the intent to have room for exactly one thing.
     */
    @Test
    fun theOnlyThingCarriedIsTheWidgetIdentifier() {
        val intent = WidgetLaunch.intentFor(context, APP_WIDGET_ID)

        val extras = intent.extras
        assertNotNull("the intent carries no extras at all", extras)
        assertEquals(setOf(AppWidgetManager.EXTRA_APPWIDGET_ID), extras!!.keySet())
        assertEquals(
            APP_WIDGET_ID,
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            ),
        )
        assertNull("the intent carries data", intent.data)
        assertTrue("the intent carries categories", intent.categories.isNullOrEmpty())
    }

    /**
     * The load-bearing flag.
     *
     * Without it a host could fill in parts of the intent before sending it, which is exactly the
     * hole the deferred bridge's deep link had to be defended against on both sides.
     */
    @Test
    fun thePendingIntentIsImmutable() {
        val pending = WidgetLaunch.pendingIntentFor(context, APP_WIDGET_ID)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue("the pending intent is mutable", pending.isImmutable)
        }
        assertEquals(context.packageName, pending.creatorPackage)
    }

    /**
     * Two widgets never share one pending intent.
     *
     * With a shared request code, `FLAG_UPDATE_CURRENT` would rewrite the single registered
     * intent each time, and every widget would end up pointing at whichever was updated last.
     */
    @Test
    fun eachWidgetGetsItsOwnPendingIntent() {
        WidgetLaunch.pendingIntentFor(context, APP_WIDGET_ID)
        WidgetLaunch.pendingIntentFor(context, OTHER_APP_WIDGET_ID)

        val first = existing(APP_WIDGET_ID)
        val second = existing(OTHER_APP_WIDGET_ID)

        assertNotNull("the first pending intent was not registered", first)
        assertNotNull("the second pending intent was not registered", second)
        assertFalse("both widgets share one pending intent", first == second)
    }

    /**
     * Looks up what is registered rather than creating anything.
     *
     * `FLAG_NO_CREATE` returns null when nothing matches, which is what makes this a question
     * about the system's own records instead of about the arguments passed in here.
     */
    private fun existing(appWidgetId: Int): PendingIntent? = PendingIntent.getActivity(
        context,
        appWidgetId,
        WidgetLaunch.intentFor(context, appWidgetId),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {

        const val APP_WIDGET_ID = 4711
        const val OTHER_APP_WIDGET_ID = 4712
        const val MAIN_ACTIVITY = "io.github.jellyforgedev.widgora.MainActivity"
    }
}
