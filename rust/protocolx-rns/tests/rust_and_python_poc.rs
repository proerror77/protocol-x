use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use protocolx_rns::{
    compute_destination_hash_hex, generate_identity, load_identity_from_secret, NodeConfig,
    RnsInboundEvent, RnsNode,
};

#[test]
fn identity_roundtrip_sign_verify_and_destination_hash() {
    let id = generate_identity().expect("generate identity");
    let secret = id.secret_bytes();
    assert_eq!(secret.len(), 64);

    let restored = load_identity_from_secret(secret).expect("restore identity");
    assert_eq!(id.identity_hash_hex(), restored.identity_hash_hex());
    assert_eq!(id.public_key_bytes(), restored.public_key_bytes());

    let msg = b"protocol-x-rns-01-signature-check";
    let sig = id.sign(msg.to_vec());
    assert_eq!(sig.len(), 64);
    assert!(restored
        .verify_with_public_key(id.public_key_bytes(), msg.to_vec(), sig.clone())
        .expect("verify"));
    assert!(!restored
        .verify_with_public_key(id.public_key_bytes(), b"tampered".to_vec(), sig)
        .expect("verify tampered"));

    let dest_a = id
        .destination_hash_hex("protocolx".into(), vec!["node".into()])
        .expect("dest hash");
    let dest_b = compute_destination_hash_hex(
        id.public_key_bytes(),
        "protocolx".into(),
        vec!["node".into()],
    )
    .expect("compute dest hash");
    assert_eq!(dest_a, dest_b);
    assert_eq!(dest_a.len(), 32);
}

#[test]
fn rust_to_rust_tcp_and_udp_bidirectional_exchange() {
    let node_a = RnsNode::new(NodeConfig {
        app_name: "protocolx".into(),
        aspects: vec!["node".into()],
        announce_app_data: b"rust-node-a".to_vec(),
        tcp_listen_addr: Some("127.0.0.1:0".into()),
        ..Default::default()
    })
    .expect("node_a");
    node_a.start().expect("start node_a");
    let listen_addr = node_a.local_tcp_addr().expect("bound tcp addr");

    let node_b = RnsNode::new(NodeConfig {
        app_name: "protocolx".into(),
        aspects: vec!["node".into()],
        announce_app_data: b"rust-node-b".to_vec(),
        tcp_connect_addrs: vec![listen_addr],
        ..Default::default()
    })
    .expect("node_b");
    node_b.start().expect("start node_b");

    let dest_a = node_a.destination_hash_hex();
    let dest_b = node_b.destination_hash_hex();

    let deadline = Instant::now() + Duration::from_secs(10);
    let mut a_heard_b = false;
    let mut b_heard_a = false;
    while Instant::now() < deadline && (!a_heard_b || !b_heard_a) {
        node_a.announce(None).expect("announce a");
        node_b.announce(None).expect("announce b");
        for ev in node_a.poll_events() {
            if let RnsInboundEvent::AnnounceHeard {
                destination_hash_hex,
                ..
            } = ev
            {
                if destination_hash_hex == dest_b {
                    a_heard_b = true;
                }
            }
        }
        for ev in node_b.poll_events() {
            if let RnsInboundEvent::AnnounceHeard {
                destination_hash_hex,
                ..
            } = ev
            {
                if destination_hash_hex == dest_a {
                    b_heard_a = true;
                }
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    assert!(a_heard_b, "node_a should hear node_b announce");
    assert!(b_heard_a, "node_b should hear node_a announce");

    node_a
        .send_single_packet(dest_b.clone(), b"hello-b".to_vec())
        .expect("a->b single");
    node_b
        .send_single_packet(dest_a.clone(), b"hello-a".to_vec())
        .expect("b->a single");

    let mut b_got = false;
    let mut a_got = false;
    while Instant::now() < deadline && (!a_got || !b_got) {
        for ev in node_b.poll_events() {
            if let RnsInboundEvent::SinglePacketReceived { payload, .. } = ev {
                if payload == b"hello-b" {
                    b_got = true;
                }
            }
        }
        for ev in node_a.poll_events() {
            if let RnsInboundEvent::SinglePacketReceived { payload, .. } = ev {
                if payload == b"hello-a" {
                    a_got = true;
                }
            }
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    assert!(b_got, "node_b did not receive single packet");
    assert!(a_got, "node_a did not receive single packet");

    node_b.stop().expect("stop b");
    node_a.stop().expect("stop a");
    assert!(!node_a.is_running());
}

#[test]
fn rust_to_rust_udp_bidirectional_exchange() {
    let sock_a = std::net::UdpSocket::bind("127.0.0.1:0").expect("bind ephemeral udp a");
    let addr_a = sock_a.local_addr().expect("addr a").to_string();
    let sock_b = std::net::UdpSocket::bind("127.0.0.1:0").expect("bind ephemeral udp b");
    let addr_b = sock_b.local_addr().expect("addr b").to_string();
    drop(sock_a);
    drop(sock_b);

    let node_a = RnsNode::new(NodeConfig {
        app_name: "protocolx".into(),
        aspects: vec!["udp".into()],
        announce_app_data: b"udp-a".to_vec(),
        udp_bind_addr: Some(addr_a.clone()),
        udp_peer_addr: Some(addr_b.clone()),
        ..Default::default()
    })
    .expect("udp node_a");
    node_a.start().expect("start udp node_a");

    let node_b = RnsNode::new(NodeConfig {
        app_name: "protocolx".into(),
        aspects: vec!["udp".into()],
        announce_app_data: b"udp-b".to_vec(),
        udp_bind_addr: Some(addr_b),
        udp_peer_addr: Some(addr_a),
        ..Default::default()
    })
    .expect("udp node_b");
    node_b.start().expect("start udp node_b");

    let dest_a = node_a.destination_hash_hex();
    let dest_b = node_b.destination_hash_hex();

    let deadline = Instant::now() + Duration::from_secs(10);
    let mut a_heard_b = false;
    let mut b_heard_a = false;
    while Instant::now() < deadline && (!a_heard_b || !b_heard_a) {
        node_a.announce(None).expect("announce a");
        node_b.announce(None).expect("announce b");
        for ev in node_a.poll_events() {
            if let RnsInboundEvent::AnnounceHeard {
                destination_hash_hex,
                ..
            } = ev
            {
                if destination_hash_hex == dest_b {
                    a_heard_b = true;
                }
            }
        }
        for ev in node_b.poll_events() {
            if let RnsInboundEvent::AnnounceHeard {
                destination_hash_hex,
                ..
            } = ev
            {
                if destination_hash_hex == dest_a {
                    b_heard_a = true;
                }
            }
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    assert!(a_heard_b && b_heard_a, "UDP announces did not converge");

    node_a
        .send_single_packet(dest_b, b"udp-ping".to_vec())
        .expect("a->b udp");
    node_b
        .send_single_packet(dest_a, b"udp-pong".to_vec())
        .expect("b->a udp");

    let mut b_got = false;
    let mut a_got = false;
    while Instant::now() < deadline && (!a_got || !b_got) {
        for ev in node_b.poll_events() {
            if let RnsInboundEvent::SinglePacketReceived { payload, .. } = ev {
                if payload == b"udp-ping" {
                    b_got = true;
                }
            }
        }
        for ev in node_a.poll_events() {
            if let RnsInboundEvent::SinglePacketReceived { payload, .. } = ev {
                if payload == b"udp-pong" {
                    a_got = true;
                }
            }
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    assert!(b_got && a_got, "UDP single packets not delivered bidirectionally");

    node_b.stop().expect("stop b");
    node_a.stop().expect("stop a");
}

#[test]
fn bidirectional_rust_and_official_python_rns_poc() {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let repo_root = manifest_dir
        .parent()
        .and_then(|p| p.parent())
        .expect("repo root");
    let python_bin = std::env::var("PROTOCOLX_RNS_PYTHON")
        .map(PathBuf::from)
        .unwrap_or_else(|_| {
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
            local
        });
    assert!(
        python_bin.exists(),
        "Python RNS venv not found at {}. Install `rns` into .venv-rns-poc or set PROTOCOLX_RNS_PYTHON.",
        python_bin.display()
    );
    let peer_script = manifest_dir.join("tests/python_rns_peer.py");
    assert!(peer_script.exists(), "missing {}", peer_script.display());

    let rust_node = RnsNode::new(NodeConfig {
        app_name: "protocolx".into(),
        aspects: vec!["node".into()],
        announce_app_data: b"rust-core-rns-01".to_vec(),
        tcp_listen_addr: Some("127.0.0.1:0".into()),
        ..Default::default()
    })
    .expect("create rust node");
    rust_node.start().expect("start rust node");

    let tcp_addr = rust_node.local_tcp_addr().expect("bound tcp addr");
    let (host, port_str) = tcp_addr.rsplit_once(':').expect("host:port");
    let rust_dest_hex = rust_node.destination_hash_hex();

    let temp_cfg_dir = std::env::temp_dir().join(format!(
        "protocolx-rns-py-poc-{}",
        std::process::id()
    ));
    let _ = std::fs::remove_dir_all(&temp_cfg_dir);
    std::fs::create_dir_all(&temp_cfg_dir).expect("create temp configdir");

    let mut child = Command::new(&python_bin)
        .arg(&peer_script)
        .arg("--host")
        .arg(host)
        .arg("--port")
        .arg(port_str)
        .arg("--rust-dest")
        .arg(&rust_dest_hex)
        .arg("--configdir")
        .arg(&temp_cfg_dir)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn python_rns_peer.py");

    let stdout = child.stdout.take().expect("child stdout");
    let (tx, rx) = std::sync::mpsc::channel::<String>();
    std::thread::spawn(move || {
        let reader = BufReader::new(stdout);
        for line in reader.lines().map_while(Result::ok) {
            let _ = tx.send(line);
        }
    });

    let deadline = Instant::now() + Duration::from_secs(20);
    let mut py_dest_hex: Option<String> = None;
    let mut rust_heard_py_announce = false;
    let mut rust_got_py_single = false;
    let mut rust_sent_py_single = false;
    let mut rust_link_id: Option<String> = None;
    let mut rust_got_py_link_pkt = false;
    let mut rust_sent_py_link_pkt = false;
    let mut py_logs: Vec<String> = Vec::new();
    let mut py_completed = false;

    let mut rust_events_log: Vec<String> = Vec::new();
    while Instant::now() < deadline {
        // Periodically announce Rust destination so Python learns our public key
        let _ = rust_node.announce(None);

        while let Ok(line) = rx.try_recv() {
            py_logs.push(line.clone());
            if line.contains("\"event\": \"ready\"") {
                if let Some(pos) = line.find("\"dest_hex\": \"") {
                    let rest = &line[pos + 13..];
                    if let Some(end) = rest.find('"') {
                        py_dest_hex = Some(rest[..end].to_string());
                    }
                }
            }
            if line.contains("\"event\": \"completed\"") {
                py_completed = true;
            }
        }

        for ev in rust_node.poll_events() {
            rust_events_log.push(format!("{ev:?}"));
            match ev {
                RnsInboundEvent::AnnounceHeard {
                    destination_hash_hex,
                    ..
                } => {
                    if py_dest_hex.as_deref() == Some(destination_hash_hex.as_str()) {
                        rust_heard_py_announce = true;
                    }
                }
                RnsInboundEvent::SinglePacketReceived { payload, .. } => {
                    if payload == b"PING-SINGLE-FROM-PYTHON-RNS" {
                        rust_got_py_single = true;
                    }
                }
                RnsInboundEvent::LinkPacketReceived { payload, .. } => {
                    if payload == b"HELLO-LINK-FROM-PYTHON" {
                        rust_got_py_link_pkt = true;
                    }
                }
                _ => {}
            }
        }

        if rust_heard_py_announce && !rust_sent_py_single {
            if let Some(ref py_dest) = py_dest_hex {
                rust_node
                    .send_single_packet(py_dest.clone(), b"PONG-SINGLE-FROM-RUST-PRNS".to_vec())
                    .expect("rust->python single packet");
                rust_sent_py_single = true;

                let link_id = rust_node
                    .establish_link(py_dest.clone())
                    .expect("rust->python establish link");
                rust_link_id = Some(link_id);
            }
        }

        if rust_got_py_link_pkt && !rust_sent_py_link_pkt {
            if let Some(ref link_id) = rust_link_id {
                rust_node
                    .send_link_packet(link_id.clone(), b"HELLO-LINK-FROM-RUST".to_vec())
                    .expect("rust->python link packet");
                rust_sent_py_link_pkt = true;
            }
        }

        if py_completed
            && rust_got_py_single
            && rust_sent_py_single
            && rust_got_py_link_pkt
            && rust_sent_py_link_pkt
        {
            break;
        }

        std::thread::sleep(Duration::from_millis(100));
    }

    let status = child.wait().expect("wait for python peer");
    while let Ok(line) = rx.try_recv() {
        py_logs.push(line);
    }
    let _ = rust_node.stop();
    let _ = std::fs::remove_dir_all(&temp_cfg_dir);

    assert!(
        status.success(),
        "python peer exited with {status}; rust_events: {rust_events_log:#?}; py_logs: {py_logs:#?}"
    );
    assert!(
        rust_heard_py_announce,
        "Rust did not hear Python RNS announce; logs: {py_logs:#?}"
    );
    assert!(
        rust_got_py_single,
        "Rust did not receive Python single encrypted packet; logs: {py_logs:#?}"
    );
    assert!(
        rust_got_py_link_pkt,
        "Rust did not receive Python Link encrypted packet; logs: {py_logs:#?}"
    );
    assert!(
        py_completed,
        "Python peer did not complete bidirectional verification; logs: {py_logs:#?}"
    );
}
