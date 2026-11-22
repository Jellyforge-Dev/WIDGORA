package io.github.jellyforgedev.widgora.widget

import io.github.jellyforgedev.widgora.core.data.InstanceConfig
import io.github.jellyforgedev.widgora.core.data.MonitorEntry
import io.github.jellyforgedev.widgora.core.data.MonitorOverview
import io.github.jellyforgedev.widgora.core.data.MonitorState
import io.github.jellyforgedev.widgora.core.data.StoredInstance
import io.github.jellyforgedev.widgora.core.data.StoredInstances
import io.github.jellyforgedev.widgora.core.data.WidgetKind
import io.github.jellyforgedev.widgora.core.data.WidgetSettings
import io.github.jellyforgedev.widgora.core.data.WidgetView
import io.github.jellyforgedev.widgora.core.data.WidgetViewState
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaMetricsReader
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaMonitorMapping
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaReadResult
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaSnapshotMapper
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Whether a widget can name a monitor, run against the names a real installation produces.
 *
 * ### Why the recording and not invented names
 *
 * The thing being asserted is that these thirty-five names do not appear. Invented names test the
 * same code against material chosen by whoever wrote the assertion; a captured response of a
 * running installation, anonymised, has the shapes the real thing has — duplicate names, names of
 * differing length, names that are substrings of one another.
 *
 * The file is `:core`'s own test resource, shared with this module through the build rather than
 * copied; see `app/build.gradle.kts` for why a second copy would be worse than a shared one.
 *
 * ### Why the states are rewritten
 *
 * The recording describes only working monitors, and a status widget names the ones that are
 * **not** working. Run against it unchanged, the "no name appears" half would pass because there
 * was nothing to name — which proves nothing at all. So every item is treated as failed, which is
 * the one condition under which a name could reach a widget, and both halves then mean something:
 * without consent none of the thirty-five appears, and with consent the first three do.
 *
 * These are tests 2 and 3 of the four listed in SECURITY.md. The first is the field-list test in
 * [WidgetRenderModelTest]; the fourth walks the inflated view tree on a device.
 */
class WidgetNamingTest {

    @Test
    fun theRecordingHasTheThirtyFiveNamesTheseTestsRestOn() {
        assertEquals(35, sampleNames().size)
        assertTrue(sampleNames().all { name -> name.isNotBlank() })
    }

    /**
     * Without consent, not one of the thirty-five names is anywhere in the model.
     *
     * Both the fields and the textual form are checked. The second is not redundant: a model is
     * written into log lines, assertion messages and crash reports by its `toString`, and a type
     * whose fields are clean while its `toString` is not would leak through every one of those.
     */
    @Test
    fun withoutConsentNoMonitorNameReachesTheModelOrItsTextualForm() {
        val render = renderOf(consent = false)

        val model = (render as WidgetRender.Nameless).model as WidgetRenderModel.Instance
        val rendered = model.title + " " + model.counts + " " + model.toString()

        for (name in sampleNames()) {
            assertFalse(rendered.contains(name), "a monitor name reached the widget: $name")
        }
    }

    /**
     * The counter-check, without which the test above could pass for the wrong reason.
     *
     * A broken pipeline produces no names either. This half insists the names are there to be
     * found, so that "none appeared" means the care worked rather than that nothing ran.
     */
    @Test
    fun withConsentTheNamesDoAppear() {
        val named = (renderOf(consent = true) as WidgetRender.Named).model

        assertEquals(NamedWidgetRenderModel.MAX_NAMES, named.names.size)
        for (name in sampleNames().take(NamedWidgetRenderModel.MAX_NAMES)) {
            assertTrue(
                named.names.any { shown -> shown.startsWith(name.take(SHORTENED_PREFIX)) },
                "the names shown were ${named.names}, expected to contain $name",
            )
        }
    }

    /** The ones that did not fit are counted rather than dropped. */
    @Test
    fun withConsentTheRestAreCounted() {
        val named = (renderOf(consent = true) as WidgetRender.Named).model

        assertEquals(sampleNames().size - NamedWidgetRenderModel.MAX_NAMES, named.remaining)
    }

    /**
     * The named model's own textual form carries no name either.
     *
     * It is the one type in this chain that is *allowed* to hold names, which is exactly why its
     * `toString` must not print them: a log line or an assertion message is not a widget.
     */
    @Test
    fun evenTheNamedModelDoesNotPrintItsNames() {
        val named = (renderOf(consent = true) as WidgetRender.Named).model

        assertTrue(named.names.isNotEmpty(), "the model carries no names, so this proves nothing")
        val printed = named.toString()
        for (name in named.names) {
            assertFalse(printed.contains(name), "toString printed a monitor name: $printed")
        }
    }

    // --- The title line ------------------------------------------------------------------------

    /**
     * With consent and a failure, the title line gets the first affected item and its mark.
     *
     * The mark is asserted against the state's own symbol rather than a character written here, so
     * that a change to the design system's marks cannot leave this test asserting a shape the
     * application no longer draws.
     */
    @Test
    fun withConsentTheTitleLineNamesTheFirstAffectedItem() {
        val named = (renderOf(consent = true) as WidgetRender.Named).model
        val headline = named.headline ?: fail("no title line was produced for a failed item")

        assertEquals(MonitorState.FAILED.symbol, headline.mark)
        assertTrue(
            headline.name.startsWith(sampleNames().first().take(SHORTENED_PREFIX)),
            "the title line names '${headline.name}', expected ${sampleNames().first()}",
        )
        assertEquals(sampleNames().size - 1, headline.others)
    }

    /**
     * And it prints no name either, for the same reason the model does not.
     *
     * The title line is the one part of this feature whose whole content is a monitor name, which
     * makes it the most likely thing to end up in a log line by accident.
     */
    @Test
    fun theTitleLineDoesNotPrintItsName() {
        val headline = (renderOf(consent = true) as WidgetRender.Named).model.headline
            ?: fail("no title line was produced for a failed item")

        assertFalse(
            headline.toString().contains(headline.name),
            "toString printed a monitor name: $headline",
        )
    }

    /**
     * Nothing wrong, nothing in the title.
     *
     * The recording as it stands describes working monitors only, which is exactly the case here:
     * a widget whose instance is healthy keeps the instance's own name on the title line, and does
     * not change shape for a condition that has not occurred.
     */
    @Test
    fun withNothingWrongTheTitleLineStaysEmpty() {
        val named = namedModel(itemsFromRecording())

        assertTrue(named.names.isEmpty(), "the recording was expected to be healthy")
        assertEquals(null, named.headline)
    }

    /**
     * An expected condition does not take the title line over.
     *
     * Maintenance and waiting are affected states — they are counted, and named in the lines below
     * — but neither is a reason to replace the title: a widget that announces a monitor somebody
     * deliberately paused is a widget that cries wolf.
     */
    @Test
    fun anExpectedConditionDoesNotTakeTheTitleLine() {
        for (state in listOf(MonitorState.MAINTENANCE, MonitorState.PENDING)) {
            val named = namedModel(
                itemsFromRecording().map { entry -> entry.copy(state = state) },
            )

            assertTrue(named.names.isNotEmpty(), "$state was expected to be an affected state")
            assertEquals(null, named.headline, "$state took the title line over")
        }
    }

    /**
     * An unrecognised state does take it, and that is deliberate.
     *
     * It may well be a failure that is not understood yet, and this project's own urgency order
     * already places it second for that reason.
     */
    @Test
    fun anUnrecognisedStateTakesTheTitleLine() {
        val named = namedModel(
            itemsFromRecording().map { entry -> entry.copy(state = MonitorState.UNKNOWN) },
        )

        assertEquals(MonitorState.UNKNOWN.symbol, named.headline?.mark)
    }

    /**
     * A single affected item counts no others, so nothing is appended beside the name.
     *
     * The count is the part of the line that must not be shortened away, and a "+0" would be the
     * one value of it that says nothing at all.
     */
    @Test
    fun aSingleAffectedItemCountsNoOthers() {
        val items = itemsFromRecording()
        val oneFailed = listOf(items.first().copy(state = MonitorState.FAILED)) + items.drop(1)

        assertEquals(0, namedModel(oneFailed).headline?.others)
    }

    // --- Helpers -------------------------------------------------------------------------------

    /**
     * A named model over the given items, built the one way it can be built.
     *
     * The consent token comes from stored settings that say yes, exactly as it does in the
     * application; there is no other way to obtain one, which is the point of it.
     */
    private fun namedModel(items: List<MonitorEntry>): NamedWidgetRenderModel {
        val consent = NameConsent.from(
            WidgetSettings(
                kind = WidgetKind.STATUS,
                instanceId = INSTANCE_ID,
                showNames = true,
            ),
        ) ?: fail("settings that say yes produced no consent")

        return NamedWidgetRenderModel.of(
            consent = consent,
            instance = WidgetRenderModel.Instance(
                title = TITLE,
                state = WidgetViewState.Display(WidgetDisplayState.Ok),
                counts = "",
                updatedAtEpochMs = NOW,
            ),
            overview = MonitorOverview(items),
        )
    }

    /**
     * The whole pipeline for one widget, with or without a stored yes.
     *
     * It starts at the reader rather than at the model, so that what is exercised is the path an
     * update actually takes: the response is parsed, mapped to items, turned into a view, and the
     * widget's stored settings decide which of the two models comes out.
     */
    private fun renderOf(consent: Boolean): WidgetRender = WidgetModelSelection.fromStorage(
        kind = WidgetKind.STATUS,
        settings = WidgetSettings(
            kind = WidgetKind.STATUS,
            instanceId = INSTANCE_ID,
            showNames = consent,
        ),
        stored = StoredInstances.Readable(
            listOf(
                StoredInstance.Valid(
                    InstanceConfig(
                        instanceId = INSTANCE_ID,
                        displayName = TITLE,
                        baseUrl = BASE_URL,
                        enabled = true,
                    ),
                ),
            ),
        ),
        views = listOf(viewFromRecording()),
        nowEpochMs = NOW,
    )

    private fun viewFromRecording(): WidgetView {
        val overview = MonitorOverview(failedItemsFromRecording())
        return WidgetView(
            instanceId = INSTANCE_ID,
            widgetId = KumaSnapshotMapper.summaryWidgetId(INSTANCE_ID),
            title = TITLE,
            snapshot = KumaSnapshotMapper.summarySnapshot(
                instanceId = INSTANCE_ID,
                title = TITLE,
                overview = overview,
                retrievedAtEpochMs = NOW,
            ),
            state = WidgetViewState.Display(WidgetDisplayState.Ok),
            overview = overview,
        )
    }

    /**
     * Every item of the recording, treated as failed.
     *
     * See the note on the class: a status widget names what is not working, and the recording
     * describes nothing that is not working.
     */
    private fun failedItemsFromRecording(): List<MonitorEntry> = itemsFromRecording()
        .map { entry -> entry.copy(state = MonitorState.FAILED) }

    private fun sampleNames(): List<String> = itemsFromRecording().map { entry -> entry.name }

    private fun itemsFromRecording(): List<MonitorEntry> {
        val result = KumaMetricsReader.read(recording())
        val success = result as? KumaReadResult.Success ?: fail(result.toString())
        return KumaMonitorMapping.overviewOf(success.monitors).entries
    }

    private fun recording(): String = javaClass.getResourceAsStream(SAMPLE)
        ?.bufferedReader()
        ?.use { reader -> reader.readText() }
        ?: fail("$SAMPLE was not on the test classpath")

    private companion object {

        const val SAMPLE = "/kuma/metrics-sample.txt"
        val INSTANCE_ID = InstanceId("kuma")
        const val TITLE = "Uptime Kuma"
        const val BASE_URL = "https://kuma.example.invalid"
        const val NOW = 1_700_000_000_000L

        /**
         * How much of a name has to match for it to count as present.
         *
         * A shown name is cut to [NamedWidgetRenderModel.MAX_NAME_CODE_POINTS] and ends in an
         * ellipsis when it was cut, so an exact comparison would fail on a long name for the
         * wrong reason. Comparing a prefix shorter than the cut asks the right question: is this
         * the name being shown.
         */
        const val SHORTENED_PREFIX = 8
    }
}
