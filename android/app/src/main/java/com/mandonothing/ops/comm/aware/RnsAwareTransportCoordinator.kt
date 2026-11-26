package com.mandonothing.ops.comm.aware

import com.mandonothing.ops.comm.CommGateway
import com.mandonothing.ops.comm.CommLinkStatus
import com.mandonothing.ops.comm.CommNetworkState
import com.mandonothing.ops.comm.CommReachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable

/**
 * Coordinates [RnsWifiAwareAdapter] and [RnsBleL2capAdapter] so BLE L2CAP automatically acts as
 * a low-bandwidth RNS fallback when Wi-Fi Aware is unavailable, and exposes merged link quality /
 * path cost metrics to [CommNetworkState] (ready for RNS-08) and [CommGateway].
 */
internal class RnsAwareTransportCoordinator(
    val wifiAwareAdapter: RnsWifiAwareAdapter<*, *>,
    val bleL2capAdapter: RnsBleL2capAdapter,
    private val scope: CoroutineScope? = null,
) : Closeable {

    private val mutableNetworkState = MutableStateFlow(CommNetworkState())
    val networkState: StateFlow<CommNetworkState> = mutableNetworkState.asStateFlow()

    private val mutableDiagnostics = MutableStateFlow(RnsAwareDiagnosticsSnapshot())
    val diagnostics: StateFlow<RnsAwareDiagnosticsSnapshot> = mutableDiagnostics.asStateFlow()

    private val jobs = mutableListOf<Job>()

    fun start() {
        wifiAwareAdapter.start()
        val initialWifi = wifiAwareAdapter.linkStatus.value
        bleL2capAdapter.onWifiAwareStateChanged(
            available = initialWifi.reachability != CommReachability.UNAVAILABLE,
            activeWifiAwarePeers = initialWifi.peerCount,
        )
        bleL2capAdapter.start()
        syncSnapshots()

        if (scope != null) {
            jobs += scope.launch {
                wifiAwareAdapter.linkStatus.collect { wifiStatus ->
                    bleL2capAdapter.onWifiAwareStateChanged(
                        available = wifiStatus.reachability != CommReachability.UNAVAILABLE,
                        activeWifiAwarePeers = wifiStatus.peerCount,
                    )
                    syncSnapshots()
                }
            }
            jobs += scope.launch {
                bleL2capAdapter.linkStatus.collect {
                    syncSnapshots()
                }
            }
            jobs += scope.launch {
                wifiAwareAdapter.diagnostics.collect { syncSnapshots() }
            }
            jobs += scope.launch {
                bleL2capAdapter.diagnostics.collect { syncSnapshots() }
            }
        }
    }

    /** Manually synchronizes BLE fallback policy and combined [CommNetworkState] / [diagnostics]. */
    fun syncSnapshots() {
        val wifiStatus = wifiAwareAdapter.linkStatus.value
        bleL2capAdapter.onWifiAwareStateChanged(
            available = wifiStatus.reachability != CommReachability.UNAVAILABLE,
            activeWifiAwarePeers = wifiStatus.peerCount,
        )
        val bleStatus = bleL2capAdapter.linkStatus.value
        mutableNetworkState.value = applyTo(mutableNetworkState.value, wifiStatus, bleStatus)

        val wifiDiag = wifiAwareAdapter.diagnostics.value
        val bleDiag = bleL2capAdapter.diagnostics.value
        val wifiLeaks = wifiDiag.leakCounters
        val bleLeaks = bleDiag.leakCounters
        mutableDiagnostics.value = RnsAwareDiagnosticsSnapshot(
            radioReachability = wifiDiag.radioReachability + bleDiag.radioReachability,
            activePeers = wifiDiag.activePeers + bleDiag.activePeers,
            standbyPeerCount = wifiDiag.standbyPeerCount + bleDiag.standbyPeerCount,
            prunedPeerCount = wifiDiag.prunedPeerCount + bleDiag.prunedPeerCount,
            rotatedPeerCount = wifiDiag.rotatedPeerCount + bleDiag.rotatedPeerCount,
            bestPathCostByRadio = wifiDiag.bestPathCostByRadio + bleDiag.bestPathCostByRadio,
            bleFallbackActive = bleDiag.bleFallbackActive,
            leakCounters = RnsLeakCounters(
                activeNetworkCallbacks = wifiLeaks.activeNetworkCallbacks,
                totalNetworkCallbacksRegistered = wifiLeaks.totalNetworkCallbacksRegistered,
                totalNetworkCallbacksUnregistered = wifiLeaks.totalNetworkCallbacksUnregistered,
                activeSockets = wifiLeaks.activeSockets + bleLeaks.activeSockets,
                totalSocketsOpened = wifiLeaks.totalSocketsOpened + bleLeaks.totalSocketsOpened,
                totalSocketsClosed = wifiLeaks.totalSocketsClosed + bleLeaks.totalSocketsClosed,
            ),
        )
    }

    /** Merges current Wi-Fi Aware and BLE L2CAP statuses/metrics into an existing [CommNetworkState]. */
    fun applyTo(
        baseState: CommNetworkState,
        wifiStatus: CommLinkStatus = wifiAwareAdapter.linkStatus.value,
        bleStatus: CommLinkStatus = bleL2capAdapter.linkStatus.value,
    ): CommNetworkState = RnsPathCostCalculator.mergeIntoNetworkState(
        base = baseState,
        wifiStatus = wifiStatus,
        bleStatus = bleStatus,
    )

    /** Registers both RNS-04 radio adapters into [CommGateway]. */
    fun registerWith(gateway: CommGateway) {
        gateway.registerAdapter(wifiAwareAdapter)
        gateway.registerAdapter(bleL2capAdapter)
    }

    override fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        wifiAwareAdapter.close()
        bleL2capAdapter.close()
        syncSnapshots()
    }
}
