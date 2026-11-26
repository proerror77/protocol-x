use std::io::Cursor;

use prns_core::crypto::{sha256, sha256_chunks, X25519SecretKey};
use prns_core::identity::{PrivateIdentityMaterial, PublicIdentityMaterial};
use rmpv::Value;

use crate::error::ProtocolxRnsError;
use crate::types::{LxmfAttachment, LxmfDeliveryMode, LxmfInboundMessage};

pub const LXMF_DEST_LEN: usize = 16;
pub const LXMF_SIG_LEN: usize = 64;
pub const LXMF_OVERHEAD: usize = LXMF_DEST_LEN * 2 + LXMF_SIG_LEN;
pub const LXMF_STAMP_SIZE: usize = 32;

/// Single-packet encrypted content ceiling from LXMF `LXMessage.ENCRYPTED_PACKET_MAX_CONTENT`.
pub const LXMF_ENCRYPTED_PACKET_MAX_CONTENT: usize = 287;
/// Link packet encrypted content ceiling from LXMF `LXMessage.LINK_PACKET_MAX_CONTENT`.
pub const LXMF_LINK_PACKET_MAX_CONTENT: usize = 319;
pub const LXMF_TIMESTAMP_SIZE: usize = 8;
pub const LXMF_STRUCT_OVERHEAD: usize = 8;

const FIELD_FILE_ATTACHMENTS: u64 = 0x05;
const FIELD_CUSTOM_TYPE: u64 = 0xFB;
const FIELD_CUSTOM_DATA: u64 = 0xFC;

/// Packed LXMF message ready for opportunistic, direct-link, or resource delivery.
#[derive(Debug, Clone)]
pub struct PackedLxmf {
    pub message_hash: [u8; 32],
    pub full_packed: Vec<u8>,
    pub content_size: usize,
}

impl PackedLxmf {
    /// Opportunistic wire payload elides the 16-byte destination hash.
    pub fn opportunistic_payload(&self) -> &[u8] {
        &self.full_packed[LXMF_DEST_LEN..]
    }

    pub fn message_hash_hex(&self) -> String {
        hex::encode(self.message_hash)
    }
}

/// Encode `lxmf.delivery` v0.5.0+ announce `app_data` as MsgPack `[display_name_bytes_or_nil, stamp_cost_or_nil]`.
pub fn pack_lxmf_announce_app_data(display_name: Option<&str>, stamp_cost: Option<u8>) -> Vec<u8> {
    let name_val = match display_name {
        Some(name) if !name.is_empty() => Value::Binary(name.as_bytes().to_vec()),
        _ => Value::Nil,
    };
    let cost_val = match stamp_cost {
        Some(cost) => Value::from(cost),
        None => Value::Nil,
    };
    let mut out = Vec::new();
    let _ = rmpv::encode::write_value(&mut out, &Value::Array(vec![name_val, cost_val]));
    out
}

/// Pack and sign an LXMF message compatible with official Python `LXMF 1.3.1`.
pub fn pack_signed(
    source_secret: &[u8; 64],
    source_dest_hash: &[u8; 16],
    dest_hash: &[u8; 16],
    timestamp_secs: f64,
    title: &str,
    content: &str,
    attachments: &[LxmfAttachment],
    custom_type: Option<&str>,
    custom_data: Option<&[u8]>,
) -> Result<PackedLxmf, ProtocolxRnsError> {
    let mut fields: Vec<(Value, Value)> = Vec::new();

    if !attachments.is_empty() {
        let items: Vec<Value> = attachments
            .iter()
            .map(|att| {
                Value::Array(vec![
                    Value::String(att.filename.clone().into()),
                    Value::Binary(att.data.clone()),
                ])
            })
            .collect();
        fields.push((Value::from(FIELD_FILE_ATTACHMENTS), Value::Array(items)));
    }

    if let Some(ctype) = custom_type {
        fields.push((
            Value::from(FIELD_CUSTOM_TYPE),
            Value::Binary(ctype.as_bytes().to_vec()),
        ));
    }

    if let Some(cdata) = custom_data {
        fields.push((
            Value::from(FIELD_CUSTOM_DATA),
            Value::Binary(cdata.to_vec()),
        ));
    }

    let payload_val = Value::Array(vec![
        Value::F64(timestamp_secs),
        Value::Binary(title.as_bytes().to_vec()),
        Value::Binary(content.as_bytes().to_vec()),
        Value::Map(fields),
    ]);

    let mut packed_payload = Vec::new();
    rmpv::encode::write_value(&mut packed_payload, &payload_val).map_err(|e| {
        ProtocolxRnsError::TransportError {
            detail: format!("failed to msgpack-encode LXMF payload: {e}"),
        }
    })?;

    let message_hash = sha256_chunks(&[dest_hash, source_dest_hash, &packed_payload]);
    let mut signed_part = Vec::with_capacity(LXMF_DEST_LEN * 2 + packed_payload.len() + 32);
    signed_part.extend_from_slice(dest_hash);
    signed_part.extend_from_slice(source_dest_hash);
    signed_part.extend_from_slice(&packed_payload);
    signed_part.extend_from_slice(&message_hash);

    let signer = PrivateIdentityMaterial::from_bytes(*source_secret);
    let sig = signer.sign(&signed_part);

    let mut full_packed = Vec::with_capacity(LXMF_OVERHEAD + packed_payload.len());
    full_packed.extend_from_slice(dest_hash);
    full_packed.extend_from_slice(source_dest_hash);
    full_packed.extend_from_slice(&sig.0);
    full_packed.extend_from_slice(&packed_payload);

    let content_size = packed_payload
        .len()
        .saturating_sub(LXMF_TIMESTAMP_SIZE + LXMF_STRUCT_OVERHEAD);

    Ok(PackedLxmf {
        message_hash,
        full_packed,
        content_size,
    })
}

/// Unpack a full LXMF wire frame (`dest_hash || src_hash || sig || msgpack_payload`) and verify signature if source key is known.
pub fn unpack_and_verify<F>(
    full_packed: &[u8],
    delivery_mode: LxmfDeliveryMode,
    lookup_source_pub: F,
) -> Result<LxmfInboundMessage, ProtocolxRnsError>
where
    F: FnOnce(&[u8; 16]) -> Option<PublicIdentityMaterial>,
{
    if full_packed.len() <= LXMF_OVERHEAD {
        return Err(ProtocolxRnsError::InvalidMaterial {
            detail: format!(
                "LXMF frame too short ({} <= {LXMF_OVERHEAD})",
                full_packed.len()
            ),
        });
    }

    let dest_hash: [u8; 16] = full_packed[..16]
        .try_into()
        .map_err(|_| ProtocolxRnsError::InvalidMaterial {
            detail: "invalid dest hash slice".to_string(),
        })?;
    let source_hash: [u8; 16] = full_packed[16..32]
        .try_into()
        .map_err(|_| ProtocolxRnsError::InvalidMaterial {
            detail: "invalid source hash slice".to_string(),
        })?;
    let sig_bytes: [u8; 64] = full_packed[32..96]
        .try_into()
        .map_err(|_| ProtocolxRnsError::InvalidMaterial {
            detail: "invalid signature slice".to_string(),
        })?;
    let raw_payload = &full_packed[96..];

    let mut cursor = Cursor::new(raw_payload);
    let unpacked = rmpv::decode::read_value(&mut cursor).map_err(|e| {
        ProtocolxRnsError::InvalidMaterial {
            detail: format!("invalid LXMF msgpack payload: {e}"),
        }
    })?;

    let Value::Array(mut items) = unpacked else {
        return Err(ProtocolxRnsError::InvalidMaterial {
            detail: "LXMF payload must be a MsgPack array".to_string(),
        });
    };
    if items.len() < 4 {
        return Err(ProtocolxRnsError::InvalidMaterial {
            detail: format!("LXMF payload array length {} < 4", items.len()),
        });
    }

    // If a trailing stamp (index 4) was appended, hash/verify over the 4-element payload.
    let canonical_payload_bytes = if items.len() > 4 {
        items.truncate(4);
        let mut buf = Vec::new();
        rmpv::encode::write_value(&mut buf, &Value::Array(items.clone())).map_err(|e| {
            ProtocolxRnsError::InvalidMaterial {
                detail: format!("failed to re-pack canonical LXMF payload: {e}"),
            }
        })?;
        buf
    } else {
        raw_payload.to_vec()
    };

    let message_hash = sha256_chunks(&[&dest_hash, &source_hash, &canonical_payload_bytes]);
    let mut signed_part = Vec::with_capacity(32 + canonical_payload_bytes.len() + 32);
    signed_part.extend_from_slice(&dest_hash);
    signed_part.extend_from_slice(&source_hash);
    signed_part.extend_from_slice(&canonical_payload_bytes);
    signed_part.extend_from_slice(&message_hash);

    let signature_valid = match lookup_source_pub(&source_hash) {
        Some(pub_mat) => {
            let sig = prns_core::crypto::Ed25519Signature(sig_bytes);
            pub_mat.verify(&signed_part, &sig).is_ok()
        }
        None => false,
    };

    let timestamp_secs = match &items[0] {
        Value::F64(v) => *v,
        Value::F32(v) => f64::from(*v),
        Value::Integer(i) => i.as_f64().unwrap_or(0.0),
        _ => 0.0,
    };
    let title = decode_text_field(&items[1]);
    let content = decode_text_field(&items[2]);

    let mut attachments = Vec::new();
    let mut custom_type = None;
    let mut custom_data = None;

    if let Value::Map(entries) = &items[3] {
        for (k, v) in entries {
            let Some(field_id) = k.as_u64() else {
                continue;
            };
            match field_id {
                FIELD_FILE_ATTACHMENTS => {
                    if let Value::Array(att_list) = v {
                        for entry in att_list {
                            if let Value::Array(pair) = entry {
                                if pair.len() >= 2 {
                                    let filename = decode_text_field(&pair[0]);
                                    if let Some(data) = decode_binary_field(&pair[1]) {
                                        attachments.push(LxmfAttachment { filename, data });
                                    }
                                }
                            }
                        }
                    }
                }
                FIELD_CUSTOM_TYPE => {
                    let s = decode_text_field(v);
                    if !s.is_empty() {
                        custom_type = Some(s);
                    }
                }
                FIELD_CUSTOM_DATA => {
                    custom_data = decode_binary_field(v);
                }
                _ => {}
            }
        }
    }

    Ok(LxmfInboundMessage {
        message_hash_hex: hex::encode(message_hash),
        source_hash_hex: hex::encode(source_hash),
        destination_hash_hex: hex::encode(dest_hash),
        timestamp_secs,
        title,
        content,
        delivery_mode,
        signature_valid,
        attachments,
        custom_type,
        custom_data,
    })
}

/// Unpack an opportunistic LXMF packet by prepending the local receiving `destination_hash`.
pub fn unpack_opportunistic<F>(
    dest_hash: &[u8; 16],
    packet_plaintext: &[u8],
    lookup_source_pub: F,
) -> Result<LxmfInboundMessage, ProtocolxRnsError>
where
    F: FnOnce(&[u8; 16]) -> Option<PublicIdentityMaterial>,
{
    let mut full = Vec::with_capacity(LXMF_DEST_LEN + packet_plaintext.len());
    full.extend_from_slice(dest_hash);
    full.extend_from_slice(packet_plaintext);
    unpack_and_verify(&full, LxmfDeliveryMode::Opportunistic, lookup_source_pub)
}

/// Build an LXMF propagation bundle `msgpack([timestamp, [dest_hash || encrypted(src||sig||payload) || stamp]])`.
pub fn pack_propagated_bundle(
    full_packed: &[u8],
    recipient_pub: &PublicIdentityMaterial,
    entropy_48: &[u8; 48],
    timestamp_secs: f64,
    stamp_cost: u8,
) -> Result<(Vec<u8>, [u8; 32]), ProtocolxRnsError> {
    if full_packed.len() <= LXMF_OVERHEAD {
        return Err(ProtocolxRnsError::InvalidMaterial {
            detail: "cannot pack propagated bundle from truncated LXMF message".to_string(),
        });
    }

    let plaintext = &full_packed[LXMF_DEST_LEN..];
    let mut eph_bytes = [0u8; 32];
    eph_bytes.copy_from_slice(&entropy_48[..32]);
    let mut iv = [0u8; 16];
    iv.copy_from_slice(&entropy_48[32..48]);
    let eph = X25519SecretKey::new(eph_bytes);

    // Token format overhead: 32B ephemeral pub + 16B IV + 16B PKCS7 max + 32B HMAC = 96B max.
    let mut cipher_buf = vec![0u8; plaintext.len() + 128];
    let cipher_len = recipient_pub
        .encrypt(&eph, &iv, plaintext, &mut cipher_buf)
        .map_err(|e| ProtocolxRnsError::TransportError {
            detail: format!("failed to encrypt propagated LXMF payload: {e:?}"),
        })?;
    cipher_buf.truncate(cipher_len);

    let mut lxm_data = Vec::with_capacity(LXMF_DEST_LEN + cipher_len);
    lxm_data.extend_from_slice(&full_packed[..LXMF_DEST_LEN]);
    lxm_data.extend_from_slice(&cipher_buf);

    let transient_id = sha256(&lxm_data);
    let stamp = generate_fast_pn_stamp(&transient_id, stamp_cost);

    let mut stamped = Vec::with_capacity(lxm_data.len() + LXMF_STAMP_SIZE);
    stamped.extend_from_slice(&lxm_data);
    stamped.extend_from_slice(&stamp);

    let bundle_val = Value::Array(vec![
        Value::F64(timestamp_secs),
        Value::Array(vec![Value::Binary(stamped)]),
    ]);
    let mut encoded = Vec::new();
    rmpv::encode::write_value(&mut encoded, &bundle_val).map_err(|e| {
        ProtocolxRnsError::TransportError {
            detail: format!("failed to encode propagation bundle: {e}"),
        }
    })?;

    Ok((encoded, transient_id))
}

/// Parse `/get` response `[transient_id, ...]` from an LXMF propagation node.
pub fn unpack_propagated_id_list(response_bytes: &[u8]) -> Result<Vec<Vec<u8>>, ProtocolxRnsError> {
    let mut cursor = Cursor::new(response_bytes);
    let val = rmpv::decode::read_value(&mut cursor).map_err(|e| {
        ProtocolxRnsError::InvalidMaterial {
            detail: format!("invalid propagation id list response: {e}"),
        }
    })?;
    let Value::Array(items) = val else {
        return Err(ProtocolxRnsError::InvalidMaterial {
            detail: "propagation /get id list was not a MsgPack array".to_string(),
        });
    };
    let mut ids = Vec::new();
    for item in items {
        if let Some(b) = decode_binary_field(&item) {
            ids.push(b);
        }
    }
    Ok(ids)
}

/// Encode `/get` request `[want_ids_or_nil, have_ids_or_nil]` for an LXMF propagation node.
pub fn pack_propagated_get_request(want_ids: Option<&[Vec<u8>]>) -> Result<Vec<u8>, ProtocolxRnsError> {
    let want_val = match want_ids {
        None => Value::Nil,
        Some(ids) => Value::Array(ids.iter().map(|id| Value::Binary(id.clone())).collect()),
    };
    let mut out = Vec::new();
    rmpv::encode::write_value(&mut out, &Value::Array(vec![want_val, Value::Nil])).map_err(
        |e| ProtocolxRnsError::TransportError {
            detail: format!("failed to encode /get request: {e}"),
        },
    )?;
    Ok(out)
}

/// Decrypt and unpack messages returned by an LXMF propagation node's `/get` endpoint.
pub fn unpack_propagated_messages<F>(
    response_bytes: &[u8],
    recipient_secret: &[u8; 64],
    mut lookup_source_pub: F,
) -> Result<Vec<LxmfInboundMessage>, ProtocolxRnsError>
where
    F: FnMut(&[u8; 16]) -> Option<PublicIdentityMaterial>,
{
    let mut cursor = Cursor::new(response_bytes);
    let val = rmpv::decode::read_value(&mut cursor).map_err(|e| {
        ProtocolxRnsError::InvalidMaterial {
            detail: format!("invalid propagation messages response: {e}"),
        }
    })?;
    let Value::Array(items) = val else {
        return Err(ProtocolxRnsError::InvalidMaterial {
            detail: "propagation /get messages response was not a MsgPack array".to_string(),
        });
    };

    let identity = PrivateIdentityMaterial::from_bytes(*recipient_secret);
    let mut decoded = Vec::new();
    for entry in items {
        let Some(lxm_data) = decode_binary_field(&entry) else {
            continue;
        };
        if lxm_data.len() <= LXMF_DEST_LEN {
            continue;
        }
        let dest_prefix = &lxm_data[..LXMF_DEST_LEN];
        let encrypted = &lxm_data[LXMF_DEST_LEN..];
        let mut plain_buf = vec![0u8; encrypted.len()];
        let Ok(plain_len) = identity.decrypt(encrypted, &mut plain_buf) else {
            continue;
        };
        plain_buf.truncate(plain_len);

        let mut full_packed = Vec::with_capacity(LXMF_DEST_LEN + plain_len);
        full_packed.extend_from_slice(dest_prefix);
        full_packed.extend_from_slice(&plain_buf);
        if let Ok(msg) = unpack_and_verify(
            &full_packed,
            LxmfDeliveryMode::Propagated,
            |src| lookup_source_pub(src),
        ) {
            decoded.push(msg);
        }
    }
    Ok(decoded)
}

/// Generate a 32-byte stamp accepted by Python `LXStamper.validate_pn_stamp` (`no_expand=True` path).
fn generate_fast_pn_stamp(transient_id: &[u8; 32], cost: u8) -> [u8; LXMF_STAMP_SIZE] {
    if cost == 0 {
        return [0u8; LXMF_STAMP_SIZE];
    }
    let bits = cost.min(24) as usize;
    let mut nonce: u64 = 0;
    loop {
        let mut candidate = [0u8; LXMF_STAMP_SIZE];
        candidate[..8].copy_from_slice(&nonce.to_le_bytes());
        let digest = sha256_chunks(&[transient_id, &candidate]);
        if has_leading_zero_bits(&digest, bits) {
            return candidate;
        }
        nonce = nonce.wrapping_add(1);
    }
}

fn has_leading_zero_bits(digest: &[u8; 32], required_bits: usize) -> bool {
    let full_bytes = required_bits / 8;
    let rem_bits = required_bits % 8;
    if digest[..full_bytes].iter().any(|&b| b != 0) {
        return false;
    }
    if rem_bits == 0 {
        return true;
    }
    let mask = 0xFFu8 << (8 - rem_bits);
    (digest[full_bytes] & mask) == 0
}

fn decode_text_field(value: &Value) -> String {
    match value {
        Value::String(s) => s.as_str().unwrap_or_default().to_string(),
        Value::Binary(b) => String::from_utf8_lossy(b).into_owned(),
        _ => String::new(),
    }
}

fn decode_binary_field(value: &Value) -> Option<Vec<u8>> {
    match value {
        Value::Binary(b) => Some(b.clone()),
        Value::String(s) => s.as_str().map(|st| st.as_bytes().to_vec()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use personal_rns::prelude::try_generate_identity_secret;
    use personal_rns::routing::announce::{derive_destination_hash, expand_name};

    #[test]
    fn lxmf_pack_and_unpack_roundtrip_with_attachments() {
        let src_secret = *try_generate_identity_secret().expect("src secret");
        let dst_secret = *try_generate_identity_secret().expect("dst secret");

        let src_mat = PrivateIdentityMaterial::from_bytes(src_secret);
        let dst_mat = PrivateIdentityMaterial::from_bytes(dst_secret);
        let expanded = expand_name("lxmf", &["delivery"]).expect("lxmf.delivery");
        let src_dest = *derive_destination_hash(&src_mat.identity_hash(), &expanded).as_bytes();
        let dst_dest = *derive_destination_hash(&dst_mat.identity_hash(), &expanded).as_bytes();

        let packed = pack_signed(
            &src_secret,
            &src_dest,
            &dst_dest,
            1_700_000_000.25,
            "Alert",
            "Cart 03 reassigned",
            &[LxmfAttachment {
                filename: "note.txt".to_string(),
                data: b"gate-b".to_vec(),
            }],
            Some("protocolx/v1"),
            Some(br#"{"ok":true}"#),
        )
        .expect("pack_signed");

        let unpacked = unpack_and_verify(&packed.full_packed, LxmfDeliveryMode::Direct, |h| {
            if *h == src_dest {
                Some(src_mat.public())
            } else {
                None
            }
        })
        .expect("unpack_and_verify");

        assert!(unpacked.signature_valid);
        assert_eq!(unpacked.title, "Alert");
        assert_eq!(unpacked.content, "Cart 03 reassigned");
        assert_eq!(unpacked.attachments.len(), 1);
        assert_eq!(unpacked.attachments[0].filename, "note.txt");
        assert_eq!(unpacked.attachments[0].data, b"gate-b");
        assert_eq!(unpacked.custom_type.as_deref(), Some("protocolx/v1"));
        assert_eq!(unpacked.custom_data.as_deref(), Some(br#"{"ok":true}"#.as_slice()));
    }
}
