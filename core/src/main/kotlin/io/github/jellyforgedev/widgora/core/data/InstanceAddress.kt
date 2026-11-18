package io.github.jellyforgedev.widgora.core.data

import java.net.URI
import java.net.URISyntaxException

/**
 * Checks and normalises the address of a service instance.
 *
 * It is deliberately stricter than a general URL parser, and it runs when an instance is saved
 * rather than when it is read, so that a bad address is refused where the user can still correct
 * it instead of surfacing much later as a failed fetch.
 *
 * Parsing uses the platform URI type rather than the HTTP client's own, because an address is a
 * piece of configuration; nothing here may depend on which client performs the request.
 */
object InstanceAddress {

    /** Why an address was refused. Each case is a distinct message for the user. */
    enum class Rejection {
        NOT_AN_ADDRESS,
        NOT_HTTPS,
        HOST_MISSING,
        CREDENTIALS_IN_ADDRESS,
        QUERY_NOT_ALLOWED,
        FRAGMENT_NOT_ALLOWED,
    }

    sealed interface Result {

        /** @param normalised the form to store; it is what [InstanceConfig] accepts. */
        data class Valid(val normalised: String) : Result

        data class Invalid(val rejection: Rejection) : Result
    }

    /**
     * Normalising means: scheme and host lower case, a trailing slash removed, an explicit port
     * kept as given. A path is kept, because an installation may sit behind a reverse proxy on a
     * sub path.
     *
     * A query string and a fragment are refused rather than dropped. Both would be silently lost
     * on normalisation, and an address the user typed deliberately should not change meaning
     * without them noticing.
     */
    fun normalise(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Invalid(Rejection.NOT_AN_ADDRESS)

        val uri = try {
            URI(trimmed)
        } catch (malformed: URISyntaxException) {
            return Result.Invalid(Rejection.NOT_AN_ADDRESS)
        }

        val scheme = uri.scheme ?: return Result.Invalid(Rejection.NOT_AN_ADDRESS)
        if (!scheme.equals(HTTPS_SCHEME, ignoreCase = true)) {
            return Result.Invalid(Rejection.NOT_HTTPS)
        }
        if (uri.userInfo != null) return Result.Invalid(Rejection.CREDENTIALS_IN_ADDRESS)
        if (uri.rawQuery != null) return Result.Invalid(Rejection.QUERY_NOT_ALLOWED)
        if (uri.rawFragment != null) return Result.Invalid(Rejection.FRAGMENT_NOT_ALLOWED)

        val host = uri.host
        if (host.isNullOrBlank()) return Result.Invalid(Rejection.HOST_MISSING)

        val port = if (uri.port == -1) "" else ":" + uri.port
        val path = uri.rawPath.orEmpty().trimEnd('/')

        return Result.Valid(HTTPS_SCHEME + "://" + host.lowercase() + port + path)
    }

    /** True when [address] is already exactly the form [normalise] produces. */
    fun isNormalised(address: String): Boolean {
        val result = normalise(address)
        return result is Result.Valid && result.normalised == address
    }

    private const val HTTPS_SCHEME = "https"
}
