use std::sync::Arc;

use personal_rns::identity::{
    PrivateIdentityMaterial, PublicIdentityMaterial, IDENTITY_SECRET_KEY_LEN,
};
use personal_rns::prelude::{try_generate_identity_secret, Zeroizing};
use personal_rns::routing::announce::{derive_destination_hash, expand_name};
use personal_rns::wire::DestinationHash;

use crate::error::ProtocolxRnsError;

/// Wraps a Reticulum identity (64-byte X25519 + Ed25519 secret keypair) for UniFFI consumers.
#[derive(uniffi::Object)]
pub struct RnsIdentity {
    secret: Zeroizing<[u8; IDENTITY_SECRET_KEY_LEN]>,
}

impl RnsIdentity {
    pub(crate) fn from_secret_array(secret: [u8; IDENTITY_SECRET_KEY_LEN]) -> Self {
        Self {
            secret: Zeroizing::new(secret),
        }
    }
}

#[uniffi::export]
impl RnsIdentity {
    /// Generate a fresh cryptographic Reticulum identity from OS entropy.
    #[uniffi::constructor]
    pub fn generate() -> Result<Arc<Self>, ProtocolxRnsError> {
        let secret = try_generate_identity_secret().map_err(|e| ProtocolxRnsError::InvalidMaterial {
            detail: format!("OS entropy failure: {e:?}"),
        })?;
        Ok(Arc::new(Self { secret }))
    }

    /// Restore a Reticulum identity from its 64-byte private key (`x25519_prv || ed25519_prv`).
    #[uniffi::constructor]
    pub fn from_secret_bytes(secret_bytes: Vec<u8>) -> Result<Arc<Self>, ProtocolxRnsError> {
        let arr: [u8; IDENTITY_SECRET_KEY_LEN] =
            secret_bytes
                .try_into()
                .map_err(|v: Vec<u8>| ProtocolxRnsError::InvalidMaterial {
                    detail: format!(
                        "expected {IDENTITY_SECRET_KEY_LEN} secret bytes, got {}",
                        v.len()
                    ),
                })?;
        Ok(Arc::new(Self::from_secret_array(arr)))
    }

    /// Export the 64-byte private key material (`x25519_prv || ed25519_prv`).
    pub fn secret_bytes(&self) -> Vec<u8> {
        self.secret.to_vec()
    }

    /// Export the 64-byte public key (`x25519_pub || ed25519_pub`), matching RNS `Identity.get_public_key()`.
    pub fn public_key_bytes(&self) -> Vec<u8> {
        PrivateIdentityMaterial::from_bytes(*self.secret)
            .public()
            .as_bytes()
            .to_vec()
    }

    /// 16-byte truncated identity hash as lowercase hex (32 chars).
    pub fn identity_hash_hex(&self) -> String {
        let mat = PrivateIdentityMaterial::from_bytes(*self.secret);
        hex::encode(mat.identity_hash().as_bytes())
    }

    /// Compute the 16-byte RNS Single destination hash (lowercase hex) for `(app_name, aspects)`.
    pub fn destination_hash_hex(
        &self,
        app_name: String,
        aspects: Vec<String>,
    ) -> Result<String, ProtocolxRnsError> {
        compute_destination_hash_for_secret(&self.secret, &app_name, &aspects)
    }

    /// Sign arbitrary payload bytes using this identity's Ed25519 key.
    pub fn sign(&self, message: Vec<u8>) -> Vec<u8> {
        let mat = PrivateIdentityMaterial::from_bytes(*self.secret);
        mat.sign(&message).0.to_vec()
    }

    /// Verify a 64-byte Ed25519 signature against a 64-byte RNS public key.
    pub fn verify_with_public_key(
        &self,
        public_key_bytes: Vec<u8>,
        message: Vec<u8>,
        signature_bytes: Vec<u8>,
    ) -> Result<bool, ProtocolxRnsError> {
        verify_signature_raw(&public_key_bytes, &message, &signature_bytes)
    }
}

/// Top-level helper exported via UniFFI to generate a new identity.
#[uniffi::export]
pub fn generate_identity() -> Result<Arc<RnsIdentity>, ProtocolxRnsError> {
    RnsIdentity::generate()
}

/// Top-level helper exported via UniFFI to restore an identity from 64 secret bytes.
#[uniffi::export]
pub fn load_identity_from_secret(secret_bytes: Vec<u8>) -> Result<Arc<RnsIdentity>, ProtocolxRnsError> {
    RnsIdentity::from_secret_bytes(secret_bytes)
}

/// Compute a Single destination hash hex given a 64-byte RNS public key, `app_name`, and `aspects`.
#[uniffi::export]
pub fn compute_destination_hash_hex(
    public_key_bytes: Vec<u8>,
    app_name: String,
    aspects: Vec<String>,
) -> Result<String, ProtocolxRnsError> {
    let pub_mat =
        PublicIdentityMaterial::from_slice(&public_key_bytes).map_err(|e| {
            ProtocolxRnsError::InvalidMaterial {
                detail: format!("expected {} public key bytes, got {}", e.expected, e.found),
            }
        })?;
    let aspect_refs: Vec<&str> = aspects.iter().map(String::as_str).collect();
    let expanded = expand_name(&app_name, &aspect_refs).map_err(|e| {
        ProtocolxRnsError::InvalidDestination {
            detail: format!("invalid destination name/aspects: {e:?}"),
        }
    })?;
    let hash: DestinationHash =
        derive_destination_hash(&pub_mat.identity_hash(), &expanded);
    Ok(hex::encode(hash.as_bytes()))
}

pub(crate) fn compute_destination_hash_for_secret(
    secret: &[u8; IDENTITY_SECRET_KEY_LEN],
    app_name: &str,
    aspects: &[String],
) -> Result<String, ProtocolxRnsError> {
    let mat = PrivateIdentityMaterial::from_bytes(*secret);
    let aspect_refs: Vec<&str> = aspects.iter().map(String::as_str).collect();
    let expanded = expand_name(app_name, &aspect_refs).map_err(|e| {
        ProtocolxRnsError::InvalidDestination {
            detail: format!("invalid destination name/aspects: {e:?}"),
        }
    })?;
    let hash = derive_destination_hash(&mat.identity_hash(), &expanded);
    Ok(hex::encode(hash.as_bytes()))
}

pub(crate) fn verify_signature_raw(
    public_key_bytes: &[u8],
    message: &[u8],
    signature_bytes: &[u8],
) -> Result<bool, ProtocolxRnsError> {
    let pub_mat =
        PublicIdentityMaterial::from_slice(public_key_bytes).map_err(|e| {
            ProtocolxRnsError::InvalidMaterial {
                detail: format!("expected {} public key bytes, got {}", e.expected, e.found),
            }
        })?;
    let sig_arr: [u8; 64] =
        signature_bytes
            .try_into()
            .map_err(|_| ProtocolxRnsError::InvalidMaterial {
                detail: format!(
                    "expected 64 signature bytes, got {}",
                    signature_bytes.len()
                ),
            })?;
    let sig = personal_rns::crypto::Ed25519Signature(sig_arr);
    Ok(pub_mat.verify(message, &sig).is_ok())
}
