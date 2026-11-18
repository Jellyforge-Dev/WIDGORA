package io.github.jellyforgedev.widgora

import android.app.Application
import io.github.jellyforgedev.widgora.core.data.Clock
import io.github.jellyforgedev.widgora.core.data.InstanceRepository
import io.github.jellyforgedev.widgora.core.data.SnapshotRepository
import io.github.jellyforgedev.widgora.core.integration.kuma.KumaMetricsClient
import io.github.jellyforgedev.widgora.core.transport.http.OkHttpTransport
import io.github.jellyforgedev.widgora.dashboard.DashboardTiming
import io.github.jellyforgedev.widgora.settings.ConnectionTester
import io.github.jellyforgedev.widgora.settings.InstanceStore
import io.github.jellyforgedev.widgora.settings.KumaConnectionTester
import io.github.jellyforgedev.widgora.settings.RepositoryInstanceStore

/**
 * The single place where the application's collaborators are built.
 *
 * This takes the place of a dependency injection framework, which the project does without at
 * this size. The instance repository in particular must exist exactly once: it owns a preference
 * file, and two instances over one file is a conflict rather than a convenience. Both screens
 * therefore reach the same one from here.
 *
 * Everything is created lazily, so nothing touches storage or the network before a screen asks
 * for it.
 */
class WidgoraApplication : Application() {

    private val instances: InstanceRepository by lazy { InstanceRepository.create(this) }

    val instanceStore: InstanceStore by lazy { RepositoryInstanceStore(instances) }

    val connectionTester: ConnectionTester by lazy { KumaConnectionTester() }

    /**
     * Holds what the dashboard shows. The transport is wired to the integration here, which is
     * this module's job, and the real clock is handed in rather than read inside.
     */
    val snapshots: SnapshotRepository by lazy {
        SnapshotRepository(
            instances = instances,
            client = KumaMetricsClient(OkHttpTransport()),
            clock = Clock.System,
            staleAfterMs = DashboardTiming.STALE_AFTER_MS,
        )
    }
}
