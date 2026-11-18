package io.github.jellyforgedev.widgora.core.data

import io.github.jellyforgedev.widgora.core.data.InstanceAddress.Rejection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceAddressTest {

    @Test
    fun acceptsAPlainHttpsAddress() {
        assertEquals("https://kuma.example.invalid", normalised("https://kuma.example.invalid"))
    }

    @Test
    fun removesATrailingSlash() {
        assertEquals("https://kuma.example.invalid", normalised("https://kuma.example.invalid/"))
    }

    /** An installation may sit behind a reverse proxy on a sub path. */
    @Test
    fun keepsAPath() {
        assertEquals(
            "https://host.example.invalid/kuma",
            normalised("https://host.example.invalid/kuma"),
        )
        assertEquals(
            "https://host.example.invalid/kuma",
            normalised("https://host.example.invalid/kuma/"),
        )
    }

    @Test
    fun keepsAnExplicitPort() {
        assertEquals(
            "https://host.example.invalid:3001",
            normalised("https://host.example.invalid:3001"),
        )
    }

    @Test
    fun lowersTheCaseOfSchemeAndHost() {
        assertEquals("https://host.example.invalid", normalised("HTTPS://Host.Example.INVALID"))
    }

    @Test
    fun keepsTheCaseOfThePath() {
        assertEquals(
            "https://host.example.invalid/Kuma",
            normalised("https://host.example.invalid/Kuma"),
        )
    }

    @Test
    fun ignoresSurroundingWhitespace() {
        assertEquals("https://kuma.example.invalid", normalised("  https://kuma.example.invalid  "))
    }

    @Test
    fun refusesPlainHttp() {
        assertEquals(Rejection.NOT_HTTPS, rejection("http://kuma.example.invalid"))
    }

    @Test
    fun refusesAnotherScheme() {
        assertEquals(Rejection.NOT_HTTPS, rejection("ftp://kuma.example.invalid"))
        assertEquals(Rejection.NOT_HTTPS, rejection("widgora://widget/x"))
    }

    @Test
    fun refusesSomethingThatIsNotAnAddress() {
        assertEquals(Rejection.NOT_AN_ADDRESS, rejection(""))
        assertEquals(Rejection.NOT_AN_ADDRESS, rejection("   "))
        assertEquals(Rejection.NOT_AN_ADDRESS, rejection("kuma.example.invalid"))
        assertEquals(Rejection.NOT_AN_ADDRESS, rejection("not an address"))
    }

    /**
     * Credentials in the address would be stored in clear as part of the configuration, which is
     * exactly what the secret store exists to prevent.
     */
    @Test
    fun refusesCredentialsInTheAddress() {
        assertEquals(
            Rejection.CREDENTIALS_IN_ADDRESS,
            rejection("https://user:pass@kuma.example.invalid"),
        )
        assertEquals(
            Rejection.CREDENTIALS_IN_ADDRESS,
            rejection("https://user@kuma.example.invalid"),
        )
    }

    /** Refused rather than dropped: normalising them away would change what the user typed. */
    @Test
    fun refusesAQueryString() {
        assertEquals(Rejection.QUERY_NOT_ALLOWED, rejection("https://kuma.example.invalid/?a=b"))
    }

    @Test
    fun refusesAFragment() {
        assertEquals(Rejection.FRAGMENT_NOT_ALLOWED, rejection("https://kuma.example.invalid/#top"))
    }

    @Test
    fun refusesAMissingHost() {
        assertEquals(Rejection.HOST_MISSING, rejection("https:///metrics"))
        assertEquals(Rejection.HOST_MISSING, rejection("https:kuma"))
    }

    @Test
    fun recognisesItsOwnOutputAsNormalised() {
        assertTrue(InstanceAddress.isNormalised("https://kuma.example.invalid"))
        assertTrue(InstanceAddress.isNormalised("https://host.example.invalid:3001/kuma"))
    }

    @Test
    fun doesNotCallAnUnnormalisedAddressNormalised() {
        assertFalse(InstanceAddress.isNormalised("https://kuma.example.invalid/"))
        assertFalse(InstanceAddress.isNormalised("HTTPS://kuma.example.invalid"))
        assertFalse(InstanceAddress.isNormalised("http://kuma.example.invalid"))
    }

    @Test
    fun theRejectionSetIsFixed() {
        assertEquals(6, Rejection.entries.size)
    }

    private fun normalised(raw: String): String {
        val result = InstanceAddress.normalise(raw)
        assertTrue(result is InstanceAddress.Result.Valid, result.toString())
        return result.normalised
    }

    private fun rejection(raw: String): Rejection {
        val result = InstanceAddress.normalise(raw)
        assertTrue(result is InstanceAddress.Result.Invalid, result.toString())
        return result.rejection
    }
}
