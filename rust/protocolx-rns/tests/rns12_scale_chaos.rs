use std::collections::HashMap;
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};

use protocolx_rns::{
    derive_event_ifac_config, CustomInterfaceKind, LxmfDeliveryMode, LxmfOutboundMessage,
    LxmfReceiptStatus, NodeConfig, RnsCustomInterfaceBridge, RnsInboundEvent, RnsNode,
};

#[derive(Clone)]
struct HubMember {
    tag: String,
    node: Arc<RnsNode>,
    partitioned: bool,
}

/// Shared RF/mesh hub connecting N real `RnsNode` instances over `CustomBridgeInterface` (`WIFI_AWARE`).
#[derive(Default)]
struct MeshHub {
    /// Star / cluster adjacency: each node connects to its immediate neighbors (`cluster_idx`, `relay`).
    links: RwLock<HashMap<String, Vec<HubMember>>>,
}

impl MeshHub {
    fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    fn connect(&self, src_tag: &str, peer_tag: &str, peer_node: Arc<RnsNode>) {
        if let Ok(mut map) = self.links.write() {
            map.entry(src_tag.to_string()).or_default().push(HubMember {
                tag: peer_tag.to_string(),
                node: peer_node,
                partitioned: false,
            });
        }
    }

    fn set_partition(&self, src_tag: &str, partitioned: bool) {
        if let Ok(mut map) = self.links.write() {
            if let Some(peers) = map.get_mut(src_tag) {
                for p in peers {
                    p.partitioned = partitioned;
                }
            }
        }
    }
}

struct HubBridge {
    hub: Arc<MeshHub>,
}

impl RnsCustomInterfaceBridge for HubBridge {
    fn on_outbound_frame(&self, interface_tag: String, frame: Vec<u8>) -> bool {
        let targets = self
            .hub
            .links
            .read()
            .ok()
            .and_then(|map| map.get(&interface_tag).cloned())
            .unwrap_or_default();
        let mut any = false;
        for m in targets {
            if m.partitioned {
                continue;
            }
            if m.node
                .push_custom_inbound_frame(m.tag, frame.clone())
                .is_ok()
            {
                any = true;
            }
        }
        any
    }

    fn on_interface_detached(&self, _interface_tag: String) {}
}

struct ScaleBenchMetrics {
    nodes: usize,
    converge_ms: u128,
    sent_count: usize,
    delivered_count: usize,
    ifac_leaks: usize,
    ptt_1hop_ms: u128,
    ptt_3hop_ms: u128,
}

fn run_scale_tier(node_count: usize) -> ScaleBenchMetrics {
    assert!(node_count >= 10);
    let ifac = derive_event_ifac_config(
        "rns12-scale".into(),
        Some("field-ops".into()),
        "scale-secret-key".into(),
        1,
    );
    let wrong_ifac = derive_event_ifac_config(
        "rns12-scale".into(),
        Some("rogue".into()),
        "wrong-secret-key".into(),
        1,
    );

    let hub = MeshHub::new();
    let mut nodes: Vec<Arc<RnsNode>> = Vec::with_capacity(node_count);

    // Node 0 is root transport relay; nodes 1..=2 are secondary cluster relays (3-hop span across clusters).
    for i in 0..node_count {
        let is_relay = i < 3;
        let node = RnsNode::new(NodeConfig {
            enable_transport_relay: is_relay,
            ifac: Some(ifac.clone()),
            ..Default::default()
        })
        .expect("create node");
        node.start().expect("start node");
        nodes.push(node);
    }

    // Wire hierarchical multi-hop topology:
    // - Relays 0 <-> 1 and 0 <-> 2 (plus 1 <-> 2 for backup reconvergence)
    // - Leaves split across relay 0 (cluster A), relay 1 (cluster B), relay 2 (cluster C)
    let mut attach_counter = 0usize;
    let mut wire_pair = |idx_a: usize, idx_b: usize| {
        attach_counter += 1;
        let tag_a = format!("n{idx_a}-to-{idx_b}-{attach_counter}");
        let tag_b = format!("n{idx_b}-to-{idx_a}-{attach_counter}");
        hub.connect(&tag_a, &tag_b, Arc::clone(&nodes[idx_b]));
        hub.connect(&tag_b, &tag_a, Arc::clone(&nodes[idx_a]));
        nodes[idx_a]
            .attach_custom_interface(
                tag_a,
                CustomInterfaceKind::WifiAware,
                500,
                50_000_000,
                Box::new(HubBridge {
                    hub: Arc::clone(&hub),
                }),
            )
            .unwrap();
        nodes[idx_b]
            .attach_custom_interface(
                tag_b,
                CustomInterfaceKind::WifiAware,
                500,
                50_000_000,
                Box::new(HubBridge {
                    hub: Arc::clone(&hub),
                }),
            )
            .unwrap();
    };

    wire_pair(0, 1);
    wire_pair(0, 2);
    wire_pair(1, 2);
    for i in 3..node_count {
        let relay = i % 3;
        wire_pair(i, relay);
    }

    // Also attach an isolated rogue node on relay 0 with wrong IFAC to verify 0 leaks at scale.
    let rogue = RnsNode::new(NodeConfig {
        ifac: Some(wrong_ifac),
        ..Default::default()
    })
    .expect("rogue");
    rogue.start().unwrap();
    hub.connect("rogue-0", "r0-rogue", Arc::clone(&nodes[0]));
    hub.connect("r0-rogue", "rogue-0", Arc::clone(&rogue));
    rogue
        .attach_custom_interface(
            "rogue-0".into(),
            CustomInterfaceKind::WifiAware,
            500,
            1_000_000,
            Box::new(HubBridge {
                hub: Arc::clone(&hub),
            }),
        )
        .unwrap();
    nodes[0]
        .attach_custom_interface(
            "r0-rogue".into(),
            CustomInterfaceKind::WifiAware,
            500,
            1_000_000,
            Box::new(HubBridge {
                hub: Arc::clone(&hub),
            }),
        )
        .unwrap();

    // Allow custom bridge interface tasks to register with the manifold before initial announces.
    std::thread::sleep(Duration::from_millis(120));

    // 1. Measure announce convergence to target leaves & relays
    let t_conv = Instant::now();
    for n in &nodes {
        let _ = n.announce_lxmf(None);
    }
    let _ = nodes[0].announce_ptt();
    let _ = nodes[5].announce_ptt();
    let _ = rogue.announce(None);

    // Verify leaf nodes across different relay clusters converge with node 0 and cross-cluster leaves
    let targets: Vec<usize> = (4..node_count).step_by((node_count / 10).max(1)).collect();
    let sample_targets = [
        nodes[0].lxmf_destination_hash_hex(),
        nodes[1].lxmf_destination_hash_hex(),
        nodes[2].lxmf_destination_hash_hex(),
        nodes[node_count - 1].lxmf_destination_hash_hex(),
        nodes[node_count - 2].lxmf_destination_hash_hex(),
    ];
    let conv_deadline = Instant::now() + Duration::from_secs(10);
    let mut last_reannounce = Instant::now();
    while Instant::now() < conv_deadline {
        let sender_ready = targets
            .iter()
            .all(|&idx| nodes[3].route_for_destination(nodes[idx].lxmf_destination_hash_hex()).is_some());
        if sender_ready
            && nodes[3]
                .route_for_destination(sample_targets[0].clone())
                .is_some()
        {
            break;
        }
        if last_reannounce.elapsed() >= Duration::from_millis(2500) {
            for &idx in &targets {
                if nodes[3]
                    .route_for_destination(nodes[idx].lxmf_destination_hash_hex())
                    .is_none()
                {
                    let _ = nodes[idx].announce_lxmf(None);
                }
            }
            let _ = nodes[0].announce_lxmf(None);
            last_reannounce = Instant::now();
        }
        std::thread::sleep(Duration::from_millis(30));
    }
    let converge_ms = t_conv.elapsed().as_millis();

    // 2. Send representative multi-hop LXMF burst across cluster leaves and check delivery rate
    let sender = &nodes[3]; // on relay 0 (since 3 % 3 == 0)
    let mut sent_count = 0usize;
    let mut delivered_count = 0usize;
    for &t_idx in &targets {
        if sender
            .route_for_destination(nodes[t_idx].lxmf_destination_hash_hex())
            .is_none()
        {
            let _ = nodes[t_idx].announce_lxmf(None);
            std::thread::sleep(Duration::from_millis(60));
        }
        sent_count += 1;
        let receipt = sender
            .send_lxmf(LxmfOutboundMessage {
                message_id: format!("scale-{node_count}-{t_idx}"),
                destination_hash_hex: nodes[t_idx].lxmf_destination_hash_hex(),
                title: "ScaleMsg".into(),
                content: format!("scale payload {node_count}->{t_idx}"),
                delivery_mode: LxmfDeliveryMode::Opportunistic,
                attachments: vec![],
                custom_type: None,
                custom_data: None,
                propagation_node_hex: None,
            })
            .expect("send_lxmf scale");
        if receipt.status == LxmfReceiptStatus::Delivered {
            delivered_count += 1;
        } else {
            eprintln!(
                "[scale fail N={node_count} target={t_idx}] has_route={} detail={}",
                sender
                    .route_for_destination(nodes[t_idx].lxmf_destination_hash_hex())
                    .is_some(),
                receipt.detail
            );
        }
    }

    // 3. Measure 1-hop PTT frame latency (node 3 -> relay 0) & 3-hop PTT frame latency (node 4 -> relay 1 -> relay 2 -> node 5)
    let ptt_wait = Instant::now() + Duration::from_secs(6);
    let mut last_ptt_ann = Instant::now();
    while Instant::now() < ptt_wait
        && (nodes[3]
            .route_for_destination(nodes[0].ptt_destination_hash_hex())
            .is_none()
            || nodes[4]
                .route_for_destination(nodes[5].ptt_destination_hash_hex())
                .is_none())
    {
        if last_ptt_ann.elapsed() >= Duration::from_millis(2000) {
            nodes[0].announce_ptt().expect("nodes[0] announce_ptt");
            nodes[5].announce_ptt().expect("nodes[5] announce_ptt");
            last_ptt_ann = Instant::now();
        }
        std::thread::sleep(Duration::from_millis(25));
    }
    let t_ptt1 = Instant::now();
    nodes[3]
        .send_ptt_frame(
            nodes[0].ptt_destination_hash_hex(),
            "scale-ptt".into(),
            "s1".into(),
            1,
            "opus".into(),
            20,
            vec![0x7Au8; 64],
        )
        .expect("1-hop ptt");
    let mut got1 = false;
    let d1 = Instant::now() + Duration::from_secs(2);
    while Instant::now() < d1 && !got1 {
        for ev in nodes[0].poll_events() {
            if matches!(ev, RnsInboundEvent::PttFrameReceived { .. }) {
                got1 = true;
            }
        }
        if !got1 {
            std::thread::sleep(Duration::from_millis(5));
        }
    }
    let ptt_1hop_ms = t_ptt1.elapsed().as_millis();
    assert!(got1, "1-hop PTT frame missing at N={node_count}");

    let t_ptt3 = Instant::now();
    nodes[4] // on relay 1
        .send_ptt_frame(
            nodes[5].ptt_destination_hash_hex(), // on relay 2 (3 hops: 4 -> 1 -> 2 -> 5)
            "scale-ptt".into(),
            "s3".into(),
            1,
            "opus".into(),
            20,
            vec![0x3Bu8; 64],
        )
        .expect("3-hop ptt");
    let mut got3 = false;
    let d3 = Instant::now() + Duration::from_secs(2);
    while Instant::now() < d3 && !got3 {
        for ev in nodes[5].poll_events() {
            if matches!(ev, RnsInboundEvent::PttFrameReceived { .. }) {
                got3 = true;
            }
        }
        if !got3 {
            std::thread::sleep(Duration::from_millis(5));
        }
    }
    let ptt_3hop_ms = t_ptt3.elapsed().as_millis();
    assert!(got3, "3-hop PTT frame missing at N={node_count}");

    // 4. Chaos test: partition cluster edge & revoke a node at scale, confirm 0 leaks
    hub.set_partition("n4-to-1-5", true);
    let revoked_hex = nodes[node_count - 1].destination_hash_hex();
    nodes[0].revoke_peer(revoked_hex.clone(), Some("chaos-revoke".into())).unwrap();
    assert!(nodes[0].send_single_packet(revoked_hex, b"must-fail".to_vec()).is_err());

    let ifac_leaks = rogue.known_destinations().len();

    let _ = rogue.stop();
    for n in nodes {
        let _ = n.stop();
    }

    ScaleBenchMetrics {
        nodes: node_count,
        converge_ms,
        sent_count,
        delivered_count,
        ifac_leaks,
        ptt_1hop_ms,
        ptt_3hop_ms,
    }
}

#[test]
fn rns12_scale_and_chaos_10_20_50_100_nodes() {
    let mut summary = Vec::new();
    for &n in &[10usize, 20, 50, 100] {
        let m = run_scale_tier(n);
        println!(
            "[RNS-12 BENCH] nodes={} converge_ms={} delivered={}/{} ifac_leaks={} ptt_1hop_ms={} ptt_3hop_ms={}",
            m.nodes,
            m.converge_ms,
            m.delivered_count,
            m.sent_count,
            m.ifac_leaks,
            m.ptt_1hop_ms,
            m.ptt_3hop_ms
        );
        assert_eq!(m.ifac_leaks, 0, "IFAC isolation leak at N={n}");
        assert_eq!(
            m.delivered_count, m.sent_count,
            "delivery rate < 100% ({}/{}) at N={n}",
            m.delivered_count, m.sent_count
        );
        assert!(
            m.ptt_1hop_ms < 500,
            "1-hop PTT latency {}ms >= 500ms at N={n}",
            m.ptt_1hop_ms
        );
        assert!(
            m.ptt_3hop_ms < 800,
            "3-hop PTT latency {}ms >= 800ms at N={n}",
            m.ptt_3hop_ms
        );
        summary.push(m);
    }
    assert_eq!(summary.len(), 4);
}
