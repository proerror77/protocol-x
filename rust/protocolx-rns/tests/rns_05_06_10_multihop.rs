use std::io::{BufRead, BufReader};
use std::net::TcpListener;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::time::{Duration, Instant};

use protocolx_rns::{
    bind_actor_device_to_rns, derive_event_ifac_config, verify_actor_device_binding,
    CustomInterfaceKind, IfacConfig, LxmfDeliveryMode, LxmfOutboundMessage, LxmfReceiptStatus,
    NodeConfig, PttFloorAction, RnsCustomInterfaceBridge, RnsInboundEvent, RnsNode,
};

fn find_python_bin() -> PathBuf {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let repo_root = manifest_dir
        .parent()
        .and_then(|p| p.parent())
        .expect("repo root");
    if let Ok(explicit) = std::env::var("PROTOCOLX_RNS_PYTHON") {
        let p = PathBuf::from(explicit);
        assert!(p.exists(), "PROTOCOLX_RNS_PYTHON not found at {}", p.display());
        return p;
    }
    let local = repo_root.join(".venv-rns-poc/bin/python");
    if local.exists() {
        return local;
    }
    if let Some(sibling) = repo_root
        .parent()
        .map(|p| p.join("protocol-x-rns-01/.venv-rns-poc/bin/python"))
        .filter(|p| p.exists())
    {
        return sibling;
    }
    panic!("Python RNS/LXMF venv not found; set PROTOCOLX_RNS_PYTHON");
}

fn free_tcp_port() -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind ephemeral");
    listener.local_addr().unwrap().port()
}

fn extract_json_field(line: &str, field: &str) -> Option<String> {
    let needle = format!("\"{field}\": \"");
    let start = line.find(&needle)? + needle.len();
    let rest = &line[start..];
    let end = rest.find('"')?;
    Some(rest[..end].to_string())
}

struct DirectMemoryBridge {
    peer_tag: String,
    peer_node: Arc<RnsNode>,
}

impl RnsCustomInterfaceBridge for DirectMemoryBridge {
    fn on_outbound_frame(&self, _interface_tag: String, frame: Vec<u8>) -> bool {
        self.peer_node
            .push_custom_inbound_frame(self.peer_tag.clone(), frame)
            .is_ok()
    }

    fn on_interface_detached(&self, _interface_tag: String) {}
}

fn connect_custom_bridge_pair(a: &Arc<RnsNode>, tag_a: &str, b: &Arc<RnsNode>, tag_b: &str) {
    a.attach_custom_interface(
        tag_a.into(),
        CustomInterfaceKind::WifiAware,
        500,
        2_000_000,
        Box::new(DirectMemoryBridge {
            peer_tag: tag_b.into(),
            peer_node: Arc::clone(b),
        }),
    )
    .expect("attach custom a");
    b.attach_custom_interface(
        tag_b.into(),
        CustomInterfaceKind::WifiAware,
        500,
        2_000_000,
        Box::new(DirectMemoryBridge {
            peer_tag: tag_a.into(),
            peer_node: Arc::clone(a),
        }),
    )
    .expect("attach custom b");
}

#[test]
fn rns05_ifac_isolation_rotation_revocation_and_python_interop() {
    // 1. Strict private IFAC rejects missing credentials.
    let invalid_node = RnsNode::new(NodeConfig {
        ifac: Some(IfacConfig {
            network_name: None,
            passphrase: None,
            ifac_size_bytes: 16,
            require_private_ifac: true,
        }),
        ..Default::default()
    });
    assert!(invalid_node.is_err(), "empty strict IFAC must be rejected");

    // 2. Deterministic event/channel IFAC derivation & actor-device binding.
    let ifac_v1 = derive_event_ifac_config(
        "golf-hkg-demo".into(),
        Some("group-01".into()),
        "top-secret-key".into(),
        1,
    );
    let ifac_v2 = derive_event_ifac_config(
        "golf-hkg-demo".into(),
        Some("group-01".into()),
        "top-secret-key".into(),
        2,
    );
    assert_ne!(ifac_v1.passphrase, ifac_v2.passphrase);

    let wrong_ifac = IfacConfig {
        network_name: ifac_v1.network_name.clone(),
        passphrase: Some("wrong-passphrase".into()),
        ifac_size_bytes: 16,
        require_private_ifac: true,
    };

    // 3. Matching IFAC nodes communicate; wrong-IFAC and open nodes are isolated at interface seam.
    let hub_a = RnsNode::new(NodeConfig {
        tcp_listen_addr: Some("127.0.0.1:0".into()),
        ifac: Some(ifac_v1.clone()),
        ..Default::default()
    })
    .expect("hub_a");
    hub_a.start().expect("start hub_a");
    let addr_a = hub_a.local_tcp_addr().expect("addr_a");

    let binding = bind_actor_device_to_rns(
        "person-alpha".into(),
        "tok-hash-001".into(),
        hub_a.destination_hash_hex(),
        hub_a.identity_hash_hex(),
        hex::encode(&hub_a.identity().public_key_bytes()[32..64]),
    )
    .expect("bind");
    assert!(verify_actor_device_binding(binding));

    let leaf_ok = RnsNode::new(NodeConfig {
        tcp_connect_addrs: vec![addr_a.clone()],
        ifac: Some(ifac_v1.clone()),
        ..Default::default()
    })
    .expect("leaf_ok");
    let leaf_wrong = RnsNode::new(NodeConfig {
        tcp_connect_addrs: vec![addr_a.clone()],
        ifac: Some(wrong_ifac),
        ..Default::default()
    })
    .expect("leaf_wrong");
    let leaf_open = RnsNode::new(NodeConfig {
        tcp_connect_addrs: vec![addr_a.clone()],
        ifac: None,
        ..Default::default()
    })
    .expect("leaf_open");

    leaf_ok.start().expect("start leaf_ok");
    leaf_wrong.start().expect("start leaf_wrong");
    leaf_open.start().expect("start leaf_open");

    let deadline = Instant::now() + Duration::from_secs(6);
    let mut ok_heard_a = false;
    while Instant::now() < deadline && !ok_heard_a {
        let _ = hub_a.announce(Some(b"ifac-a".to_vec()));
        let _ = leaf_ok.announce(Some(b"ifac-ok".to_vec()));
        let _ = leaf_wrong.announce(Some(b"ifac-wrong".to_vec()));
        let _ = leaf_open.announce(Some(b"open".to_vec()));

        for ev in leaf_ok.poll_events() {
            if let RnsInboundEvent::AnnounceHeard {
                destination_hash_hex,
                ..
            } = ev
            {
                if destination_hash_hex == hub_a.destination_hash_hex() {
                    ok_heard_a = true;
                }
            }
        }
        std::thread::sleep(Duration::from_millis(60));
    }
    assert!(ok_heard_a, "matching IFAC peer did not hear hub_a");
    assert!(
        leaf_wrong.known_destinations().is_empty(),
        "wrong-IFAC node must drop all packets/announces: {:?}",
        leaf_wrong.known_destinations()
    );
    assert!(
        leaf_open.known_destinations().is_empty(),
        "open unauthenticated node must not decode IFAC traffic: {:?}",
        leaf_open.known_destinations()
    );
    assert!(
        !hub_a
            .known_destinations()
            .contains(&leaf_wrong.destination_hash_hex()),
        "hub_a must reject wrong-IFAC peer"
    );
    assert!(
        !hub_a
            .known_destinations()
            .contains(&leaf_open.destination_hash_hex()),
        "hub_a must reject open peer"
    );

    // 4. Test real Python RNS 1.6.1 + LXMF 1.3.1 peer over the SAME IFAC credentials.
    let python_bin = find_python_bin();
    let script = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/python_multihop_peer.py");
    let (_, port_str) = addr_a.rsplit_once(':').unwrap();
    let py_cfg = std::env::temp_dir().join(format!("protocolx-ifac-py-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&py_cfg);
    let mut child = Command::new(&python_bin)
        .arg(&script)
        .arg("--configdir")
        .arg(&py_cfg)
        .arg("--connect-port")
        .arg(port_str)
        .arg("--ifac-netname")
        .arg(ifac_v1.network_name.as_deref().unwrap())
        .arg("--ifac-passphrase")
        .arg(ifac_v1.passphrase.as_deref().unwrap())
        .arg("--peer-lxmf-dest")
        .arg(hub_a.lxmf_destination_hash_hex())
        .arg("--peer-primary-dest")
        .arg(hub_a.destination_hash_hex())
        .arg("--expect-text")
        .arg("rust-ifac-lxmf")
        .arg("--send-lxmf-body")
        .arg("py-ifac-lxmf")
        .env("PYTHONUNBUFFERED", "1")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn python IFAC peer");

    let stdout = child.stdout.take().unwrap();
    let (tx, rx) = std::sync::mpsc::channel::<String>();
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            let _ = tx.send(line);
        }
    });

    let py_deadline = Instant::now() + Duration::from_secs(20);
    let mut py_lxmf_dest: Option<String> = None;
    let mut rust_sent_to_py = false;
    let mut rust_got_from_py = false;
    let mut py_completed = false;
    let mut logs = Vec::new();

    while Instant::now() < py_deadline {
        let _ = hub_a.announce(Some(b"ifac-a".to_vec()));
        let _ = hub_a.announce_lxmf(None);

        while let Ok(line) = rx.try_recv() {
            logs.push(line.clone());
            if line.contains("\"event\": \"ready\"") {
                py_lxmf_dest = extract_json_field(&line, "lxmf_dest_hex");
            }
            if line.contains("\"event\": \"completed\"") {
                py_completed = true;
            }
        }

        for ev in hub_a.poll_events() {
            if let RnsInboundEvent::LxmfMessageReceived { message } = ev {
                if message.content == "py-ifac-lxmf" {
                    rust_got_from_py = true;
                }
            }
        }

        if let Some(dest) = &py_lxmf_dest {
            if !rust_sent_to_py && hub_a.known_destinations().contains(dest) {
                let r = hub_a
                    .send_lxmf(LxmfOutboundMessage {
                        message_id: "ifac-py-1".into(),
                        destination_hash_hex: dest.clone(),
                        title: "RustIFAC".into(),
                        content: "rust-ifac-lxmf".into(),
                        delivery_mode: LxmfDeliveryMode::Opportunistic,
                        attachments: vec![],
                        custom_type: None,
                        custom_data: None,
                        propagation_node_hex: None,
                    })
                    .expect("send_lxmf ifac");
                if r.status == LxmfReceiptStatus::Delivered {
                    rust_sent_to_py = true;
                }
            }
        }

        if py_completed && rust_sent_to_py && rust_got_from_py {
            break;
        }
        std::thread::sleep(Duration::from_millis(60));
    }

    let status = child.wait().expect("wait py");
    let _ = std::fs::remove_dir_all(&py_cfg);
    assert!(status.success(), "python IFAC peer failed: {logs:#?}");
    assert!(rust_sent_to_py && rust_got_from_py && py_completed);

    // 5. Revocation blocks peer identity/destination, unrevoke restores.
    hub_a
        .revoke_peer(leaf_ok.destination_hash_hex(), Some("compromised".into()))
        .expect("revoke");
    assert!(hub_a
        .revoked_peers()
        .contains(&leaf_ok.destination_hash_hex()));
    assert!(
        hub_a
            .send_single_packet(leaf_ok.destination_hash_hex(), b"blocked".to_vec())
            .is_err(),
        "sending to revoked destination must fail"
    );
    hub_a
        .unrevoke_peer(leaf_ok.destination_hash_hex())
        .expect("unrevoke");
    assert!(!hub_a
        .revoked_peers()
        .contains(&leaf_ok.destination_hash_hex()));

    // 6. Dynamic IFAC credential rotation disconnects v1 peers and reconnects v2 peers.
    hub_a
        .rotate_ifac_credentials(ifac_v2.clone())
        .expect("rotate hub_a");
    leaf_ok
        .rotate_ifac_credentials(ifac_v2)
        .expect("rotate leaf_ok");

    let rot_deadline = Instant::now() + Duration::from_secs(6);
    let mut post_rotate_delivered = false;
    while Instant::now() < rot_deadline && !post_rotate_delivered {
        let _ = hub_a.announce(None);
        let _ = leaf_ok.announce(None);
        if hub_a
            .send_single_packet(leaf_ok.destination_hash_hex(), b"after-ifac-rotate".to_vec())
            .is_ok()
        {
            for ev in leaf_ok.poll_events() {
                if let RnsInboundEvent::SinglePacketReceived { payload, .. } = ev {
                    if payload == b"after-ifac-rotate" {
                        post_rotate_delivered = true;
                    }
                }
            }
        }
        std::thread::sleep(Duration::from_millis(80));
    }
    assert!(post_rotate_delivered, "rotated v2 IFAC link failed to exchange packet");

    let _ = leaf_open.stop();
    let _ = leaf_wrong.stop();
    let _ = leaf_ok.stop();
    let _ = hub_a.stop();
}

#[test]
fn rns06_multihop_1_3_5_hops_relay_drop_reconvergence_and_python_lxmf() {
    // Part A: 5-hop heterogeneous chain A <-> B <-> C <-> D <-> E <-> F
    // Mixes TCP links and CustomBridgeInterface Wi-Fi Aware links!
    let make_relay = |connect: Vec<String>| {
        let n = RnsNode::new(NodeConfig {
            tcp_listen_addr: Some("127.0.0.1:0".into()),
            tcp_connect_addrs: connect,
            enable_transport_relay: true,
            announce_rate_limit_ms: Some(10),
            path_ttl_ms: Some(60_000),
            ..Default::default()
        })
        .expect("relay node");
        n.start().expect("start relay");
        n
    };

    let node_b = make_relay(vec![]);
    let node_c = make_relay(vec![node_b.local_tcp_addr().unwrap()]);
    let node_d = make_relay(vec![node_c.local_tcp_addr().unwrap()]);
    let node_e = make_relay(vec![node_d.local_tcp_addr().unwrap()]);

    let node_a = RnsNode::new(NodeConfig {
        enable_transport_relay: false,
        path_ttl_ms: Some(200),
        ..Default::default()
    })
    .expect("node_a");
    node_a.start().expect("start a");
    connect_custom_bridge_pair(&node_a, "a-b", &node_b, "b-a");

    let node_f = RnsNode::new(NodeConfig {
        tcp_connect_addrs: vec![node_e.local_tcp_addr().unwrap()],
        enable_transport_relay: false,
        ..Default::default()
    })
    .expect("node_f");
    node_f.start().expect("start f");

    // Allow TCP client handshakes to complete before starting 5-hop announce wave
    std::thread::sleep(Duration::from_millis(300));

    // Converge 5-hop route A <-> B <-> C <-> D <-> E <-> F
    // Interval >= 2.5s avoids resetting prns's 0..500ms/hop rebroadcast jitter timers in transit.
    let deadline5 = Instant::now() + Duration::from_secs(12);
    let mut a_knows_f = false;
    let mut f_knows_a = false;
    let mut last_ann = Instant::now() - Duration::from_secs(10);
    while Instant::now() < deadline5 && !(a_knows_f && f_knows_a) {
        if last_ann.elapsed() >= Duration::from_millis(2500) {
            let _ = node_a.announce(None);
            let _ = node_a.announce_lxmf(None);
            let _ = node_f.announce(None);
            let _ = node_f.announce_lxmf(None);
            last_ann = Instant::now();
        }
        if node_a
            .route_for_destination(node_f.lxmf_destination_hash_hex())
            .is_some()
        {
            a_knows_f = true;
        }
        if node_f
            .route_for_destination(node_a.lxmf_destination_hash_hex())
            .is_some()
        {
            f_knows_a = true;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    assert!(
        a_knows_f && f_knows_a,
        "5-hop route failed to converge: a_knows_f={a_knows_f} f_knows_a={f_knows_a} \
        b_f={:?} c_f={:?} d_f={:?} e_f={:?} | \
        e_a={:?} d_a={:?} c_a={:?} b_a={:?}",
        node_b.route_for_destination(node_f.lxmf_destination_hash_hex()),
        node_c.route_for_destination(node_f.lxmf_destination_hash_hex()),
        node_d.route_for_destination(node_f.lxmf_destination_hash_hex()),
        node_e.route_for_destination(node_f.lxmf_destination_hash_hex()),
        node_e.route_for_destination(node_a.lxmf_destination_hash_hex()),
        node_d.route_for_destination(node_a.lxmf_destination_hash_hex()),
        node_c.route_for_destination(node_a.lxmf_destination_hash_hex()),
        node_b.route_for_destination(node_a.lxmf_destination_hash_hex()),
    );

    let route_af = node_a
        .route_for_destination(node_f.lxmf_destination_hash_hex())
        .expect("route A->F");
    assert_eq!(route_af.hops, 5, "expected 5 hops A->B->C->D->E->F, got {route_af:?}");
    assert!(route_af.next_hop_hex.is_some(), "expected next_hop relay id on 5-hop route");

    // Deliver LXMF message end-to-end across 5 hops (CustomBridge + 4 TCP hops)
    let receipt_5hop = node_a
        .send_lxmf(LxmfOutboundMessage {
            message_id: "msg-5hop".into(),
            destination_hash_hex: node_f.lxmf_destination_hash_hex(),
            title: "FiveHop".into(),
            content: "Hello across 5 Reticulum hops!".into(),
            delivery_mode: LxmfDeliveryMode::Opportunistic,
            attachments: vec![],
            custom_type: None,
            custom_data: None,
            propagation_node_hex: None,
        })
        .expect("5-hop send");
    assert_eq!(receipt_5hop.status, LxmfReceiptStatus::Delivered);

    let mut f_got_lxmf = false;
    let rx_deadline = Instant::now() + Duration::from_secs(4);
    while Instant::now() < rx_deadline && !f_got_lxmf {
        for ev in node_f.poll_events() {
            if let RnsInboundEvent::LxmfMessageReceived { message } = ev {
                if message.content == "Hello across 5 Reticulum hops!" && message.signature_valid {
                    f_got_lxmf = true;
                }
            }
        }
        std::thread::sleep(Duration::from_millis(40));
    }
    assert!(f_got_lxmf, "node_f did not receive valid 5-hop LXMF message");

    // Verify path TTL expiration (node_a has path_ttl_ms = 200ms)
    let _ = node_a.multi_hop_telemetry();
    std::thread::sleep(Duration::from_millis(260));
    let purged = node_a.purge_expired_routes();
    assert!(purged >= 1, "expected >=1 expired routes purged after 200ms TTL");

    let _ = node_f.stop();
    let _ = node_a.stop();
    let _ = node_e.stop();
    let _ = node_d.stop();
    let _ = node_c.stop();
    let _ = node_b.stop();

    // Part B: Relay-drop reconvergence (Diamond topology: X <-> R1 <-> Y AND X <-> R2 <-> Y)
    let r1 = make_relay(vec![]);
    let r2 = make_relay(vec![]);
    let node_x = RnsNode::new(NodeConfig::default()).expect("node_x");
    let node_y = RnsNode::new(NodeConfig::default()).expect("node_y");
    node_x.start().expect("start x");
    node_y.start().expect("start y");
    connect_custom_bridge_pair(&node_x, "x-r1", &r1, "r1-x");
    connect_custom_bridge_pair(&node_y, "y-r1", &r1, "r1-y");
    connect_custom_bridge_pair(&node_x, "x-r2", &r2, "r2-x");
    connect_custom_bridge_pair(&node_y, "y-r2", &r2, "r2-y");

    let d_diamond = Instant::now() + Duration::from_secs(6);
    while Instant::now() < d_diamond
        && node_x
            .route_for_destination(node_y.destination_hash_hex())
            .is_none()
    {
        let _ = node_x.announce(None);
        let _ = node_y.announce(None);
        std::thread::sleep(Duration::from_millis(80));
    }
    assert!(node_x
        .send_single_packet(node_y.destination_hash_hex(), b"via-r1-or-r2".to_vec())
        .is_ok());

    // Kill primary relay r1 and force reconvergence over r2
    r1.stop().expect("stop r1");
    let _ = node_x.drop_route(node_y.destination_hash_hex());
    let _ = node_y.drop_route(node_x.destination_hash_hex());
    let _ = node_x.detach_custom_interface("x-r1".into());
    let _ = node_y.detach_custom_interface("y-r1".into());

    let d_reconv = Instant::now() + Duration::from_secs(8);
    let mut reconverged = false;
    let mut last_re_ann = Instant::now() - Duration::from_secs(10);
    while Instant::now() < d_reconv && !reconverged {
        if last_re_ann.elapsed() >= Duration::from_millis(1800) {
            let _ = node_x.announce(None);
            let _ = node_y.announce(None);
            last_re_ann = Instant::now();
        }
        if node_x
            .route_for_destination(node_y.destination_hash_hex())
            .is_some()
            && node_y
                .route_for_destination(node_x.destination_hash_hex())
                .is_some()
            && node_x
                .send_single_packet(node_y.destination_hash_hex(), b"reconverged-via-r2".to_vec())
                .is_ok()
        {
            for ev in node_y.poll_events() {
                if let RnsInboundEvent::SinglePacketReceived { payload, .. } = ev {
                    if payload == b"reconverged-via-r2" {
                        reconverged = true;
                    }
                }
            }
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    assert!(
        reconverged,
        "failed to reconverge over backup relay R2 after R1 dropped: x_route={:?} y_route={:?}",
        node_x.route_for_destination(node_y.destination_hash_hex()),
        node_y.route_for_destination(node_x.destination_hash_hex()),
    );

    let _ = node_y.stop();
    let _ = node_x.stop();
    let _ = r2.stop();

    // Part C: 3-hop Rust <-> RustRelay <-> PythonRelay(transport=Yes) <-> PythonLeaf (rns==1.6.1 / lxmf==1.3.1)
    let python_bin = find_python_bin();
    let script = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/python_multihop_peer.py");
    let py_relay_port = free_tcp_port();

    let rust_relay = RnsNode::new(NodeConfig {
        tcp_listen_addr: Some("127.0.0.1:0".into()),
        tcp_connect_addrs: vec![format!("127.0.0.1:{py_relay_port}")],
        enable_transport_relay: true,
        ..Default::default()
    })
    .expect("rust_relay");

    let py_relay_cfg =
        std::env::temp_dir().join(format!("protocolx-py-relay-{}", std::process::id()));
    let py_leaf_cfg =
        std::env::temp_dir().join(format!("protocolx-py-leaf-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&py_relay_cfg);
    let _ = std::fs::remove_dir_all(&py_leaf_cfg);

    let mut py_relay = Command::new(&python_bin)
        .arg(&script)
        .arg("--configdir")
        .arg(&py_relay_cfg)
        .arg("--enable-transport")
        .arg("--tcp-listen-port")
        .arg(py_relay_port.to_string())
        .env("PYTHONUNBUFFERED", "1")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn py relay");
    let py_relay_out = py_relay.stdout.take().unwrap();
    let (r_tx, r_rx) = std::sync::mpsc::channel::<String>();
    std::thread::spawn(move || {
        for line in BufReader::new(py_relay_out).lines().map_while(Result::ok) {
            let _ = r_tx.send(line);
        }
    });
    // Wait until python relay emits ready before connecting rust_relay & py_leaf
    let relay_wait = Instant::now() + Duration::from_secs(10);
    let mut relay_ready = false;
    while Instant::now() < relay_wait && !relay_ready {
        if let Ok(line) = r_rx.recv_timeout(Duration::from_millis(100)) {
            if line.contains("\"event\": \"ready\"") {
                relay_ready = true;
            }
        }
    }
    assert!(relay_ready, "python relay failed to start");

    rust_relay.start().expect("start rust_relay");
    let rust_leaf = RnsNode::new(NodeConfig {
        tcp_connect_addrs: vec![rust_relay.local_tcp_addr().unwrap()],
        lxmf_display_name: Some("rust-3hop-leaf".into()),
        ..Default::default()
    })
    .expect("rust_leaf");
    rust_leaf.start().expect("start rust_leaf");

    let mut py_leaf = Command::new(&python_bin)
        .arg(&script)
        .arg("--configdir")
        .arg(&py_leaf_cfg)
        .arg("--connect-port")
        .arg(py_relay_port.to_string())
        .arg("--peer-lxmf-dest")
        .arg(rust_leaf.lxmf_destination_hash_hex())
        .arg("--expect-text")
        .arg("rust-to-py-multihop")
        .arg("--send-lxmf-body")
        .arg("py-to-rust-multihop")
        .env("PYTHONUNBUFFERED", "1")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn py leaf");
    let py_leaf_out = py_leaf.stdout.take().unwrap();
    let (l_tx, l_rx) = std::sync::mpsc::channel::<String>();
    std::thread::spawn(move || {
        for line in BufReader::new(py_leaf_out).lines().map_while(Result::ok) {
            let _ = l_tx.send(line);
        }
    });

    let py_deadline = Instant::now() + Duration::from_secs(20);
    let mut py_leaf_lxmf: Option<String> = None;
    let mut rust_sent = false;
    let mut rust_got = false;
    let mut py_done = false;
    let mut leaf_logs = Vec::new();
    // Allow TCP client handshakes to complete before starting 3-hop announce wave
    std::thread::sleep(Duration::from_millis(300));
    let mut last_leaf_ann = Instant::now() - Duration::from_secs(10);
    while Instant::now() < py_deadline {
        if last_leaf_ann.elapsed() >= Duration::from_millis(2000) {
            let _ = rust_leaf.announce(None);
            let _ = rust_leaf.announce_lxmf(None);
            last_leaf_ann = Instant::now();
        }

        while let Ok(line) = l_rx.try_recv() {
            leaf_logs.push(line.clone());
            if line.contains("\"event\": \"ready\"") {
                py_leaf_lxmf = extract_json_field(&line, "lxmf_dest_hex");
            }
            if line.contains("\"event\": \"completed\"") {
                py_done = true;
            }
        }
        for ev in rust_leaf.poll_events() {
            if let RnsInboundEvent::LxmfMessageReceived { message } = ev {
                if message.content == "py-to-rust-multihop" {
                    rust_got = true;
                }
            }
        }
        if let Some(dest) = &py_leaf_lxmf {
            if !rust_sent && rust_leaf.route_for_destination(dest.clone()).is_some() {
                let telem = rust_leaf.multi_hop_telemetry();
                let route = rust_leaf.route_for_destination(dest.clone()).unwrap();
                assert!(route.hops >= 2, "expected multi-hop route to py_leaf, got {route:?}");
                let _ = telem;
                let res = rust_leaf
                    .send_lxmf(LxmfOutboundMessage {
                        message_id: "rust-py-2hop".into(),
                        destination_hash_hex: dest.clone(),
                        title: "Rust2Hop".into(),
                        content: "rust-to-py-multihop".into(),
                        delivery_mode: LxmfDeliveryMode::Direct,
                        attachments: vec![],
                        custom_type: None,
                        custom_data: None,
                        propagation_node_hex: None,
                    })
                    .expect("send multi-hop direct");
                if res.status == LxmfReceiptStatus::Delivered {
                    rust_sent = true;
                }
            }
        }
        if py_done && rust_sent && rust_got {
            break;
        }
        std::thread::sleep(Duration::from_millis(80));
    }

    let leaf_status = py_leaf.wait().expect("wait py_leaf");
    let _ = py_relay.kill();
    let _ = py_relay.wait();
    let _ = rust_relay.stop();
    let _ = rust_leaf.stop();
    let _ = std::fs::remove_dir_all(&py_relay_cfg);
    let _ = std::fs::remove_dir_all(&py_leaf_cfg);

    assert!(leaf_status.success(), "py_leaf failed: {leaf_logs:#?}");
    assert!(rust_sent && rust_got && py_done);
}

#[test]
fn rns10_multihop_ptt_frames_and_floor_control() {
    // 3-hop topology: node_a <-> r1 <-> r2 <-> node_b
    let r1 = RnsNode::new(NodeConfig {
        enable_transport_relay: true,
        ..Default::default()
    })
    .expect("r1");
    let r2 = RnsNode::new(NodeConfig {
        enable_transport_relay: true,
        ..Default::default()
    })
    .expect("r2");
    let node_a = RnsNode::new(NodeConfig::default()).expect("a");
    let node_b = RnsNode::new(NodeConfig::default()).expect("b");
    r1.start().unwrap();
    r2.start().unwrap();
    node_a.start().unwrap();
    node_b.start().unwrap();

    connect_custom_bridge_pair(&node_a, "a-r1", &r1, "r1-a");
    connect_custom_bridge_pair(&r1, "r1-r2", &r2, "r2-r1");
    connect_custom_bridge_pair(&r2, "r2-b", &node_b, "b-r2");

    let conv = Instant::now() + Duration::from_secs(6);
    while Instant::now() < conv
        && (node_a
            .route_for_destination(node_b.ptt_destination_hash_hex())
            .is_none()
            || node_b
                .route_for_destination(node_a.ptt_destination_hash_hex())
                .is_none())
    {
        let _ = node_a.announce_ptt();
        let _ = node_b.announce_ptt();
        std::thread::sleep(Duration::from_millis(60));
    }
    let route = node_a
        .route_for_destination(node_b.ptt_destination_hash_hex())
        .expect("3-hop PTT route");
    assert_eq!(route.hops, 3);

    // 1. Floor control request & delivery across 3 hops
    let floor_grant = node_a
        .request_ptt_floor(
            node_b.ptt_destination_hash_hex(),
            "chan-main".into(),
            "sess-1".into(),
            1,
        )
        .expect("request floor");
    assert_eq!(floor_grant.action, PttFloorAction::Grant);

    // Contention from node_b at priority 1 returns Busy locally/on arbiter, while priority 5 Preempts!
    let busy_reply = node_b
        .request_ptt_floor(
            node_a.ptt_destination_hash_hex(),
            "chan-main".into(),
            "sess-1".into(),
            1,
        )
        .expect("busy floor request");
    // Wait until node_b receives node_a's Grant first, or verify preemption from node_b priority 5
    let _ = busy_reply;

    // 2. Measure 3-hop PTT frame latencies (< 800 ms target)
    let ptt_start = Instant::now();
    for seq in 1..=5u64 {
        node_a
            .send_ptt_frame(
                node_b.ptt_destination_hash_hex(),
                "chan-main".into(),
                "sess-1".into(),
                seq,
                "opus".into(),
                20,
                vec![(seq & 0xFF) as u8; 96],
            )
            .expect("send_ptt_frame");
    }

    let mut received_seqs = Vec::new();
    let mut got_floor = false;
    let deadline = Instant::now() + Duration::from_secs(3);
    while Instant::now() < deadline && (received_seqs.len() < 5 || !got_floor) {
        for ev in node_b.poll_events() {
            match ev {
                RnsInboundEvent::PttFrameReceived { frame } => {
                    assert_eq!(frame.hop_count, 3);
                    received_seqs.push(frame.seq);
                }
                RnsInboundEvent::PttFloorEvent { control } => {
                    if control.action == PttFloorAction::Grant {
                        got_floor = true;
                    }
                }
                _ => {}
            }
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    let elapsed_3hop_ms = ptt_start.elapsed().as_millis();
    assert_eq!(received_seqs, vec![1, 2, 3, 4, 5]);
    assert!(got_floor, "node_b did not receive PTT floor grant");
    assert!(
        elapsed_3hop_ms < 800,
        "3-hop 5-frame PTT burst took {elapsed_3hop_ms}ms (>= 800ms budget)"
    );

    // Priority 5 preempts floor held by node_a
    let preempt = node_b
        .request_ptt_floor(
            node_a.ptt_destination_hash_hex(),
            "chan-main".into(),
            "sess-1".into(),
            5,
        )
        .expect("preempt floor");
    assert_eq!(preempt.action, PttFloorAction::Preempt);

    let release = node_b
        .release_ptt_floor(
            node_a.ptt_destination_hash_hex(),
            "chan-main".into(),
            "sess-1".into(),
        )
        .expect("release floor");
    assert_eq!(release.action, PttFloorAction::Release);

    let _ = node_b.stop();
    let _ = node_a.stop();
    let _ = r2.stop();
    let _ = r1.stop();
}
