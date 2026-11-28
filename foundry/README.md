# TOC Foundry Solution (`@toc/foundry-solution`)

Project TOC 的 **MDN Man Do Nothing Foundry** 业务解决方案包，遵循 `@mdn-foundry/engine` v0.2.1 的 `ontology-installation-v1` 安装契约。

## 目录结构

- `src/ontology.mjs`：TOC 双域（Golf + Conference）完整 `OntologyDocument` 源码，涵盖：
  - `WorkItem` / `ReservableResource` / `ProcessLog` / `Scenario` 四个 Interface
  - `Event` / `Person` / `TransferLeg` / `Vehicle` / `ArrivalPlan` / `ArrivalPlanReceptionStageLog` / `Session` / `Room` / `FlightObservation` / `TakObservation` / `OntologyScenario` 11 个 ObjectType
  - `ArrivalPlan.receptionStage` 声明式 **Machinery** 状态机（`pending -> met -> boarded -> hotelHandoff -> checkedIn`）
  - 原生 **Resource Reservation**（`AssignVehicle`、`RebookSessionRoom`）
  - 原生 **Scenario** 合并与 5 个不可变审阅 Action（HITL 硬护栏）
  - `DispatchCopilot` Agent Profile（只读观测 + 仅允许提交 Scenario 审批）
- `implementation.mjs`：可信实现包（注册 `handlers`、`effects`、`scenarioDecisions`、`agentInstructions`）
- `toc-ontology.package.json`：由 `npm run build` 生成的 `eigos-ontology-package-v1` 制品（含 canonical SHA-256 摘要）
- `manifest.json`：由 `npm run build` 生成的 `ontology-installation-v1` 安装清单

## 本地构建与验证

```bash
cd /Users/proerror/Documents/toc/toc-foundry-solution
npm run build
npm test
```

## 接入 MDN Foundry Engine 运行

1. 在 `MDN-Man-Do-Nothing-Foundry` 打包得到 `@mdn-foundry/engine` 和 `@mdn-foundry/sdk`（`npm run pack:release`）。
2. 准备 PostgreSQL 17 并设置环境变量：

```bash
export DATABASE_URL="postgresql://postgres:postgres@127.0.0.1:5432/toc_foundry"
export EIGOS_SOLUTION_MANIFEST="/Users/proerror/Documents/toc/toc-foundry-solution/manifest.json"
export TOC_EDGE_BRIDGE_URL="http://127.0.0.1:8000"
export TOC_EDGE_BRIDGE_TOKEN="<internal-bridge-secret>"
```

3. 初始化、发布并启动三进程组：

```bash
node node_modules/@mdn-foundry/engine/apps/engine.js check
node node_modules/@mdn-foundry/engine/apps/engine.js initialize
node node_modules/@mdn-foundry/engine/apps/engine.js publish

# 分别启动 Gateway (HTTP/MCP/A2A + /workbench/)、Worker、Indexer
node node_modules/@mdn-foundry/engine/apps/engine.js gateway
node node_modules/@mdn-foundry/engine/apps/engine.js worker
node node_modules/@mdn-foundry/engine/apps/engine.js indexer
```

4. 访问 Gateway 的 `/workbench/` 即可使用浏览器 Ontology 工作台，并通过 `grant-modeler <owner>` / `grant-reviewer <owner>` 配置双人审阅权限。

## 边缘兼容桥接边界（零破坏 Android/Web）

| 现有 TOC 路由 | 边缘层保留 | Foundry 承接 |
| :--- | :--- | :--- |
| `POST /api/events/{event_id}/actions` | 鉴权、JWT 转换 | 映射至 Foundry `AcceptTransfer` / `DeclineTransfer` / `AssignVehicle` / `ReschedulePickup` |
| `POST /api/events/{event_id}/arrival-plans/{id}/stages` | 鉴权 | 映射至 Foundry `AdvanceReceptionStage`（由 Machinery 校验门控与状态顺序） |
| `POST /api/rns/v1/envelopes` | 离线 Ed25519/ECDSA 验签、3 层去重、409 协商格式组装 | 验签通过后转调 Foundry Action / `RecordTakObservation` |
| `GET /api/events/{event_id}/tak/*` & CoT 入站 | OpenTAKServer TLS CoT 2.0 编解码 | 入站写 `RecordTakObservation`；出站由 Foundry `DispatchTakCot` Outbox 驱动 |
| LiveKit PTT / 流式 ASR | 实时音频流、话权租约、转写 | 转写结果作为只读观测触发 Foundry `Indexer -> Automate -> DispatchCopilot` |
