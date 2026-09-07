package com.github.pompomon.btapp.bluetooth

internal interface ReconnectScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit)
    fun cancel()
}

internal sealed interface ReconnectAction {
    data object RegisterHid : ReconnectAction
    data class Connect(val host: RememberedHost) : ReconnectAction
    data object DisconnectCurrent : ReconnectAction
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
    ReconnectNow,
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
    private var switchPending = false

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
        switchPending = false
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
        switchPending = false
        retryCount = 0
        cancelRetry()
        return if (prerequisitesAvailable) advance(bondedHosts) else emptyList()
    }

    fun onManualDisconnect() {
        reconnectSuppressed = true
        intent = Intent.None
        switchPending = false
        retryCount = 0
        cancelRetry()
    }

    fun forgetRememberedHost() {
        hostStore.load()?.let { hostStore.remove(it.address) }
        reconnectSuppressed = true
        intent = Intent.None
        switchPending = false
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

    fun onReconnectInProgress() {
        intent = Intent.Reconnect
        connectionRequested = true
        cancelRetry()
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
        if (switchPending) {
            switchPending = false
            intent = Intent.Reconnect
            return if (!reconnectSuppressed && foreground && hostStore.load() != null) {
                ReconnectDisposition.ReconnectNow
            } else {
                ReconnectDisposition.Idle
            }
        }
        if (reconnectSuppressed || !foreground || hostStore.load() == null) {
            if (intent != Intent.AwaitingPair) intent = Intent.None
            return ReconnectDisposition.Idle
        }
        intent = Intent.Reconnect
        return scheduleRetry()
    }

    fun onConnected(host: RememberedHost): ConnectionDecision {
        val address = normalizeBluetoothAddress(host.address) ?: return ConnectionDecision.Disconnect
        val acceptingPair = intent == Intent.AwaitingPair
        val selectedAddress = normalizeBluetoothAddress(hostStore.load()?.address)
        if (
            !acceptingPair &&
            (!foreground || reconnectSuppressed || selectedAddress != address)
        ) {
            return ConnectionDecision.Disconnect
        }
        registered = true
        registrationRequested = false
        connectionRequested = false
        switchPending = false
        cancelRetry()
        connectedAddress = address
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

    fun rememberedHosts(): List<RememberedHost> = hostStore.loadAll()

    fun selectHost(
        address: String,
        currentAddress: String?,
        prerequisitesAvailable: Boolean,
        bondedHosts: Collection<RememberedHost>?
    ): List<ReconnectAction> {
        val normalized = normalizeBluetoothAddress(address) ?: return emptyList()
        val candidate = hostStore.loadAll().firstOrNull { it.address == normalized } ?: return emptyList()
        if (bondedHosts != null && bondedHosts.none { normalizeBluetoothAddress(it.address) == normalized }) {
            hostStore.remove(normalized)
            return listOf(ReconnectAction.RememberedHostUnavailable(candidate.name))
        }
        hostStore.select(normalized) ?: return emptyList()
        reconnectSuppressed = false
        retryCount = 0
        cancelRetry()

        val activeAddress = normalizeBluetoothAddress(currentAddress)
        if (activeAddress == normalized) {
            switchPending = false
            intent = Intent.None
            return emptyList()
        }

        intent = Intent.Reconnect
        connectionRequested = false
        if (activeAddress != null) {
            switchPending = true
            return listOf(ReconnectAction.DisconnectCurrent)
        }
        switchPending = false
        return if (prerequisitesAvailable) advance(bondedHosts) else emptyList()
    }

    fun onSwitchDisconnected(bondedHosts: Collection<RememberedHost>?): List<ReconnectAction> {
        connectionRequested = false
        connectedAddress = null
        if (!switchPending) return emptyList()
        switchPending = false
        intent = Intent.Reconnect
        return if (foreground) advance(bondedHosts) else emptyList()
    }

    fun onSwitchDisconnectFailed(currentAddress: String?) {
        switchPending = false
        connectionRequested = false
        intent = Intent.None
        reconnectSuppressed = false
        normalizeBluetoothAddress(currentAddress)?.let(hostStore::select)
    }

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
            hostStore.remove(remembered.address)
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
            hostStore.remove(rememberedAddress)
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
