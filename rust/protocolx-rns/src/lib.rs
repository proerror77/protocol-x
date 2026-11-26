uniffi::setup_scaffolding!();

pub mod bridge;
pub mod error;
pub mod identity;
pub mod ifac;
pub mod lxmf;
pub mod node;
pub mod ptt;
pub mod store;
pub mod types;

pub use error::ProtocolxRnsError;
pub use identity::{
    compute_destination_hash_hex, generate_identity, load_identity_from_secret, RnsIdentity,
};
pub use ifac::{
    bind_actor_device_to_rns, derive_event_ifac_config, verify_actor_device_binding,
};
pub use node::RnsNode;
pub use types::{
    ActorDeviceBinding, CustomInterfaceKind, CustomLinkMetrics, IfacConfig, LxmfAttachment,
    LxmfDeliveryMode, LxmfDeliveryReceipt, LxmfInboundMessage, LxmfOutboundMessage,
    LxmfReceiptStatus, MultiHopTelemetry, NodeConfig, NodeStatus, PttFloorAction,
    PttFloorControlMessage, PttFramePacket, RnsCustomInterfaceBridge, RnsEventListener,
    RnsInboundEvent, RouteTelemetry,
};
