use std::collections::HashMap;
use std::io::Cursor;
use std::sync::Mutex;

use rmpv::Value;

use crate::error::ProtocolxRnsError;
use crate::types::{PttFloorAction, PttFloorControlMessage, PttFramePacket};

pub const PTT_WIRE_MAGIC: &[u8; 5] = b"PXPT1";
const KIND_PTT_FRAME: u64 = 1;
const KIND_PTT_FLOOR: u64 = 2;

/// Decoded RNS-10 PTT wire payload (`PXPT1` + MsgPack).
#[derive(Debug, Clone, PartialEq)]
pub(crate) enum DecodedPttWire {
    Frame(PttFramePacket),
    Floor(PttFloorControlMessage),
}

pub(crate) fn encode_ptt_frame_wire(frame: &PttFramePacket) -> Result<Vec<u8>, ProtocolxRnsError> {
    let sender_bytes = hex::decode(&frame.sender_hash_hex).map_err(|e| {
        ProtocolxRnsError::InvalidMaterial {
            detail: format!("invalid sender_hash_hex: {e}"),
        }
    })?;
    let val = Value::Array(vec![
        Value::Integer(KIND_PTT_FRAME.into()),
        Value::String(frame.channel_id.clone().into()),
        Value::String(frame.session_id.clone().into()),
        Value::Binary(sender_bytes),
        Value::Integer(frame.seq.into()),
        Value::Integer(frame.timestamp_ms.into()),
        Value::String(frame.codec.clone().into()),
        Value::Integer(u64::from(frame.duration_ms).into()),
        Value::Binary(frame.audio_bytes.clone()),
    ]);
    let mut out = Vec::with_capacity(PTT_WIRE_MAGIC.len() + 64 + frame.audio_bytes.len());
    out.extend_from_slice(PTT_WIRE_MAGIC);
    rmpv::encode::write_value(&mut out, &val).map_err(|e| ProtocolxRnsError::TransportError {
        detail: format!("failed to encode PTT frame: {e}"),
    })?;
    Ok(out)
}

pub(crate) fn encode_ptt_floor_wire(
    control: &PttFloorControlMessage,
) -> Result<Vec<u8>, ProtocolxRnsError> {
    let actor_bytes = hex::decode(&control.actor_hash_hex).map_err(|e| {
        ProtocolxRnsError::InvalidMaterial {
            detail: format!("invalid actor_hash_hex: {e}"),
        }
    })?;
    let holder_val = match &control.holder_hash_hex {
        Some(h) => Value::Binary(hex::decode(h).unwrap_or_default()),
        None => Value::Nil,
    };
    let val = Value::Array(vec![
        Value::Integer(KIND_PTT_FLOOR.into()),
        Value::String(control.channel_id.clone().into()),
        Value::String(control.session_id.clone().into()),
        Value::Binary(actor_bytes),
        Value::Integer(u64::from(action_to_code(control.action)).into()),
        Value::Integer(u64::from(control.priority).into()),
        Value::Integer(control.timestamp_ms.into()),
        holder_val,
        Value::String(control.detail.clone().into()),
    ]);
    let mut out = Vec::with_capacity(PTT_WIRE_MAGIC.len() + 96);
    out.extend_from_slice(PTT_WIRE_MAGIC);
    rmpv::encode::write_value(&mut out, &val).map_err(|e| ProtocolxRnsError::TransportError {
        detail: format!("failed to encode PTT floor message: {e}"),
    })?;
    Ok(out)
}

pub(crate) fn try_decode_ptt_wire(
    raw: &[u8],
    observed_hops: u8,
) -> Option<Result<DecodedPttWire, ProtocolxRnsError>> {
    if !raw.starts_with(PTT_WIRE_MAGIC) {
        return None;
    }
    Some(decode_ptt_wire_body(&raw[PTT_WIRE_MAGIC.len()..], observed_hops))
}

fn decode_ptt_wire_body(body: &[u8], observed_hops: u8) -> Result<DecodedPttWire, ProtocolxRnsError> {
    let mut cursor = Cursor::new(body);
    let val = rmpv::decode::read_value(&mut cursor).map_err(|e| {
        ProtocolxRnsError::InvalidMaterial {
            detail: format!("invalid PTT msgpack payload: {e}"),
        }
    })?;
    let Value::Array(items) = val else {
        return Err(ProtocolxRnsError::InvalidMaterial {
            detail: "PTT payload must be a MsgPack array".to_string(),
        });
    };
    let kind = items.first().and_then(Value::as_u64).unwrap_or(0);
    match kind {
        KIND_PTT_FRAME if items.len() >= 9 => {
            let sender_bytes = match &items[3] {
                Value::Binary(b) => b.clone(),
                _ => Vec::new(),
            };
            let audio_bytes = match &items[8] {
                Value::Binary(b) => b.clone(),
                _ => Vec::new(),
            };
            Ok(DecodedPttWire::Frame(PttFramePacket {
                channel_id: items[1].as_str().unwrap_or_default().to_string(),
                session_id: items[2].as_str().unwrap_or_default().to_string(),
                sender_hash_hex: hex::encode(sender_bytes),
                seq: items[4].as_u64().unwrap_or(0),
                timestamp_ms: items[5].as_u64().unwrap_or(0),
                codec: items[6].as_str().unwrap_or("opus").to_string(),
                duration_ms: items[7].as_u64().unwrap_or(20) as u32,
                audio_bytes,
                hop_count: observed_hops,
            }))
        }
        KIND_PTT_FLOOR if items.len() >= 9 => {
            let actor_bytes = match &items[3] {
                Value::Binary(b) => b.clone(),
                _ => Vec::new(),
            };
            let holder_hash_hex = match &items[7] {
                Value::Binary(b) if !b.is_empty() => Some(hex::encode(b)),
                Value::String(s) => s.as_str().map(str::to_string),
                _ => None,
            };
            let code = items[4].as_u64().unwrap_or(0) as u8;
            Ok(DecodedPttWire::Floor(PttFloorControlMessage {
                channel_id: items[1].as_str().unwrap_or_default().to_string(),
                session_id: items[2].as_str().unwrap_or_default().to_string(),
                actor_hash_hex: hex::encode(actor_bytes),
                action: code_to_action(code),
                priority: items[5].as_u64().unwrap_or(0) as u8,
                timestamp_ms: items[6].as_u64().unwrap_or(0),
                holder_hash_hex,
                detail: items[8].as_str().unwrap_or_default().to_string(),
            }))
        }
        _ => Err(ProtocolxRnsError::InvalidMaterial {
            detail: format!("unknown or truncated PTT wire record kind {kind}"),
        }),
    }
}

fn action_to_code(action: PttFloorAction) -> u8 {
    match action {
        PttFloorAction::Request => 1,
        PttFloorAction::Grant => 2,
        PttFloorAction::Busy => 3,
        PttFloorAction::Preempt => 4,
        PttFloorAction::Release => 5,
    }
}

fn code_to_action(code: u8) -> PttFloorAction {
    match code {
        2 => PttFloorAction::Grant,
        3 => PttFloorAction::Busy,
        4 => PttFloorAction::Preempt,
        5 => PttFloorAction::Release,
        _ => PttFloorAction::Request,
    }
}

#[derive(Debug, Clone)]
struct FloorHolder {
    actor_hash_hex: String,
    priority: u8,
}

/// Deterministic floor-control arbiter supporting contention (`Busy`) and priority preemption (`Preempt`).
#[derive(Default)]
pub(crate) struct PttFloorArbiter {
    holders: Mutex<HashMap<(String, String), FloorHolder>>,
}

impl PttFloorArbiter {
    pub(crate) fn new() -> Self {
        Self::default()
    }

    /// Arbitrate an incoming or local floor request/release and emit the resulting `PttFloorControlMessage`.
    pub(crate) fn apply_control(
        &self,
        incoming: PttFloorControlMessage,
    ) -> PttFloorControlMessage {
        let key = (incoming.channel_id.clone(), incoming.session_id.clone());
        let Ok(mut map) = self.holders.lock() else {
            return incoming;
        };

        match incoming.action {
            PttFloorAction::Request => match map.get(&key).cloned() {
                None => {
                    map.insert(
                        key,
                        FloorHolder {
                            actor_hash_hex: incoming.actor_hash_hex.clone(),
                            priority: incoming.priority,
                        },
                    );
                    PttFloorControlMessage {
                        action: PttFloorAction::Grant,
                        holder_hash_hex: Some(incoming.actor_hash_hex.clone()),
                        detail: "floor granted".to_string(),
                        ..incoming
                    }
                }
                Some(cur) if cur.actor_hash_hex == incoming.actor_hash_hex => {
                    map.insert(
                        key,
                        FloorHolder {
                            actor_hash_hex: incoming.actor_hash_hex.clone(),
                            priority: cur.priority.max(incoming.priority),
                        },
                    );
                    PttFloorControlMessage {
                        action: PttFloorAction::Grant,
                        holder_hash_hex: Some(cur.actor_hash_hex),
                        detail: "floor renewed".to_string(),
                        ..incoming
                    }
                }
                Some(cur) if incoming.priority > cur.priority => {
                    map.insert(
                        key,
                        FloorHolder {
                            actor_hash_hex: incoming.actor_hash_hex.clone(),
                            priority: incoming.priority,
                        },
                    );
                    PttFloorControlMessage {
                        action: PttFloorAction::Preempt,
                        holder_hash_hex: Some(incoming.actor_hash_hex.clone()),
                        detail: format!(
                            "preempted {} (priority {} > {})",
                            cur.actor_hash_hex, incoming.priority, cur.priority
                        ),
                        ..incoming
                    }
                }
                Some(cur) => PttFloorControlMessage {
                    action: PttFloorAction::Busy,
                    holder_hash_hex: Some(cur.actor_hash_hex),
                    detail: "floor busy".to_string(),
                    ..incoming
                },
            },
            PttFloorAction::Grant | PttFloorAction::Preempt => {
                map.insert(
                    key,
                    FloorHolder {
                        actor_hash_hex: incoming
                            .holder_hash_hex
                            .clone()
                            .unwrap_or_else(|| incoming.actor_hash_hex.clone()),
                        priority: incoming.priority,
                    },
                );
                incoming
            }
            PttFloorAction::Release => {
                if let Some(cur) = map.get(&key) {
                    if cur.actor_hash_hex == incoming.actor_hash_hex {
                        map.remove(&key);
                    }
                }
                PttFloorControlMessage {
                    action: PttFloorAction::Release,
                    holder_hash_hex: None,
                    detail: "floor released".to_string(),
                    ..incoming
                }
            }
            PttFloorAction::Busy => incoming,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ptt_frame_and_floor_codec_and_arbitration_roundtrip() {
        let frame = PttFramePacket {
            channel_id: "group-1".into(),
            session_id: "sess-1".into(),
            sender_hash_hex: "aa".repeat(16),
            seq: 42,
            timestamp_ms: 1_700_000_000_000,
            codec: "opus".into(),
            duration_ms: 20,
            audio_bytes: vec![0x5Au8; 80],
            hop_count: 0,
        };
        let wire = encode_ptt_frame_wire(&frame).expect("encode frame");
        assert!(wire.len() < 200, "compact single-packet fit: {}", wire.len());
        let decoded = try_decode_ptt_wire(&wire, 3)
            .expect("magic matched")
            .expect("decoded");
        match decoded {
            DecodedPttWire::Frame(out) => {
                assert_eq!(out.seq, 42);
                assert_eq!(out.hop_count, 3);
                assert_eq!(out.audio_bytes, frame.audio_bytes);
            }
            _ => panic!("expected frame"),
        }

        let arbiter = PttFloorArbiter::new();
        let req1 = arbiter.apply_control(PttFloorControlMessage {
            channel_id: "group-1".into(),
            session_id: "sess-1".into(),
            actor_hash_hex: "aa".repeat(16),
            action: PttFloorAction::Request,
            priority: 1,
            timestamp_ms: 100,
            holder_hash_hex: None,
            detail: String::new(),
        });
        assert_eq!(req1.action, PttFloorAction::Grant);

        let req2 = arbiter.apply_control(PttFloorControlMessage {
            channel_id: "group-1".into(),
            session_id: "sess-1".into(),
            actor_hash_hex: "bb".repeat(16),
            action: PttFloorAction::Request,
            priority: 1,
            timestamp_ms: 110,
            holder_hash_hex: None,
            detail: String::new(),
        });
        assert_eq!(req2.action, PttFloorAction::Busy);
        assert_eq!(req2.holder_hash_hex.as_deref(), Some("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));

        let preempt = arbiter.apply_control(PttFloorControlMessage {
            channel_id: "group-1".into(),
            session_id: "sess-1".into(),
            actor_hash_hex: "cc".repeat(16),
            action: PttFloorAction::Request,
            priority: 3,
            timestamp_ms: 120,
            holder_hash_hex: None,
            detail: String::new(),
        });
        assert_eq!(preempt.action, PttFloorAction::Preempt);
        assert_eq!(preempt.holder_hash_hex.as_deref(), Some("cccccccccccccccccccccccccccccccc"));
    }
}
