use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use prns_core::interfaces::{
    AnnounceBandwidthCap, BitrateBps, ConfiguredInterfacePolicy, EgressCapability,
    IngressCapability, InterfaceCapabilities, InterfaceDefaults, InterfaceDescriptor,
    InterfaceGravity, InterfaceId, InterfaceKind, InterfaceMode, MtuPolicy, ReportsStatus,
    StatusView, TransportCapability,
};
use prns_runtime::manifold::interface_seam::{Interface, InterfaceSeam};
use prns_runtime_tokio::manifold::driver::TokioInterfaceStatus;
use tokio::sync::mpsc;

use crate::types::{CustomInterfaceKind, CustomLinkMetrics, RnsCustomInterfaceBridge};

/// Idempotent guard ensuring `on_interface_detached` fires exactly once even if teardown cancels `run`.
pub(crate) struct BridgeDetachHandle {
    interface_tag: String,
    bridge: Arc<dyn RnsCustomInterfaceBridge>,
    notified: AtomicBool,
}

impl BridgeDetachHandle {
    pub(crate) fn new(interface_tag: String, bridge: Arc<dyn RnsCustomInterfaceBridge>) -> Self {
        Self {
            interface_tag,
            bridge,
            notified: AtomicBool::new(false),
        }
    }

    pub(crate) fn notify_detached_once(&self) {
        if !self.notified.swap(true, Ordering::SeqCst) {
            self.bridge
                .on_interface_detached(self.interface_tag.clone());
        }
    }
}

/// Runtime registry for custom pluggable interfaces and their Kotlin-reported link metrics.
pub(crate) struct BridgeRegistry {
    pub(crate) inbound_txs: Mutex<HashMap<String, mpsc::UnboundedSender<Vec<u8>>>>,
    pub(crate) interface_ids: Mutex<HashMap<String, InterfaceId>>,
    pub(crate) metrics: Mutex<HashMap<String, HashMap<String, CustomLinkMetrics>>>,
    pub(crate) detach_handles: Mutex<HashMap<String, Arc<BridgeDetachHandle>>>,
}

impl BridgeRegistry {
    pub(crate) fn new() -> Self {
        Self {
            inbound_txs: Mutex::new(HashMap::new()),
            interface_ids: Mutex::new(HashMap::new()),
            metrics: Mutex::new(HashMap::new()),
            detach_handles: Mutex::new(HashMap::new()),
        }
    }
}

/// Pluggable Reticulum `Interface` bridging frames between `personal-rns` and Kotlin transports.
pub(crate) struct CustomBridgeInterface {
    id: InterfaceId,
    interface_tag: String,
    kind: InterfaceKind,
    hw_mtu: usize,
    bitrate: BitrateBps,
    channel_tag: Vec<u8>,
    inbound_rx: mpsc::UnboundedReceiver<Vec<u8>>,
    bridge: Arc<dyn RnsCustomInterfaceBridge>,
    detach_handle: Arc<BridgeDetachHandle>,
    status: TokioInterfaceStatus,
}

impl CustomBridgeInterface {
    pub(crate) fn new(
        interface_tag: String,
        kind: CustomInterfaceKind,
        hw_mtu: usize,
        bitrate: BitrateBps,
        inbound_rx: mpsc::UnboundedReceiver<Vec<u8>>,
        bridge: Arc<dyn RnsCustomInterfaceBridge>,
        detach_handle: Arc<BridgeDetachHandle>,
    ) -> Self {
        let prns_kind = match kind {
            CustomInterfaceKind::WifiAware => InterfaceKind::WifiAwarePeer,
            CustomInterfaceKind::BleL2cap => InterfaceKind::BluetoothPeer,
            CustomInterfaceKind::Custom => InterfaceKind::Pipe,
        };
        let channel_tag = interface_tag.as_bytes().to_vec();
        let id = InterfaceId::from_channel_tag(prns_kind, &channel_tag);
        let status = TokioInterfaceStatus::new(
            id,
            prns_core::interfaces::ConnectionState::Connected,
        );
        Self {
            id,
            interface_tag,
            kind: prns_kind,
            hw_mtu: hw_mtu.max(500),
            bitrate,
            channel_tag,
            inbound_rx,
            bridge,
            detach_handle,
            status,
        }
    }

    pub(crate) fn id(&self) -> InterfaceId {
        self.id
    }
}

impl Interface for CustomBridgeInterface {
    const HW_MTU: usize = 65_535;
    const KIND: InterfaceKind = InterfaceKind::Pipe;

    fn channel_tag(&self) -> &[u8] {
        &self.channel_tag
    }

    fn descriptor(&self) -> InterfaceDescriptor {
        let mut desc = InterfaceDefaults {
            capabilities: InterfaceCapabilities {
                ingress: IngressCapability::Enabled,
                egress: EgressCapability::Enabled(TransportCapability::CrossInterfaceOnly),
            },
            mode: InterfaceMode::Full,
            gravity: InterfaceGravity::ZERO,
            bitrate: self.bitrate,
            mtu: MtuPolicy::fixed(self.hw_mtu),
            announce_rate_limit: None,
            announce_bandwidth_cap: AnnounceBandwidthCap::RNS_DEFAULT,
            airtime_duty_cycle: None,
        }
        .configured(ConfiguredInterfacePolicy::default())
        .descriptor(self.id);
        let _ = self.kind;
        desc.hardware_mtu = Some(self.hw_mtu);
        desc
    }

    async fn run<S: InterfaceSeam>(mut self, mut seam: S) {
        self.status
            .set_connection(prns_core::interfaces::ConnectionState::Connected);

        loop {
            tokio::select! {
                inbound = self.inbound_rx.recv() => {
                    match inbound {
                        Some(frame) if !frame.is_empty() => {
                            self.status.add_rx(frame.len() as u64);
                            seam.next_inbound(&frame).await;
                        }
                        Some(_) => {}
                        None => break,
                    }
                }
                outbound = seam.next_outbound() => {
                    if outbound.is_empty() {
                        continue;
                    }
                    let frame = outbound.to_vec();
                    let len = frame.len() as u64;
                    let bridge = Arc::clone(&self.bridge);
                    let tag = self.interface_tag.clone();
                    let accepted = tokio::task::spawn_blocking(move || bridge.on_outbound_frame(tag, frame))
                        .await
                        .unwrap_or(false);
                    if accepted {
                        self.status.add_tx(len);
                    }
                }
            }
        }

        self.status
            .set_connection(prns_core::interfaces::ConnectionState::Disconnected);
        let dh = Arc::clone(&self.detach_handle);
        let _ = tokio::task::spawn_blocking(move || {
            dh.notify_detached_once();
        })
        .await;
    }
}

impl Drop for CustomBridgeInterface {
    fn drop(&mut self) {
        self.detach_handle.notify_detached_once();
    }
}

impl ReportsStatus for CustomBridgeInterface {
    fn status_view(&self) -> Option<StatusView> {
        let status = self.status.clone();
        Some(Arc::new(move || {
            vec![prns_core::interfaces::InterfaceVitals::of(&status)]
        }))
    }

    fn connection_view(&self) -> Option<prns_core::interfaces::ConnectionView> {
        Some(prns_core::interfaces::ConnectionView::of(
            self.status.clone(),
        ))
    }
}
