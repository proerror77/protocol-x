package com.mandonothing.ops.comm.rns

import android.content.Context
import android.os.Build
import com.mandonothing.ops.NearbyGroup
import com.mandonothing.ops.NearbySettings
import com.mandonothing.ops.comm.aware.AndroidBleL2capDriver
import com.mandonothing.ops.comm.aware.RnsAwareBridgeConnector
import com.mandonothing.ops.comm.aware.RnsAwareTransportCoordinator
import com.mandonothing.ops.comm.aware.RnsBleL2capAdapter
import com.mandonothing.ops.comm.aware.RnsCustomBridgeSink
import com.mandonothing.ops.comm.aware.RnsIfacConfig
import com.mandonothing.ops.comm.aware.RnsIfacDerivation
import com.mandonothing.ops.comm.aware.RnsIfacFrameGuard
import com.mandonothing.ops.comm.aware.RnsLinkConfig
import com.mandonothing.ops.comm.aware.RnsRadioKind
import com.mandonothing.ops.comm.aware.RnsWifiAwareAdapter
import com.mandonothing.ops.comm.aware.UniFfiRnsNodeBridgeSink
import com.mandonothing.ops.comm.network.RnsTopologyDiagnostics
import com.mandonothing.ops.comm.ptt.RnsPttFloorAction
import com.mandonothing.ops.comm.ptt.RnsPttFloorControl
import com.mandonothing.ops.comm.ptt.RnsPttFrame
import com.mandonothing.ops.comm.ptt.RnsPttTransportBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.Closeable
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * RNS-10 [RnsPttTransportBridge] backed by [RnsRuntimeCoordinator] and [RnsCoreEngine] (UniFFI `RnsNode`).
 */
internal class RnsCorePttBridge(
    private val coordinator: RnsRuntimeCoordinator,
    private val engine: RnsCoreEngine,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : RnsPttTransportBridge {

    override val localHashHex: String
        get() = coordinator.snapshot.value.destinationHashHex
            .ifBlank { runCatching { engine.status().destinationHashHex }.getOrDefault("") }
            .ifBlank { "local-rns-node" }

    override fun peerDestinations(channelId: String): List<String> {
        val allPeers = coordinator.peers.value
        val matching = allPeers.filter { it.appDataText == channelId }.map { it.destinationHashHex }
        val fallback = allPeers.map { it.destinationHashHex }
        val gw = coordinator.activeGatewayDestinationHex()
        val combined = (matching.ifEmpty { fallback } + listOfNotNull(gw))
            .map { it.lowercase() }
            .distinct()
            .filterNot { coordinator.isPeerRevoked(it) }
        return combined
    }

    override fun sendPttFrame(destinationHashHex: String, frame: RnsPttFrame): Boolean {
        if (!engine.isAvailable || coordinator.isPeerRevoked(destinationHashHex)) return false
        return engine.sendPttFrame(
            destinationHashHex = destinationHashHex,
            channelId = frame.channelId,
            sessionId = frame.sessionId,
            seq = frame.seq,
            codec = frame.codec,
            durationMs = frame.durationMs,
            audioBytes = frame.audioBytes,
        )
    }

    override fun requestPttFloor(
        destinationHashHex: String,
        channelId: String,
        sessionId: String,
        priority: Int,
    ): RnsPttFloorControl {
        val result = if (engine.isAvailable && !coordinator.isPeerRevoked(destinationHashHex)) {
            engine.requestPttFloor(destinationHashHex, channelId, sessionId, priority)
        } else null
        return result?.let(::toFloorControl) ?: RnsPttFloorControl(
            channelId = channelId,
            sessionId = sessionId,
            actorHashHex = localHashHex,
            action = RnsPttFloorAction.GRANT,
            priority = priority,
            timestampMs = clockMs(),
            holderHashHex = localHashHex,
            detail = "local_grant",
        )
    }

    override fun releasePttFloor(
        destinationHashHex: String,
        channelId: String,
        sessionId: String,
    ): RnsPttFloorControl {
        val result = if (engine.isAvailable && !coordinator.isPeerRevoked(destinationHashHex)) {
            engine.releasePttFloor(destinationHashHex, channelId, sessionId)
        } else null
        return result?.let(::toFloorControl) ?: RnsPttFloorControl(
            channelId = channelId,
            sessionId = sessionId,
            actorHashHex = localHashHex,
            action = RnsPttFloorAction.RELEASE,
            timestampMs = clockMs(),
            detail = "local_release",
        )
    }

    companion object {
        fun toPttFrame(event: RnsCoreEvent.PttFrameReceived, senderName: String = ""): RnsPttFrame =
            RnsPttFrame(
                channelId = event.channelId,
                sessionId = event.sessionId,
                senderHashHex = event.senderHashHex,
                senderName = senderName.ifBlank { event.senderHashHex.take(8) },
                seq = event.seq,
                timestampMs = event.timestampMs,
                codec = event.codec,
                durationMs = event.durationMs,
                audioBytes = event.audioBytes,
                hopCount = event.hopCount,
                endOfBurst = event.audioBytes.isEmpty(),
            )

        fun toFloorControl(event: RnsCoreEvent.PttFloorEvent, actorName: String = ""): RnsPttFloorControl {
            val action = runCatching { RnsPttFloorAction.valueOf(event.action.uppercase()) }
                .getOrDefault(RnsPttFloorAction.GRANT)
            return RnsPttFloorControl(
                channelId = event.channelId,
                sessionId = event.sessionId,
                actorHashHex = event.actorHashHex,
                actorName = actorName.ifBlank { event.actorHashHex.take(8) },
                action = action,
                priority = event.priority,
                timestampMs = event.timestampMs,
                holderHashHex = event.holderHashHex,
                detail = event.detail,
            )
        }
    }
}

/**
 * Coordinates RNS-04↔03 bridge attachment (`RnsAwareBridgeConnector`), RNS-05 IFAC rotation/revocation,
 * RNS-06 multi-hop topology ingestion (`RnsTopologyDiagnostics`), and optional Android physical drivers.
 */
internal class RnsAwareIntegrationController(
    private val coordinator: RnsRuntimeCoordinator,
    private val engine: RnsCoreEngine,
    val topologyDiagnostics: RnsTopologyDiagnostics,
    sink: RnsCustomBridgeSink = UniFfiRnsNodeBridgeSink { (engine as? UniFFIRnsCoreEngine)?.activeNode() },
    initialIfacConfig: RnsIfacConfig? = null,
    scope: CoroutineScope? = null,
) : Closeable {

    val ifacGuard: RnsIfacFrameGuard = RnsIfacFrameGuard(
        initialIfacConfig ?: RnsIfacConfig(
            networkName = "protocolx.pending",
            ifacKey = "pending-lease".toByteArray(Charsets.UTF_8),
            allowPublicRns = false,
            expiresAtMs = Long.MAX_VALUE,
        ),
    )

    val bridgeConnector: RnsAwareBridgeConnector = RnsAwareBridgeConnector(
        sink = sink,
        ifacGuard = ifacGuard,
        coreEngineProvider = { engine },
        onTopologyMetrics = { tag, metrics ->
            val radio = if (tag.contains("ble", ignoreCase = true)) RnsRadioKind.BLE_L2CAP else RnsRadioKind.WIFI_AWARE
            topologyDiagnostics.onLinkMetricsUpdated(metrics.peerId, radio, metrics)
        },
    )

    @Volatile
    var awareCoordinator: RnsAwareTransportCoordinator? = null
        private set

    private var eventForwardJob: Job? = null

    init {
        coordinator.onIfacRotated = { lease ->
            bridgeConnector.rotateIfacConfig(leaseToAwareIfacConfig(lease))
        }
        coordinator.onPeerRevoked = { actorId, hash ->
            bridgeConnector.revokePeer(peerId = actorId, destinationHashHex = hash)
        }
        if (scope != null) {
            eventForwardJob = scope.launch {
                coordinator.coreEvents.collect { event ->
                    topologyDiagnostics.onCoreEvent(event)
                }
            }
        }
    }

    fun startAndroidRadios(
        context: Context,
        config: RnsRuntimeConfig,
        scope: CoroutineScope,
    ) {
        if (Build.VERSION.SDK_INT < 29) return
        val nearbyCfg = NearbySettings(context).read(
            account = config.accountKey,
            event = config.eventId,
            channel = config.channelId,
            title = config.channelName,
        ) ?: return

        coordinator.currentLease()?.let { lease ->
            bridgeConnector.rotateIfacConfig(leaseToAwareIfacConfig(lease))
        } ?: run {
            val derived = RnsIfacDerivation.deriveConfig(
                eventId = config.eventId,
                channelId = config.channelId,
                channelSecret = nearbyCfg.secret.toByteArray(Charsets.UTF_8),
                expiresAtMs = nearbyCfg.expires,
            )
            bridgeConnector.rotateIfacConfig(derived)
        }

        if (awareCoordinator != null) return
        val group = NearbyGroup(nearbyCfg.secret, config.eventId)
        val tokenBytes = MessageDigest.getInstance("SHA-256")
            .digest("${config.accountKey}|${config.actorId}|${config.channelId}".toByteArray(Charsets.UTF_8))
        val localToken = ByteBuffer.wrap(tokenBytes).int

        val linkConfig = RnsLinkConfig()
        val wifiAdapter = RnsWifiAwareAdapter.create(
            context = context,
            bridge = bridgeConnector,
            serviceName = group.serviceName,
            wifiPassphrase = group.wifiPassword,
            localDeviceToken = localToken,
            config = linkConfig,
        )
        val bleAdapter = RnsBleL2capAdapter(
            bridge = bridgeConnector,
            localDeviceToken = localToken,
            config = linkConfig,
            driver = AndroidBleL2capDriver(context, group.radioUuid, localToken),
            ifacGuard = ifacGuard,
        )
        val coord = RnsAwareTransportCoordinator(wifiAdapter, bleAdapter, scope = scope)
        bridgeConnector.bindCoordinator(coord)
        awareCoordinator = coord
        coord.start()
    }

    fun pollMultiHopTelemetry() {
        if (!engine.isAvailable) return
        val telemetry = runCatching { engine.multiHopTelemetry() }.getOrNull() ?: return
        topologyDiagnostics.ingestCoreTelemetry(telemetry)
    }

    override fun close() {
        eventForwardJob?.cancel()
        eventForwardJob = null
        coordinator.onIfacRotated = null
        coordinator.onPeerRevoked = null
        runCatching { awareCoordinator?.close() }
        awareCoordinator = null
        runCatching { bridgeConnector.close() }
    }

    companion object {
        fun leaseToAwareIfacConfig(lease: RnsIfacLease): RnsIfacConfig =
            RnsIfacConfig(
                networkName = lease.networkName,
                ifacKey = lease.passphrase.toByteArray(Charsets.UTF_8),
                expiresAtMs = lease.expiresAtMs,
                allowPublicRns = !lease.requirePrivateIfac,
                revokedPeerIds = lease.revokedActors,
                revokedDestinationHashes = lease.revokedDeviceHashes,
            )
    }
}
