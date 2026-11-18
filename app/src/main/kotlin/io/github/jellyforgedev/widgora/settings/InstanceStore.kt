package io.github.jellyforgedev.widgora.settings

import io.github.jellyforgedev.widgora.core.data.InstanceConfig
import io.github.jellyforgedev.widgora.core.data.InstanceRepository
import io.github.jellyforgedev.widgora.core.data.SecretState
import io.github.jellyforgedev.widgora.core.data.StoredInstance
import io.github.jellyforgedev.widgora.model.InstanceId
import kotlinx.coroutines.flow.Flow

/**
 * What the settings screen needs from storage, and nothing more.
 *
 * The repository itself is a final class that only exists with a real preference file behind it,
 * so the screen's state holder would not be testable without a device if it depended on it
 * directly. This interface is the seam: the application passes the real repository through
 * [RepositoryInstanceStore], a unit test passes a double.
 */
interface InstanceStore {

    val instances: Flow<List<StoredInstance>>

    suspend fun secretOf(instanceId: InstanceId): SecretState

    suspend fun save(config: InstanceConfig, apiKey: String?)

    suspend fun delete(instanceId: InstanceId)
}

/** Passes everything through to the repository; it adds no behaviour of its own. */
class RepositoryInstanceStore(private val repository: InstanceRepository) : InstanceStore {

    override val instances: Flow<List<StoredInstance>> get() = repository.instances

    override suspend fun secretOf(instanceId: InstanceId): SecretState =
        repository.secretOf(instanceId)

    override suspend fun save(config: InstanceConfig, apiKey: String?) =
        repository.save(config, apiKey)

    override suspend fun delete(instanceId: InstanceId) = repository.delete(instanceId)
}
