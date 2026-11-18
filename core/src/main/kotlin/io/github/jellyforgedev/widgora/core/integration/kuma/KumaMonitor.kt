package io.github.jellyforgedev.widgora.core.integration.kuma

/**
 * One monitor, as far as the metrics endpoint describes it.
 *
 * There is deliberately no field for the monitored address, host name or port. The endpoint
 * reports all three, and all three are internal infrastructure detail that nothing downstream
 * needs in order to display a status. Leaving them out of the type is more reliable than
 * remembering not to pass them on.
 *
 * Identity is [monitorId] alone. Monitor names are not unique: the same name can belong to two
 * different monitors.
 */
data class KumaMonitor(
    val monitorId: String,
    val name: String?,
    val type: String?,
    val status: KumaMonitorStatus,
    val responseTimeMs: Long?,
    val uptimeRatioByWindow: Map<String, Double>,
    val certificateValid: Boolean?,
    val certificateDaysRemaining: Int?,
)
