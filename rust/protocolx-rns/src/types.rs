use serde::{Deserialize, Serialize};

/// Configuration for starting a Protocol X RNS + LXMF transport node.
#[derive(Debug, Clone, uniffi::Record)]
pub struct NodeConfig {
    /// Optional 64-byte private identity key (`x25519_prv || ed25519_prv`).
    /// When `None`, a fresh identity is generated on node construction.
    pub identity_secret: Option<Vec<u8>>,
    /// Primary RNS destination application name (e.g. `"protocolx"`).
    pub app_name: String,
    /// Primary RNS destination aspects (e.g. `["node"]` or `["delivery"]`).
    pub aspects: Vec<String>,
    /// Default announce `app_data` payload advertised with primary destination announces.
    pub announce_app_data: Vec<u8>,
    /// Optional local TCP server bind address (e.g. `"127.0.0.1:0"` or `"0.0.0.0:4242"`).
    pub tcp_listen_addr: Option<String>,
    /// Remote TCP server endpoints to connect to as `TCPClientInterface`.
    pub tcp_connect_addrs: Vec<String>,
    /// Optional local UDP bind address (requires `udp_peer_addr` when set).
    pub udp_bind_addr: Option<String>,
    /// Optional remote UDP peer address paired with `udp_bind_addr`.
    pub udp_peer_addr: Option<String>,
    /// Bitrate hint for UDP interface pacing (bits/sec; defaults to 1_000_000 when 0).
    pub udp_bitrate_bps: u64,
    /// Whether this node acts as a Reticulum transport relay (`enable_transport`).
    pub enable_transport_relay: bool,
    /// Optional directory for persisting RNS routing/ratchets and LXMF messages/receipts.
    pub storage_dir: Option<String>,
    /// Optional display name advertised in `lxmf.delivery` announces.
    pub lxmf_display_name: Option<String>,
    /// Optional default LXMF propagation node destination hash hex (32 chars).
    pub propagation_node_hex: Option<String>,
}

impl Default for NodeConfig {
    fn default() -> Self {
        Self {
            identity_secret: None,
            app_name: "protocolx".to_string(),
            aspects: vec!["node".to_string()],
            announce_app_data: Vec::new(),
            tcp_listen_addr: None,
            tcp_connect_addrs: Vec::new(),
            udp_bind_addr: None,
            udp_peer_addr: None,
            udp_bitrate_bps: 1_000_000,
            enable_transport_relay: false,
            storage_dir: None,
            lxmf_display_name: None,
            propagation_node_hex: None,
        }
    }
}

/// Snapshot of the RNS node's runtime state.
#[derive(Debug, Clone, uniffi::Record)]
pub struct NodeStatus {
    pub running: bool,
    pub identity_hash_hex: String,
    pub destination_hash_hex: String,
    pub tcp_listen_addr: Option<String>,
    pub known_peers_count: u32,
    pub active_links_count: u32,
}

/// Delivery mode for an outbound/inbound LXMF message.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum LxmfDeliveryMode {
    Opportunistic,
    Direct,
    Propagated,
}

/// Lifecycle state for an outbound LXMF message delivery receipt.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum LxmfReceiptStatus {
    Pending,
    Delivered,
    Propagated,
    Failed,
}

/// Pluggable custom transport interface kind (RNS-04 Wi-Fi Aware / BLE L2CAP bridge).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum CustomInterfaceKind {
    WifiAware,
    BleL2cap,
    Custom,
}

/// File attachment carried in LXMF `FIELD_FILE_ATTACHMENTS` (0x05).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct LxmfAttachment {
    pub filename: String,
    pub data: Vec<u8>,
}

/// Outbound LXMF message request submitted to `RnsNode::send_lxmf`.
#[derive(Debug, Clone, uniffi::Record)]
pub struct LxmfOutboundMessage {
    pub message_id: String,
    pub destination_hash_hex: String,
    pub title: String,
    pub content: String,
    pub delivery_mode: LxmfDeliveryMode,
    pub attachments: Vec<LxmfAttachment>,
    pub custom_type: Option<String>,
    pub custom_data: Option<Vec<u8>>,
    pub propagation_node_hex: Option<String>,
}

/// Decoded inbound LXMF message delivered via single packet, direct link, Resource, or propagation node.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct LxmfInboundMessage {
    pub message_hash_hex: String,
    pub source_hash_hex: String,
    pub destination_hash_hex: String,
    pub timestamp_secs: f64,
    pub title: String,
    pub content: String,
    pub delivery_mode: LxmfDeliveryMode,
    pub signature_valid: bool,
    pub attachments: Vec<LxmfAttachment>,
    pub custom_type: Option<String>,
    pub custom_data: Option<Vec<u8>>,
}

/// Delivery receipt emitted when an LXMF message is submitted, delivered, propagated, or fails.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, uniffi::Record)]
pub struct LxmfDeliveryReceipt {
    pub client_message_id: String,
    pub message_hash_hex: String,
    pub destination_hash_hex: String,
    pub status: LxmfReceiptStatus,
    pub detail: String,
    pub timestamp_ms: u64,
}

/// Per-peer link health metrics reported by custom Android radio adapters (Wi-Fi Aware / BLE L2CAP).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct CustomLinkMetrics {
    pub peer_id: String,
    pub connected: bool,
    pub rssi_dbm: Option<i32>,
    pub rtt_ms: Option<u32>,
    pub estimated_bandwidth_bps: Option<u64>,
}

/// Inbound events surfaced by the RNS + LXMF transport core to Kotlin/native callers.
#[derive(Debug, Clone, PartialEq, uniffi::Enum)]
pub enum RnsInboundEvent {
    AnnounceHeard {
        destination_hash_hex: String,
        hops: u8,
    },
    SinglePacketReceived {
        destination_hash_hex: String,
        payload: Vec<u8>,
    },
    LinkEstablished {
        link_id_hex: String,
        rtt_millis: u64,
    },
    LinkPacketReceived {
        link_id_hex: String,
        payload: Vec<u8>,
    },
    LinkClosed {
        link_id_hex: String,
    },
    ResourceReceived {
        link_id_hex: String,
        resource_hash_hex: String,
        data: Vec<u8>,
    },
    LxmfMessageReceived {
        message: LxmfInboundMessage,
    },
    LxmfDeliveryStatus {
        receipt: LxmfDeliveryReceipt,
    },
}

/// Push callback interface for receiving RNS/LXMF inbound events in Kotlin/Swift.
#[uniffi::export(callback_interface)]
pub trait RnsEventListener: Send + Sync {
    fn on_event(&self, event: RnsInboundEvent);
}

/// Pluggable frame transport bridge implemented by Kotlin Wi-Fi Aware / BLE L2CAP adapters (RNS-04).
#[uniffi::export(callback_interface)]
pub trait RnsCustomInterfaceBridge: Send + Sync {
    fn on_outbound_frame(&self, interface_tag: String, frame: Vec<u8>) -> bool;
    fn on_interface_detached(&self, interface_tag: String);
}
