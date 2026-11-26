use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use protocolx_rns::{
    CustomInterfaceKind, CustomLinkMetrics, LxmfAttachment, LxmfDeliveryMode, LxmfOutboundMessage,
    LxmfReceiptStatus, NodeConfig, RnsCustomInterfaceBridge, RnsInboundEvent, RnsNode,
};

fn python_bin_and_script() -> (PathBuf, PathBuf) {
    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let repo_root = manifest_dir
        .parent()
        .and_then(|p| p.parent())
        .expect("repo root");
    let candidates = [
        repo_root.join(".venv-rns-poc/bin/python"),
        repo_root
            .parent()
            .map(|p| p.join("protocol-x-rns-01/.venv-rns-poc/bin/python"))
            .unwrap_or_default(),
    ];
    let python_bin = std::env::var("PROTOCOLX_RNS_PYTHON")
        .map(PathBuf::from)
        .unwrap_or_else(|_| {
            candidates
                .into_iter()
                .find(|p| p.exists())
                .expect("Python RNS/LXMF venv not found (.venv-rns-poc or PROTOCOLX_RNS_PYTHON)")
        });
    let script = manifest_dir.join("tests/python_lxmf_peer.py");
    assert!(script.exists(), "missing {}", script.display());
    (python_bin, script)
}

fn extract_json_field(line: &str, key: &str) -> Option<String> {
    let needle = format!("\"{key}\": \"");
    let pos = line.find(&needle)?;
    let rest = &line[pos + needle.len()..];
    let end = rest.find('"')?;
    Some(rest[..end].to_string())
}

#[test]
fn bidirectional_rust_and_python_lxmf_opportunistic_direct_and_resource() {
    let (python_bin, peer_script) = python_bin_and_script();
    let temp_dir = std::env::temp_dir().join(format!("protocolx-lxmf-direct-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&temp_dir);
    let rust_storage = temp_dir.join("rust_store");
    let py_cfg = temp_dir.join("py_cfg");
    std::fs::create_dir_all(&rust_storage).unwrap();
    std::fs::create_dir_all(&py_cfg).unwrap();

    let rust_node = RnsNode::new(NodeConfig {
        app_name: "protocolx".into(),
        aspects: vec!["node".into()],
        tcp_listen_addr: Some("127.0.0.1:0".into()),
        storage_dir: Some(rust_storage.to_string_lossy().into_owned()),
        lxmf_display_name: Some("rust-lxmf-node".into()),
        ..Default::default()
    })
    .expect("create rust node");
    rust_node.start().expect("start rust node");

    let tcp_addr = rust_node.local_tcp_addr().expect("bound tcp");
    let (host, port_str) = tcp_addr.rsplit_once(':').expect("host:port");
    let rust_lxmf_hex = rust_node.lxmf_destination_hash_hex();
    let rust_secret = rust_node.identity().secret_bytes();

    let mut child = Command::new(&python_bin)
        .arg(&peer_script)
        .arg("--host")
        .arg(host)
        .arg("--port")
        .arg(port_str)
        .arg("--rust-lxmf-dest")
        .arg(&rust_lxmf_hex)
        .arg("--configdir")
        .arg(&py_cfg)
        .arg("--mode")
        .arg("direct")
        .env("PYTHONUNBUFFERED", "1")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn python_lxmf_peer.py");

    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    let (tx, rx) = std::sync::mpsc::channel::<String>();
    let tx_err = tx.clone();
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            let _ = tx.send(line);
        }
    });
    std::thread::spawn(move || {
        for line in BufReader::new(stderr).lines().map_while(Result::ok) {
            let _ = tx_err.send(format!("[py-stderr] {line}"));
        }
    });

    let deadline = Instant::now() + Duration::from_secs(25);
    let mut py_lxmf_dest: Option<String> = None;
    let mut rust_heard_py = false;
    let mut rust_sent_opp = false;
    let mut rust_sent_resource = false;
    let mut rust_got_py_opp = false;
    let mut rust_got_py_resource = false;
    let mut py_completed = false;
    let mut py_logs = Vec::new();

    let rust_attachment_bytes: Vec<u8> = (0..6144).map(|i| (i % 247) as u8).collect();
    let py_expected_bytes: Vec<u8> = (0..5120).map(|i| (i % 251) as u8).collect();

    while Instant::now() < deadline {
        let _ = rust_node.announce_lxmf(None);

        while let Ok(line) = rx.try_recv() {
            py_logs.push(line.clone());
            if line.contains("\"event\": \"ready\"") {
                py_lxmf_dest = extract_json_field(&line, "lxmf_dest_hex");
            }
            if line.contains("\"event\": \"completed\"") {
                py_completed = true;
            }
        }

        for ev in rust_node.poll_events() {
            match ev {
                RnsInboundEvent::AnnounceHeard {
                    destination_hash_hex,
                    ..
                } => {
                    if py_lxmf_dest.as_deref() == Some(destination_hash_hex.as_str()) {
                        rust_heard_py = true;
                    }
                }
                RnsInboundEvent::LxmfMessageReceived { message } => {
                    if message.title == "OppPy" && message.content == "Opp body from Python LXMF" {
                        rust_got_py_opp = true;
                    }
                    if message.title == "ResPy"
                        && message.attachments.len() == 1
                        && message.attachments[0].filename == "py_chunk.bin"
                        && message.attachments[0].data == py_expected_bytes
                    {
                        rust_got_py_resource = true;
                    }
                }
                _ => {}
            }
        }

        if rust_heard_py && !rust_sent_opp {
            let dest = py_lxmf_dest.clone().unwrap();
            let receipt = rust_node
                .send_lxmf(LxmfOutboundMessage {
                    message_id: "rust-opp-1".into(),
                    destination_hash_hex: dest.clone(),
                    title: "OppRust".into(),
                    content: "Hello Opp from Rust".into(),
                    delivery_mode: LxmfDeliveryMode::Opportunistic,
                    attachments: vec![],
                    custom_type: None,
                    custom_data: None,
                    propagation_node_hex: None,
                })
                .expect("send opp");
            assert_eq!(receipt.status, LxmfReceiptStatus::Delivered);
            rust_sent_opp = true;

            let res_receipt = rust_node
                .send_lxmf(LxmfOutboundMessage {
                    message_id: "rust-res-1".into(),
                    destination_hash_hex: dest,
                    title: "ResRust".into(),
                    content: "6KB attachment from Rust".into(),
                    delivery_mode: LxmfDeliveryMode::Direct,
                    attachments: vec![LxmfAttachment {
                        filename: "field_photo.bin".into(),
                        data: rust_attachment_bytes.clone(),
                    }],
                    custom_type: Some("protocolx/photo".into()),
                    custom_data: Some(br#"{"seq":1}"#.to_vec()),
                    propagation_node_hex: None,
                })
                .expect("send resource lxmf");
            assert_eq!(res_receipt.status, LxmfReceiptStatus::Delivered);
            rust_sent_resource = true;
        }

        if py_completed && rust_got_py_opp && rust_got_py_resource && rust_sent_opp && rust_sent_resource {
            break;
        }
        std::thread::sleep(Duration::from_millis(80));
    }

    let status = child.wait().expect("wait py peer");
    while let Ok(line) = rx.try_recv() {
        py_logs.push(line);
    }

    // Verify FAILED receipt for unknown destination
    let failed_receipt = rust_node
        .send_lxmf(LxmfOutboundMessage {
            message_id: "rust-fail-1".into(),
            destination_hash_hex: "00112233445566778899aabbccddeeff".into(),
            title: "Fail".into(),
            content: "Unreachable".into(),
            delivery_mode: LxmfDeliveryMode::Opportunistic,
            attachments: vec![],
            custom_type: None,
            custom_data: None,
            propagation_node_hex: None,
        })
        .expect("send_lxmf returns Failed receipt instead of panicking");
    assert_eq!(failed_receipt.status, LxmfReceiptStatus::Failed);

    assert!(status.success(), "python failed: {py_logs:#?}");
    assert!(rust_got_py_opp, "Rust did not receive Python opportunistic LXMF; logs: {py_logs:#?}");
    assert!(rust_got_py_resource, "Rust did not receive Python 5KB Resource LXMF; logs: {py_logs:#?}");
    assert!(py_completed, "Python did not complete; logs: {py_logs:#?}");

    rust_node.stop().expect("stop node");

    // Verify persistence across node restart
    let reloaded = RnsNode::new(NodeConfig {
        identity_secret: Some(rust_secret),
        storage_dir: Some(rust_storage.to_string_lossy().into_owned()),
        ..Default::default()
    })
    .expect("reload node");
    assert!(reloaded.persisted_lxmf_messages().len() >= 2);
    assert!(reloaded.persisted_lxmf_receipts().len() >= 3);

    let _ = std::fs::remove_dir_all(&temp_dir);
}

#[test]
fn rust_and_python_lxmf_propagation_node_roundtrip() {
    let (python_bin, peer_script) = python_bin_and_script();
    let temp_dir = std::env::temp_dir().join(format!("protocolx-lxmf-prop-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&temp_dir);
    std::fs::create_dir_all(&temp_dir).unwrap();

    let rust_node = RnsNode::new(NodeConfig {
        tcp_listen_addr: Some("127.0.0.1:0".into()),
        lxmf_display_name: Some("rust-prop-client".into()),
        ..Default::default()
    })
    .expect("create rust node");
    rust_node.start().expect("start rust node");

    let tcp_addr = rust_node.local_tcp_addr().unwrap();
    let (host, port_str) = tcp_addr.rsplit_once(':').unwrap();
    let rust_lxmf_hex = rust_node.lxmf_destination_hash_hex();

    let mut child = Command::new(&python_bin)
        .arg(&peer_script)
        .arg("--host")
        .arg(host)
        .arg("--port")
        .arg(port_str)
        .arg("--rust-lxmf-dest")
        .arg(&rust_lxmf_hex)
        .arg("--configdir")
        .arg(temp_dir.join("py_cfg"))
        .arg("--mode")
        .arg("propagation")
        .env("PYTHONUNBUFFERED", "1")
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("spawn python propagation peer");

    let stdout = child.stdout.take().unwrap();
    let stderr = child.stderr.take().unwrap();
    let (tx, rx) = std::sync::mpsc::channel::<String>();
    let tx_err = tx.clone();
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            let _ = tx.send(line);
        }
    });
    std::thread::spawn(move || {
        for line in BufReader::new(stderr).lines().map_while(Result::ok) {
            let _ = tx_err.send(format!("[py-stderr] {line}"));
        }
    });

    let deadline = Instant::now() + Duration::from_secs(25);
    let mut py_lxmf_dest: Option<String> = None;
    let mut pn_dest: Option<String> = None;
    let mut heard_py_delivery = false;
    let mut heard_pn = false;
    let mut rust_sent_prop = false;
    let mut py_sent_prop = false;
    let mut rust_fetched_prop = false;
    let mut py_completed = false;
    let mut py_logs = Vec::new();

    while Instant::now() < deadline {
        let _ = rust_node.announce_lxmf(None);

        while let Ok(line) = rx.try_recv() {
            py_logs.push(line.clone());
            if line.contains("\"event\": \"ready\"") {
                py_lxmf_dest = extract_json_field(&line, "lxmf_dest_hex");
                pn_dest = extract_json_field(&line, "pn_dest_hex");
            }
            if line.contains("\"event\": \"python_propagated_queued\"") {
                py_sent_prop = true;
            }
            if line.contains("\"event\": \"completed\"") {
                py_completed = true;
            }
        }

        for ev in rust_node.poll_events() {
            if let RnsInboundEvent::AnnounceHeard {
                destination_hash_hex,
                ..
            } = ev
            {
                if py_lxmf_dest.as_deref() == Some(destination_hash_hex.as_str()) {
                    heard_py_delivery = true;
                }
                if pn_dest.as_deref() == Some(destination_hash_hex.as_str()) {
                    heard_pn = true;
                }
            }
        }

        if heard_py_delivery && heard_pn && !rust_sent_prop {
            let receipt = rust_node
                .send_lxmf(LxmfOutboundMessage {
                    message_id: "rust-prop-1".into(),
                    destination_hash_hex: py_lxmf_dest.clone().unwrap(),
                    title: "PropRust".into(),
                    content: "Propagated from Rust to Python via PN".into(),
                    delivery_mode: LxmfDeliveryMode::Propagated,
                    attachments: vec![],
                    custom_type: None,
                    custom_data: None,
                    propagation_node_hex: pn_dest.clone(),
                })
                .expect("send propagated lxmf");
            if receipt.status != LxmfReceiptStatus::Propagated {
                std::thread::sleep(Duration::from_millis(200));
                while let Ok(line) = rx.try_recv() {
                    py_logs.push(line);
                }
            }
            assert_eq!(
                receipt.status,
                LxmfReceiptStatus::Propagated,
                "propagated receipt detail: {} | py_logs: {py_logs:#?}",
                receipt.detail
            );
            rust_sent_prop = true;
        }

        if py_sent_prop && heard_pn && !rust_fetched_prop {
            std::thread::sleep(Duration::from_millis(300));
            let msgs = match rust_node.fetch_propagated_lxmf(pn_dest.clone()) {
                Ok(m) => m,
                Err(e) => {
                    while let Ok(line) = rx.try_recv() {
                        py_logs.push(line);
                    }
                    panic!("fetch_propagated_lxmf failed: {e:?} | py_logs: {py_logs:#?}");
                }
            };
            if msgs
                .iter()
                .any(|m| m.title == "PropPy" && m.content == "Propagated body from Python LXMF")
            {
                rust_fetched_prop = true;
            }
        }

        if py_completed && rust_sent_prop && rust_fetched_prop {
            break;
        }
        std::thread::sleep(Duration::from_millis(80));
    }

    let status = child.wait().expect("wait py peer");
    let _ = rust_node.stop();
    let _ = std::fs::remove_dir_all(&temp_dir);

    assert!(status.success(), "python failed: {py_logs:#?}");
    assert!(rust_sent_prop, "Rust did not send propagated message; logs: {py_logs:#?}");
    assert!(rust_fetched_prop, "Rust did not fetch propagated message from Python PN; logs: {py_logs:#?}");
    assert!(py_completed, "Python did not complete propagation test; logs: {py_logs:#?}");
}

struct LossyMemoryBridge {
    peer_tag: String,
    peer_node: Arc<RnsNode>,
    drop_next_link_data: Arc<AtomicUsize>,
    tx_count: Arc<AtomicUsize>,
    detached: Arc<Mutex<bool>>,
}

impl RnsCustomInterfaceBridge for LossyMemoryBridge {
    fn on_outbound_frame(&self, _interface_tag: String, frame: Vec<u8>) -> bool {
        let idx = self.tx_count.fetch_add(1, Ordering::SeqCst);
        // Simulate transient packet loss on one mid-flight frame after initial announces.
        if idx == 4 && self.drop_next_link_data.swap(0, Ordering::SeqCst) > 0 {
            return false;
        }
        self.peer_node
            .push_custom_inbound_frame(self.peer_tag.clone(), frame)
            .is_ok()
    }

    fn on_interface_detached(&self, _interface_tag: String) {
        if let Ok(mut d) = self.detached.lock() {
            *d = true;
        }
    }
}

#[test]
fn custom_bridge_wifi_aware_interface_with_metrics_and_loss_recovery() {
    let node_a = RnsNode::new(NodeConfig::default()).expect("node_a");
    let node_b = RnsNode::new(NodeConfig::default()).expect("node_b");
    node_a.start().expect("start a");
    node_b.start().expect("start b");

    let detached_a = Arc::new(Mutex::new(false));
    let bridge_a = Box::new(LossyMemoryBridge {
        peer_tag: "aware-b".into(),
        peer_node: Arc::clone(&node_b),
        drop_next_link_data: Arc::new(AtomicUsize::new(1)),
        tx_count: Arc::new(AtomicUsize::new(0)),
        detached: Arc::clone(&detached_a),
    });
    let bridge_b = Box::new(LossyMemoryBridge {
        peer_tag: "aware-a".into(),
        peer_node: Arc::clone(&node_a),
        drop_next_link_data: Arc::new(AtomicUsize::new(0)),
        tx_count: Arc::new(AtomicUsize::new(0)),
        detached: Arc::new(Mutex::new(false)),
    });

    node_a
        .attach_custom_interface(
            "aware-a".into(),
            CustomInterfaceKind::WifiAware,
            1196,
            25_000_000,
            bridge_a,
        )
        .expect("attach aware-a");
    node_b
        .attach_custom_interface(
            "aware-b".into(),
            CustomInterfaceKind::WifiAware,
            1196,
            25_000_000,
            bridge_b,
        )
        .expect("attach aware-b");

    node_a
        .update_custom_interface_metrics(
            "aware-a".into(),
            CustomLinkMetrics {
                peer_id: "peer-b".into(),
                connected: true,
                rssi_dbm: Some(-54),
                rtt_ms: Some(12),
                estimated_bandwidth_bps: Some(25_000_000),
            },
        )
        .expect("update metrics");
    let metrics = node_a.get_custom_interface_metrics("aware-a".into());
    assert_eq!(metrics.len(), 1);
    assert_eq!(metrics[0].rssi_dbm, Some(-54));

    let dest_b = node_b.lxmf_destination_hash_hex();
    let deadline = Instant::now() + Duration::from_secs(10);
    let mut heard_b = false;
    while Instant::now() < deadline && !heard_b {
        let _ = node_b.announce_lxmf(Some("aware-peer-b".into()));
        for ev in node_a.poll_events() {
            if let RnsInboundEvent::AnnounceHeard {
                destination_hash_hex,
                ..
            } = ev
            {
                if destination_hash_hex == dest_b {
                    heard_b = true;
                }
            }
        }
        std::thread::sleep(Duration::from_millis(80));
    }
    assert!(heard_b, "node_a did not hear node_b announce over custom Wi-Fi Aware bridge");

    // Send direct LXMF message (even if a frame drops, link/retry recovers cleanly)
    let receipt = node_a
        .send_lxmf(LxmfOutboundMessage {
            message_id: "aware-direct-1".into(),
            destination_hash_hex: dest_b,
            title: "AwareDirect".into(),
            content: "Over pluggable Wi-Fi Aware bridge".into(),
            delivery_mode: LxmfDeliveryMode::Direct,
            attachments: vec![],
            custom_type: None,
            custom_data: None,
            propagation_node_hex: None,
        })
        .expect("send over custom bridge");
    assert_eq!(receipt.status, LxmfReceiptStatus::Delivered);

    let mut received = false;
    while Instant::now() < deadline && !received {
        for ev in node_b.poll_events() {
            if let RnsInboundEvent::LxmfMessageReceived { message } = ev {
                if message.title == "AwareDirect" {
                    received = true;
                }
            }
        }
        std::thread::sleep(Duration::from_millis(40));
    }
    assert!(received, "node_b did not receive LXMF over custom bridge");

    assert!(node_a.detach_custom_interface("aware-a".into()).expect("detach"));
    std::thread::sleep(Duration::from_millis(100));
    assert!(*detached_a.lock().unwrap(), "on_interface_detached not invoked");

    node_b.stop().expect("stop b");
    node_a.stop().expect("stop a");
}
