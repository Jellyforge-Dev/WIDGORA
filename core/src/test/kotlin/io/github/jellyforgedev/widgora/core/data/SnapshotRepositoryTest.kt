package io.github.jellyforgedev.widgora.core.data

import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaMetricsClient
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaSnapshotMapper
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The repository against a fake transport, a real metrics client and a clock the test sets.
 *
 * The real client is used on purpose: what matters here is how a real answer, a real failure and
 * a real refusal travel through to a tile.
 */
class SnapshotRepositoryTest {

    @Test
    fun beforeTheFirstRetrievalATileIsLoading() = runBlocking {
        val world = World()
        world.configure()

        world.repository.recompute()

        assertEquals(WidgetDisplayState.Loading, world.displayState())
        assertNull(world.view().snapshot)
        assertEquals(0, world.transport.reads)
    }

    @Test
    fun aSuccessfulRetrievalShowsTheSummary() = runBlocking {
        val world = World()
        world.configure()
        world.transport.answerWith(TWO_MONITORS)

        world.repository.refresh()

        assertEquals(WidgetDisplayState.Ok, world.displayState())
        val snapshot = assertNotNull(world.view().snapshot)
        assertEquals("1 up, 1 down", snapshot.primaryValue)
        assertEquals("Uptime Kuma", snapshot.title)
        assertEquals(NOW, snapshot.updatedAtEpochMs)
    }

    @Test
    fun aSnapshotBecomesStaleOnceTheThresholdPasses() = runBlocking {
        val world = World()
        world.configure()
        world.transport.answerWith(TWO_MONITORS)
        world.repository.refresh()

        world.clock.advanceBy(STALE_AFTER_MS - 1)
        world.repository.recompute()
        assertEquals(WidgetDisplayState.Ok, world.displayState())

        world.clock.advanceBy(1)
        world.repository.recompute()
        assertEquals(WidgetDisplayState.Stale, world.displayState())
        // The outdated value stays visible, with the time it was obtained.
        assertEquals(NOW, assertNotNull(world.view().snapshot).updatedAtEpochMs)
    }

    /**
     * An answer that describes no monitor cannot become an empty tile, because the reader
     * rejects it as malformed before a snapshot exists. The empty display state is therefore
     * unreachable through this integration; the rule that produces it is covered where it
     * lives, in the domain module.
     */
    @Test
    fun anAnswerWithoutMonitorsIsMalformedRatherThanEmpty() = runBlocking {
        val world = World()
        world.configure()
        world.transport.answerWith("# HELP monitor_status Monitor Status\n")

        world.repository.refresh()

        assertEquals(WidgetDisplayState.Error(Reason.MALFORMED_RESPONSE), world.displayState())
    }

    @Test
    fun anUnreachableServiceGoesOfflineAndKeepsTheLastSnapshot() = runBlocking {
        val world = World()
        world.configure()
        world.transport.answerWith(TWO_MONITORS)
        world.repository.refresh()

        world.clock.advanceBy(1_000)
        world.transport.failWith(TransportResult.Unreachable)
        world.repository.refresh()

        assertEquals(WidgetDisplayState.Offline, world.displayState())
        val snapshot = assertNotNull(world.view().snapshot)
        assertEquals("1 up, 1 down", snapshot.primaryValue)
        // The timestamp is the one of the successful retrieval, not of the failed attempt.
        assertEquals(NOW, snapshot.updatedAtEpochMs)
    }

    @Test
    fun aRejectedKeyNamesItsReasonAndKeepsTheLastSnapshot() = runBlocking {
        val world = World()
        world.configure()
        world.transport.answerWith(TWO_MONITORS)
        world.repository.refresh()

        world.transport.failWith(TransportResult.HttpStatus(401))
        world.repository.refresh()

        assertEquals(WidgetDisplayState.Error(Reason.UNAUTHORIZED), world.displayState())
        assertNotNull(world.view().snapshot)
    }

    @Test
    fun aMissingEndpointNamesItsOwnReason() = runBlocking {
        val world = World()
        world.configure()
        world.transport.failWith(TransportResult.HttpStatus(404))

        world.repository.refresh()

        assertEquals(WidgetDisplayState.Error(Reason.ENDPOINT_NOT_FOUND), world.displayState())
    }

    @Test
    fun anOversizedAnswerNamesItsOwnReason() = runBlocking {
        val world = World()
        world.configure()
        world.transport.failWith(TransportResult.TooLarge)

        world.repository.refresh()

        assertEquals(WidgetDisplayState.Error(Reason.OVERSIZED), world.displayState())
    }

    /**
     * Walks the instance through success, failure, failure and success again. Every step is
     * applied with the transition check of the lifecycle type, so an illegal order would throw
     * rather than pass unnoticed: this test fails loudly if the order is ever wrong.
     */
    @Test
    fun repeatedRetrievalsFollowTheLifecycleInEveryOrder() = runBlocking {
        val world = World()
        world.configure()

        world.transport.answerWith(TWO_MONITORS)
        world.repository.refresh()
        assertEquals(WidgetDisplayState.Ok, world.displayState())

        world.transport.failWith(TransportResult.Unreachable)
        world.repository.refresh()
        assertEquals(WidgetDisplayState.Offline, world.displayState())

        world.repository.refresh()
        assertEquals(WidgetDisplayState.Offline, world.displayState())

        world.transport.answerWith(TWO_MONITORS)
        world.repository.refresh()
        assertEquals(WidgetDisplayState.Ok, world.displayState())
        assertEquals(4, world.transport.reads)
    }

    /**
     * A refresh that arrives while one is running is dropped. The latches make this
     * deterministic: the second call happens while the first is inside the transport.
     */
    @Test
    fun aRefreshDuringARetrievalIsIgnored() {
        val world = World()
        runBlocking { world.configure() }
        world.transport.answerWith(TWO_MONITORS)

        val insideTransport = CountDownLatch(1)
        val mayFinish = CountDownLatch(1)
        world.transport.beforeAnswering = {
            insideTransport.countDown()
            mayFinish.await()
        }

        runBlocking {
            val first = launch(Dispatchers.IO) { world.repository.refresh() }
            insideTransport.await()

            world.repository.refresh()

            mayFinish.countDown()
            first.join()
        }

        assertEquals(1, world.transport.reads)
    }

    @Test
    fun aDisabledInstanceIsNotRetrieved() = runBlocking {
        val world = World()
        world.configure(enabled = false)

        world.repository.refresh()

        assertEquals(WidgetViewState.Notice(InstanceNotice.DISABLED), world.view().state)
        assertEquals(0, world.transport.reads)
    }

    @Test
    fun anInstanceWithoutAReadableKeyIsNotRetrieved() = runBlocking {
        val world = World()
        world.configure()
        world.secrets.decryptionFails = true

        world.repository.refresh()

        assertEquals(WidgetViewState.Notice(InstanceNotice.SECRET_UNUSABLE), world.view().state)
        assertEquals(0, world.transport.reads)
    }

    @Test
    fun anInvalidEntryIsNotRetrieved() = runBlocking {
        val world = World()
        world.configure()
        world.breakTheStoredAddress()

        world.repository.refresh()

        assertEquals(WidgetViewState.Notice(InstanceNotice.ENTRY_INVALID), world.view().state)
        assertEquals(0, world.transport.reads)
    }

    @Test
    fun theSnapshotSourceReturnsExactlyWhatIsHeld() = runBlocking {
        val world = World()
        world.configure()
        assertEquals(emptyList(), world.repository.all())

        world.transport.answerWith(TWO_MONITORS)
        world.repository.refresh()

        val expectedId = KumaSnapshotMapper.summaryWidgetId(INSTANCE_ID)
        assertEquals(1, world.repository.all().size)
        assertEquals(expectedId, world.repository.all().single().widgetId)
        assertNotNull(world.repository.byId(expectedId))
        assertNull(world.repository.byId(KumaSnapshotMapper.summaryWidgetId(InstanceId("other"))))
    }

    @Test
    fun theCredentialAppearsInNoViewAndNoText() = runBlocking {
        val world = World()
        world.configure()
        world.transport.answerWith(TWO_MONITORS)

        world.repository.refresh()

        val rendered = world.repository.views.value.toString()
        assertFalse(rendered.contains(API_KEY), rendered)
        assertTrue(world.transport.sawTheKey, "the key never reached the transport")
    }

    /** The pieces one test needs, wired the way the application wires them. */
    private class World {

        val transport = ScriptedTransport()
        val secrets = ReversibleSecretStore()
        val preferences = InMemoryPreferences()
        val instances = InstanceRepository(preferences, secrets)
        val clock = SettableClock(NOW)
        val repository = SnapshotRepository(
            instances = instances,
            client = KumaMetricsClient(transport),
            clock = clock,
            staleAfterMs = STALE_AFTER_MS,
        )

        suspend fun configure(enabled: Boolean = true) {
            instances.save(
                InstanceConfig(
                    instanceId = INSTANCE_ID,
                    displayName = "Uptime Kuma",
                    baseUrl = "https://kuma.example.invalid",
                    enabled = enabled,
                ),
                apiKey = API_KEY,
            )
        }

        /** Writes an address the current rules reject, as a downgrade would leave behind. */
        suspend fun breakTheStoredAddress() {
            preferences.updateData { preferences ->
                preferences.toMutablePreferences().apply {
                    this[stringPreferencesKey("instance.kuma.baseUrl")] =
                        "http://insecure.invalid"
                }
            }
        }

        fun view(): WidgetView = repository.views.value.single()

        fun displayState(): WidgetDisplayState {
            val state = view().state
            assertTrue(state is WidgetViewState.Display, state.toString())
            return state.displayState
        }
    }

    /** A transport that answers what the test tells it to, and counts what it was asked. */
    private class ScriptedTransport : Transport {

        var reads = 0
        var sawTheKey = false
        var beforeAnswering: (() -> Unit)? = null

        private var answer: TransportResult = TransportResult.Unreachable

        fun answerWith(body: String) {
            answer = TransportResult.Success(body)
        }

        fun failWith(result: TransportResult) {
            answer = result
        }

        override fun read(request: TransportRequest): TransportResult {
            reads++
            val credentials = request.credentials
            if (credentials is TransportCredentials.Basic && credentials.password == API_KEY) {
                sawTheKey = true
            }
            beforeAnswering?.invoke()
            return answer
        }
    }

    private companion object {
        val INSTANCE_ID = InstanceId("kuma")
        const val API_KEY = "uk1_dashboard_secret"
        const val NOW = 1_700_000_000_000
        const val STALE_AFTER_MS = 180_000L
        const val TWO_MONITORS =
            "monitor_status{monitor_id=\"1\",monitor_name=\"Service 01\"} 1\n" +
                "monitor_status{monitor_id=\"2\",monitor_name=\"Service 02\"} 0\n"
    }
}
