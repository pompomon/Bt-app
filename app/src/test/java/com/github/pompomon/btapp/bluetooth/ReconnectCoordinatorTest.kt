package com.github.pompomon.btapp.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectCoordinatorTest {
    private val remembered = RememberedHost("AA:BB:CC:DD:EE:01", "Office PC")
    private val other = RememberedHost("AA:BB:CC:DD:EE:02", "Other PC")

    @Test fun `saved bonded host is selected after HID registration`() {
        val fixture = Fixture(remembered)

        assertEquals(
            listOf(ReconnectAction.RegisterHid),
            fixture.coordinator.onForeground(true, listOf(remembered, other))
        )
        assertEquals(
            listOf(ReconnectAction.Connect(remembered)),
            fixture.coordinator.onRegistrationSucceeded(listOf(remembered, other))
        )
    }

    @Test fun `stale saved host is cleared when it is no longer bonded`() {
        val fixture = Fixture(remembered)

        assertEquals(
            listOf(ReconnectAction.RememberedHostUnavailable("Office PC")),
            fixture.coordinator.onForeground(true, listOf(other))
        )
        assertNull(fixture.store.host)
    }

    @Test fun `malformed saved address is cleared`() {
        val malformed = RememberedHost("not-an-address", "Office PC")
        val fixture = Fixture(malformed)

        assertEquals(
            listOf(ReconnectAction.RememberedHostUnavailable("Office PC")),
            fixture.coordinator.onForeground(true, listOf(remembered))
        )
        assertNull(fixture.store.host)
    }

    @Test fun `reconnect waits for registration and suppresses duplicate requests`() {
        val fixture = Fixture(remembered)

        val beforeRegistration = fixture.coordinator.onForeground(true, listOf(remembered))
        val firstRegistered = fixture.coordinator.onRegistrationSucceeded(listOf(remembered))
        val duplicateRegistered = fixture.coordinator.onRegistrationSucceeded(listOf(remembered))

        assertEquals(listOf(ReconnectAction.RegisterHid), beforeRegistration)
        assertEquals(listOf(ReconnectAction.Connect(remembered)), firstRegistered)
        assertTrue(duplicateRegistered.isEmpty())
    }

    @Test fun `successful connection persists host and resets retry backoff`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnectionRequestFailed()
        assertEquals(listOf(1_000L), fixture.scheduler.delays)

        assertEquals(ConnectionDecision.Accept, fixture.coordinator.onConnected(remembered))
        assertEquals(remembered, fixture.store.host)
        assertFalse(fixture.scheduler.hasPendingTask)

        assertEquals(ReconnectDisposition.RetryScheduled, fixture.coordinator.onConnectionLost())
        assertEquals(listOf(1_000L, 1_000L), fixture.scheduler.delays)
    }

    @Test fun `unexpected disconnect schedules a reconnect while foregrounded`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)

        assertEquals(ReconnectDisposition.RetryScheduled, fixture.coordinator.onConnectionLost())
        fixture.scheduler.runPending()
        assertEquals(listOf(ReconnectAction.Retry), fixture.asyncActions.single())

        assertEquals(
            listOf(ReconnectAction.Connect(remembered)),
            fixture.coordinator.onRetry(true, listOf(remembered))
        )
    }

    @Test fun `backgrounding cancels retries until the next foreground entry`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)
        fixture.coordinator.onConnectionLost()

        fixture.coordinator.onBackground()

        assertFalse(fixture.scheduler.hasPendingTask)
        assertEquals(
            listOf(ReconnectAction.Connect(remembered)),
            fixture.coordinator.onForeground(true, listOf(remembered))
        )
    }

    @Test fun `foreground validation can confirm an existing connection without reconnecting`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)
        fixture.coordinator.onBackground()

        assertTrue(fixture.coordinator.onForeground(true, listOf(remembered)).isEmpty())
        assertEquals(ConnectionDecision.Accept, fixture.coordinator.onConnected(remembered))
        assertFalse(fixture.scheduler.hasPendingTask)
    }

    @Test fun `failed foreground validation enters the existing retry flow`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)
        fixture.coordinator.onBackground()
        fixture.coordinator.onForeground(true, listOf(remembered))

        assertEquals(ReconnectDisposition.RetryScheduled, fixture.coordinator.onConnectionLost())
        fixture.scheduler.runPending()
        assertEquals(listOf(ReconnectAction.Retry), fixture.asyncActions.single())
        assertEquals(
            listOf(ReconnectAction.Connect(remembered)),
            fixture.coordinator.onRetry(true, listOf(remembered))
        )
    }

    @Test fun `backgrounding cancels a reconnect observed during foreground validation`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)
        fixture.coordinator.onBackground()
        fixture.coordinator.onForeground(true, listOf(remembered))
        fixture.coordinator.onReconnectInProgress()

        assertTrue(fixture.coordinator.onBackground())
    }

    @Test fun `backgrounding cancels an in-flight automatic reconnect`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()

        assertTrue(fixture.coordinator.onBackground())
        assertEquals(ConnectionDecision.Disconnect, fixture.coordinator.onConnected(remembered))
    }

    @Test fun `failed background cancellation allows reconnect after returning to foreground`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()

        assertTrue(fixture.coordinator.onBackground())
        fixture.coordinator.onConnectionBlocked()

        assertEquals(
            listOf(ReconnectAction.Connect(remembered)),
            fixture.coordinator.onForeground(true, listOf(remembered))
        )
    }

    @Test fun `unavailable Bluetooth prerequisites cancel pending retry`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)
        fixture.coordinator.onConnectionLost()

        fixture.coordinator.onPrerequisitesUnavailable()

        assertFalse(fixture.scheduler.hasPendingTask)
        assertTrue(fixture.asyncActions.isEmpty())
    }

    @Test fun `registration completing in background waits for foreground`() {
        val fixture = Fixture(remembered)
        fixture.coordinator.onForeground(true, listOf(remembered))
        fixture.coordinator.onBackground()

        assertTrue(fixture.coordinator.onRegistrationSucceeded(listOf(remembered)).isEmpty())
        assertEquals(
            listOf(ReconnectAction.Connect(remembered)),
            fixture.coordinator.onForeground(true, listOf(remembered))
        )
    }

    @Test fun `manual disconnect retains host and suppresses reconnect`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)

        fixture.coordinator.onManualDisconnect()

        assertEquals(ReconnectDisposition.Idle, fixture.coordinator.onConnectionRequestFailed())
        assertTrue(fixture.coordinator.onForeground(true, listOf(remembered)).isEmpty())
        assertEquals(ReconnectDisposition.Idle, fixture.coordinator.onConnectionLost())
        assertEquals(remembered, fixture.store.host)
        assertFalse(fixture.scheduler.hasPendingTask)
    }

    @Test fun `automatic retries stop after the configured limit`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()

        repeat(3) {
            assertEquals(ReconnectDisposition.RetryScheduled, fixture.coordinator.onConnectionRequestFailed())
            fixture.scheduler.runPending()
            fixture.coordinator.onRetry(true, listOf(remembered))
        }

        assertEquals(ReconnectDisposition.Exhausted, fixture.coordinator.onConnectionRequestFailed())
        assertEquals(listOf(1_000L, 3_000L, 10_000L), fixture.scheduler.delays)
        assertFalse(fixture.scheduler.hasPendingTask)
    }

    @Test fun `pairing requests discoverability only after registration`() {
        val fixture = Fixture()
        fixture.coordinator.onForeground(true, emptyList())

        assertEquals(
            listOf(ReconnectAction.RegisterHid),
            fixture.coordinator.onPairRequested(true)
        )
        assertEquals(
            listOf(ReconnectAction.RequestDiscoverability),
            fixture.coordinator.onRegistrationSucceeded(emptyList())
        )
        assertTrue(fixture.coordinator.onRegistrationSucceeded(emptyList()).isEmpty())
    }

    @Test fun `permission request preserves explicit pairing intent`() {
        val fixture = Fixture(remembered)
        fixture.coordinator.onForeground(false, null)
        fixture.coordinator.onPairRequested(false)

        fixture.coordinator.prepareForPermissionRequest()

        assertEquals(
            listOf(ReconnectAction.RegisterHid),
            fixture.coordinator.onPrerequisitesAvailable(listOf(remembered))
        )
        assertEquals(
            listOf(ReconnectAction.RequestDiscoverability),
            fixture.coordinator.onRegistrationSucceeded(listOf(remembered))
        )
    }

    @Test fun `failed pairing attempt remains awaiting another host`() {
        val fixture = Fixture()
        fixture.coordinator.onForeground(true, emptyList())
        fixture.coordinator.onPairRequested(true)
        fixture.coordinator.onRegistrationSucceeded(emptyList())
        fixture.coordinator.onConnectionRequested()

        assertEquals(ReconnectDisposition.Idle, fixture.coordinator.onConnectionLost())
        assertFalse(fixture.coordinator.onBackground())
        assertEquals(ConnectionDecision.Accept, fixture.coordinator.onConnected(other))
        assertEquals(other, fixture.store.host)
    }

    @Test fun `cancelled pairing window rejects later host connections`() {
        val fixture = Fixture()
        fixture.coordinator.onForeground(true, emptyList())
        fixture.coordinator.onPairRequested(true)
        fixture.coordinator.onRegistrationSucceeded(emptyList())

        assertTrue(fixture.coordinator.onPairingWindowClosed(emptyList()).isEmpty())
        assertEquals(ConnectionDecision.Disconnect, fixture.coordinator.onConnected(other))
        assertNull(fixture.store.host)
    }

    @Test fun `closed pairing window resumes reconnecting remembered host`() {
        val fixture = Fixture(remembered)
        fixture.coordinator.onForeground(true, listOf(remembered))
        fixture.coordinator.onPairRequested(true)
        fixture.coordinator.onRegistrationSucceeded(listOf(remembered))

        assertEquals(
            listOf(ReconnectAction.Connect(remembered)),
            fixture.coordinator.onPairingWindowClosed(listOf(remembered))
        )
    }

    @Test fun `pairing another host retains both hosts and selects the new host`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)

        assertEquals(
            listOf(ReconnectAction.RequestDiscoverability),
            fixture.coordinator.onPairRequested(true)
        )
        assertEquals(ConnectionDecision.Accept, fixture.coordinator.onConnected(other))

        assertEquals(other, fixture.store.host)
        assertEquals(setOf(remembered, other), fixture.store.loadAll().toSet())
    }

    @Test fun `switching hosts disconnects the active host before connecting the target`() {
        val fixture = Fixture(remembered)
        fixture.store.save(other)
        fixture.store.select(remembered.address)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)

        assertEquals(
            listOf(ReconnectAction.DisconnectCurrent),
            fixture.coordinator.selectHost(
                other.address,
                remembered.address,
                prerequisitesAvailable = true,
                bondedHosts = listOf(remembered, other)
            )
        )
        assertEquals(
            listOf(ReconnectAction.Connect(other)),
            fixture.coordinator.onSwitchDisconnected(listOf(remembered, other))
        )
        assertEquals(ConnectionDecision.Accept, fixture.coordinator.onConnected(other))
        assertEquals(other, fixture.store.host)
    }

    @Test fun `switching to a stale host removes only that host`() {
        val fixture = Fixture(remembered)
        fixture.store.save(other)
        fixture.store.select(remembered.address)

        assertEquals(
            listOf(ReconnectAction.RememberedHostUnavailable("Other PC")),
            fixture.coordinator.selectHost(
                other.address,
                remembered.address,
                prerequisitesAvailable = true,
                bondedHosts = listOf(remembered)
            )
        )
        assertEquals(listOf(remembered), fixture.store.loadAll())
    }

    @Test fun `stale switch target keeps the active host connected`() {
        val fixture = Fixture(remembered)
        fixture.store.save(other)
        fixture.store.select(remembered.address)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)

        assertTrue(
            fixture.coordinator.selectHost(
                other.address,
                remembered.address,
                prerequisitesAvailable = true,
                bondedHosts = listOf(remembered)
            ).isEmpty()
        )
        assertEquals(remembered, fixture.store.host)
        assertEquals(listOf(remembered), fixture.store.loadAll())
    }

    @Test fun `forgetting the selected host retains other remembered hosts`() {
        val fixture = Fixture(remembered)
        fixture.store.save(other)

        fixture.coordinator.forgetRememberedHost()

        assertEquals(listOf(remembered), fixture.store.loadAll())
        assertEquals(remembered, fixture.store.host)
    }

    @Test fun `selecting the active host does not reconnect`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)

        assertTrue(
            fixture.coordinator.selectHost(
                remembered.address,
                remembered.address,
                prerequisitesAvailable = true,
                bondedHosts = listOf(remembered)
            ).isEmpty()
        )
    }

    @Test fun `switching back during disconnect reconnects the original host`() {
        val fixture = Fixture(remembered)
        fixture.store.save(other)
        fixture.store.select(remembered.address)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)
        fixture.coordinator.selectHost(
            other.address,
            remembered.address,
            prerequisitesAvailable = true,
            bondedHosts = listOf(remembered, other)
        )

        assertTrue(
            fixture.coordinator.selectHost(
                remembered.address,
                remembered.address,
                prerequisitesAvailable = true,
                bondedHosts = listOf(remembered, other)
            ).isEmpty()
        )
        assertEquals(
            listOf(ReconnectAction.Connect(remembered)),
            fixture.coordinator.onSwitchDisconnected(listOf(remembered, other))
        )
    }

    @Test fun `lost connection during a switch reconnects immediately`() {
        val fixture = Fixture(remembered)
        fixture.store.save(other)
        fixture.store.select(remembered.address)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)
        fixture.coordinator.selectHost(
            other.address,
            remembered.address,
            prerequisitesAvailable = true,
            bondedHosts = listOf(remembered, other)
        )

        assertEquals(ReconnectDisposition.ReconnectNow, fixture.coordinator.onConnectionLost())
        assertEquals(
            listOf(ReconnectAction.Connect(other)),
            fixture.coordinator.onPrerequisitesAvailable(listOf(remembered, other))
        )
    }

    @Test fun `unexpected host is rejected outside the pairing window`() {
        val fixture = Fixture(remembered)
        fixture.connectRememberedHost()

        assertEquals(ConnectionDecision.Disconnect, fixture.coordinator.onConnected(other))
        assertEquals(remembered, fixture.store.host)
    }

    @Test fun `host switch resumes after returning to foreground`() {
        val fixture = Fixture(remembered)
        fixture.store.save(other)
        fixture.store.select(remembered.address)
        fixture.connectRememberedHost()
        fixture.coordinator.onConnected(remembered)
        fixture.coordinator.selectHost(
            other.address,
            remembered.address,
            prerequisitesAvailable = true,
            bondedHosts = listOf(remembered, other)
        )

        fixture.coordinator.onBackground()
        assertTrue(fixture.coordinator.onSwitchDisconnected(listOf(remembered, other)).isEmpty())
        assertEquals(
            listOf(ReconnectAction.Connect(other)),
            fixture.coordinator.onForeground(true, listOf(remembered, other))
        )
    }

    private class Fixture(initialHost: RememberedHost? = null) {
        val store = FakeStore(initialHost)
        val scheduler = FakeScheduler()
        val asyncActions = mutableListOf<List<ReconnectAction>>()
        val coordinator = ReconnectCoordinator(
            store,
            scheduler,
            asyncActions::add,
            retryDelaysMillis = listOf(1_000L, 3_000L, 10_000L)
        )

        fun connectRememberedHost() {
            coordinator.onForeground(true, listOfNotNull(store.host))
            coordinator.onRegistrationSucceeded(listOfNotNull(store.host))
        }
    }

    private class FakeStore(initialHost: RememberedHost?) : RememberedHostStore {
        private val hosts = mutableListOf<RememberedHost>()
        private var selectedAddress: String? = null
        val host: RememberedHost? get() = load()

        init {
            initialHost?.let(::save)
        }

        override fun load(): RememberedHost? =
            hosts.firstOrNull { it.address == selectedAddress } ?: hosts.firstOrNull()

        override fun loadAll(): List<RememberedHost> = hosts.toList()

        override fun save(host: RememberedHost) {
            hosts.removeAll { it.address == host.address }
            hosts += host
            selectedAddress = host.address
        }

        override fun select(address: String): RememberedHost? {
            val host = hosts.firstOrNull { it.address == address } ?: return null
            selectedAddress = host.address
            return host
        }

        override fun remove(address: String) {
            hosts.removeAll { it.address == address }
            if (selectedAddress == address) selectedAddress = hosts.firstOrNull()?.address
        }

        override fun clear() {
            hosts.clear()
            selectedAddress = null
        }
    }

    private class FakeScheduler : ReconnectScheduler {
        val delays = mutableListOf<Long>()
        private var pendingTask: (() -> Unit)? = null
        val hasPendingTask: Boolean get() = pendingTask != null

        override fun schedule(delayMillis: Long, task: () -> Unit) {
            delays += delayMillis
            pendingTask = task
        }

        override fun cancel() {
            pendingTask = null
        }

        fun runPending() {
            val task = pendingTask
            pendingTask = null
            task?.invoke()
        }
    }
}
