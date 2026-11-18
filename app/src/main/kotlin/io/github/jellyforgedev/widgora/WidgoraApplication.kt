package io.github.jellyforgedev.widgora

import android.app.Application
import io.github.jellyforgedev.widgora.core.data.InstanceRepository
import io.github.jellyforgedev.widgora.settings.ConnectionTester
import io.github.jellyforgedev.widgora.settings.InstanceStore
import io.github.jellyforgedev.widgora.settings.KumaConnectionTester
import io.github.jellyforgedev.widgora.settings.RepositoryInstanceStore

/**
 * The single place where the application's collaborators are built.
 *
 * This takes the place of a dependency injection framework, which the project does without at
 * this size. The repository in particular must exist exactly once: it owns a preference file,
 * and two instances over one file is a conflict rather than a convenience.
 *
 * Both are created lazily, so nothing touches storage before a screen asks for it.
 */
class WidgoraApplication : Application() {

    val instanceStore: InstanceStore by lazy {
        RepositoryInstanceStore(InstanceRepository.create(this))
    }

    val connectionTester: ConnectionTester by lazy { KumaConnectionTester() }
}
