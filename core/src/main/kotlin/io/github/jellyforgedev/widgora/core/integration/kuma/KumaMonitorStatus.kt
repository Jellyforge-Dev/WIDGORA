package io.github.jellyforgedev.widgora.core.integration.kuma

/**
 * State of a single monitor as reported by the metrics endpoint.
 *
 * The numeric values are not guessed. The upstream project declares them as
 * `DOWN = 0`, `UP = 1`, `PENDING = 2`, `MAINTENANCE = 3`, the metrics endpoint writes the
 * heartbeat value into the gauge without translating it, and the gauge's own help text repeats
 * the same mapping. The help text is part of every response, so a future change to it is
 * visible in the data rather than silent.
 */
enum class KumaMonitorStatus {

    DOWN,
    UP,
    PENDING,
    MAINTENANCE,

    /**
     * Any value the endpoint reports that is not one of the four known ones.
     *
     * It exists so that a newly introduced state cannot be absorbed into one of the known
     * states. An unknown state is never counted as working.
     */
    UNKNOWN,

    ;

    val countsAsUp: Boolean get() = this == UP

    val countsAsDown: Boolean get() = this == DOWN

    internal companion object {

        fun fromWireValue(value: Double): KumaMonitorStatus {
            // A gauge carries a floating point number. Anything that is not exactly one of the
            // four documented integers, including a fractional value or NaN, is unknown.
            return when (value) {
                0.0 -> DOWN
                1.0 -> UP
                2.0 -> PENDING
                3.0 -> MAINTENANCE
                else -> UNKNOWN
            }
        }
    }
}
