use std::collections::{HashMap, HashSet, VecDeque};
use std::path::PathBuf;
use std::sync::{mpsc, Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use personal_rns::identity::{
    PrivateIdentityMaterial, PublicIdentityMaterial, IDENTITY_SECRET_KEY_LEN,
};
use personal_rns::prelude::{
    request_endpoints, try_generate_identity_secret, AnnounceAppData, AnnounceNow,
    AnnounceTarget, BitrateBps, DestinationHash, Diagnostic, GrowableHeap, LinkRequestPolicy,
    ManuallyAttached, Message, NoPersistence, NodePersistence, PreConfiguredDestination,
    PrnsEvent, PrnsNode, PrnsNodeHandle, PrnsNodeRecipe, ProofStrategy, RatchetPolicy,
    RequestEndpointId, ResourceStrategy, ServeMyRequestEndpoints, TcpClientInterface, TcpServer,
    Zeroizing,
};
use personal_rns::routing::announce::{derive_destination_hash, expand_name};
use personal_rns::routing::delivery::Delivery;
use personal_rns::routing::links::LinkId;
use personal_rns::udp::UdpInterface;
use prns_runtime_tokio::runtime::node_introspection::DestinationIdentityQuery;
use prns_runtime_tokio::runtime::AttachedInterface;
use tokio::runtime::Runtime;
use tokio::sync::{oneshot, Notify};

use crate::bridge::{BridgeRegistry, CustomBridgeInterface};
use crate::error::ProtocolxRnsError;
use crate::identity::{compute_destination_hash_for_secret, RnsIdentity};
use crate::lxmf::{
    pack_lxmf_announce_app_data, pack_propagated_bundle, pack_propagated_get_request, pack_signed,
    unpack_and_verify, unpack_opportunistic, unpack_propagated_id_list, unpack_propagated_messages,
    LXMF_ENCRYPTED_PACKET_MAX_CONTENT, LXMF_LINK_PACKET_MAX_CONTENT, LXMF_OVERHEAD,
};
use crate::store::LxmfLocalStore;
use crate::types::{
    CustomInterfaceKind, CustomLinkMetrics, LxmfDeliveryMode, LxmfDeliveryReceipt,
    LxmfInboundMessage, LxmfOutboundMessage, LxmfReceiptStatus, NodeConfig, NodeStatus,
    RnsCustomInterfaceBridge, RnsEventListener, RnsInboundEvent,
};

struct RuntimeState {
    handle: PrnsNodeHandle,
    shutdown_tx: Option<oneshot::Sender<()>>,
    thread_handle: Option<JoinHandle<()>>,
    bound_tcp_addr: Option<String>,
    custom_ifaces: HashMap<String, AttachedInterface>,
}

struct SharedEventState {
    queue: Mutex<VecDeque<RnsInboundEvent>>,
    notify: Notify,
    listener: Mutex<Option<Box<dyn RnsEventListener>>>,
    known_peers: Mutex<HashSet<String>>,
    active_links: Mutex<HashSet<String>>,
    lxmf_messages: Mutex<Vec<LxmfInboundMessage>>,
    lxmf_seen_hashes: Mutex<HashSet<String>>,
    lxmf_receipts: Mutex<Vec<LxmfDeliveryReceipt>>,
    outbound_links: Mutex<HashMap<[u8; 16], LinkId>>,
    peer_pubkeys: Mutex<HashMap<[u8; 16], PublicIdentityMaterial>>,
    announce_lookup_tx: Mutex<Option<tokio::sync::mpsc::UnboundedSender<DestinationHash>>>,
    store: Option<LxmfLocalStore>,
    bridges: BridgeRegistry,
}

impl SharedEventState {
    fn new(store: Option<LxmfLocalStore>) -> Self {
        let (messages, receipts) = match &store {
            Some(s) => (s.load_messages(), s.load_receipts()),
            None => (Vec::new(), Vec::new()),
        };
        let seen: HashSet<String> = messages
            .iter()
            .map(|m| m.message_hash_hex.clone())
            .collect();

        Self {
            queue: Mutex::new(VecDeque::new()),
            notify: Notify::new(),
            listener: Mutex::new(None),
            known_peers: Mutex::new(HashSet::new()),
            active_links: Mutex::new(HashSet::new()),
            lxmf_messages: Mutex::new(messages),
            lxmf_seen_hashes: Mutex::new(seen),
            lxmf_receipts: Mutex::new(receipts),
            outbound_links: Mutex::new(HashMap::new()),
            peer_pubkeys: Mutex::new(HashMap::new()),
            announce_lookup_tx: Mutex::new(None),
            store,
            bridges: BridgeRegistry::new(),
        }
    }

    fn lookup_peer_pubkey(&self, dest_hash: &[u8; 16]) -> Option<PublicIdentityMaterial> {
        self.peer_pubkeys
            .lock()
            .ok()
            .and_then(|m| m.get(dest_hash).copied())
    }

    fn push_event(&self, event: RnsInboundEvent) {
        match &event {
            RnsInboundEvent::AnnounceHeard {
                destination_hash_hex,
                ..
            } => {
                if let Ok(mut peers) = self.known_peers.lock() {
                    peers.insert(destination_hash_hex.clone());
                }
            }
            RnsInboundEvent::LinkEstablished { link_id_hex, .. } => {
                if let Ok(mut links) = self.active_links.lock() {
                    links.insert(link_id_hex.clone());
                }
            }
            RnsInboundEvent::LinkClosed { link_id_hex } => {
                if let Ok(mut links) = self.active_links.lock() {
                    links.remove(link_id_hex);
                }
                if let Ok(mut out_links) = self.outbound_links.lock() {
                    out_links.retain(|_, lid| hex::encode(lid.as_bytes()) != *link_id_hex);
                }
            }
            _ => {}
        }

        if let Ok(mut queue) = self.queue.lock() {
            queue.push_back(event.clone());
        }
        self.notify.notify_waiters();

        if let Ok(guard) = self.listener.lock() {
            if let Some(listener) = guard.as_ref() {
                listener.on_event(event);
            }
        }
    }

    fn record_inbound_lxmf(&self, msg: LxmfInboundMessage) -> bool {
        let is_new = {
            let Ok(mut seen) = self.lxmf_seen_hashes.lock() else {
                return false;
            };
            seen.insert(msg.message_hash_hex.clone())
        };
        if !is_new {
            return false;
        }
        if let Ok(mut list) = self.lxmf_messages.lock() {
            list.push(msg.clone());
            if let Some(store) = &self.store {
                let _ = store.save_messages(&list);
            }
        }
        self.push_event(RnsInboundEvent::LxmfMessageReceived { message: msg });
        true
    }

    fn record_receipt(&self, receipt: LxmfDeliveryReceipt) {
        if let Ok(mut list) = self.lxmf_receipts.lock() {
            list.push(receipt.clone());
            if let Some(store) = &self.store {
                let _ = store.save_receipts(&list);
            }
        }
        self.push_event(RnsInboundEvent::LxmfDeliveryStatus { receipt });
    }
}

/// Lifecycle, messaging, LXMF, and custom interface bridge controller for an RNS node.
#[derive(uniffi::Object)]
pub struct RnsNode {
    config: NodeConfig,
    secret: Zeroizing<[u8; IDENTITY_SECRET_KEY_LEN]>,
    identity_hash: personal_rns::identity::IdentityHash,
    identity_hash_hex: String,
    destination_hash: DestinationHash,
    destination_hash_hex: String,
    lxmf_destination_hash: DestinationHash,
    lxmf_destination_hash_hex: String,
    tokio_rt: Runtime,
    runtime: Mutex<Option<RuntimeState>>,
    events: Arc<SharedEventState>,
}

#[uniffi::export]
impl RnsNode {
    /// Construct a new (stopped) RNS + LXMF node from `NodeConfig`.
    #[uniffi::constructor]
    pub fn new(config: NodeConfig) -> Result<Arc<Self>, ProtocolxRnsError> {
        let secret = match &config.identity_secret {
            Some(bytes) => {
                let arr: [u8; IDENTITY_SECRET_KEY_LEN] =
                    bytes
                        .clone()
                        .try_into()
                        .map_err(|v: Vec<u8>| ProtocolxRnsError::InvalidMaterial {
                            detail: format!(
                                "expected {IDENTITY_SECRET_KEY_LEN} identity secret bytes, got {}",
                                v.len()
                            ),
                        })?;
                Zeroizing::new(arr)
            }
            None => try_generate_identity_secret().map_err(|e| {
                ProtocolxRnsError::InvalidMaterial {
                    detail: format!("OS entropy failure: {e:?}"),
                }
            })?,
        };

        let mat = PrivateIdentityMaterial::from_bytes(*secret);
        let identity_hash = mat.identity_hash();
        let identity_hash_hex = hex::encode(identity_hash.as_bytes());
        let destination_hash_hex =
            compute_destination_hash_for_secret(&secret, &config.app_name, &config.aspects)?;
        let dest_bytes = hex::decode(&destination_hash_hex).map_err(|e| {
            ProtocolxRnsError::InvalidDestination {
                detail: format!("hex decode failure: {e}"),
            }
        })?;
        let destination_hash = DestinationHash::from_slice(&dest_bytes).ok_or_else(|| {
            ProtocolxRnsError::InvalidDestination {
                detail: "unexpected destination hash length".to_string(),
            }
        })?;

        let lxmf_expanded =
            expand_name("lxmf", &["delivery"]).map_err(|e| ProtocolxRnsError::InvalidDestination {
                detail: format!("failed to expand lxmf.delivery: {e:?}"),
            })?;
        let lxmf_destination_hash = derive_destination_hash(&identity_hash, &lxmf_expanded);
        let lxmf_destination_hash_hex = hex::encode(lxmf_destination_hash.as_bytes());

        let store = match &config.storage_dir {
            Some(dir) if !dir.trim().is_empty() => Some(LxmfLocalStore::open(&PathBuf::from(dir))?),
            _ => None,
        };

        let tokio_rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .map_err(|e| ProtocolxRnsError::InterfaceError {
                detail: format!("failed to build tokio runtime: {e}"),
            })?;

        Ok(Arc::new(Self {
            config,
            secret,
            identity_hash,
            identity_hash_hex,
            destination_hash,
            destination_hash_hex,
            lxmf_destination_hash,
            lxmf_destination_hash_hex,
            tokio_rt,
            runtime: Mutex::new(None),
            events: Arc::new(SharedEventState::new(store)),
        }))
    }

    pub fn identity(&self) -> Arc<RnsIdentity> {
        Arc::new(RnsIdentity::from_secret_array(*self.secret))
    }

    pub fn identity_hash_hex(&self) -> String {
        self.identity_hash_hex.clone()
    }

    pub fn destination_hash_hex(&self) -> String {
        self.destination_hash_hex.clone()
    }

    /// 16-byte `lxmf.delivery` Single destination hash as lowercase hex.
    pub fn lxmf_destination_hash_hex(&self) -> String {
        self.lxmf_destination_hash_hex.clone()
    }

    /// Start the Reticulum + LXMF transport engine and attach configured TCP/UDP interfaces.
    pub fn start(&self) -> Result<(), ProtocolxRnsError> {
        let mut guard = self.runtime.lock().map_err(|_| ProtocolxRnsError::TransportError {
            detail: "runtime mutex poisoned".to_string(),
        })?;
        if guard.is_some() {
            return Err(ProtocolxRnsError::AlreadyRunning);
        }

        let config = self.config.clone();
        let secret = Zeroizing::new(*self.secret);
        let lxmf_dest_hash = self.lxmf_destination_hash;
        let events = Arc::clone(&self.events);
        let (ready_tx, ready_rx) =
            mpsc::sync_channel::<Result<(Option<String>, PrnsNodeHandle), ProtocolxRnsError>>(1);
        let (shutdown_tx, shutdown_rx) = oneshot::channel::<()>();

        let thread_handle = std::thread::Builder::new()
            .name("protocolx-rns-node".to_string())
            .spawn(move || {
                let rt = match tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                {
                    Ok(rt) => rt,
                    Err(e) => {
                        let _ = ready_tx.send(Err(ProtocolxRnsError::InterfaceError {
                            detail: format!("failed to build node runtime: {e}"),
                        }));
                        return;
                    }
                };

                rt.block_on(async move {
                    let persistence = match &config.storage_dir {
                        Some(dir) if !dir.trim().is_empty() => {
                            let rns_dir = PathBuf::from(dir).join("rns-state");
                            match NodePersistence::custom_dir(&rns_dir) {
                                Ok(p) => Some(p),
                                Err(e) => {
                                    let _ = ready_tx.send(Err(ProtocolxRnsError::InterfaceError {
                                        detail: format!(
                                            "failed to open RNS persistence {}: {e}",
                                            rns_dir.display()
                                        ),
                                    }));
                                    return;
                                }
                            }
                        }
                        _ => None,
                    };

                    drive_node_thread(
                        config,
                        secret,
                        lxmf_dest_hash,
                        events,
                        persistence,
                        ready_tx,
                        shutdown_rx,
                    )
                    .await;
                });
            })
            .map_err(|e| ProtocolxRnsError::InterfaceError {
                detail: format!("failed to spawn node thread: {e}"),
            })?;

        let (bound_tcp_addr, handle) = ready_rx
            .recv()
            .map_err(|e| ProtocolxRnsError::InterfaceError {
                detail: format!("node thread exited before initialization: {e}"),
            })??;

        *guard = Some(RuntimeState {
            handle,
            shutdown_tx: Some(shutdown_tx),
            thread_handle: Some(thread_handle),
            bound_tcp_addr,
            custom_ifaces: HashMap::new(),
        });

        Ok(())
    }

    pub fn stop(&self) -> Result<(), ProtocolxRnsError> {
        let state = {
            let mut guard = self.runtime.lock().map_err(|_| {
                ProtocolxRnsError::TransportError {
                    detail: "runtime mutex poisoned".to_string(),
                }
            })?;
            guard.take()
        };

        if let Some(mut st) = state {
            if let Ok(mut txs) = self.events.bridges.inbound_txs.lock() {
                txs.clear();
            }
            if let Ok(mut ids) = self.events.bridges.interface_ids.lock() {
                ids.clear();
            }
            for (_, attached) in st.custom_ifaces.drain() {
                attached.teardown();
            }
            if let Some(tx) = st.shutdown_tx.take() {
                let _ = tx.send(());
            }
            if let Some(th) = st.thread_handle.take() {
                let _ = th.join();
            }
        }
        Ok(())
    }

    pub fn is_running(&self) -> bool {
        self.runtime.lock().map(|g| g.is_some()).unwrap_or(false)
    }

    pub fn local_tcp_addr(&self) -> Option<String> {
        self.runtime
            .lock()
            .ok()
            .and_then(|g| g.as_ref().and_then(|s| s.bound_tcp_addr.clone()))
    }

    pub fn status(&self) -> NodeStatus {
        let (running, tcp_listen_addr) = self
            .runtime
            .lock()
            .map(|g| match g.as_ref() {
                Some(st) => (true, st.bound_tcp_addr.clone()),
                None => (false, None),
            })
            .unwrap_or((false, None));
        let known_peers_count = self
            .events
            .known_peers
            .lock()
            .map(|s| s.len() as u32)
            .unwrap_or(0);
        let active_links_count = self
            .events
            .active_links
            .lock()
            .map(|s| s.len() as u32)
            .unwrap_or(0);

        NodeStatus {
            running,
            identity_hash_hex: self.identity_hash_hex.clone(),
            destination_hash_hex: self.destination_hash_hex.clone(),
            tcp_listen_addr,
            known_peers_count,
            active_links_count,
        }
    }

    pub fn set_listener(&self, listener: Option<Box<dyn RnsEventListener>>) {
        if let Ok(mut guard) = self.events.listener.lock() {
            *guard = listener;
        }
    }

    pub fn poll_events(&self) -> Vec<RnsInboundEvent> {
        self.events
            .queue
            .lock()
            .map(|mut q| q.drain(..).collect())
            .unwrap_or_default()
    }

    pub fn wait_for_event(&self, timeout_ms: u64) -> Option<RnsInboundEvent> {
        self.tokio_rt.block_on(async {
            let deadline = tokio::time::Instant::now() + Duration::from_millis(timeout_ms);
            loop {
                let notified = self.events.notify.notified();
                if let Ok(mut q) = self.events.queue.lock() {
                    if let Some(ev) = q.pop_front() {
                        return Some(ev);
                    }
                }
                if tokio::time::timeout_at(deadline, notified).await.is_err() {
                    return None;
                }
            }
        })
    }

    pub fn known_destinations(&self) -> Vec<String> {
        self.events
            .known_peers
            .lock()
            .map(|s| s.iter().cloned().collect())
            .unwrap_or_default()
    }

    pub fn announce(&self, app_data: Option<Vec<u8>>) -> Result<(), ProtocolxRnsError> {
        let handle = self.active_handle()?;
        let payload = encode_announce_app_data(app_data)?;
        self.tokio_rt
            .block_on(handle.announce_now(AnnounceNow {
                destination: self.destination_hash,
                target: AnnounceTarget::AllInterfaces,
                app_data: payload,
            }))
            .map_err(|e| ProtocolxRnsError::TransportError {
                detail: format!("announce failed: {e:?}"),
            })
    }

    /// Broadcast an RNS announce for this node's `lxmf.delivery` destination.
    pub fn announce_lxmf(&self, display_name: Option<String>) -> Result<(), ProtocolxRnsError> {
        let handle = self.active_handle()?;
        let effective_name = display_name.or_else(|| self.config.lxmf_display_name.clone());
        let app_data_bytes = pack_lxmf_announce_app_data(effective_name.as_deref(), None);
        let payload = encode_announce_app_data(Some(app_data_bytes))?;
        self.tokio_rt
            .block_on(handle.announce_now(AnnounceNow {
                destination: self.lxmf_destination_hash,
                target: AnnounceTarget::AllInterfaces,
                app_data: payload,
            }))
            .map_err(|e| ProtocolxRnsError::TransportError {
                detail: format!("announce_lxmf failed: {e:?}"),
            })
    }

    pub fn send_single_packet(
        &self,
        destination_hash_hex: String,
        payload: Vec<u8>,
    ) -> Result<(), ProtocolxRnsError> {
        let handle = self.active_handle()?;
        let dest = parse_destination_hash(&destination_hash_hex)?;
        self.tokio_rt
            .block_on(handle.send_single_packet(dest, &payload))
            .map(|_| ())
            .map_err(|e| ProtocolxRnsError::TransportError {
                detail: format!("send_single_packet failed: {e:?}"),
            })
    }

    pub fn establish_link(&self, destination_hash_hex: String) -> Result<String, ProtocolxRnsError> {
        let handle = self.active_handle()?;
        let dest = parse_destination_hash(&destination_hash_hex)?;
        let link_id = self
            .tokio_rt
            .block_on(handle.establish_link(dest))
            .map_err(|e| ProtocolxRnsError::TransportError {
                detail: format!("establish_link failed: {e:?}"),
            })?;
        let link_id_hex = hex::encode(link_id.as_bytes());
        if let Ok(mut links) = self.events.active_links.lock() {
            links.insert(link_id_hex.clone());
        }
        Ok(link_id_hex)
    }

    pub fn send_link_packet(
        &self,
        link_id_hex: String,
        payload: Vec<u8>,
    ) -> Result<(), ProtocolxRnsError> {
        let handle = self.active_handle()?;
        let link_id = parse_link_id(&link_id_hex)?;
        self.tokio_rt
            .block_on(handle.send_link_packet(link_id, &payload))
            .map(|_| ())
            .map_err(|e| ProtocolxRnsError::TransportError {
                detail: format!("send_link_packet failed: {e:?}"),
            })
    }

    pub fn send_resource(
        &self,
        link_id_hex: String,
        payload: Vec<u8>,
    ) -> Result<(), ProtocolxRnsError> {
        let handle = self.active_handle()?;
        let link_id = parse_link_id(&link_id_hex)?;
        self.tokio_rt
            .block_on(handle.send_resource(link_id, payload.len() as u64, payload.as_slice()))
            .map_err(|e| ProtocolxRnsError::TransportError {
                detail: format!("send_resource failed: {e:?}"),
            })
    }

    pub fn close_link(&self, link_id_hex: String) -> Result<bool, ProtocolxRnsError> {
        let handle = self.active_handle()?;
        let link_id = parse_link_id(&link_id_hex)?;
        Ok(handle.close_link(link_id))
    }

    /// Pack, sign, and deliver an LXMF message via Opportunistic, Direct-Link/Resource, or Propagation Node.
    pub fn send_lxmf(
        &self,
        message: LxmfOutboundMessage,
    ) -> Result<LxmfDeliveryReceipt, ProtocolxRnsError> {
        let handle = self.active_handle()?;
        let dest = parse_destination_hash(&message.destination_hash_hex)?;
        let ts_secs = now_secs_f64();

        let packed = pack_signed(
            &self.secret,
            self.lxmf_destination_hash.as_bytes(),
            dest.as_bytes(),
            ts_secs,
            &message.title,
            &message.content,
            &message.attachments,
            message.custom_type.as_deref(),
            message.custom_data.as_deref(),
        )?;
        let message_hash_hex = packed.message_hash_hex();

        let effective_mode = match message.delivery_mode {
            LxmfDeliveryMode::Opportunistic
                if packed.content_size <= LXMF_ENCRYPTED_PACKET_MAX_CONTENT =>
            {
                LxmfDeliveryMode::Opportunistic
            }
            LxmfDeliveryMode::Opportunistic => LxmfDeliveryMode::Direct,
            other => other,
        };

        let result: Result<(LxmfReceiptStatus, String), String> = match effective_mode {
            LxmfDeliveryMode::Opportunistic => {
                let res = self
                    .tokio_rt
                    .block_on(handle.send_single_packet(dest, packed.opportunistic_payload()));
                match res {
                    Ok(_) => Ok((
                        LxmfReceiptStatus::Delivered,
                        "opportunistic single packet delivered with proof".to_string(),
                    )),
                    Err(e) => Err(format!("opportunistic delivery failed: {e:?}")),
                }
            }
            LxmfDeliveryMode::Direct => self.send_lxmf_direct(&handle, dest, &packed.full_packed),
            LxmfDeliveryMode::Propagated => {
                let pn_hex = message
                    .propagation_node_hex
                    .as_deref()
                    .or(self.config.propagation_node_hex.as_deref())
                    .ok_or_else(|| ProtocolxRnsError::InvalidDestination {
                        detail: "propagated LXMF requires propagation_node_hex".to_string(),
                    })?;
                let pn_dest = parse_destination_hash(pn_hex)?;
                self.send_lxmf_propagated(&handle, dest, pn_dest, &packed.full_packed, ts_secs)
            }
        };

        let receipt = match result {
            Ok((status, detail)) => LxmfDeliveryReceipt {
                client_message_id: message.message_id,
                message_hash_hex,
                destination_hash_hex: message.destination_hash_hex,
                status,
                detail,
                timestamp_ms: now_millis(),
            },
            Err(detail) => LxmfDeliveryReceipt {
                client_message_id: message.message_id,
                message_hash_hex,
                destination_hash_hex: message.destination_hash_hex,
                status: LxmfReceiptStatus::Failed,
                detail,
                timestamp_ms: now_millis(),
            },
        };

        self.events.record_receipt(receipt.clone());
        Ok(receipt)
    }

    /// Fetch pending messages addressed to this node from an LXMF propagation node (`/get`).
    pub fn fetch_propagated_lxmf(
        &self,
        propagation_node_hex: Option<String>,
    ) -> Result<Vec<LxmfInboundMessage>, ProtocolxRnsError> {
        let handle = self.active_handle()?;
        let pn_hex = propagation_node_hex
            .as_deref()
            .or(self.config.propagation_node_hex.as_deref())
            .ok_or_else(|| ProtocolxRnsError::InvalidDestination {
                detail: "fetch_propagated_lxmf requires propagation_node_hex".to_string(),
            })?;
        let pn_dest = parse_destination_hash(pn_hex)?;

        let fetched = self.tokio_rt.block_on(async {
            let link_id = handle.establish_link(pn_dest).await.map_err(|e| {
                ProtocolxRnsError::TransportError {
                    detail: format!("failed to link propagation node: {e:?}"),
                }
            })?;
            handle
                .identify(link_id, self.identity_hash)
                .await
                .map_err(|e| ProtocolxRnsError::TransportError {
                    detail: format!("failed to identify on propagation link: {e:?}"),
                })?;

            let list_req = pack_propagated_get_request(None)?;
            let (list_resp, _) = handle
                .request(link_id, RequestEndpointId::of("/get"), &list_req)
                .await
                .map_err(|e| ProtocolxRnsError::TransportError {
                    detail: format!("propagation /get list failed: {e:?}"),
                })?;
            let ids = unpack_propagated_id_list(&list_resp)?;
            if ids.is_empty() {
                let _ = handle.close_link(link_id);
                return Ok::<_, ProtocolxRnsError>(Vec::new());
            }

            let fetch_req = pack_propagated_get_request(Some(&ids))?;
            let (fetch_resp, _) = handle
                .request(link_id, RequestEndpointId::of("/get"), &fetch_req)
                .await
                .map_err(|e| ProtocolxRnsError::TransportError {
                    detail: format!("propagation /get download failed: {e:?}"),
                })?;
            let _ = handle.close_link(link_id);

            let mut decoded = unpack_propagated_messages(&fetch_resp, &self.secret, |src_hash| {
                let src_dest = DestinationHash::new(*src_hash);
                tokio::task::block_in_place(|| {
                    tokio::runtime::Handle::current().block_on(async {
                        handle
                            .destination_identity(DestinationIdentityQuery::Destination(src_dest))
                            .await
                            .map(|snap| snap.public)
                    })
                })
            })?;

            for msg in &decoded {
                self.events.record_inbound_lxmf(msg.clone());
            }
            Ok(std::mem::take(&mut decoded))
        })?;

        Ok(fetched)
    }

    pub fn persisted_lxmf_messages(&self) -> Vec<LxmfInboundMessage> {
        self.events
            .lxmf_messages
            .lock()
            .map(|l| l.clone())
            .unwrap_or_default()
    }

    pub fn persisted_lxmf_receipts(&self) -> Vec<LxmfDeliveryReceipt> {
        self.events
            .lxmf_receipts
            .lock()
            .map(|l| l.clone())
            .unwrap_or_default()
    }

    /// Attach a pluggable Kotlin radio interface (Wi-Fi Aware / BLE L2CAP / Custom) into the RNS engine.
    pub fn attach_custom_interface(
        &self,
        interface_tag: String,
        kind: CustomInterfaceKind,
        hw_mtu: u32,
        bitrate_bps: u64,
        bridge: Box<dyn RnsCustomInterfaceBridge>,
    ) -> Result<(), ProtocolxRnsError> {
        let mut guard = self.runtime.lock().map_err(|_| ProtocolxRnsError::TransportError {
            detail: "runtime mutex poisoned".to_string(),
        })?;
        let state = guard.as_mut().ok_or(ProtocolxRnsError::NotRunning)?;
        if state.custom_ifaces.contains_key(&interface_tag) {
            return Err(ProtocolxRnsError::InterfaceError {
                detail: format!("custom interface '{interface_tag}' is already attached"),
            });
        }

        let bps = if bitrate_bps == 0 {
            250_000
        } else {
            bitrate_bps
        };
        let bitrate = BitrateBps::new(bps).ok_or_else(|| ProtocolxRnsError::InterfaceError {
            detail: "bitrate_bps must be non-zero".to_string(),
        })?;

        let bridge: Arc<dyn RnsCustomInterfaceBridge> = Arc::from(bridge);
        let detach_handle = Arc::new(crate::bridge::BridgeDetachHandle::new(
            interface_tag.clone(),
            Arc::clone(&bridge),
        ));
        let (inbound_tx, inbound_rx) = tokio::sync::mpsc::unbounded_channel();
        let iface = CustomBridgeInterface::new(
            interface_tag.clone(),
            kind,
            hw_mtu as usize,
            bitrate,
            inbound_rx,
            bridge,
            Arc::clone(&detach_handle),
        );
        let iface_id = iface.id();
        let attached = state.handle.add_interface(iface);
        state.custom_ifaces.insert(interface_tag.clone(), attached);

        if let Ok(mut txs) = self.events.bridges.inbound_txs.lock() {
            txs.insert(interface_tag.clone(), inbound_tx);
        }
        if let Ok(mut ids) = self.events.bridges.interface_ids.lock() {
            ids.insert(interface_tag.clone(), iface_id);
        }
        if let Ok(mut dhs) = self.events.bridges.detach_handles.lock() {
            dhs.insert(interface_tag, detach_handle);
        }
        Ok(())
    }

    /// Detach a previously attached pluggable custom interface.
    pub fn detach_custom_interface(&self, interface_tag: String) -> Result<bool, ProtocolxRnsError> {
        let mut guard = self.runtime.lock().map_err(|_| ProtocolxRnsError::TransportError {
            detail: "runtime mutex poisoned".to_string(),
        })?;
        let state = guard.as_mut().ok_or(ProtocolxRnsError::NotRunning)?;

        if let Ok(mut txs) = self.events.bridges.inbound_txs.lock() {
            txs.remove(&interface_tag);
        }
        if let Ok(mut ids) = self.events.bridges.interface_ids.lock() {
            ids.remove(&interface_tag);
        }
        if let Ok(mut metrics) = self.events.bridges.metrics.lock() {
            metrics.remove(&interface_tag);
        }
        let detach_handle = self
            .events
            .bridges
            .detach_handles
            .lock()
            .ok()
            .and_then(|mut dhs| dhs.remove(&interface_tag));

        let attached = state.custom_ifaces.remove(&interface_tag);
        drop(guard);

        if let Some(attached) = attached {
            attached.teardown();
            if let Some(dh) = detach_handle {
                dh.notify_detached_once();
            }
            Ok(true)
        } else {
            Ok(false)
        }
    }

    /// Push a raw RNS wire frame received by a Kotlin radio adapter into the RNS engine.
    pub fn push_custom_inbound_frame(
        &self,
        interface_tag: String,
        frame: Vec<u8>,
    ) -> Result<(), ProtocolxRnsError> {
        if !self.is_running() {
            return Err(ProtocolxRnsError::NotRunning);
        }
        let tx = self
            .events
            .bridges
            .inbound_txs
            .lock()
            .map_err(|_| ProtocolxRnsError::TransportError {
                detail: "bridge registry mutex poisoned".to_string(),
            })?
            .get(&interface_tag)
            .cloned()
            .ok_or_else(|| ProtocolxRnsError::InterfaceError {
                detail: format!("unknown custom interface '{interface_tag}'"),
            })?;

        tx.send(frame).map_err(|_| ProtocolxRnsError::InterfaceError {
            detail: format!("custom interface '{interface_tag}' receiver closed"),
        })
    }

    /// Record per-peer RSSI, RTT, and bandwidth metrics from Android Wi-Fi Aware / BLE L2CAP adapters.
    pub fn update_custom_interface_metrics(
        &self,
        interface_tag: String,
        metrics: CustomLinkMetrics,
    ) -> Result<(), ProtocolxRnsError> {
        let mut map = self
            .events
            .bridges
            .metrics
            .lock()
            .map_err(|_| ProtocolxRnsError::TransportError {
                detail: "metrics mutex poisoned".to_string(),
            })?;
        let entry = map.entry(interface_tag).or_default();
        if metrics.connected {
            entry.insert(metrics.peer_id.clone(), metrics);
        } else {
            entry.insert(metrics.peer_id.clone(), metrics);
        }
        Ok(())
    }

    /// Retrieve current link health metrics tracked for `interface_tag`.
    pub fn get_custom_interface_metrics(&self, interface_tag: String) -> Vec<CustomLinkMetrics> {
        self.events
            .bridges
            .metrics
            .lock()
            .ok()
            .and_then(|m| m.get(&interface_tag).map(|peers| peers.values().cloned().collect()))
            .unwrap_or_default()
    }
}

impl RnsNode {
    fn active_handle(&self) -> Result<PrnsNodeHandle, ProtocolxRnsError> {
        self.runtime
            .lock()
            .map_err(|_| ProtocolxRnsError::TransportError {
                detail: "runtime mutex poisoned".to_string(),
            })?
            .as_ref()
            .map(|st| st.handle.clone())
            .ok_or(ProtocolxRnsError::NotRunning)
    }

    fn send_lxmf_direct(
        &self,
        handle: &PrnsNodeHandle,
        dest: DestinationHash,
        full_packed: &[u8],
    ) -> Result<(LxmfReceiptStatus, String), String> {
        let dest_key = *dest.as_bytes();
        let cached_link = self
            .events
            .outbound_links
            .lock()
            .ok()
            .and_then(|m| m.get(&dest_key).copied());

        for attempt in 0..2 {
            let link_id = if attempt == 0 {
                if let Some(lid) = cached_link {
                    lid
                } else {
                    let lid = self
                        .tokio_rt
                        .block_on(handle.establish_link(dest))
                        .map_err(|e| format!("direct link establish failed: {e:?}"))?;
                    if let Ok(mut m) = self.events.outbound_links.lock() {
                        m.insert(dest_key, lid);
                    }
                    lid
                }
            } else {
                let lid = self
                    .tokio_rt
                    .block_on(handle.establish_link(dest))
                    .map_err(|e| format!("direct link retry establish failed: {e:?}"))?;
                if let Ok(mut m) = self.events.outbound_links.lock() {
                    m.insert(dest_key, lid);
                }
                lid
            };

            let send_res = if full_packed.len() <= LXMF_LINK_PACKET_MAX_CONTENT {
                self.tokio_rt
                    .block_on(handle.send_link_packet(link_id, full_packed))
                    .map(|_| "direct link packet delivered".to_string())
                    .map_err(|e| format!("send_link_packet failed: {e:?}"))
            } else {
                self.tokio_rt
                    .block_on(handle.send_resource(
                        link_id,
                        full_packed.len() as u64,
                        full_packed,
                    ))
                    .map(|_| format!("direct resource delivered ({} bytes)", full_packed.len()))
                    .map_err(|e| format!("send_resource failed: {e:?}"))
            };

            match send_res {
                Ok(detail) => return Ok((LxmfReceiptStatus::Delivered, detail)),
                Err(err) => {
                    if let Ok(mut m) = self.events.outbound_links.lock() {
                        m.remove(&dest_key);
                    }
                    if attempt == 1 {
                        return Err(err);
                    }
                }
            }
        }

        Err("direct LXMF delivery failed after retry".to_string())
    }

    fn send_lxmf_propagated(
        &self,
        handle: &PrnsNodeHandle,
        recipient_dest: DestinationHash,
        pn_dest: DestinationHash,
        full_packed: &[u8],
        ts_secs: f64,
    ) -> Result<(LxmfReceiptStatus, String), String> {
        let recipient_snap = self
            .tokio_rt
            .block_on(handle.destination_identity(DestinationIdentityQuery::Destination(recipient_dest)))
            .ok_or_else(|| {
                format!(
                    "unknown recipient public key for {}; wait for announce first",
                    hex::encode(recipient_dest.as_bytes())
                )
            })?;

        let mut entropy_48 = [0u8; 48];
        handle.fill_entropy(&mut entropy_48);
        let (bundle, transient_id) = pack_propagated_bundle(
            full_packed,
            &recipient_snap.public,
            &entropy_48,
            ts_secs,
            16,
        )
        .map_err(|e| e.to_string())?;

        let link_id = self
            .tokio_rt
            .block_on(handle.establish_link(pn_dest))
            .map_err(|e| format!("propagation link establish failed: {e:?}"))?;

        let send_res = if bundle.len() <= LXMF_LINK_PACKET_MAX_CONTENT {
            self.tokio_rt
                .block_on(handle.send_link_packet(link_id, &bundle))
                .map(|_| ())
                .map_err(|e| format!("propagation link packet failed: {e:?}"))
        } else {
            self.tokio_rt
                .block_on(handle.send_resource(link_id, bundle.len() as u64, bundle.as_slice()))
                .map_err(|e| format!("propagation resource failed: {e:?}"))
        };
        let _ = handle.close_link(link_id);
        send_res?;

        Ok((
            LxmfReceiptStatus::Propagated,
            format!(
                "propagated via {} (transient_id={})",
                hex::encode(pn_dest.as_bytes()),
                hex::encode(transient_id)
            ),
        ))
    }
}

impl Drop for RnsNode {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}

async fn drive_node_thread(
    config: NodeConfig,
    secret: Zeroizing<[u8; IDENTITY_SECRET_KEY_LEN]>,
    lxmf_dest_hash: DestinationHash,
    events: Arc<SharedEventState>,
    persistence: Option<NodePersistence>,
    ready_tx: mpsc::SyncSender<Result<(Option<String>, PrnsNodeHandle), ProtocolxRnsError>>,
    shutdown_rx: oneshot::Receiver<()>,
) {
    let setup = async {
        let tcp_server = match &config.tcp_listen_addr {
            Some(addr) => {
                let srv = TcpServer::bind(addr.as_str()).await.map_err(|e| {
                    ProtocolxRnsError::InterfaceError {
                        detail: format!("TCP bind failed on {addr}: {e}"),
                    }
                })?;
                let bound = srv.local_addr().map_err(|e| ProtocolxRnsError::InterfaceError {
                    detail: format!("failed to query TCP local_addr: {e}"),
                })?;
                Some((srv, bound.to_string()))
            }
            None => None,
        };

        let udp_iface = match (&config.udp_bind_addr, &config.udp_peer_addr) {
            (Some(bind_addr), Some(peer_addr)) => {
                let bps = if config.udp_bitrate_bps == 0 {
                    1_000_000
                } else {
                    config.udp_bitrate_bps
                };
                let bitrate = BitrateBps::new(bps).ok_or_else(|| {
                    ProtocolxRnsError::InterfaceError {
                        detail: "udp_bitrate_bps must be non-zero".to_string(),
                    }
                })?;
                let iface = UdpInterface::bind(bind_addr.as_str(), peer_addr.as_str(), bitrate)
                    .await
                    .map_err(|e| ProtocolxRnsError::InterfaceError {
                        detail: format!("UDP bind failed on {bind_addr} -> {peer_addr}: {e}"),
                    })?;
                Some(iface)
            }
            (Some(_), None) | (None, Some(_)) => {
                return Err(ProtocolxRnsError::InterfaceError {
                    detail: "both udp_bind_addr and udp_peer_addr must be provided".to_string(),
                });
            }
            (None, None) => None,
        };

        let app_name: &'static str = Box::leak(config.app_name.clone().into_boxed_str());
        let aspect_slices: Vec<&'static str> = config
            .aspects
            .iter()
            .map(|a| -> &'static str { Box::leak(a.clone().into_boxed_str()) })
            .collect();
        let aspects_static: &'static [&'static str] = Box::leak(aspect_slices.into_boxed_slice());
        let announce_data_static: &'static [u8] =
            Box::leak(config.announce_app_data.clone().into_boxed_slice());

        let primary_dest = PreConfiguredDestination::Single {
            app_name,
            aspects: aspects_static,
            identity: Zeroizing::new(*secret),
            announce_app_data: announce_data_static,
            proof: ProofStrategy::ProveAll,
            link_requests: LinkRequestPolicy::AcceptAll,
            ratchet: RatchetPolicy::NoRatchets,
            resource_strategy: ResourceStrategy::Accept {
                max_uncompressed_bytes: 16 * 1024 * 1024,
                accept_compressed: true,
            },
            maximum_request_bytes: Default::default(),
            request_endpoints: ServeMyRequestEndpoints::No,
        };

        let lxmf_app_data: &'static [u8] = Box::leak(
            pack_lxmf_announce_app_data(config.lxmf_display_name.as_deref(), None)
                .into_boxed_slice(),
        );
        let lxmf_dest = PreConfiguredDestination::Single {
            app_name: "lxmf",
            aspects: &["delivery"],
            identity: Zeroizing::new(*secret),
            announce_app_data: lxmf_app_data,
            proof: ProofStrategy::ProveAll,
            link_requests: LinkRequestPolicy::AcceptAll,
            ratchet: RatchetPolicy::NoRatchets,
            resource_strategy: ResourceStrategy::Accept {
                max_uncompressed_bytes: 16 * 1024 * 1024,
                accept_compressed: true,
            },
            maximum_request_bytes: Default::default(),
            request_endpoints: ServeMyRequestEndpoints::No,
        };

        let transport_identity = if config.enable_transport_relay {
            Some(Zeroizing::new(*secret))
        } else {
            None
        };

        let event_sink = Arc::clone(&events);
        let (handle_tx, handle_rx) = std::sync::mpsc::sync_channel::<PrnsNodeHandle>(1);
        macro_rules! build_prns_node {
            ($pers:expr) => {{
                let handle_tx = handle_tx.clone();
                let event_sink = Arc::clone(&event_sink);
                PrnsNode::new_with_handle(move |node_handle| {
                    let _ = handle_tx.send(node_handle);
                    PrnsNodeRecipe {
                        transport_identity,
                        pre_configured_destinations: [primary_dest, lxmf_dest],
                        app_state: (),
                        storage: GrowableHeap,
                        request_endpoints: request_endpoints![],
                        interfaces: ManuallyAttached,
                        persistence: $pers,
                        on_event: move |event, _state| match event {
                            PrnsEvent::Diagnostic(Diagnostic::AnnounceHeard {
                                destination,
                                hops,
                                ..
                            }) => {
                                if let Ok(tx_guard) = event_sink.announce_lookup_tx.lock() {
                                    if let Some(tx) = tx_guard.as_ref() {
                                        let _ = tx.send(destination);
                                    }
                                }
                                event_sink.push_event(RnsInboundEvent::AnnounceHeard {
                                    destination_hash_hex: hex::encode(destination.as_bytes()),
                                    hops,
                                });
                            }
                            PrnsEvent::Diagnostic(Diagnostic::LinkEstablished(established)) => {
                                event_sink.push_event(RnsInboundEvent::LinkEstablished {
                                    link_id_hex: hex::encode(established.link_id.as_bytes()),
                                    rtt_millis: established.rtt_millis,
                                });
                            }
                            PrnsEvent::Diagnostic(Diagnostic::LinkClosed { link_id, .. }) => {
                                event_sink.push_event(RnsInboundEvent::LinkClosed {
                                    link_id_hex: hex::encode(link_id.as_bytes()),
                                });
                            }
                            PrnsEvent::Message(Message::Delivered(delivery)) => match delivery {
                                Delivery::Single(single) => {
                                    if single.destination == lxmf_dest_hash {
                                        if let Ok(msg) = unpack_opportunistic(
                                            lxmf_dest_hash.as_bytes(),
                                            single.plaintext,
                                            |src| event_sink.lookup_peer_pubkey(src),
                                        ) {
                                            event_sink.record_inbound_lxmf(msg);
                                            return;
                                        }
                                    }
                                    event_sink.push_event(RnsInboundEvent::SinglePacketReceived {
                                        destination_hash_hex: hex::encode(
                                            single.destination.as_bytes(),
                                        ),
                                        payload: single.plaintext.to_vec(),
                                    });
                                }
                                Delivery::Link(link) => {
                                    if link.plaintext.len() > LXMF_OVERHEAD
                                        && &link.plaintext[..16] == lxmf_dest_hash.as_bytes()
                                    {
                                        if let Ok(msg) = unpack_and_verify(
                                            link.plaintext,
                                            LxmfDeliveryMode::Direct,
                                            |src| event_sink.lookup_peer_pubkey(src),
                                        ) {
                                            event_sink.record_inbound_lxmf(msg);
                                            return;
                                        }
                                    }
                                    event_sink.push_event(RnsInboundEvent::LinkPacketReceived {
                                        link_id_hex: hex::encode(link.link_id.as_bytes()),
                                        payload: link.plaintext.to_vec(),
                                    });
                                }
                                Delivery::Plain(plain) => {
                                    event_sink.push_event(RnsInboundEvent::SinglePacketReceived {
                                        destination_hash_hex: hex::encode(
                                            plain.destination.as_bytes(),
                                        ),
                                        payload: plain.payload.to_vec(),
                                    });
                                }
                                Delivery::Group(group) => {
                                    event_sink.push_event(RnsInboundEvent::SinglePacketReceived {
                                        destination_hash_hex: hex::encode(
                                            group.destination.as_bytes(),
                                        ),
                                        payload: group.plaintext.to_vec(),
                                    });
                                }
                            },
                            PrnsEvent::Message(Message::Resource {
                                link_id,
                                hash,
                                data,
                                ..
                            }) => {
                                if data.len() > LXMF_OVERHEAD
                                    && &data[..16] == lxmf_dest_hash.as_bytes()
                                {
                                    if let Ok(msg) = unpack_and_verify(
                                        data,
                                        LxmfDeliveryMode::Direct,
                                        |src| event_sink.lookup_peer_pubkey(src),
                                    ) {
                                        event_sink.record_inbound_lxmf(msg);
                                        return;
                                    }
                                }
                                event_sink.push_event(RnsInboundEvent::ResourceReceived {
                                    link_id_hex: hex::encode(link_id.as_bytes()),
                                    resource_hash_hex: hex::encode(hash.as_bytes()),
                                    data: data.to_vec(),
                                });
                            }
                            _ => {}
                        },
                    }
                })
            }};
        }

        match persistence {
            Some(p) => {
                let node = build_prns_node!(p);
                finish_node_setup(
                    node,
                    handle_rx,
                    tcp_server,
                    udp_iface,
                    &config,
                    Arc::clone(&events),
                    ready_tx.clone(),
                    shutdown_rx,
                )
                .await;
            }
            None => {
                let node = build_prns_node!(NoPersistence);
                finish_node_setup(
                    node,
                    handle_rx,
                    tcp_server,
                    udp_iface,
                    &config,
                    Arc::clone(&events),
                    ready_tx.clone(),
                    shutdown_rx,
                )
                .await;
            }
        }
        Ok::<_, ProtocolxRnsError>(())
    }
    .await;

    if let Err(err) = setup {
        let _ = ready_tx.send(Err(err));
    }
}

async fn finish_node_setup<St, R, F, S>(
    node: PrnsNode<St, R, F, S>,
    handle_rx: std::sync::mpsc::Receiver<PrnsNodeHandle>,
    tcp_server: Option<(TcpServer, String)>,
    udp_iface: Option<UdpInterface>,
    config: &NodeConfig,
    events: Arc<SharedEventState>,
    ready_tx: mpsc::SyncSender<Result<(Option<String>, PrnsNodeHandle), ProtocolxRnsError>>,
    shutdown_rx: oneshot::Receiver<()>,
) where
    R: prns_runtime::runtime::request_endpoints::RequestEndpointSet<St>,
    F: FnMut(PrnsEvent<'_>, &St),
    S: prns_runtime::storage::StorageLayout,
{
    let handle = handle_rx.recv().unwrap_or_else(|_| node.handle());

    let (lookup_tx, mut lookup_rx) = tokio::sync::mpsc::unbounded_channel::<DestinationHash>();
    if let Ok(mut guard) = events.announce_lookup_tx.lock() {
        *guard = Some(lookup_tx);
    }
    let lookup_handle = handle.clone();
    let lookup_events = Arc::clone(&events);
    let lookup_task = tokio::spawn(async move {
        while let Some(dest) = lookup_rx.recv().await {
            if let Some(snap) = lookup_handle
                .destination_identity(DestinationIdentityQuery::Destination(dest))
                .await
            {
                if let Ok(mut map) = lookup_events.peer_pubkeys.lock() {
                    map.insert(*dest.as_bytes(), snap.public);
                }
            }
        }
    });

    let mut bound_tcp_addr = None;
    let mut supervisors = Vec::new();
    let mut attached = Vec::new();
    if let Some((srv, bound)) = tcp_server {
        supervisors.push(handle.supervise(srv));
        bound_tcp_addr = Some(bound);
    }
    for peer_addr in &config.tcp_connect_addrs {
        attached.push(handle.attach(TcpClientInterface::new(peer_addr.clone())));
    }
    if let Some(udp) = udp_iface {
        attached.push(handle.attach(udp));
    }

    let _ = ready_tx.send(Ok((bound_tcp_addr, handle)));
    let _ = node
        .run_until(async move {
            let _ = shutdown_rx.await;
        })
        .await;
    if let Ok(mut guard) = events.announce_lookup_tx.lock() {
        *guard = None;
    }
    lookup_task.abort();
    drop(supervisors);
    drop(attached);
}

fn encode_announce_app_data(app_data: Option<Vec<u8>>) -> Result<AnnounceAppData, ProtocolxRnsError> {
    match app_data {
        None => Ok(AnnounceAppData::Registered),
        Some(bytes) => {
            let mut buf = personal_rns::routing::announce::emit::AnnounceAppDataBytes::new();
            buf.extend_from_slice(&bytes)
                .map_err(|()| ProtocolxRnsError::InvalidMaterial {
                    detail: "announce app_data exceeds maximum RNS announce capacity".to_string(),
                })?;
            Ok(AnnounceAppData::Data(buf))
        }
    }
}

fn parse_destination_hash(hex_str: &str) -> Result<DestinationHash, ProtocolxRnsError> {
    let bytes = hex::decode(hex_str.trim()).map_err(|e| ProtocolxRnsError::InvalidMaterial {
        detail: format!("invalid destination hash hex '{hex_str}': {e}"),
    })?;
    DestinationHash::from_slice(&bytes).ok_or_else(|| ProtocolxRnsError::InvalidMaterial {
        detail: format!(
            "destination hash must be 16 bytes (32 hex chars), got {} bytes",
            bytes.len()
        ),
    })
}

fn parse_link_id(hex_str: &str) -> Result<LinkId, ProtocolxRnsError> {
    let bytes = hex::decode(hex_str.trim()).map_err(|e| ProtocolxRnsError::InvalidMaterial {
        detail: format!("invalid link_id hex '{hex_str}': {e}"),
    })?;
    let arr: [u8; 16] =
        bytes
            .try_into()
            .map_err(|v: Vec<u8>| ProtocolxRnsError::InvalidMaterial {
                detail: format!(
                    "link_id must be 16 bytes (32 hex chars), got {} bytes",
                    v.len()
                ),
            })?;
    Ok(LinkId::new(arr))
}

fn now_secs_f64() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0)
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
