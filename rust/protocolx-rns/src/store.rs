use std::fs;
use std::path::{Path, PathBuf};

use crate::error::ProtocolxRnsError;
use crate::types::{LxmfDeliveryReceipt, LxmfInboundMessage};

/// File-backed local persistence for decoded LXMF messages and delivery receipts.
pub(crate) struct LxmfLocalStore {
    messages_path: PathBuf,
    receipts_path: PathBuf,
}

impl LxmfLocalStore {
    pub(crate) fn open(storage_dir: &Path) -> Result<Self, ProtocolxRnsError> {
        fs::create_dir_all(storage_dir).map_err(|e| ProtocolxRnsError::InterfaceError {
            detail: format!("failed to create storage_dir {}: {e}", storage_dir.display()),
        })?;
        Ok(Self {
            messages_path: storage_dir.join("lxmf_messages.json"),
            receipts_path: storage_dir.join("lxmf_receipts.json"),
        })
    }

    pub(crate) fn load_messages(&self) -> Vec<LxmfInboundMessage> {
        load_json_vec(&self.messages_path)
    }

    pub(crate) fn load_receipts(&self) -> Vec<LxmfDeliveryReceipt> {
        load_json_vec(&self.receipts_path)
    }

    pub(crate) fn save_messages(&self, messages: &[LxmfInboundMessage]) -> Result<(), ProtocolxRnsError> {
        write_json_atomic(&self.messages_path, messages)
    }

    pub(crate) fn save_receipts(&self, receipts: &[LxmfDeliveryReceipt]) -> Result<(), ProtocolxRnsError> {
        write_json_atomic(&self.receipts_path, receipts)
    }
}

fn load_json_vec<T: serde::de::DeserializeOwned>(path: &Path) -> Vec<T> {
    let Ok(bytes) = fs::read(path) else {
        return Vec::new();
    };
    serde_json::from_slice(&bytes).unwrap_or_default()
}

fn write_json_atomic<T: serde::Serialize + ?Sized>(
    path: &Path,
    value: &T,
) -> Result<(), ProtocolxRnsError> {
    let tmp_path = path.with_extension("json.tmp");
    let encoded = serde_json::to_vec_pretty(value).map_err(|e| ProtocolxRnsError::TransportError {
        detail: format!("failed to serialize store {}: {e}", path.display()),
    })?;
    fs::write(&tmp_path, encoded).map_err(|e| ProtocolxRnsError::TransportError {
        detail: format!("failed to write {}: {e}", tmp_path.display()),
    })?;
    fs::rename(&tmp_path, path).map_err(|e| ProtocolxRnsError::TransportError {
        detail: format!("failed to commit {}: {e}", path.display()),
    })?;
    Ok(())
}
