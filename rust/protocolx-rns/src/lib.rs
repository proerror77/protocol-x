uniffi::setup_scaffolding!();

pub mod error;
pub mod identity;
pub mod node;
pub mod types;

pub use error::ProtocolxRnsError;
pub use identity::{
    compute_destination_hash_hex, generate_identity, load_identity_from_secret, RnsIdentity,
};
pub use node::RnsNode;
pub use types::{NodeConfig, NodeStatus, RnsEventListener, RnsInboundEvent};
