package io.github.jellyforgedev.widgora.devhost

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.widget.WidgetProviders
import io.github.jellyforgedev.widgora.widget.config.WidgetConfigActivity
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether the configuration step actually starts when a host asks for it.
 *
 * ### Why this test exists, written down because its absence cost a television test
 *
 * The step did not start on the television at all, and nothing in the suite noticed. The reason is
 * worth stating exactly: **no test went through a host.** The screen's own device test composes it
 * directly with a fake store, which proves what the screen does once it is on the display; the
 * layout tests build view hierarchies and measure them. Between a host binding a widget and that
 * screen appearing lies one platform call — `startAppWidgetConfigureActivityForResult` — and not
 * one test had ever made it. A gap of that shape is invisible from inside: every test passes, and
 * the one thing nobody exercised is the thing a user does first.
 *
 * What was missing there was the background-activity-start permission a sender has to grant from
 * Android 14 on; see [WidgetHostActivity]'s own note for the evidence. This test drives the same
 * sequence the television does — bind, start the step, wait for it — and fails if the step does not
 * come up.
 *
 * **It was run against the version without that permission, and it failed** `[V]` — on the tablet
 * emulator, API 34, the same level as the television, with the message this test carries. That is
 * what makes it worth keeping rather than a test written to pass.
 *
 * ### Both halves are verified, in this form
 *
 * It was changed twice after the negative check — the cleanup no longer deletes the whole host,
 * and the start no longer goes through `startActivitySync` — and the whole suite was then run
 * again on the same emulator: 41 tests, no failures, this one in 3.4s `[V]`. So the form that
 * passes and the form that caught the defect are the same form.
 *
 * ### What it needs, and what it does when it does not have it
 *
 * Binding a widget is privileged, and an ordinary application is refused it until the shell says
 * otherwise. Where that grant is absent the test is **skipped rather than failed**: a red test for
 * a missing grant teaches people to ignore red tests. The grant is one command, and the message
 * names it.
 *
 * ### What it changes on the device
 *
 * It deletes this host's widgets afterwards — the debug host's own, not the launcher's — and clears
 * the preference file in which that host remembers its placement. A widget placed by hand in it for
 * looking at is therefore gone after a run. That is stated here rather than discovered.
 */
@RunWith(AndroidJUnit4::class)
class HostConfigureDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Gives back the widget this run placed, and nothing more.
     *
     * The first version called `deleteHost`, which removes every widget of that host in one call
     * and is the tidier-looking choice. A run containing it ended with `INSTRUMENTATION_ABORTED:
     * System has crashed`, and the emulator then took some twenty minutes to answer again `[V]`.
     * An earlier run of the same code had passed, so **that call is a suspect and not a proven
     * cause** `[U]` — and the device was not damaged, only unresponsive, which is worth recording
     * because it was first mistaken for a broken image.
     *
     * It is a good enough suspect to drop. The instrumentation runs in the application's own
     * process, so that call deletes a host another object in the same process is listening to,
     * and a cleanup that gives back only the one identifier that was placed — exactly what the
     * host's own "remove widget" button does — needs none of that reach.
     */
    @After
    fun releaseWhatThisTestPlaced() {
        val placed = preferences()
            .getInt(WidgetHostActivity.KEY_APP_WIDGET_ID, HostWidgetIds.NO_APP_WIDGET_ID)
        if (placed != HostWidgetIds.NO_APP_WIDGET_ID) {
            AppWidgetHost(context, WidgetHostActivity.HOST_ID).deleteAppWidgetId(placed)
        }
        preferences().edit().clear().commit()
    }

    /**
     * A host that binds a widget reaches the configuration step.
     *
     * The monitor is installed before the screen is started, because the step appears within
     * milliseconds of the binding and a monitor added afterwards would miss it.
     */
    @Test
    fun theConfigurationStepStartsWhenAHostAsksForIt() {
        assumeTrue(
            "this package may not bind widgets yet; run: " +
                "adb shell appwidget grantbind --package ${context.packageName}",
            bindingIsAllowed(),
        )

        // From a state with nothing placed, so the host binds and configures rather than
        // reattaching what a previous run left behind.
        preferences().edit().clear().commit()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val hostMonitor = instrumentation.addMonitor(
            WidgetHostActivity::class.java.name,
            null,
            false,
        )
        val stepMonitor = instrumentation.addMonitor(
            WidgetConfigActivity::class.java.name,
            null,
            false,
        )

        // Started with a plain intent and awaited through a monitor, rather than with
        // `startActivitySync`. That call waits for the main thread to go idle as well as for the
        // start, and one run failed in it with "Could not launch intent ... within 45000 ms.
        // Perhaps the main thread has not gone idle" `[V]` — on a host that binds a widget and
        // starts another activity on top of itself while still settling. Whether it was idleness
        // or the device already failing is not settled `[U]`; either way a monitor asks only the
        // question this test is asking, which is whether the activity started.
        context.startActivity(
            Intent(context, WidgetHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        var host: Activity? = null
        var step: Activity? = null
        try {
            host = instrumentation.waitForMonitorWithTimeout(hostMonitor, TIMEOUT_MS)
            assertNotNull("the debug host itself did not start", host)

            step = instrumentation.waitForMonitorWithTimeout(stepMonitor, TIMEOUT_MS)
            assertNotNull(
                "the configuration step did not start within ${TIMEOUT_MS}ms — on a device this " +
                    "is what a user sees as a widget that cannot be set up",
                step,
            )
        } finally {
            instrumentation.removeMonitor(hostMonitor)
            instrumentation.removeMonitor(stepMonitor)
            instrumentation.runOnMainSync {
                step?.finish()
                host?.finish()
            }
        }
    }

    /**
     * Whether the shell grant is in place, asked by trying it rather than by reading a setting.
     *
     * The identifier is allocated and given back immediately. Leaving one allocated would be a
     * widget the system believes exists and nothing on any screen would ever mention.
     */
    private fun bindingIsAllowed(): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        val provider = manager.getInstalledProvidersForPackage(context.packageName, null)
            .firstOrNull { info ->
                info.provider.className == WidgetProviders.classNameOf(WidgetKind.STATUS)
            }
            ?.provider
            ?: return false

        val host = AppWidgetHost(context, PROBE_HOST_ID)
        val appWidgetId = host.allocateAppWidgetId()
        return try {
            manager.bindAppWidgetIdIfAllowed(appWidgetId, provider)
        } finally {
            host.deleteAppWidgetId(appWidgetId)
        }
    }

    private fun preferences() =
        context.getSharedPreferences(WidgetHostActivity.PREFERENCES, Context.MODE_PRIVATE)

    private companion object {

        /** A host of its own for the probe, so the one under test keeps its own record. */
        const val PROBE_HOST_ID = 97

        /** Generous: the screen reads a settings file before it can appear. */
        const val TIMEOUT_MS = 8_000L
    }
}
