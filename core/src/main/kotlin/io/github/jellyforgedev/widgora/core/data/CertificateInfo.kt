package io.github.jellyforgedev.widgora.core.data

/**
 * What the source says about a certificate's validity.
 *
 * Three values rather than a `Boolean?`, because the wording of each is load-bearing and a boolean
 * invites exactly the claim that must not be made. The source writes its validity gauge as a plain
 * no for everything that is not an explicit yes — a missing statement included — so a no from it
 * supports "not valid as far as the source says" and nothing stronger. It does **not** support
 * "expired": that is a different claim about a different thing, and nothing in the answer backs it.
 *
 * [UNKNOWN] is not the same as having no certificate at all. An item without one carries no
 * [CertificateInfo]; this value means the source does hold certificate information for the item and
 * either did not state whether it is valid or contradicted itself about it.
 */
enum class CertificateValidity {

    /** The source stated that the certificate is valid. */
    VALID_ACCORDING_TO_SOURCE,

    /**
     * The source did not state that the certificate is valid.
     *
     * Deliberately phrased as the absence of a yes rather than the presence of a problem. The gauge
     * behind it is written as no both for a certificate that is genuinely bad and for one the source
     * has no opinion on, and those two are not distinguishable in what arrives.
     */
    NOT_VALID_ACCORDING_TO_SOURCE,

    /** Certificate information exists, but the validity was not stated, or was contradicted. */
    UNKNOWN,
}

/**
 * The certificate of one monitored item, as far as the source describes it.
 *
 * **Absence is the ordinary case.** An item that terminates no TLS — a ping check, a plain
 * connection test — carries `null` here rather than an instance with empty values. The distinction
 * earns its nullability: the list of items that *do* have a certificate says which of the user's
 * hosts terminate TLS, which is topology in the same sense a host name is, and a placeholder on
 * every item would erase the one honest way to say "this does not apply".
 *
 * Confirmed against a captured, anonymised response of a running installation `[V]`: twenty of its
 * thirty-five items carry certificate rows and the other fifteen carry none at all.
 *
 * **Why a figure is never shown on its own.** The first integration's source never clears these
 * values — they go when the monitor goes and not otherwise `[V]` — so a number can outlive the
 * check that produced it. Whatever displays one is therefore obliged to show the time the data was
 * obtained beside it, and to mark an item whose own state is not active. A reassuring figure nobody
 * has re-checked for weeks is the failure mode this type exists to make visible rather than
 * comfortable.
 *
 * This type is held inside the application only. It reaches no host and is never logged.
 *
 * @param daysRemaining whole days left, in the source's own unit. `null` where no figure was stated
 *   or two rows disagreed. It may be zero or negative: a figure that has run out is still a figure,
 *   and clamping it would throw away the one case anybody needs to see.
 */
data class CertificateInfo(
    val validity: CertificateValidity,
    val daysRemaining: Int?,
)
