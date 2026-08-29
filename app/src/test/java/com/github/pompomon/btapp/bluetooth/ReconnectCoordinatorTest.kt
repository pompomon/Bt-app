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

        assertEquals(ConnectionDecision.Accept, fixture.coordinator.onConnected(other))
        assertEquals(other, fixture.store.host)
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

    private class FakeStore(var host: RememberedHost?) : RememberedHostStore {
        override fun load(): RememberedHost? = host

        override fun save(host: RememberedHost) {
            this.host = host
        }

        override fun clear() {
            host = null
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
