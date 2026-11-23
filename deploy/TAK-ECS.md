# ATAK 双向点位与阿里云 ECS

现场试点使用 OpenTAKServer，完整验收包括 Android 双机通话和 TAK 双向点位。LiveKit 承载工作人员的实时语音。当前适配器通过 TLS CoT 将调度员选定的汇报原文片段和点位发给 OpenTAKServer，由它投递给指定 TAK 终端。Ops 通过 HTTPS Marti 接口按 UID 回读。ATAK 点位自动写入当前活动的 Observation 属于下一阶段。

## 接入试点

在 OpenTAKServer 为 Ops 适配器创建专用账号并签发客户端证书。将证书和终端配置到同一活动组，确认目标设备的 CoT UID。共享消息明确指定终端 UID。

让 Ops 容器能访问 OpenTAKServer 的 TLS CoT 和 HTTPS Marti 入口。Marti 入口通过客户端证书保护 `GET /Marti/api/cot/xml/{uid}`。两个入口的证书都应匹配各自使用的主机名。将 `ca.pem`、`client.pem` 和 `client.key` 放到受限的本机目录，授予 Ops 容器用户 UID 10001 读取权限。

在 `deploy/ptt/pilot.env` 加上以下配置，并替换主机、UID 和证书目录。

```dotenv
OPS_TAK_EVENT_ID=golf-demo
OPS_TAK_COT_HOST=tak.example.net
OPS_TAK_COT_PORT=8089
OPS_TAK_MARTI_URL=https://tak.example.net:8443
OPS_TAK_SENDER_UID=ops-golf-bridge
OPS_TAK_RECIPIENT_UID=registered-tak-device-uid
OPS_TAK_TLS_DIR=/absolute/private/adapter-tls
```

需要在 OpenTAKServer 1.7.13 的 GeoChat 历史保留汇报时，配置事先核准的活动参考点 `OPS_TAK_REPORT_LATITUDE` 和 `OPS_TAK_REPORT_LONGITUDE`。指挥台共享前显示该参考点。它表示汇报关联的活动位置。无参考点的汇报仍可定向发送，并在 CoT 记录中回读原文。

```sh
docker compose --env-file deploy/ptt/pilot.env \
  -f deploy/ptt/compose.yaml -f deploy/ptt/compose.tak.yaml up --build -d
```

在 Android 工作人员端提交关联任务的汇报。用调度员访问码登录指挥台，选中该条汇报，点击「共享到 TAK」，确认接收端和展示的原文片段。指挥台显示「OpenTAKServer 已收到」后可展开 CoT UID。试点点位通过 [`/tak/points` 接口](../docs/API-CONTRACT.md#opentakserver-试点) 发布。

「等待回读」表示发送结果需要确认。再次核对只查询同一 UID。显示连接失败时，请求可以用原内容重试。OpenTAKServer 的收件回执与 ATAK 终端的实际接收分别核实。

## Android PTT 与 TAK

Android 工作人员端和 ATAK 收听端共用同一个 LiveKit 房间。当前高尔夫试点房间是 `ops-golf-demo`。Ops 按活动授予麦克风话权。OpenTAKServer 接收由 LiveKit 实际发布和撤下麦克风音轨产生的事件。

在 `pilot.env` 设置 `OPS_TAK_PTT_EVENTS=true`，并将 LiveKit 和 OpenTAKServer 配置到同一活动。把以下片段合并到自托管 LiveKit 的配置里。使用 LiveKit 已配置的签名 API Key。示例 URL 适用于本机 Compose 网络。跨主机时使用受信任的 HTTPS 入口。

```yaml
webhook:
  api_key: <livekit-api-key>
  urls:
    - http://ops:8000/internal/ptt/livekit-events
```

Ops 验证回调签名和原始内容哈希。匹配试点房间的工作人员或调度员麦克风事件定向发给配置的 TAK 接收 UID。通过 Marti 回读 `t-x-ops-ptt` 的 `detail/ops_ptt` 可核对角色、会话、开始或结束、来源时间和同一个 `track_sid`。重试沿用同一个 CoT UID。 [LiveKit Webhooks](https://docs.livekit.io/intro/basics/rooms-participants-tracks/webhooks-events/)

联调 ATAK 收听时，TAKVox v3.3 使用适配后的令牌服务加入这个房间。令牌服务需按已授权的 TAK 客户端证书绑定身份，用 `GET /v1/channels` 返回固定试点频道，在 `POST /v1/token` 返回同一房间的 WSS 地址和短期只听令牌。收听测试关闭 TAKVox 的麦克风预热。ATAK 端需要发言时，客户端需把按键动作接入 Ops 的 `claim`、`renew`、`release` 话权接口。令牌适配和实体 ATAK 音频互通待联调。 [TAKVox v3.3](https://github.com/tcmouratec/takvox/releases/tag/v3.3)

## 本机联调实例

当前工作区的本机实例使用 `data/tak-local-pilot/` 保存合成数据、证书和访问码。指挥台在 `http://127.0.0.1:8000`。客户端证书保护的 CoT 和 Marti 探测入口分别绑定 `127.0.0.1:18089` 和 `127.0.0.1:18443`。

查看本机实例的服务状态时，从项目根目录执行以下命令。

```sh
lab=data/tak-local-pilot
docker compose -p golf-ops-tak-local --env-file "$lab/pilot.env" \
  -f deploy/ptt/compose.yaml -f deploy/ptt/compose.tak.yaml -f "$lab/compose.yaml" ps
```

将 `ps` 改为 `stop` 可停止本机实例，改为 `up -d` 可恢复。两条命令均保留合成数据。新环境可按上节配置自己的 OpenTAKServer。

## 服务端选择

| 方案 | 对高尔夫试点的用途 |
| --- | --- |
| OpenTAKServer | 现场试点使用。官方文档覆盖 Ubuntu 安装、ATAK 客户端证书、TLS CoT 和群组。 |
| 官方 TAK Server | 需要官方 TAK 组件兼容、PostgreSQL/PostGIS 和更完整管理能力时单独评估。 |
| FreeTAKServer | 支持 CoT 和 TLS。可作为备选，用相同设备和验收步骤比较。 |
| CloudTAK | 可配合 OpenTAKServer 使用的浏览器 TAK 客户端，适合以后给多屏指挥台加地图视图。 |

## ECS 准备

本项目的公网试点使用阿里云中国香港（`cn-hongkong`）的 `opentak-hk`。这台 VPC ECS 是 `ecs.e-c1m2.large`，配置为 2 vCPU、4 GiB 内存和 40 GiB 系统盘，运行 Ubuntu 24.04。OpenTAKServer、LiveKit 和 Ops 已在这台机器运行，三个服务的本机健康检查通过。

手机 App 使用 `https://47.238.64.250:9443`，同一入口的 `/rtc` 提供 WSS。ECS 安全组放行 TCP 9443、TCP 7881 和 UDP 7882。LiveKit 公告 `47.238.64.250`，Ops :8000、LiveKit :7880 和信令鉴权 :7890 绑定回环地址。现有 443 管理入口继续按运维来源访问。 [LiveKit 媒体端口](https://docs.livekit.io/transport/self-hosting/ports-firewall/)

`golf-public-acme.conf` 在 TCP 80 只提供 ACME HTTP-01 路径。Certbot 的 `golf-pilot-hk` 证书覆盖该 IP，`golf-pilot-cert-renew.timer` 每天检查两次。续签成功后重载 Nginx。手机使用系统 CA 直接验证。 [Let's Encrypt IP 证书](https://letsencrypt.org/2026/03/11/shorter-certs-certbot)

`golf-livekit` 通过回环地址向 Ops 发送签名音轨事件。`golf-ops-pilot` 使用 `golf_hk_ptt_bridge` 专用客户端证书连接 CoT 8089 和 Marti 8443。此试点的 CoT 目标为专用验收 UID `ops-hk-ptt-audit`。验收时按同一 `track_sid` 读取 `started`、`ended` 的 CoT UID，再逐一通过 `/Marti/api/cot/xml/{uid}` 回读。

使用以下命令回读目标实例，核对实例名称、公网地址和运行状态。

```sh
aliyun ecs DescribeInstances \
  --region cn-hongkong \
  --RegionId cn-hongkong \
  --InstanceName opentak-hk \
  --PageNumber 1 \
  --PageSize 20
```

使用已有 SSH 核对 `golf-livekit`、`golf-ops-pilot`、`opentakserver` 和证书续签定时器。手机双机验收时分别登录两个角色，轮流按键，由另一台手机确认听到声音。

## 实机点位验收

1. 在 OpenTAKServer 建活动专用的 CoT 群组，为 ATAK 和适配器分别签发客户端证书，并确认已指定接收端的 UID。
2. 共享一条合成文字汇报。核对 OpenTAKServer 回读中同一 CoT UID 和原文片段，再确认指定 ATAK 终端的聊天内容。
3. 从中台发布获准共享的测试点位，例如接驳点调整。核对 OpenTAKServer 中同一 CoT UID、经纬度和名称，再确认 ATAK 地图显示。检查其他终端收不到这两条定向共享。
4. 双向点位验收还需在 ATAK 放置测试点，确认中台收到同一 UID、经纬度、来源时间、接收时间和失效时间。定位观测只更新态势层，现场交接继续使用已有授权行动。

在香港 ECS 完成公网入口后，使用实际证书和实体 ATAK 终端核对上述步骤。

[OpenTAKServer 安装](https://docs.opentakserver.io/installation/installation.html) · [端口](https://docs.opentakserver.io/architecture.html) · [证书登记](https://docs.opentakserver.io/certificate_enrollment.html) · [群组](https://docs.opentakserver.io/groups.html) · [ECS 实例查询](https://www.alibabacloud.com/help/zh/ecs/developer-reference/api-ecs-2014-05-26-describeinstances)
