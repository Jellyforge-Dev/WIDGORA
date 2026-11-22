package io.github.jellyforgedev.widgora.widget

import android.content.Context
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jellyforgedev.widgora.core.data.MonitorEntry
import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.core.data.WidgetSettings
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaMetricsReader
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaMonitorMapping
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaReadResult
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaSnapshotMapper
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The test that matters: what actually crosses into the host's process.
 *
 * ### Why the other three are not enough
 *
 * The field-list test says a name *cannot* be carried. The two pipeline tests say none *is*
 * carried. Both describe what was intended to be put into the view hierarchy. This one reads the
 * hierarchy itself — the thing a launcher receives — and reads it twice: once inflated, and once
 * after the round trip through the serialisation a host is handed.
 *
 * The parcel half is not ceremony. A `RemoteViews` object reaches a host by being parcelled, and
 * whatever survives that journey is in the host's process; anything asserted only on the object
 * in this process is asserted on the near side of the boundary.
 *
 * ### What is collected
 *
 * Every text and every content description of every view in the tree, visible or not. Not only
 * the visible ones: an invisible view's text is in the parcel just the same, so a name behind
 * `View.GONE` would have crossed the boundary regardless.
 *
 * It is the fourth of the four tests listed in SECURITY.md.
 */
@RunWith(AndroidJUnit4::class)
class WidgetNamesDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theRecordingHasTheThirtyFiveNamesThisTestRestsOn() {
        assertEquals(35, recordedNames().size)
    }

    /** Without consent, no name of the recording is anywhere in the inflated tree. */
    @Test
    fun withoutConsentNoNameIsInTheInflatedTree() {
        for (layout in WidgetViews.Layout.entries) {
            val strings = stringsIn(
                inflate(WidgetViews.build(context, APP_WIDGET_ID, namelessModel(), layout)),
            )
            assertNoRecordedName("inflated, $layout", strings)
        }
    }

    /** And none survives the parcel either, which is the half that crosses the boundary. */
    @Test
    fun withoutConsentNoNameSurvivesTheParcel() {
        for (layout in WidgetViews.Layout.entries) {
            val restored = parcelAndBack(
                WidgetViews.build(context, APP_WIDGET_ID, namelessModel(), layout),
            )
            assertNoRecordedName("parcelled, $layout", stringsIn(inflate(restored)))
        }
    }

    /**
     * The two smaller steps carry **exactly one** name with consent, and it is the right one.
     *
     * This is the assertion that changed when the title line was given to the first affected
     * monitor, and the change widens what may cross the boundary: those two steps used to carry no
     * name at all. So the question is no longer "is any name here" but "is anything here that was
     * not consented to and asked for" — one name, the first affected one, in the title line and
     * nowhere else.
     *
     * The counting is done per collected string rather than per name, because names in a real
     * installation are substrings of one another: a shorter name contained in the one being shown
     * appears too, and in the same string. What would be a finding is a *second* view carrying a
     * name, and that is what is asserted.
     */
    @Test
    fun theSmallerStepsCarryExactlyTheOneNameTheTitleHasRoomFor() {
        val model = namedModel()
        val headline = requireNotNull(model.headline) { "the model has no title line to check" }

        for (layout in listOf(WidgetViews.Layout.COMPACT, WidgetViews.Layout.FULL)) {
            val restored = parcelAndBack(
                WidgetViews.buildNaming(context, APP_WIDGET_ID, model, layout),
            )
            val strings = stringsIn(inflate(restored))

            assertTrue(
                "$layout did not carry the name the title line was given: $strings",
                strings.any { text -> text.contains(headline.name) },
            )

            val elsewhere = strings
                .filter { text -> recordedNames().any { name -> text.contains(name) } }
                .filterNot { text -> text.contains(headline.name) }
            assertTrue(
                "$layout carried a monitor name outside its title line: $elsewhere",
                elsewhere.isEmpty(),
            )
        }
    }

    /**
     * The counter-check: with consent, in the box that carries names, they do cross the parcel.
     *
     * Without this half every assertion above could pass on a widget that draws nothing at all,
     * and the suite would be reporting care it never exercised.
     */
    @Test
    fun withConsentTheNamesDoCrossTheParcel() {
        val model = namedModel()
        val restored = parcelAndBack(
            WidgetViews.buildNaming(context, APP_WIDGET_ID, model, WidgetViews.Layout.FULL_NAMED),
        )

        val strings = stringsIn(inflate(restored))
        assertTrue("the model carries no names, so this proves nothing", model.names.isNotEmpty())
        for (name in model.names) {
            assertTrue(
                "the name '$name' did not survive the parcel: $strings",
                strings.any { text -> text.contains(name) },
            )
        }
    }

    // --- The models ----------------------------------------------------------------------------

    /**
     * Every item of the recording treated as failed, which is when a name could appear.
     *
     * The recording describes only working monitors, and a status widget names the ones that are
     * not working. Left as it is, the assertions above would pass because there was nothing to
     * name.
     */
    private fun failedItems(): List<MonitorEntry> = recordedItems()
        .map { entry -> entry.copy(state = MonitorState.FAILED) }

    /**
     * A model with data in it, which is the only condition under which names are drawn at all.
     *
     * The first version of this test built one without a snapshot. The widget then reports that
     * it is loading, the counts and the names are both hidden — and the counter-check below
     * failed, correctly, for a reason that had nothing to do with the boundary being tested. The
     * snapshot is built from the same items, so what the widget says about itself is true.
     */
    private fun namelessModel(): WidgetRenderModel.Instance {
        val overview = MonitorOverview(failedItems())
        return WidgetRenderModels.forInstance(
            title = TITLE,
            state = WidgetViewState.Display(WidgetDisplayState.Ok),
            snapshot = KumaSnapshotMapper.summarySnapshot(
                instanceId = INSTANCE_ID,
                title = TITLE,
                overview = overview,
                retrievedAtEpochMs = NOW,
            ),
            nowEpochMs = NOW,
        )
    }

    private fun namedModel(): NamedWidgetRenderModel {
        val consent = requireNotNull(
            NameConsent.from(
                WidgetSettings(
                    kind = WidgetKind.STATUS,
                    instanceId = InstanceId("kuma"),
                    showNames = true,
                ),
            ),
        ) { "settings that say yes produced no consent" }

        return NamedWidgetRenderModel.of(
            consent = consent,
            instance = namelessModel(),
            overview = MonitorOverview(failedItems()),
        )
    }

    // --- Reading the tree ----------------------------------------------------------------------

    private fun inflate(views: RemoteViews): View {
        var root: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            root = views.apply(context, null)
        }
        return requireNotNull(root) { "the views were not inflated" }
    }

    /**
     * Every text and content description in the tree, whether the view is visible or not.
     *
     * An invisible view's text is in the parcel exactly as a visible one's is, so restricting
     * this to what can be seen would be checking the wrong boundary.
     */
    private fun stringsIn(view: View): List<String> = buildList {
        view.contentDescription?.let { description -> add(description.toString()) }
        if (view is TextView) view.text?.let { text -> add(text.toString()) }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) addAll(stringsIn(view.getChildAt(index)))
        }
    }

    private fun assertNoRecordedName(where: String, strings: List<String>) {
        assertTrue("$where: nothing was collected at all", strings.isNotEmpty())
        for (name in recordedNames()) {
            assertTrue(
                "$where: the monitor name '$name' reached the host's side",
                strings.none { text -> text.contains(name) },
            )
        }
    }

    private fun parcelAndBack(views: RemoteViews): RemoteViews {
        val parcel = Parcel.obtain()
        return try {
            views.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            RemoteViews.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    // --- The recording -------------------------------------------------------------------------

    private fun recordedNames(): List<String> = recordedItems().map { entry -> entry.name }

    private fun recordedItems(): List<MonitorEntry> {
        val result = KumaMetricsReader.read(recording())
        val success = requireNotNull(result as? KumaReadResult.Success) { result.toString() }
        return KumaMonitorMapping.overviewOf(success.monitors).entries
    }

    /**
     * The captured response, read from the test package's own Java resources.
     *
     * It is `:core`'s test resource, shared through the build rather than copied; see
     * `app/build.gradle.kts`.
     */
    private fun recording(): String {
        val stream = requireNotNull(javaClass.getResourceAsStream(SAMPLE)) {
            "$SAMPLE is not in the test package's resources"
        }
        return stream.bufferedReader().use { reader -> reader.readText() }
    }

    private companion object {

        const val SAMPLE = "/kuma/metrics-sample.txt"
        const val APP_WIDGET_ID = 4715
        const val TITLE = "Uptime Kuma"
        const val NOW = 1_700_000_000_000L
        val INSTANCE_ID = InstanceId("kuma")
    }
}
