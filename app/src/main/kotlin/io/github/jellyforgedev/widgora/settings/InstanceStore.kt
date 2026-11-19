package io.github.jellyforgedev.widgora.settings

import io.github.jellyforgedev.widgora.core.data.InstanceConfig
import io.github.jellyforgedev.widgora.core.data.InstanceRepository
import io.github.jellyforgedev.widgora.core.data.SecretState
import io.github.jellyforgedev.widgora.core.data.StorageWriteResult
import io.github.jellyforgedev.widgora.core.data.StoredInstances
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

    /**
     * What is stored, or the statement that it cannot be read.
     *
     * The distinction reaches this screen rather than stopping at the dashboard, because this is
     * the screen that can act on it: an unreadable file is the one situation in which the settings
     * offer to throw storage away.
     */
    val instances: Flow<StoredInstances>

    suspend fun secretOf(instanceId: InstanceId): SecretState

    suspend fun save(config: InstanceConfig, apiKey: String?): StorageWriteResult

    suspend fun delete(instanceId: InstanceId): StorageWriteResult

    /** Removes the stored configuration and all key material. Only a user action calls it. */
    suspend fun reset(): StorageWriteResult
}

/** Passes everything through to the repository; it adds no behaviour of its own. */
class RepositoryInstanceStore(private val repository: InstanceRepository) : InstanceStore {

    override val instances: Flow<StoredInstances> get() = repository.instances

    override suspend fun secretOf(instanceId: InstanceId): SecretState =
        repository.secretOf(instanceId)

    override suspend fun save(config: InstanceConfig, apiKey: String?): StorageWriteResult =
        repository.save(config, apiKey)

    override suspend fun delete(instanceId: InstanceId): StorageWriteResult =
        repository.delete(instanceId)

    override suspend fun reset(): StorageWriteResult = repository.reset()
}
