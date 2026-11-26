/// Configuration for starting a Protocol X RNS transport node.
#[derive(Debug, Clone, uniffi::Record)]
pub struct NodeConfig {
    /// Optional 64-byte private identity key (`x25519_prv || ed25519_prv`).
    /// When `None`, a fresh identity is generated on node construction.
    pub identity_secret: Option<Vec<u8>>,
    /// Primary RNS destination application name (e.g. `"protocolx"`).
    pub app_name: String,
    /// Primary RNS destination aspects (e.g. `["node"]` or `["delivery"]`).
    pub aspects: Vec<String>,
    /// Default announce `app_data` payload advertised with announces.
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

/// Inbound events surfaced by the RNS transport core to Kotlin/native callers.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
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
}

/// Push callback interface for receiving RNS inbound events in Kotlin/Swift.
#[uniffi::export(callback_interface)]
pub trait RnsEventListener: Send + Sync {
    fn on_event(&self, event: RnsInboundEvent);
}
