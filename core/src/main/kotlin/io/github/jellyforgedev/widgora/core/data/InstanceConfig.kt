package io.github.jellyforgedev.widgora.core.data

import io.github.jellyforgedev.widgora.model.InstanceId

/**
 * One configured service instance, without its secret.
 *
 * The type lives in this module and not in the domain module on purpose: it carries a server
 * address, and the domain module is the one the bridge is allowed to see. Nothing that names
 * where a service runs may travel that way.
 *
 * **There is no field for the API key.** The key is held by the secret store, keyed by
 * [instanceId], so an instance can be listed, displayed and logged without the secret being
 * anywhere near it. There is no field for permitting plain HTTP either: cleartext is refused
 * outright, so an instance cannot carry permission for it.
 *
 * The constructor demands an already normalised address. A caller that has user input runs it
 * through [InstanceAddress.normalise] first and shows the rejection; by the time a configuration
 * exists, the address is known to be usable.
 */
data class InstanceConfig(
    val instanceId: InstanceId,
    val displayName: String,
    val baseUrl: String,
    val enabled: Boolean,
) {

    init {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "displayName exceeds " + MAX_DISPLAY_NAME_LENGTH + " characters"
        }
        require(InstanceAddress.isNormalised(baseUrl)) {
            "baseUrl must be a normalised https address without credentials, query or fragment"
        }
    }

    companion object {

        const val MAX_DISPLAY_NAME_LENGTH = 64
    }
}
