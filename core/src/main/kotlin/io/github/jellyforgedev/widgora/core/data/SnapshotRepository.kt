package io.github.jellyforgedev.widgora.core.data

import io.github.jellyforgedev.widgora.core.integration.kuma.KumaMetricsClient
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaReadResult
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaSnapshotMapper
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaSummaryTemplates
import io.github.jellyforgedev.widgora.model.DisplayStateRule
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.IntegrationInstanceState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import io.github.jellyforgedev.widgora.model.WidgetId
import io.github.jellyforgedev.widgora.model.WidgetSnapshot
import io.github.jellyforgedev.widgora.model.WidgetSnapshotSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/**
 * Holds the newest snapshot of every instance and what each tile should show.
 *
 * It is the first implementation of [WidgetSnapshotSource], the read interface declared in the
 * domain module so that the bridge can consume snapshots without depending on this module.
 *
 * Everything it holds lives in memory. A snapshot is a view of a service at a moment in time;
 * writing it to disk would mean deciding how long a stale value may be presented after a
 * restart, and that decision belongs to a later step, not to a cache.
 *
 * **Thread contract.** [refresh] blocks the calling coroutine while it retrieves, inheriting the
 * transport's contract, so it must not run on the main thread.
 */
class SnapshotRepository(
    private val instances: InstanceRepository,
    private val client: KumaMetricsClient,
    private val clock: Clock,
    private val staleAfterMs: Long,
    /** The wording of the summary. It comes from the application's resources. */
    private val summaryTemplates: KumaSummaryTemplates,
) : WidgetSnapshotSource {

    /** What is known about one instance between retrievals. */
    private data class Held(
        val snapshot: WidgetSnapshot?,
        val outcome: Outcome,
        val lifecycle: IntegrationInstanceState,
    )

    /** The result of the last retrieval, which is not the same thing as a display state. */
    private sealed interface Outcome {
        data object NeverRetrieved : Outcome
        data object Retrieved : Outcome
        data object Unreachable : Outcome
        data class Failed(val reason: Reason) : Outcome
    }

    /** Whether an instance is to be retrieved, decided before anything is sent. */
    private sealed interface Plan {
        data class Retrieve(val config: InstanceConfig) : Plan
        data class Hold(val instance: StoredInstance, val notice: InstanceNotice) : Plan
    }

    private val held = MutableStateFlow<Map<InstanceId, Held>>(emptyMap())

    private val mutableViews = MutableStateFlow<List<WidgetView>>(emptyList())

    /** What the dashboard renders. It changes on a retrieval and on a recomputation. */
    val views: StateFlow<List<WidgetView>> get() = mutableViews

    /**
     * Guards against two retrievals at once.
     *
     * A refresh that arrives while one is running is dropped rather than queued: the newer one
     * would ask the same question of the same server, and the answer is already on its way.
     * [Mutex.tryLock] is not reentrant, which is exactly what is wanted here.
     */
    private val retrieving = Mutex()

    /** Retrieves every eligible instance, then publishes. A concurrent call does nothing. */
    suspend fun refresh() {
        if (!retrieving.tryLock()) return
        try {
            val plans = plan()
            for (plan in plans) {
                if (plan is Plan.Retrieve) retrieve(plan.config)
            }
            publish(plans)
        } finally {
            retrieving.unlock()
        }
    }

    /**
     * Derives the views again without retrieving anything.
     *
     * Whether a snapshot is outdated depends on the time, not on new data, so the display state
     * has to be re-derived once the clock has moved on.
     */
    suspend fun recompute() {
        publish(plan())
    }

    override fun all(): List<WidgetSnapshot> = held.value.values.mapNotNull { it.snapshot }

    override fun byId(widgetId: WidgetId): WidgetSnapshot? =
        all().firstOrNull { snapshot -> snapshot.widgetId == widgetId }

    private suspend fun plan(): List<Plan> = instances.instances.first().map { instance ->
        when (instance) {
            is StoredInstance.Invalid -> Plan.Hold(instance, InstanceNotice.ENTRY_INVALID)

            is StoredInstance.Valid -> when {
                !instance.config.enabled -> Plan.Hold(instance, InstanceNotice.DISABLED)

                // The credential is only checked for existence here and read again inside the
                // retrieval, so that it never travels in a plan object or lives in a field.
                instances.secretOf(instance.instanceId) !is SecretState.Present ->
                    Plan.Hold(instance, InstanceNotice.SECRET_UNUSABLE)

                else -> Plan.Retrieve(instance.config)
            }
        }
    }

    private suspend fun retrieve(config: InstanceConfig) {
        val instanceId = config.instanceId
        val secret = instances.secretOf(instanceId)
        if (secret !is SecretState.Present) return

        val before = held.value[instanceId]
        var lifecycle = before?.lifecycle ?: IntegrationInstanceState.CONFIGURED
        if (lifecycle == IntegrationInstanceState.CONFIGURED) {
            lifecycle = lifecycle.transitionTo(IntegrationInstanceState.ENABLED)
        }
        // transitionTo refuses an illegal step rather than absorbing it: a wrong order here
        // would be a defect in this class, not something a server or a user can cause.
        lifecycle = lifecycle.transitionTo(IntegrationInstanceState.FETCHING)

        val result = client.fetch(config.baseUrl, secret.apiKey)
        val now = clock.nowEpochMs()

        put(
            instanceId,
            when (result) {
                is KumaReadResult.Success -> Held(
                    snapshot = KumaSnapshotMapper.summarySnapshot(
                        instanceId = instanceId,
                        title = config.displayName,
                        monitors = result.monitors,
                        retrievedAtEpochMs = now,
                        templates = summaryTemplates,
                    ),
                    outcome = Outcome.Retrieved,
                    lifecycle = lifecycle.transitionTo(IntegrationInstanceState.ENABLED),
                )

                is KumaReadResult.Failure -> Held(
                    // The last successful snapshot is kept. It is shown with its own timestamp
                    // and marked as outdated, which is of more use than an empty tile.
                    snapshot = before?.snapshot,
                    outcome = if (result.reason == Reason.UNREACHABLE) {
                        Outcome.Unreachable
                    } else {
                        Outcome.Failed(result.reason)
                    },
                    lifecycle = lifecycle.transitionTo(IntegrationInstanceState.FAILING),
                )

                // A saved instance always carries a normalised https address, so this is a
                // guard rather than an expected case, and there is no better cause to name.
                KumaReadResult.AddressRejected -> Held(
                    snapshot = before?.snapshot,
                    outcome = Outcome.Failed(Reason.UNKNOWN),
                    lifecycle = lifecycle.transitionTo(IntegrationInstanceState.FAILING),
                )
            },
        )
    }

    private fun put(instanceId: InstanceId, entry: Held) {
        held.value = held.value + (instanceId to entry)
    }

    private fun publish(plans: List<Plan>) {
        val now = clock.nowEpochMs()
        mutableViews.value = plans.map { plan -> view(plan, now) }
    }

    private fun view(plan: Plan, now: Long): WidgetView = when (plan) {
        is Plan.Hold -> WidgetView(
            instanceId = plan.instance.instanceId,
            widgetId = KumaSnapshotMapper.summaryWidgetId(plan.instance.instanceId),
            title = titleOf(plan.instance),
            snapshot = held.value[plan.instance.instanceId]?.snapshot,
            state = WidgetViewState.Notice(plan.notice),
        )

        is Plan.Retrieve -> {
            val entry = held.value[plan.config.instanceId]
            WidgetView(
                instanceId = plan.config.instanceId,
                widgetId = KumaSnapshotMapper.summaryWidgetId(plan.config.instanceId),
                title = plan.config.displayName,
                snapshot = entry?.snapshot,
                state = WidgetViewState.Display(displayStateOf(entry, now)),
            )
        }
    }

    private fun displayStateOf(entry: Held?, now: Long): WidgetDisplayState =
        when (val outcome = entry?.outcome) {
            null, Outcome.NeverRetrieved -> WidgetDisplayState.Loading
            Outcome.Unreachable -> WidgetDisplayState.Offline
            is Outcome.Failed -> WidgetDisplayState.Error(outcome.reason)
            Outcome.Retrieved -> DisplayStateRule.stateFor(
                snapshot = entry.snapshot,
                nowEpochMs = now,
                staleAfterMs = staleAfterMs,
            )
        }

    private fun titleOf(instance: StoredInstance): String = when (instance) {
        is StoredInstance.Valid -> instance.config.displayName
        // An invalid entry may have lost its name as well; the identifier is then all there is
        // to address it by.
        is StoredInstance.Invalid -> instance.displayName
            ?.takeIf { it.isNotBlank() }
            ?: instance.instanceId.value
    }
}
