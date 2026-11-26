use std::collections::{HashMap, HashSet};
use std::sync::Mutex;

use prns_core::crypto::sha256_chunks;
use prns_core::interfaces::{IfacContext, IfacSize};

use crate::error::ProtocolxRnsError;
use crate::types::{ActorDeviceBinding, IfacConfig};

const BINDING_DOMAIN_TAG: &[u8] = b"protocolx-rns-actor-binding-v1";
const IFAC_DERIVE_DOMAIN_TAG: &[u8] = b"protocolx-rns-ifac-v1";

/// Derive deterministic per-event/channel IFAC network name and passphrase (`IfacSize::WIDE` = 16 bytes).
#[uniffi::export]
pub fn derive_event_ifac_config(
    event_id: String,
    channel_id: Option<String>,
    shared_secret: String,
    epoch: u32,
) -> IfacConfig {
    let channel_part = channel_id
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or("event-global");
    let epoch_bytes = epoch.to_be_bytes();
    let netname_digest = sha256_chunks(&[
        IFAC_DERIVE_DOMAIN_TAG,
        b"netname",
        event_id.trim().as_bytes(),
        channel_part.as_bytes(),
        &epoch_bytes,
    ]);
    let passphrase_digest = sha256_chunks(&[
        IFAC_DERIVE_DOMAIN_TAG,
        b"passphrase",
        event_id.trim().as_bytes(),
        channel_part.as_bytes(),
        shared_secret.as_bytes(),
        &epoch_bytes,
    ]);

    IfacConfig {
        network_name: Some(format!("px-ifac-{}", hex::encode(&netname_digest[..12]))),
        passphrase: Some(hex::encode(passphrase_digest)),
        ifac_size_bytes: 16,
        require_private_ifac: true,
    }
}

/// Bind `actor_id` and `device_token_hash` to an RNS identity, destination hash, and Ed25519 public key.
#[uniffi::export]
pub fn bind_actor_device_to_rns(
    actor_id: String,
    device_token_hash: String,
    rns_destination_hash_hex: String,
    rns_identity_hash_hex: String,
    ed25519_public_key_hex: String,
) -> Result<ActorDeviceBinding, ProtocolxRnsError> {
    let actor = actor_id.trim().to_string();
    let token_hash = device_token_hash.trim().to_string();
    if actor.is_empty() || token_hash.is_empty() {
        return Err(ProtocolxRnsError::InvalidMaterial {
            detail: "actor_id and device_token_hash must be non-empty".to_string(),
        });
    }
    let dest_bytes = decode_fixed_hex::<16>(&rns_destination_hash_hex, "rns_destination_hash_hex")?;
    let id_bytes = decode_fixed_hex::<16>(&rns_identity_hash_hex, "rns_identity_hash_hex")?;
    let pub_bytes = decode_fixed_hex::<32>(&ed25519_public_key_hex, "ed25519_public_key_hex")?;

    let digest = compute_binding_digest(&actor, &token_hash, &dest_bytes, &id_bytes, &pub_bytes);
    Ok(ActorDeviceBinding {
        actor_id: actor,
        device_token_hash: token_hash,
        rns_destination_hash_hex: hex::encode(dest_bytes),
        rns_identity_hash_hex: hex::encode(id_bytes),
        ed25519_public_key_hex: hex::encode(pub_bytes),
        binding_digest_hex: hex::encode(digest),
    })
}

/// Verify an `ActorDeviceBinding` digest and canonical field lengths.
#[uniffi::export]
pub fn verify_actor_device_binding(binding: ActorDeviceBinding) -> bool {
    let actor = binding.actor_id.trim();
    let token = binding.device_token_hash.trim();
    if actor.is_empty() || token.is_empty() {
        return false;
    }
    let Ok(dest) = decode_fixed_hex::<16>(&binding.rns_destination_hash_hex, "dest") else {
        return false;
    };
    let Ok(id) = decode_fixed_hex::<16>(&binding.rns_identity_hash_hex, "id") else {
        return false;
    };
    let Ok(ed_pub) = decode_fixed_hex::<32>(&binding.ed25519_public_key_hex, "ed_pub") else {
        return false;
    };
    let Ok(expected_digest) = decode_fixed_hex::<32>(&binding.binding_digest_hex, "digest") else {
        return false;
    };
    let actual = compute_binding_digest(actor, token, &dest, &id, &ed_pub);
    actual == expected_digest
}

fn compute_binding_digest(
    actor_id: &str,
    device_token_hash: &str,
    dest_hash: &[u8; 16],
    identity_hash: &[u8; 16],
    ed25519_pub: &[u8; 32],
) -> [u8; 32] {
    sha256_chunks(&[
        BINDING_DOMAIN_TAG,
        actor_id.as_bytes(),
        b"|",
        device_token_hash.as_bytes(),
        b"|",
        dest_hash,
        identity_hash,
        ed25519_pub,
    ])
}

fn decode_fixed_hex<const N: usize>(raw: &str, label: &str) -> Result<[u8; N], ProtocolxRnsError> {
    let trimmed = raw.trim();
    let bytes = hex::decode(trimmed).map_err(|e| ProtocolxRnsError::InvalidMaterial {
        detail: format!("invalid {label} hex: {e}"),
    })?;
    bytes.try_into().map_err(|v: Vec<u8>| ProtocolxRnsError::InvalidMaterial {
        detail: format!("expected {N} bytes for {label}, got {}", v.len()),
    })
}

/// Build an `IfacContext` from `Option<IfacConfig>`, enforcing `require_private_ifac`.
pub(crate) fn build_ifac_context(
    ifac: &Option<IfacConfig>,
) -> Result<Option<(IfacContext, Option<String>)>, ProtocolxRnsError> {
    let Some(cfg) = ifac else {
        return Ok(None);
    };
    let netname = cfg
        .network_name
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty());
    let passphrase = cfg
        .passphrase
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty());

    if netname.is_none() && passphrase.is_none() {
        if cfg.require_private_ifac {
            return Err(ProtocolxRnsError::InvalidMaterial {
                detail: "require_private_ifac=true requires non-empty network_name or passphrase"
                    .to_string(),
            });
        }
        return Ok(None);
    }

    let size_bytes = if cfg.ifac_size_bytes == 0 {
        16usize
    } else {
        usize::from(cfg.ifac_size_bytes)
    };
    let size = IfacSize::new(size_bytes).map_err(|e| ProtocolxRnsError::InvalidMaterial {
        detail: format!("invalid ifac_size_bytes ({size_bytes}): {e:?}"),
    })?;
    let ctx = IfacContext::derive(netname, passphrase, size).ok_or_else(|| {
        ProtocolxRnsError::InvalidMaterial {
            detail: "failed to derive IFAC context".to_string(),
        }
    })?;

    Ok(Some((ctx, netname.map(str::to_string))))
}

/// Runtime revocation table blocking revoked identity or destination hashes at node and bridge boundaries.
#[derive(Default)]
pub(crate) struct RevocationList {
    hashes: Mutex<HashSet<[u8; 16]>>,
    reasons: Mutex<HashMap<[u8; 16], String>>,
}

impl RevocationList {
    pub(crate) fn new() -> Self {
        Self::default()
    }

    pub(crate) fn parse_hash16(hex_str: &str) -> Result<[u8; 16], ProtocolxRnsError> {
        let trimmed = hex_str.trim();
        let bytes = hex::decode(trimmed).map_err(|e| ProtocolxRnsError::InvalidDestination {
            detail: format!("invalid 16-byte hash hex '{trimmed}': {e}"),
        })?;
        bytes.try_into().map_err(|_| ProtocolxRnsError::InvalidDestination {
            detail: format!("expected 32 hex chars, got '{}'", trimmed),
        })
    }

    pub(crate) fn revoke(&self, hash: [u8; 16], reason: Option<String>) {
        if let Ok(mut set) = self.hashes.lock() {
            set.insert(hash);
        }
        if let Some(r) = reason {
            if let Ok(mut map) = self.reasons.lock() {
                map.insert(hash, r);
            }
        }
    }

    pub(crate) fn unrevoke(&self, hash: &[u8; 16]) {
        if let Ok(mut set) = self.hashes.lock() {
            set.remove(hash);
        }
        if let Ok(mut map) = self.reasons.lock() {
            map.remove(hash);
        }
    }

    pub(crate) fn is_revoked(&self, hash: &[u8; 16]) -> bool {
        self.hashes
            .lock()
            .map(|set| set.contains(hash))
            .unwrap_or(false)
    }

    pub(crate) fn all_hex(&self) -> Vec<String> {
        let mut out: Vec<String> = self
            .hashes
            .lock()
            .map(|set| set.iter().map(hex::encode).collect())
            .unwrap_or_default();
        out.sort();
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn event_ifac_derivation_and_actor_binding_roundtrip() {
        let a = derive_event_ifac_config(
            "golf-demo".into(),
            Some("group-abc".into()),
            "shared-secret-1".into(),
            1,
        );
        let b = derive_event_ifac_config(
            "golf-demo".into(),
            Some("group-abc".into()),
            "shared-secret-1".into(),
            1,
        );
        let c = derive_event_ifac_config(
            "golf-demo".into(),
            Some("group-abc".into()),
            "shared-secret-1".into(),
            2,
        );
        assert_eq!(a, b);
        assert_ne!(a.passphrase, c.passphrase);
        assert!(build_ifac_context(&Some(a)).expect("valid ifac").is_some());

        let empty_strict = IfacConfig {
            network_name: None,
            passphrase: None,
            ifac_size_bytes: 16,
            require_private_ifac: true,
        };
        assert!(build_ifac_context(&Some(empty_strict)).is_err());

        let binding = bind_actor_device_to_rns(
            "person-123".into(),
            "deadbeef01".into(),
            "11".repeat(16),
            "22".repeat(16),
            "33".repeat(32),
        )
        .expect("bind");
        assert!(verify_actor_device_binding(binding.clone()));
        let mut tampered = binding;
        tampered.actor_id = "person-999".into();
        assert!(!verify_actor_device_binding(tampered));
    }
}
