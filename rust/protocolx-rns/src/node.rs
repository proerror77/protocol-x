use std::collections::{HashSet, VecDeque};
use std::sync::{mpsc, Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

use personal_rns::identity::{PrivateIdentityMaterial, IDENTITY_SECRET_KEY_LEN};
use personal_rns::prelude::{
    request_endpoints, try_generate_identity_secret, AnnounceAppData, AnnounceNow,
    AnnounceTarget, BitrateBps, DestinationHash, Diagnostic, GrowableHeap, LinkRequestPolicy,
    ManuallyAttached, Message, NoPersistence, PreConfiguredDestination, PrnsEvent, PrnsNode,
    PrnsNodeHandle, PrnsNodeRecipe, ProofStrategy, RatchetPolicy, ResourceStrategy,
    ServeMyRequestEndpoints, TcpClientInterface, TcpServer, Zeroizing,
};
use personal_rns::routing::delivery::Delivery;
use personal_rns::routing::links::LinkId;
use personal_rns::udp::UdpInterface;
use tokio::runtime::Runtime;
use tokio::sync::{oneshot, Notify};

use crate::error::ProtocolxRnsError;
use crate::identity::{compute_destination_hash_for_secret, RnsIdentity};
use crate::types::{NodeConfig, NodeStatus, RnsEventListener, RnsInboundEvent};

struct RuntimeState {
    handle: PrnsNodeHandle,
    shutdown_tx: Option<oneshot::Sender<()>>,
    thread_handle: Option<JoinHandle<()>>,
    bound_tcp_addr: Option<String>,
}

struct SharedEventState {
    queue: Mutex<VecDeque<RnsInboundEvent>>,
    notify: Notify,
    listener: Mutex<Option<Box<dyn RnsEventListener>>>,
    known_peers: Mutex<HashSet<String>>,
    active_links: Mutex<HashSet<String>>,
}

impl SharedEventState {
    fn new() -> Self {
        Self {
            queue: Mutex::new(VecDeque::new()),
            notify: Notify::new(),
            listener: Mutex::new(None),
            known_peers: Mutex::new(HashSet::new()),
            active_links: Mutex::new(HashSet::new()),
        }
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
}

/// Lifecycle and messaging controller for a Reticulum Network Stack node.
#[derive(uniffi::Object)]
pub struct RnsNode {
    config: NodeConfig,
    secret: Zeroizing<[u8; IDENTITY_SECRET_KEY_LEN]>,
    identity_hash_hex: String,
    destination_hash: DestinationHash,
    destination_hash_hex: String,
    tokio_rt: Runtime,
    runtime: Mutex<Option<RuntimeState>>,
    events: Arc<SharedEventState>,
}

#[uniffi::export]
impl RnsNode {
    /// Construct a new (stopped) RNS node from `NodeConfig`.
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
        let identity_hash_hex = hex::encode(mat.identity_hash().as_bytes());
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

        let tokio_rt = tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .map_err(|e| ProtocolxRnsError::InterfaceError {
                detail: format!("failed to build tokio runtime: {e}"),
            })?;

        Ok(Arc::new(Self {
            config,
            secret,
            identity_hash_hex,
            destination_hash,
            destination_hash_hex,
            tokio_rt,
            runtime: Mutex::new(None),
            events: Arc::new(SharedEventState::new()),
        }))
    }

    /// Return a copy of this node's cryptographic identity.
    pub fn identity(&self) -> Arc<RnsIdentity> {
        Arc::new(RnsIdentity::from_secret_array(*self.secret))
    }

    /// 16-byte identity hash as lowercase hex.
    pub fn identity_hash_hex(&self) -> String {
        self.identity_hash_hex.clone()
    }

    /// 16-byte primary Single destination hash as lowercase hex.
    pub fn destination_hash_hex(&self) -> String {
        self.destination_hash_hex.clone()
    }

    /// Start the Reticulum transport engine and attach configured TCP/UDP interfaces.
    pub fn start(&self) -> Result<(), ProtocolxRnsError> {
        let mut guard = self.runtime.lock().map_err(|_| ProtocolxRnsError::TransportError {
            detail: "runtime mutex poisoned".to_string(),
        })?;
        if guard.is_some() {
            return Err(ProtocolxRnsError::AlreadyRunning);
        }

        let config = self.config.clone();
        let secret = Zeroizing::new(*self.secret);
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
                    let setup = async {
                        let tcp_server = match &config.tcp_listen_addr {
                            Some(addr) => {
                                let srv = TcpServer::bind(addr.as_str()).await.map_err(|e| {
                                    ProtocolxRnsError::InterfaceError {
                                        detail: format!("TCP bind failed on {addr}: {e}"),
                                    }
                                })?;
                                let bound = srv.local_addr().map_err(|e| {
                                    ProtocolxRnsError::InterfaceError {
                                        detail: format!("failed to query TCP local_addr: {e}"),
                                    }
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
                                let iface = UdpInterface::bind(
                                    bind_addr.as_str(),
                                    peer_addr.as_str(),
                                    bitrate,
                                )
                                .await
                                .map_err(|e| ProtocolxRnsError::InterfaceError {
                                    detail: format!(
                                        "UDP bind failed on {bind_addr} -> {peer_addr}: {e}"
                                    ),
                                })?;
                                Some(iface)
                            }
                            (Some(_), None) | (None, Some(_)) => {
                                return Err(ProtocolxRnsError::InterfaceError {
                                    detail:
                                        "both udp_bind_addr and udp_peer_addr must be provided"
                                            .to_string(),
                                });
                            }
                            (None, None) => None,
                        };

                        let app_name: &'static str =
                            Box::leak(config.app_name.clone().into_boxed_str());
                        let aspect_slices: Vec<&'static str> = config
                            .aspects
                            .iter()
                            .map(|a| -> &'static str { Box::leak(a.clone().into_boxed_str()) })
                            .collect();
                        let aspects_static: &'static [&'static str] =
                            Box::leak(aspect_slices.into_boxed_slice());
                        let announce_data_static: &'static [u8] =
                            Box::leak(config.announce_app_data.clone().into_boxed_slice());

                        let destination = PreConfiguredDestination::Single {
                            app_name,
                            aspects: aspects_static,
                            identity: Zeroizing::new(*secret),
                            announce_app_data: announce_data_static,
                            proof: ProofStrategy::ProveAll,
                            link_requests: LinkRequestPolicy::AcceptAll,
                            ratchet: RatchetPolicy::NoRatchets,
                            resource_strategy: ResourceStrategy::Accept {
                                max_uncompressed_bytes: 4 * 1024 * 1024,
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
                        let node = PrnsNode::new(PrnsNodeRecipe {
                            transport_identity,
                            pre_configured_destinations: [destination],
                            app_state: (),
                            storage: GrowableHeap,
                            request_endpoints: request_endpoints![],
                            interfaces: ManuallyAttached,
                            persistence: NoPersistence,
                            on_event: move |event, _state| match event {
                                PrnsEvent::Diagnostic(Diagnostic::AnnounceHeard {
                                    destination,
                                    hops,
                                    ..
                                }) => {
                                    event_sink.push_event(RnsInboundEvent::AnnounceHeard {
                                        destination_hash_hex: hex::encode(destination.as_bytes()),
                                        hops,
                                    });
                                }
                                PrnsEvent::Diagnostic(Diagnostic::LinkEstablished(
                                    established,
                                )) => {
                                    event_sink.push_event(RnsInboundEvent::LinkEstablished {
                                        link_id_hex: hex::encode(established.link_id.as_bytes()),
                                        rtt_millis: established.rtt_millis,
                                    });
                                }
                                PrnsEvent::Diagnostic(Diagnostic::LinkClosed {
                                    link_id, ..
                                }) => {
                                    event_sink.push_event(RnsInboundEvent::LinkClosed {
                                        link_id_hex: hex::encode(link_id.as_bytes()),
                                    });
                                }
                                PrnsEvent::Message(Message::Delivered(delivery)) => {
                                    match delivery {
                                        Delivery::Single(single) => {
                                            event_sink.push_event(
                                                RnsInboundEvent::SinglePacketReceived {
                                                    destination_hash_hex: hex::encode(
                                                        single.destination.as_bytes(),
                                                    ),
                                                    payload: single.plaintext.to_vec(),
                                                },
                                            );
                                        }
                                        Delivery::Link(link) => {
                                            event_sink.push_event(
                                                RnsInboundEvent::LinkPacketReceived {
                                                    link_id_hex: hex::encode(
                                                        link.link_id.as_bytes(),
                                                    ),
                                                    payload: link.plaintext.to_vec(),
                                                },
                                            );
                                        }
                                        Delivery::Plain(plain) => {
                                            event_sink.push_event(
                                                RnsInboundEvent::SinglePacketReceived {
                                                    destination_hash_hex: hex::encode(
                                                        plain.destination.as_bytes(),
                                                    ),
                                                    payload: plain.payload.to_vec(),
                                                },
                                            );
                                        }
                                        Delivery::Group(group) => {
                                            event_sink.push_event(
                                                RnsInboundEvent::SinglePacketReceived {
                                                    destination_hash_hex: hex::encode(
                                                        group.destination.as_bytes(),
                                                    ),
                                                    payload: group.plaintext.to_vec(),
                                                },
                                            );
                                        }
                                    }
                                }
                                PrnsEvent::Message(Message::Resource {
                                    link_id,
                                    hash,
                                    data,
                                    ..
                                }) => {
                                    event_sink.push_event(RnsInboundEvent::ResourceReceived {
                                        link_id_hex: hex::encode(link_id.as_bytes()),
                                        resource_hash_hex: hex::encode(hash.as_bytes()),
                                        data: data.to_vec(),
                                    });
                                }
                                _ => {}
                            },
                        });

                        let handle = node.handle();

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

                        Ok::<_, ProtocolxRnsError>((
                            bound_tcp_addr,
                            handle,
                            node,
                            supervisors,
                            attached,
                        ))
                    }
                    .await;

                    match setup {
                        Ok((bound_tcp_addr, handle, node, _supervisors, _attached)) => {
                            let _ = ready_tx.send(Ok((bound_tcp_addr, handle)));
                            let _ = node
                                .run_until(async move {
                                    let _ = shutdown_rx.await;
                                })
                                .await;
                        }
                        Err(err) => {
                            let _ = ready_tx.send(Err(err));
                        }
                    }
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
        });

        Ok(())
    }

    /// Gracefully shut down the transport node and attached interfaces.
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
        self.runtime
            .lock()
            .map(|g| g.is_some())
            .unwrap_or(false)
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

    /// Register a push event listener for Kotlin/Swift callbacks.
    pub fn set_listener(&self, listener: Option<Box<dyn RnsEventListener>>) {
        if let Ok(mut guard) = self.events.listener.lock() {
            *guard = listener;
        }
    }

    /// Drain all currently queued inbound events without blocking.
    pub fn poll_events(&self) -> Vec<RnsInboundEvent> {
        self.events
            .queue
            .lock()
            .map(|mut q| q.drain(..).collect())
            .unwrap_or_default()
    }

    /// Wait up to `timeout_ms` for the next queued inbound event.
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

    /// Return all destination hashes observed via valid announces.
    pub fn known_destinations(&self) -> Vec<String> {
        self.events
            .known_peers
            .lock()
            .map(|s| s.iter().cloned().collect())
            .unwrap_or_default()
    }

    /// Broadcast an RNS announce for this node's primary Single destination.
    pub fn announce(&self, app_data: Option<Vec<u8>>) -> Result<(), ProtocolxRnsError> {
        let handle = self.active_handle()?;
        let payload = match app_data {
            None => AnnounceAppData::Registered,
            Some(bytes) => {
                let mut buf = personal_rns::routing::announce::emit::AnnounceAppDataBytes::new();
                buf.extend_from_slice(&bytes).map_err(|()| {
                    ProtocolxRnsError::InvalidMaterial {
                        detail: "announce app_data exceeds maximum RNS announce capacity"
                            .to_string(),
                    }
                })?;
                AnnounceAppData::Data(buf)
            }
        };

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

    /// Send an encrypted single-packet message to an announced destination hash.
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

    /// Establish a cryptographic ECDH RNS Link to an announced destination.
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

    /// Send an encrypted packet over an established RNS Link.
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

    /// Transfer a multi-packet Resource payload over an established RNS Link.
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

    /// Tear down an active RNS Link.
    pub fn close_link(&self, link_id_hex: String) -> Result<bool, ProtocolxRnsError> {
        let handle = self.active_handle()?;
        let link_id = parse_link_id(&link_id_hex)?;
        Ok(handle.close_link(link_id))
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
}

impl Drop for RnsNode {
    fn drop(&mut self) {
        let _ = self.stop();
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
                detail: format!("link_id must be 16 bytes (32 hex chars), got {} bytes", v.len()),
            })?;
    Ok(LinkId::new(arr))
}
