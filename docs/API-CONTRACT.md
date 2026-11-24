# Operations API contract v0.5

This contract is for a local integration prototype, not production authentication or an operational dispatch system. All initial records are synthetic. The same UI consumes both ontology bindings.

## Common

- API prefix `/api`; JSON; UTF-8. Errors: HTTP status + `{"detail":"human readable message"}`.
- Local-only demo identity headers: `X-Demo-Role: dispatcher|staff`, `X-Demo-Actor: demo-dispatcher|demo-staff` (ASCII). Defaults are dispatcher/demo-dispatcher. The server validates known role/actor pairs. Never deploy this identity mechanism on a public network.
- Pilot identity uses `Authorization: Bearer <access-code>`. The server resolves role and actor from the access code.
- ObjectRef: `{ontology_id, object_type, id}`. Event ID is independent of ontology ID. Object version is independent of schema/binding versions.
- All writes are event-scoped; all references must resolve within that event. All timestamps are UTC ISO 8601.

## Endpoints

### GET /api/bootstrap

`{mode:"local_demo"|"pilot", events:[{id,title,ontology_id}], roles:["dispatcher","staff"], identity:{role,actor}, ptt_event_id:null|string, integrations:[{id,label,status,detail}]}`

`video_enabled` and `voice_archive_enabled` are pilot capability flags for `ptt_event_id`.

Integrations expose `not_connected`, `local_ready`, or `configured_unverified`; never claim a configured external service is tested.

### GET /api/events/{event_id}/workspace

Returns `{event:{id,title,ontology_id}, ontology:{id,label,schema_version,binding_version}, tasks:[], messages:[], proposals:[], receipts:[], summary:{open_tasks,pending_proposals,messages}, integrations:[], tak:null|{recipient_uid,report_reference_point,reports}}`.

Task: `{ref,title,description,status,status_label,owner,subject_label,resource_label,version,progress:[{label,status}],allowed_actions:[{id,label,progress_index?}],replacement_options:[{ref,label}]}`.

The binding maps independent golf TransferLeg/Vehicle and conference Session/Room schemas into this task contract. Frontend must not branch on event IDs, golf types, or conference types.

Message: `{id,author,text,created_at,source:"text",subject_ref|null}`.

Proposal: `{id,title,reason,engine:"deterministic_demo",message_id,evidence_quote,status:"proposed"|"executed",target_ref,expected_version,action_type:"replace_resource",parameters:{replacement_ref},created_at}`.

Receipt: `{id,action_type,target_ref,status:"committed",actor,created_at,idempotency_key,result,proposal_id|null}`.

### GET /api/events/{event_id}/workspace/changes

Android 前台以活动工作区的同一身份订阅。初次连接返回 `{revision}`；随后带 `?after=<revision>` 等待已提交的任务、接待卡或活动相关变更，空闲约 20 秒返回同一版本。收到新版本后，App 通过原工作区接口重新获取本人有权读取的视图。PostgreSQL 在提交后发送活动变更信号，本机 SQLite 演示通过数据版本识别提交。订阅暂不可用时自动重试和同步，重新接入后校对工作区。

### POST /api/events/{event_id}/messages

Body `{text,subject_ref|null}` -> message (201). Optional `Idempotency-Key` accepts a UUIDv4. The same caller replaying the same key and body receives the original message. Text is evidence only and cannot change task state. A missing subject cannot be guessed by the proposer.

### POST /api/events/{event_id}/proposals

Body `{message_id}` -> proposal (201); dispatcher only. Explicit deterministic demo of report-to-proposal, not an LLM call. Needs a subject_ref identifying one task and failure/unavailable keywords. Candidate selected only among binding-defined resources; fails clearly if none. One open proposal per source message (idempotent duplicate request).

### POST /api/events/{event_id}/actions

Body `{action_type,target_ref,expected_version,idempotency_key,parameters,proposal_id?}` -> receipt. Replaying an identical request with the same actor and key returns the original receipt. A conflicting key or task version returns 409. The server checks authorization, expected version, event scope and allowed action in the transaction that writes the update and receipt.

| Action | Parameters and result |
| --- | --- |
| `acknowledge` | Empty parameters. Staff may accept tasks assigned to them. Updates the dispatch state. |
| `replace_resource` | `replacement_ref` points to an eligible resource. Available to dispatchers. When supplied, `proposal_id` must match the action, parameters and version. |
| `confirm_progress` | `progress_index` is the zero-based index from the current `allowed_actions` entry. After acceptance, the assigned worker or dispatcher confirms the current step. The server marks it `done` and opens the next pending step. The receipt records `progress_label`, actor, and timestamp. |

Acceptance and resource replacement leave field progress intact. After the final confirmation, no progress action is exposed.

### GET /healthz

`{status:"ok",mode:"local_demo"}`. This checks local application only, not LiveKit, TAK, AI, or production readiness.

## LiveKit pilot

When LiveKit is configured, `POST /api/events/{event_id}/ptt/join` returns the receive-only session. `POST /api/events/{event_id}/ptt/floor/{claim|renew|release}` uses `{session_id,press_id}` to manage microphone publishing. On success, `claim` and `renew` return the remaining `lease_ms`. `release` returns `{ "lease_ms": 0 }` for the released or already inactive press. See [pilot setup](../deploy/ptt/README.md).

The optional join body `{callsign}` sets up to 32 printable characters for the participant label. The server appends the authenticated pilot role. If enabled, `/video/join` and `/video/floor/{claim|renew|release}` use the same authenticated lease contract for camera access in a separate video room. Each room starts with subscriber-only grants.

`workspace.voice_messages` contains recent PTT audio records for the configured activity. Each record includes `track_sid`, `actor`, `speaker_name`, `created_at`, `ended_at`, `status`, `duration_ms`, `partial`, `text`, and `has_audio`. The same track ID resolves to a WAV via authenticated `GET /api/events/{event_id}/voice/{track_sid}/audio` while the original is available.

## OpenTAKServer 试点

以下接口需要调度员试点访问码，只对已配置的活动开放。Ops 保留共享记录并向配置的 TAK 终端 UID 发送 CoT。

- `POST /api/events/{event_id}/tak/reports/{message_id}` 接收 `{excerpt?}`。片段必须来自汇报原文，最多 255 字。省略后使用原始汇报或已保存的共享片段。每条汇报保留一个 CoT UID。
- `POST /api/events/{event_id}/tak/points` 接收 `{request_id, title, latitude, longitude}`，其中 `request_id` 为 UUIDv4。相同 ID 重放相同点位，保留原 CoT UID。
- 响应包含 `{cot_uid, status, recipient_uid, received_at}`。服务端回读匹配时返回 200 和 `received`。暂时未能确认时返回 202 和 `awaiting_readback`，重放同一请求只回读。TLS 连接未建立时返回 503 和 `retryable`，可重试。同键改变内容或接收端返回 409。
- 工作区的 `tak.reports` 提供按汇报 ID 查询的共享状态和已确认共享的片段，该字段仅向配置活动的调度员显示。可选的 `report_reference_point` 为活动参考点 `[latitude,longitude]`，供共享前预览。已提交的请求保留当时的参考点。

启用 `OPS_TAK_PTT_EVENTS` 后，LiveKit 用签名请求调用内部 `/internal/ptt/livekit-events`。Ops 将同一活动的麦克风发布与撤下事件持久化为独立 TAK 投递记录并定向发送。OpenTAKServer 回读匹配后返回 204。待回读时返回 503 让 LiveKit 重试，重试沿用同一个 CoT UID。 [配置说明](../deploy/TAK-ECS.md#android-ptt-与-tak)

### Android 航班值班提醒

个人绑定的现场人员可通过 `GET /api/arrival-flight-notices?after=<seq>` 读取本人当前指派的接机航班通知。数据来自已写入 PostgreSQL 的机场观测。响应只含事件、接待任务、航班、通知类型和观测时间，照片继续由原接待卡接口按任务授权。调度和共享试点身份不能使用该通知接口。客户端以通知序号续读，页面退出、断网恢复或批量到达时仍使用同一序号去重。

Android 在「更多 → 开启后台航班提醒」开启本班值守，与对讲频道独立。系统通知打开同一活动的接待卡，用户结束值班或退出个人账号会停止后台监听。Android 15 的 dataSync 前台服务受平台后台时限约束。设备强制停止应用后须重新打开并开启值守。

## 频道附近通信与 RNS P0 离线/网关同步（v0.28.0）

详细结构化契约见 [docs/RNS-TOC-CONTRACT.md](RNS-TOC-CONTRACT.md)（`protocol-x.rns.envelope/v1`）。

- **RNS 设备绑定与 IFAC 凭证（`POST /api/field/events/{event_id}/nearby` & `GET /api/field/events/{event_id}/nearby/revocations`）：**
  已绑定设备提交 P-256 公钥及可选 `rns_identity`（`dest_hash`、`identity_hash`、`ed25519_pub`、`x25519_pub`）。服务端返回 12 小时有效的群组凭证、按频道派生的私有 IFAC 参数（`network_name`、`passphrase`、`generation`）、`rns_gateway` 与 `rns_peers`。成员退出、停用或设备被撤销时立即轮换密钥并写入 CRL（`/nearby/revocations`）。Android `v0.28.0` 统一经 `RnsRuntimeService`（Wi-Fi Aware / BLE L2CAP + Rust RNS/LXMF）传输并执行 `LegacyNearbyMigration` 清理旧版自研 Mesh 缓存。
- **统一 RNS 信封入库（`POST /api/rns/v1/envelopes` & `POST /api/rns/v1/envelopes/batch`）：**
  接收本端或经多跳中继的签名 `EnvelopeV1`（`task`、`message`、`attachment`、`location`、`delivery_receipt`），按 `(event_id, envelope_id)`、payload `fingerprint` 与业务层 `idempotency_key` 执行三层去重（0 重复行动）；版本冲突时返回带 `current_version`、`current_status`、`current_progress` 与 `allowed_actions` 的 `409` 结构化信息。
- **RNS / TAK 网关（`POST /api/rns/v1/gateway/dispatch`、`GET /api/rns/v1/gateway/status`、`POST /api/events/{event_id}/tak/inbound`、`GET /api/events/{event_id}/tak/deliveries`）：**
  私有 IFAC Python `rns`/`lxmf` 网关双向桥接 RNS 现场网状节点、TOC 工作区与 OpenTAKServer CoT（支持回读确认、确定性 UID 去重与回流抑制）。

### 香港到港动态与接待出行

`GET /api/events/{event_id}/flight-arrivals/{flight}` 按航班号读取香港机场到港动态。可传 `service_date=YYYY-MM-DD`，支持香港当地今日和昨日。优先匹配主航班号，允许用唯一的代码共享号查询。结果保留 `source_updated_at`、`state`、`source_url` 和原到港记录 `flight`。多条匹配返回 409，未匹配时返回 `flight: null`。本入口与现有到港表和接机观察共用缓存。

接待卡的 `travel_links` 提供 Google Maps 驾车路线、酒店地点和 Uber 链接。调度员可选填会合点及酒店上落车点的 WGS84 坐标。两个坐标都有时预填 Uber 路线，仅由乘客在 Uber 核价及确认。没有坐标的接待卡仍提供酒店地址、Google Maps 和 Uber 入口。

`POST /api/events/{event_id}/arrival-plans/{plan_id}/route-estimate?version=<version>` 以接待卡当前版本查询 Google Maps 驾车距离及预计时间。调用者必须能查看该接待卡，落地响应包含 `provider`、`distance_meters`、`duration_seconds` 和 `observed_at`。结果即时显示，接机和酒店风险计算继续使用调度员确认的安排。
