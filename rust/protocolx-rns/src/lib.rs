uniffi::setup_scaffolding!();

pub mod bridge;
pub mod error;
pub mod identity;
pub mod lxmf;
pub mod node;
pub mod store;
pub mod types;

pub use error::ProtocolxRnsError;
pub use identity::{
    compute_destination_hash_hex, generate_identity, load_identity_from_secret, RnsIdentity,
};
pub use node::RnsNode;
pub use types::{
    CustomInterfaceKind, CustomLinkMetrics, LxmfAttachment, LxmfDeliveryMode,
    LxmfDeliveryReceipt, LxmfInboundMessage, LxmfOutboundMessage, LxmfReceiptStatus, NodeConfig,
    NodeStatus, RnsCustomInterfaceBridge, RnsEventListener, RnsInboundEvent,
};
