package io.github.jellyforgedev.widgora.settings

import io.github.jellyforgedev.widgora.core.data.InstanceAddress
import io.github.jellyforgedev.widgora.core.data.InstanceConfig
import io.github.jellyforgedev.widgora.core.data.SecretState
import io.github.jellyforgedev.widgora.core.data.StorageWriteResult
import io.github.jellyforgedev.widgora.core.data.StoredInstance
import io.github.jellyforgedev.widgora.core.data.StoredInstances
import io.github.jellyforgedev.widgora.model.InstanceId
import io.github.jellyforgedev.widgora.model.WidgetDisplayState.Error.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class SettingsStateHolderTest {

    @Test
    fun withNothingStoredTheFormIsEmpty() = runBlocking {
        val holder = holder()

        holder.load()

        val state = holder.state.value
        assertFalse(state.loading)
        assertFalse(state.existing)
        assertEquals("", state.displayName)
        assertEquals("", state.address)
        assertEquals(StoredKey.NONE, state.storedKey)
    }

    /**
     * The rule that must not regress: a key saved earlier never reaches the state, not even
     * masked. The whole state is inspected, not just the input field.
     */
    @Test
    fun loadingFillsNameAndAddressButNeverTheKey() = runBlocking {
        val store = FakeInstanceStore()
        store.put(config())
        store.secret = SecretState.Present(STORED_KEY)
        val holder = holder(store)

        holder.load()

        val state = holder.state.value
        assertEquals("Uptime Kuma", state.displayName)
        assertEquals("https://kuma.example.invalid", state.address)
        assertEquals("", state.apiKeyInput)
        assertEquals(StoredKey.STORED, state.storedKey)
        assertTrue(state.existing)
        assertFalse(state.toString().contains(STORED_KEY), state.toString())
    }

    @Test
    fun loadingReportsAKeyThatCanNoLongerBeRead() = runBlocking {
        val store = FakeInstanceStore()
        store.put(config())
        store.secret = SecretState.Unreadable
        val holder = holder(store)

        holder.load()

        assertEquals(StoredKey.UNREADABLE, holder.state.value.storedKey)
    }

    @Test
    fun loadingReportsAStoredEntryThatIsNoLongerValid() = runBlocking {
        val store = FakeInstanceStore()
        store.stored.value = StoredInstances.Readable(
            listOf(
                StoredInstance.Invalid(
                    instanceId = InstanceId("kuma"),
                    displayName = "Uptime Kuma",
                    baseUrl = "http://insecure.invalid",
                ),
            ),
        )
        val holder = holder(store)

        holder.load()

        val state = holder.state.value
        assertTrue(state.storedEntryInvalid)
        assertTrue(state.existing)
        assertEquals("Uptime Kuma", state.displayName)
        assertEquals("http://insecure.invalid", state.address)
    }

    @Test
    fun savingNeedsADisplayName() = runBlocking {
        val holder = holder()
        holder.onAddressChange("https://kuma.example.invalid")
        holder.onApiKeyChange(ENTERED_KEY)
        holder.onDisplayNameChange("   ")

        assertFalse(holder.save())
        assertEquals(MissingField.DISPLAY_NAME, holder.state.value.missingField)
    }

    @Test
    fun savingSurfacesTheReasonAnAddressWasRefused() = runBlocking {
        val cases = mapOf(
            "http://kuma.example.invalid" to InstanceAddress.Rejection.NOT_HTTPS,
            "kuma.example.invalid" to InstanceAddress.Rejection.NOT_AN_ADDRESS,
            "https://user:pass@kuma.example.invalid" to
                InstanceAddress.Rejection.CREDENTIALS_IN_ADDRESS,
            "https://kuma.example.invalid?a=b" to InstanceAddress.Rejection.QUERY_NOT_ALLOWED,
            "https://kuma.example.invalid#top" to InstanceAddress.Rejection.FRAGMENT_NOT_ALLOWED,
        )
        for ((address, expected) in cases) {
            val holder = holder()
            holder.onDisplayNameChange("Uptime Kuma")
            holder.onApiKeyChange(ENTERED_KEY)
            holder.onAddressChange(address)

            assertFalse(holder.save(), address)
            assertEquals(expected, holder.state.value.addressRejection, address)
        }
    }

    @Test
    fun savingANewInstanceNeedsAKey() = runBlocking {
        val holder = holder()
        holder.onDisplayNameChange("Uptime Kuma")
        holder.onAddressChange("https://kuma.example.invalid")

        assertFalse(holder.save())
        assertEquals(MissingField.API_KEY, holder.state.value.missingField)
    }

    @Test
    fun savingStoresTheNormalisedAddressAndTheEnteredKey() = runBlocking {
        val store = FakeInstanceStore()
        val holder = holder(store)
        holder.onDisplayNameChange("  Uptime Kuma  ")
        holder.onAddressChange("https://kuma.example.invalid/")
        holder.onApiKeyChange(ENTERED_KEY)

        assertTrue(holder.save())

        assertEquals("https://kuma.example.invalid", store.savedConfig?.baseUrl)
        assertEquals("Uptime Kuma", store.savedConfig?.displayName)
        assertEquals(ENTERED_KEY, store.savedKey)
        assertEquals(true, store.savedConfig?.enabled)
    }

    @Test
    fun savingClearsTheKeyFieldAndReportsSuccess() = runBlocking {
        val holder = holder()
        holder.onDisplayNameChange("Uptime Kuma")
        holder.onAddressChange("https://kuma.example.invalid")
        holder.onApiKeyChange(ENTERED_KEY)

        holder.save()

        val state = holder.state.value
        assertEquals("", state.apiKeyInput)
        assertEquals(StoredKey.STORED, state.storedKey)
        assertTrue(state.savedJustNow)
    }

    /** An empty key field on an existing instance means "keep what is stored". */
    @Test
    fun anEmptyKeyFieldLeavesTheStoredKeyUntouched() = runBlocking {
        val store = FakeInstanceStore()
        store.put(config())
        store.secret = SecretState.Present(STORED_KEY)
        val holder = holder(store)
        holder.load()

        holder.onDisplayNameChange("Renamed")
        assertTrue(holder.save())

        assertNull(store.savedKey)
        assertEquals("Renamed", store.savedConfig?.displayName)
    }

    /**
     * A stored key belongs to the address it was entered for. Saving against a changed address
     * with an empty field must not quietly hand that key to a different server, so the field
     * becomes mandatory and says why.
     */
    @Test
    fun aChangedAddressMakesTheKeyFieldMandatoryWhenSaving() = runBlocking {
        val store = FakeInstanceStore()
        store.put(config())
        store.secret = SecretState.Present(STORED_KEY)
        val holder = holder(store)
        holder.load()

        holder.onAddressChange("https://someone-else.example.invalid")

        assertFalse(holder.save())
        assertEquals(
            MissingField.API_KEY_FOR_CHANGED_ADDRESS,
            holder.state.value.missingField,
        )
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun aChangedAddressIsNeverTestedWithTheStoredKey() = runBlocking {
        val store = FakeInstanceStore()
        store.put(config())
        store.secret = SecretState.Present(STORED_KEY)
        val tester = FakeTester(ConnectionTestResult.Reachable(1))
        val holder = holder(store, tester)
        holder.load()

        holder.onAddressChange("https://someone-else.example.invalid")
        holder.testConnection()

        assertEquals(emptyList(), tester.calls)
        assertEquals(
            MissingField.API_KEY_FOR_CHANGED_ADDRESS,
            holder.state.value.missingField,
        )
        assertNull(holder.state.value.testOutcome)
    }

    @Test
    fun aChangedAddressWithAFreshKeyIsSavedAndTested() = runBlocking {
        val store = FakeInstanceStore()
        store.put(config())
        store.secret = SecretState.Present(STORED_KEY)
        val tester = FakeTester(ConnectionTestResult.Reachable(1))
        val holder = holder(store, tester)
        holder.load()

        holder.onAddressChange("https://someone-else.example.invalid")
        holder.onApiKeyChange(ENTERED_KEY)
        holder.testConnection()
        assertEquals(listOf("https://someone-else.example.invalid" to ENTERED_KEY), tester.calls)

        assertTrue(holder.save())
        assertEquals("https://someone-else.example.invalid", store.savedConfig?.baseUrl)
        assertEquals(ENTERED_KEY, store.savedKey)
    }

    /** Only the normalised form is compared, so a trailing slash is not a different address. */
    @Test
    fun anAddressThatOnlyDiffersBeforeNormalisationStillUsesTheStoredKey() = runBlocking {
        val store = FakeInstanceStore()
        store.put(config())
        store.secret = SecretState.Present(STORED_KEY)
        val tester = FakeTester(ConnectionTestResult.Reachable(1))
        val holder = holder(store, tester)
        holder.load()

        holder.onAddressChange("https://kuma.example.invalid/")
        holder.testConnection()

        assertEquals(listOf("https://kuma.example.invalid" to STORED_KEY), tester.calls)
        assertNull(holder.state.value.missingField)
    }

    /**
     * After saving under a new address, the stored key belongs to that address, so an empty
     * field is allowed again.
     */
    @Test
    fun savingUnderANewAddressRebindsTheStoredKey() = runBlocking {
        val store = FakeInstanceStore()
        val holder = holder(store)
        holder.onDisplayNameChange("Uptime Kuma")
        holder.onAddressChange("https://kuma.example.invalid")
        holder.onApiKeyChange(ENTERED_KEY)
        holder.save()

        holder.onDisplayNameChange("Renamed")

        assertTrue(holder.save())
        assertNull(store.savedKey)
    }

    /** With an unreadable key, "keep what is stored" is not an option. */
    @Test
    fun anUnreadableKeyMustBeReplacedBeforeSaving() = runBlocking {
        val store = FakeInstanceStore()
        store.put(config())
        store.secret = SecretState.Unreadable
        val holder = holder(store)
        holder.load()

        assertFalse(holder.save())
        assertEquals(MissingField.API_KEY, holder.state.value.missingField)
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun theConnectionTestUsesTheEnteredValuesAndSavesNothing() = runBlocking {
        val store = FakeInstanceStore()
        val tester = FakeTester(ConnectionTestResult.Reachable(monitorCount = 35))
        val holder = holder(store, tester)
        holder.onDisplayNameChange("Uptime Kuma")
        holder.onAddressChange("https://kuma.example.invalid/")
        holder.onApiKeyChange(ENTERED_KEY)

        holder.testConnection()

        assertEquals(listOf("https://kuma.example.invalid" to ENTERED_KEY), tester.calls)
        assertEquals(0, store.saveCalls)
        assertEquals(ConnectionTestResult.Reachable(35), holder.state.value.testOutcome)
        assertFalse(holder.state.value.testing)
    }

    @Test
    fun theConnectionTestFallsBackToTheStoredKey() = runBlocking {
        val store = FakeInstanceStore()
        store.put(config())
        store.secret = SecretState.Present(STORED_KEY)
        val tester = FakeTester(ConnectionTestResult.Reachable(1))
        val holder = holder(store, tester)
        holder.load()

        holder.testConnection()

        assertEquals(listOf("https://kuma.example.invalid" to STORED_KEY), tester.calls)
        assertFalse(holder.state.value.toString().contains(STORED_KEY))
    }

    @Test
    fun theConnectionTestReportsEveryOutcome() = runBlocking {
        val outcomes = listOf(
            ConnectionTestResult.Reachable(3),
            ConnectionTestResult.Failed(Reason.UNAUTHORIZED),
            ConnectionTestResult.Failed(Reason.ENDPOINT_NOT_FOUND),
            ConnectionTestResult.Failed(Reason.UNREACHABLE),
            ConnectionTestResult.Failed(Reason.MALFORMED_RESPONSE),
            ConnectionTestResult.Failed(Reason.OVERSIZED),
            ConnectionTestResult.Failed(Reason.UNKNOWN),
            ConnectionTestResult.AddressRejected,
        )
        for (outcome in outcomes) {
            val holder = holder(tester = FakeTester(outcome))
            holder.onAddressChange("https://kuma.example.invalid")
            holder.onApiKeyChange(ENTERED_KEY)

            holder.testConnection()

            assertEquals(outcome, holder.state.value.testOutcome)
        }
    }

    @Test
    fun theConnectionTestRefusesAnInvalidAddressWithoutAsking() = runBlocking {
        val tester = FakeTester(ConnectionTestResult.Reachable(1))
        val holder = holder(tester = tester)
        holder.onAddressChange("http://kuma.example.invalid")
        holder.onApiKeyChange(ENTERED_KEY)

        holder.testConnection()

        assertEquals(InstanceAddress.Rejection.NOT_HTTPS, holder.state.value.addressRejection)
        assertEquals(emptyList(), tester.calls)
        assertNull(holder.state.value.testOutcome)
    }

    @Test
    fun theConnectionTestNeedsAKey() = runBlocking {
        val tester = FakeTester(ConnectionTestResult.Reachable(1))
        val holder = holder(tester = tester)
        holder.onAddressChange("https://kuma.example.invalid")

        holder.testConnection()

        assertEquals(MissingField.API_KEY, holder.state.value.missingField)
        assertEquals(emptyList(), tester.calls)
    }

    @Test
    fun changingTheAddressDropsAnEarlierVerdict() = runBlocking {
        val holder = holder(tester = FakeTester(ConnectionTestResult.Reachable(2)))
        holder.onAddressChange("https://kuma.example.invalid")
        holder.onApiKeyChange(ENTERED_KEY)
        holder.testConnection()

        holder.onAddressChange("https://other.example.invalid")

        assertNull(holder.state.value.testOutcome)
    }

    @Test
    fun deletingAsksForConfirmationFirst() = runBlocking {
        val store = FakeInstanceStore()
        store.put(config())
        val holder = holder(store)
        holder.load()

        holder.requestDelete()
        assertTrue(holder.state.value.confirmingDelete)
        assertEquals(emptyList(), store.deleted)

        holder.cancelDelete()
        assertFalse(holder.state.value.confirmingDelete)
        assertEquals(emptyList(), store.deleted)
    }

    @Test
    fun confirmingDeleteRemovesTheInstanceAndEmptiesTheForm() = runBlocking {
        val store = FakeInstanceStore()
        store.put(config())
        store.secret = SecretState.Present(STORED_KEY)
        val holder = holder(store)
        holder.load()
        holder.requestDelete()

        holder.confirmDelete()

        assertEquals(listOf(InstanceId("kuma")), store.deleted)
        val state = holder.state.value
        assertFalse(state.existing)
        assertFalse(state.confirmingDelete)
        assertEquals("", state.displayName)
        assertEquals("", state.address)
        assertEquals(StoredKey.NONE, state.storedKey)
    }

    // --- A configuration that cannot be read --------------------------------------------------

    /**
     * An unreadable file is said out loud, with an empty form and the way out beside it.
     *
     * Not claimed to be an existing instance: nothing is known about what is stored, not even an
     * identifier, so offering a delete would be offering to delete something unnamed. What is
     * offered instead is the reset, which is the only honest action over storage nobody can read.
     */
    @Test
    fun anUnreadableConfigurationIsReportedAndNothingIsInvented() = runBlocking {
        val store = FakeInstanceStore()
        store.stored.value = StoredInstances.Unreadable
        val holder = holder(store)

        holder.load()

        val state = holder.state.value
        assertTrue(state.configurationUnreadable)
        assertFalse(state.existing)
        assertFalse(state.storedEntryInvalid)
        assertEquals("", state.displayName)
        assertEquals("", state.address)
        // Whether a key is stored is part of what could not be read, so nothing is claimed.
        assertEquals(StoredKey.NONE, state.storedKey)
    }

    @Test
    fun anEmptyConfigurationIsNotAnUnreadableOne() = runBlocking {
        val holder = holder()

        holder.load()

        assertFalse(holder.state.value.configurationUnreadable)
    }

    /** Entering the instance again is the other way out, and it clears the notice. */
    @Test
    fun savingOverAnUnreadableConfigurationClearsTheNotice() = runBlocking {
        val store = FakeInstanceStore()
        store.stored.value = StoredInstances.Unreadable
        val holder = holder(store)
        holder.load()

        holder.onDisplayNameChange("Uptime Kuma")
        holder.onAddressChange("https://kuma.example.invalid")
        holder.onApiKeyChange(ENTERED_KEY)

        assertTrue(holder.save())
        val state = holder.state.value
        assertFalse(state.configurationUnreadable)
        assertTrue(state.savedJustNow)
    }

    // --- Writes that do not happen ------------------------------------------------------------

    /**
     * The crash this replaces, seen from the screen.
     *
     * Saving over a damaged file used to raise out of a coroutine nobody was watching. Now it is
     * a message, and nothing on the screen pretends the values were kept — in particular the key
     * field keeps what was typed, because asking for it again would be the user's reward for a
     * failure that was not theirs.
     */
    @Test
    fun aFailedSaveIsReportedAndClaimsNothing() = runBlocking {
        val store = FakeInstanceStore()
        store.writesFail = true
        val holder = holder(store)
        holder.onDisplayNameChange("Uptime Kuma")
        holder.onAddressChange("https://kuma.example.invalid")
        holder.onApiKeyChange(ENTERED_KEY)

        assertFalse(holder.save())

        val state = holder.state.value
        assertEquals(FailedWrite.SAVE, state.failedWrite)
        assertFalse(state.savedJustNow)
        assertEquals(ENTERED_KEY, state.apiKeyInput)
        assertEquals(StoredKey.NONE, state.storedKey)
        assertEquals(1, store.saveCalls)
    }

    @Test
    fun aFailedDeleteLeavesTheInstanceOnTheScreen() = runBlocking {
        val store = FakeInstanceStore()
        store.put(config())
        store.secret = SecretState.Present(STORED_KEY)
        val holder = holder(store)
        holder.load()
        holder.requestDelete()
        store.writesFail = true

        holder.confirmDelete()

        val state = holder.state.value
        assertEquals(FailedWrite.DELETE, state.failedWrite)
        assertFalse(state.confirmingDelete)
        assertTrue(state.existing)
        assertEquals("Uptime Kuma", state.displayName)
        assertEquals(emptyList(), store.deleted)
    }

    /** A verdict about an earlier attempt must not sit beside values the user is changing. */
    @Test
    fun typingClearsTheReportOfAFailedWrite() = runBlocking {
        val store = FakeInstanceStore()
        store.writesFail = true
        val holder = holder(store)
        holder.onDisplayNameChange("Uptime Kuma")
        holder.onAddressChange("https://kuma.example.invalid")
        holder.onApiKeyChange(ENTERED_KEY)
        holder.save()
        assertEquals(FailedWrite.SAVE, holder.state.value.failedWrite)

        holder.onDisplayNameChange("Uptime Kuma ")

        assertNull(holder.state.value.failedWrite)
    }

    // --- The reset ----------------------------------------------------------------------------

    /** Never automatic: it takes two deliberate actions, like deleting. */
    @Test
    fun resettingAsksForConfirmationFirst() = runBlocking {
        val store = FakeInstanceStore()
        store.stored.value = StoredInstances.Unreadable
        val holder = holder(store)
        holder.load()

        holder.requestReset()
        assertTrue(holder.state.value.confirmingReset)
        assertEquals(0, store.resetCalls)

        holder.cancelReset()
        assertFalse(holder.state.value.confirmingReset)
        assertEquals(0, store.resetCalls)
    }

    @Test
    fun confirmingResetEmptiesEverythingAndSaysSo() = runBlocking {
        val store = FakeInstanceStore()
        store.stored.value = StoredInstances.Unreadable
        val holder = holder(store)
        holder.load()
        holder.requestReset()

        holder.confirmReset()

        assertEquals(1, store.resetCalls)
        val state = holder.state.value
        assertTrue(state.resetJustNow)
        assertFalse(state.confirmingReset)
        // Nothing is stored any more, so nothing is unreadable any more either.
        assertFalse(state.configurationUnreadable)
        assertFalse(state.existing)
        assertEquals("", state.address)
        assertEquals(StoredKey.NONE, state.storedKey)
        assertNull(state.failedWrite)
    }

    @Test
    fun aFailedResetIsReportedAndNotMistakenForASuccess() = runBlocking {
        val store = FakeInstanceStore()
        store.stored.value = StoredInstances.Unreadable
        store.resetFails = true
        val holder = holder(store)
        holder.load()
        holder.requestReset()

        holder.confirmReset()

        val state = holder.state.value
        assertEquals(FailedWrite.RESET, state.failedWrite)
        assertFalse(state.resetJustNow)
        assertFalse(state.confirmingReset)
        // Still unreadable, because nothing was removed.
        assertTrue(state.configurationUnreadable)
    }

    private fun holder(
        store: InstanceStore = FakeInstanceStore(),
        tester: ConnectionTester = FakeTester(ConnectionTestResult.Reachable(0)),
    ): SettingsStateHolder = SettingsStateHolder(store, tester)

    private fun config(): InstanceConfig = InstanceConfig(
        instanceId = InstanceId("kuma"),
        displayName = "Uptime Kuma",
        baseUrl = "https://kuma.example.invalid",
        enabled = true,
    )

    private class FakeInstanceStore : InstanceStore {

        val stored = MutableStateFlow<StoredInstances>(StoredInstances.Readable(emptyList()))
        var secret: SecretState = SecretState.Missing
        var savedConfig: InstanceConfig? = null
        var savedKey: String? = null
        var saveCalls = 0
        val deleted = mutableListOf<InstanceId>()
        var resetCalls = 0

        /** Makes saving and deleting fail, the way an unwritable file does. */
        var writesFail = false

        /** Makes the reset itself fail, which is a different situation from a failed save. */
        var resetFails = false

        fun put(config: InstanceConfig) {
            stored.value = StoredInstances.Readable(listOf(StoredInstance.Valid(config)))
        }

        override val instances: Flow<StoredInstances> get() = stored

        override suspend fun secretOf(instanceId: InstanceId): SecretState = secret

        override suspend fun save(config: InstanceConfig, apiKey: String?): StorageWriteResult {
            // Counted before the outcome is decided: a refused write is still an attempt that
            // reached storage, and a test about validation has to be able to tell the two apart.
            saveCalls++
            if (writesFail) return StorageWriteResult.FAILED
            savedConfig = config
            savedKey = apiKey
            put(config)
            if (apiKey != null) secret = SecretState.Present(apiKey)
            return StorageWriteResult.DONE
        }

        override suspend fun delete(instanceId: InstanceId): StorageWriteResult {
            if (writesFail) return StorageWriteResult.FAILED
            deleted.add(instanceId)
            stored.value = StoredInstances.Readable(emptyList())
            secret = SecretState.Missing
            return StorageWriteResult.DONE
        }

        override suspend fun reset(): StorageWriteResult {
            resetCalls++
            if (resetFails) return StorageWriteResult.FAILED
            stored.value = StoredInstances.Readable(emptyList())
            secret = SecretState.Missing
            return StorageWriteResult.DONE
        }
    }

    private class FakeTester(private val result: ConnectionTestResult) : ConnectionTester {

        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun test(baseUrl: String, apiKey: String): ConnectionTestResult {
            calls.add(baseUrl to apiKey)
            return result
        }
    }

    private companion object {
        const val STORED_KEY = "uk1_stored_secret"
        const val ENTERED_KEY = "uk2_entered_secret"
    }
}
