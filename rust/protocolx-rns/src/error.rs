use thiserror::Error;

/// Errors surfaced across the Protocol X RNS Rust core and UniFFI boundary.
#[derive(Debug, Error, uniffi::Error)]
pub enum ProtocolxRnsError {
    #[error("invalid key or hash material: {detail}")]
    InvalidMaterial { detail: String },

    #[error("invalid destination configuration: {detail}")]
    InvalidDestination { detail: String },

    #[error("transport node is not running")]
    NotRunning,

    #[error("transport node is already running")]
    AlreadyRunning,

    #[error("failed to bind or attach interface: {detail}")]
    InterfaceError { detail: String },

    #[error("send or link operation failed: {detail}")]
    TransportError { detail: String },

    #[error("signature verification failed")]
    VerificationFailed,
}
