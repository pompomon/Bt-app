package com.github.pompomon.btapp

import com.github.pompomon.btapp.bluetooth.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStatusTest {
    @Test fun `confirmed connection has healthy status and accepts input`() {
        val state = ConnectionState.Connected("Office PC")

        assertEquals(
            ConnectionStatus("Connected to Office PC.", ConnectionStatusTone.Connected),
            connectionStatus(state)
        )
        assertTrue(state.showsInputControls)
        assertTrue(state.acceptsHidInput)
    }

    @Test fun `connection check keeps controls visible but blocks input`() {
        val state = ConnectionState.CheckingConnection("Office PC")

        assertEquals(
            ConnectionStatus("Checking connection to Office PC…", ConnectionStatusTone.Progress),
            connectionStatus(state)
        )
        assertTrue(state.showsInputControls)
        assertFalse(state.acceptsHidInput)
    }

    @Test fun `reconnect states keep controls visible but block input`() {
        val states = listOf(
            ConnectionState.Reconnecting("Office PC"),
            ConnectionState.ReconnectFailed("Office PC", "Connection lost. Retrying…"),
            ConnectionState.Disconnecting("Office PC")
        )

        states.forEach {
            assertTrue(it.showsInputControls)
            assertFalse(it.acceptsHidInput)
        }
    }

    @Test fun `failed report is shown as degraded and blocks input`() {
        val state = ConnectionState.Connected("Office PC", "Could not send the HID report.")

        assertEquals(
            ConnectionStatus("Could not send the HID report.", ConnectionStatusTone.Error),
            connectionStatus(state)
        )
        assertTrue(state.showsInputControls)
        assertFalse(state.acceptsHidInput)
    }

    @Test fun `registered but disconnected status is idle without input controls`() {
        val state = ConnectionState.Registered("Office PC")

        assertEquals(
            ConnectionStatus(
                "HID registered. Ready to reconnect to Office PC.",
                ConnectionStatusTone.Idle
            ),
            connectionStatus(state)
        )
        assertFalse(state.showsInputControls)
        assertFalse(state.acceptsHidInput)
    }
}
