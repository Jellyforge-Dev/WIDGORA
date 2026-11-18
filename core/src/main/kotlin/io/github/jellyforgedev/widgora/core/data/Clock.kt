package io.github.jellyforgedev.widgora.core.data

/**
 * Reads the wall clock.
 *
 * It exists so that nothing below the application layer calls the clock directly. Whether a
 * snapshot counts as outdated is a comparison against "now", and a test that cannot choose
 * "now" would have to sleep in order to exercise it.
 */
fun interface Clock {

    fun nowEpochMs(): Long

    companion object {

        /** The real clock. The only place in this module that reads the system time. */
        val System: Clock = Clock { java.lang.System.currentTimeMillis() }
    }
}
