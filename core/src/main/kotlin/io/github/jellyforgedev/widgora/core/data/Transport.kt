package io.github.jellyforgedev.widgora.core.data

/**
 * Reads data from a remote service.
 *
 * The abstraction is a transport rather than an HTTP client on purpose. The first integration
 * returns Prometheus text over HTTP, a later one needs a WebSocket alongside its REST calls, and
 * an energy system speaks Modbus TCP. Committing the rest of the project to an HTTP client type
 * would force a rewrite as soon as the second kind of transport arrives.
 *
 * **Thread contract.** An implementation blocks the calling thread for the whole call, including
 * connection setup and reading the response. A caller must never invoke this on the main thread.
 * There is no coroutine or callback form, because the scheduling belongs to whoever owns the
 * lifecycle, not to the transport.
 *
 * An implementation never throws for a condition a caller is expected to handle; a failed read
 * is a [TransportResult], not an exception.
 */
interface Transport {

    fun read(request: TransportRequest): TransportResult
}

/**
 * One read, described without reference to a protocol.
 *
 * @param url the full address to read from
 * @param credentials optional credentials for this one read; never stored here
 * @param maxResponseBytes upper bound enforced while reading, not after
 */
data class TransportRequest(
    val url: String,
    val credentials: TransportCredentials? = null,
    val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) {

    init {
        require(url.isNotBlank()) { "url must not be blank" }
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
    }

    companion object {

        /**
         * Two mebibytes. For scale, a response describing 35 monitors measures roughly 78
         * thousand bytes, so this leaves room for a far larger installation while still bounding
         * what a hostile or broken server can make this application allocate.
         */
        const val DEFAULT_MAX_RESPONSE_BYTES = 2L * 1024 * 1024
    }
}

/** Credentials for a single read. */
sealed interface TransportCredentials {

    /**
     * User name and password of HTTP basic authentication.
     *
     * [toString] is overridden because the generated one would print the password into any log
     * or crash report that formats this value.
     */
    data class Basic(val username: String, val password: String) : TransportCredentials {

        override fun toString(): String = "Basic(username=***, password=***)"
    }
}

/** Outcome of one read. */
sealed interface TransportResult {

    /**
     * The service answered successfully.
     *
     * [toString] reports only the length. The body carries monitor and host names, and
     * SECURITY.md forbids any integration from logging a raw response body.
     */
    data class Success(val body: String) : TransportResult {

        override fun toString(): String = "Success(bodyLength=" + body.length + ")"
    }

    /** The service answered, but not with success. The code is the protocol status. */
    data class HttpStatus(val code: Int) : TransportResult

    /**
     * The service could not be reached: no connection, a timeout, a name that does not resolve,
     * or a failed TLS handshake. Deliberately one outcome: the distinction is of no use to the
     * person looking at a television, and the detail would have to be carried as server text.
     */
    data object Unreachable : TransportResult

    /** The response exceeded [TransportRequest.maxResponseBytes] while being read. */
    data object TooLarge : TransportResult

    /**
     * The address was refused before any connection was attempted, because it is unusable or
     * because its scheme is not permitted.
     */
    data object AddressRejected : TransportResult
}
