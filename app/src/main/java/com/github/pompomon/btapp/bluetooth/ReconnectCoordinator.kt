package com.github.pompomon.btapp.bluetooth

internal interface ReconnectScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit)
    fun cancel()
}

internal sealed interface ReconnectAction {
    data object RegisterHid : ReconnectAction
    data class Connect(val host: RememberedHost) : ReconnectAction
    data object RequestDiscoverability : ReconnectAction
    data class RememberedHostUnavailable(val deviceName: String) : ReconnectAction
    data object Retry : ReconnectAction
}

internal enum class ConnectionDecision {
    Accept,
    Disconnect
}

internal enum class ReconnectDisposition {
    Idle,
    RetryScheduled,
    Exhausted
}

internal class ReconnectCoordinator(
    private val hostStore: RememberedHostStore,
    private val scheduler: ReconnectScheduler,
    private val onAsyncActions: (List<ReconnectAction>) -> Unit,
    private val maxRetries: Int = 3,
    private val retryDelaysMillis: List<Long> = listOf(1_000L, 3_000L, 10_000L)
) {
    private enum class Intent {
        None,
        Reconnect,
        Pair,
        AwaitingPair
    }

    private var intent = Intent.None
    private var foreground = false
    private var registered = false
    private var registrationRequested = false
    private var connectionRequested = false
    private var reconnectSuppressed = false
    private var connectedAddress: String? = null
    private var retryCount = 0
    private var retryScheduled = false

    fun onForeground(
        prerequisitesAvailable: Boolean,
        bondedHosts: Collection<RememberedHost>?
    ): List<ReconnectAction> {
        val enteringForeground = !foreground
        foreground = true
        if (enteringForeground) retryCount = 0
        if (!reconnectSuppressed && intent == Intent.None && hostStore.load() != null) {
            intent = Intent.Reconnect
        }
        return if (prerequisitesAvailable) advance(bondedHosts) else emptyList()
    }

    fun onBackground(): Boolean {
        val cancelConnection = connectionRequested && intent == Intent.Reconnect
        foreground = false
        cancelRetry()
        return cancelConnection
    }

    fun onPrerequisitesUnavailable() {
        connectionRequested = false
        cancelRetry()
    }

    fun onPrerequisitesAvailable(bondedHosts: Collection<RememberedHost>?): List<ReconnectAction> =
        if (foreground) advance(bondedHosts) else emptyList()

    fun prepareForPermissionRequest() {
        if (intent != Intent.None) return
        if (hostStore.load() == null) {
            onPairRequested(false)
        } else {
            onManualReconnect(false, null)
        }
    }

    fun onPairRequested(prerequisitesAvailable: Boolean): List<ReconnectAction> {
        reconnectSuppressed = true
        intent = Intent.Pair
        retryCount = 0
        cancelRetry()
        return if (prerequisitesAvailable) advance(emptyList()) else emptyList()
    }

    fun onPairingWindowClosed(bondedHosts: Collection<RememberedHost>?): List<ReconnectAction> {
        if (intent != Intent.AwaitingPair) return emptyList()
        val hasRememberedHost = hostStore.load() != null
        reconnectSuppressed = !hasRememberedHost
        intent = if (hasRememberedHost) Intent.Reconnect else Intent.None
        return advance(bondedHosts)
    }

    fun onManualReconnect(
        prerequisitesAvailable: Boolean,
        bondedHosts: Collection<RememberedHost>?
    ): List<ReconnectAction> {
        reconnectSuppressed = false
        intent = Intent.Reconnect
        retryCount = 0
        cancelRetry()
        return if (prerequisitesAvailable) advance(bondedHosts) else emptyList()
    }

    fun onManualDisconnect() {
        reconnectSuppressed = true
        intent = Intent.None
        retryCount = 0
        cancelRetry()
    }

    fun forgetRememberedHost() {
        hostStore.clear()
        reconnectSuppressed = true
        intent = Intent.None
        connectionRequested = false
        retryCount = 0
        cancelRetry()
    }

    fun onRegistrationSucceeded(bondedHosts: Collection<RememberedHost>?): List<ReconnectAction> {
        registered = true
        registrationRequested = false
        return advance(bondedHosts)
    }

    fun onRegistrationFailed() {
        registrationRequested = false
    }

    fun onRegistrationLost() {
        registered = false
        registrationRequested = false
        connectionRequested = false
        connectedAddress = null
    }

    fun onConnectionRequested() {
        connectionRequested = true
    }

    fun onConnectionBlocked() {
        connectionRequested = false
        cancelRetry()
    }

    fun onConnectionRequestFailed(): ReconnectDisposition {
        connectionRequested = false
        if (reconnectSuppressed || !foreground || hostStore.load() == null) {
            return ReconnectDisposition.Idle
        }
        intent = Intent.Reconnect
        return scheduleRetry()
    }

    fun onConnectionLost(): ReconnectDisposition {
        connectionRequested = false
        connectedAddress = null
        if (reconnectSuppressed || !foreground || hostStore.load() == null) {
            if (intent != Intent.AwaitingPair) intent = Intent.None
            return ReconnectDisposition.Idle
        }
        intent = Intent.Reconnect
        return scheduleRetry()
    }

    fun onConnected(host: RememberedHost): ConnectionDecision {
        registered = true
        registrationRequested = false
        connectionRequested = false
        cancelRetry()
        if ((reconnectSuppressed || !foreground) && intent != Intent.AwaitingPair) {
            return ConnectionDecision.Disconnect
        }
        connectedAddress = normalizeBluetoothAddress(host.address)
        hostStore.save(host)
        reconnectSuppressed = false
        intent = Intent.None
        retryCount = 0
        return ConnectionDecision.Accept
    }

    fun onRetry(
        prerequisitesAvailable: Boolean,
        bondedHosts: Collection<RememberedHost>?
    ): List<ReconnectAction> {
        if (!foreground || reconnectSuppressed || hostStore.load() == null) return emptyList()
        intent = Intent.Reconnect
        return if (prerequisitesAvailable) advance(bondedHosts) else emptyList()
    }

    fun rememberedHost(): RememberedHost? = hostStore.load()

    fun isReconnectPending(): Boolean = intent == Intent.Reconnect

    private fun advance(bondedHosts: Collection<RememberedHost>?): List<ReconnectAction> {
        if (!foreground) return emptyList()
        return when (intent) {
            Intent.None, Intent.AwaitingPair -> emptyList()
            Intent.Pair -> {
                if (registered) {
                    intent = Intent.AwaitingPair
                    listOf(ReconnectAction.RequestDiscoverability)
                } else {
                    requestRegistration()
                }
            }
            Intent.Reconnect -> advanceReconnect(bondedHosts)
        }
    }

    private fun advanceReconnect(bondedHosts: Collection<RememberedHost>?): List<ReconnectAction> {
        val remembered = hostStore.load() ?: run {
            intent = Intent.None
            return emptyList()
        }
        val rememberedAddress = normalizeBluetoothAddress(remembered.address) ?: run {
            hostStore.clear()
            intent = Intent.None
            return listOf(ReconnectAction.RememberedHostUnavailable(remembered.name))
        }
        if (normalizeBluetoothAddress(connectedAddress) == rememberedAddress) {
            intent = Intent.None
            return emptyList()
        }
        if (bondedHosts == null) return emptyList()
        val target = bondedHosts.firstOrNull {
            normalizeBluetoothAddress(it.address) == rememberedAddress
        } ?: run {
            hostStore.clear()
            intent = Intent.None
            return listOf(ReconnectAction.RememberedHostUnavailable(remembered.name))
        }
        if (!registered) return requestRegistration()
        if (connectionRequested) return emptyList()

        connectionRequested = true
        val displayName = if (target.name == DEFAULT_HOST_NAME) remembered.name else target.name
        return listOf(ReconnectAction.Connect(target.copy(name = safeHostName(displayName))))
    }

    private fun requestRegistration(): List<ReconnectAction> {
        if (registrationRequested) return emptyList()
        registrationRequested = true
        return listOf(ReconnectAction.RegisterHid)
    }

    private fun scheduleRetry(): ReconnectDisposition {
        if (retryScheduled) return ReconnectDisposition.RetryScheduled
        if (reconnectSuppressed || !foreground || retryCount >= maxRetries) {
            return ReconnectDisposition.Exhausted
        }
        val delay = retryDelaysMillis.getOrElse(retryCount) {
            retryDelaysMillis.lastOrNull() ?: 0L
        }
        retryCount += 1
        retryScheduled = true
        scheduler.schedule(delay) {
            retryScheduled = false
            onAsyncActions(listOf(ReconnectAction.Retry))
        }
        return ReconnectDisposition.RetryScheduled
    }

    private fun cancelRetry() {
        if (!retryScheduled) return
        scheduler.cancel()
        retryScheduled = false
    }
}
