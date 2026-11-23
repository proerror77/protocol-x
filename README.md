# Protocol X

Android 客户端、指挥台 API 和频道权限源码都在这个目录。Android 的版本号和构建号在 [android/app/build.gradle.kts](android/app/build.gradle.kts) 一起维护。

### Android 发布流程

1. 每项改动在自己的分支提交，通过审查后汇入 `main`。在发布提交里统一提高构建号。
2. 从待交付的提交进入 `android/`，运行 `./gradlew :app:testDebugUnitTest :app:assembleDebug`。
3. 记录 APK 版本、构建号、提交 SHA 和 APK SHA-256。服务端部署和真机验收各自记录。
4. 在同版本的公开 GitHub Release 上传 `Protocol_X_v<版本>.apk` 和 `protocol-x-android.json`。清单字段为 `version_name`、`version_code`、`bytes`、`sha256`，均来自签名后的 APK。App 从「更多 → 关于 Protocol X」检查更新。

0.27.1 汇合了 0.27.0 的附近通信、频道动态增量更新和 0.26.3 的 PTT 尾音优化。定向私信和照片依赖新版频道公钥接口。

## Android 工作人员端 MVP v0.3（历史说明）

Android 工程在 [android/](android/)。客户端使用 Kotlin、原生 Android Activity 和 LiveKit Android SDK。高尔夫和会议活动共用同一个 APK，任务、状态和操作按钮由服务器投影的 WorkItem 驱动。

- 工作人员用试点访问码进入活动，确认接单，发送关联任务的文字汇报，并按顺序确认现场节点。
- 调度员用 Web 指挥台查看汇报、确认更换资源。手机前台每 10 秒尝试同步，统一展示汇报、接单、换资源与现场确认回执。
- 前台 PTT 通过 LiveKit 传输音频，由 Ops API 管理话权。香港试点提供按需现场画面、同条语音的原音回放和 AI 转写。

[Android 构建与双机演练](deploy/ptt/README.md) · [OpenTAKServer 与阿里云部署](deploy/TAK-ECS.md)

## Web 指挥台与 v0.1 演示背景

这个项目把讨论中的第一条业务链路落实为可运行代码：**工作人员汇报 → 保留证据 → 生成待确认建议 → 授权行动 → 持久化状态与回执**。

它是本机业务原型。所有示例资料为合成数据；LiveKit、ASR、LLM、TAK、路线、地图和航班尚未接入。界面会明确显示这些状态。当前建议由关键词规则产生，不能称为 AI。当前 `committed` 回执只证明数据库写入成功，不代表通知已经送到工作人员手机，更不代表球员已经交接。

## 1. 立即运行

环境：Python 3.12。前端无 npm 构建步骤，不需要模型 API Key、TAK 设备或付费平台账户。

macOS / Linux，在解压后的项目目录：

```sh
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
python run.py
```

Windows PowerShell：

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe run.py
```

打开 **http://127.0.0.1:8000**。API 文档是 **http://127.0.0.1:8000/docs**。运行器固定监听本机，以免演示身份被误用为公开登录系统。Ctrl+C 停止应用。

首次运行自动生成 `data/ops.db`；重启不会重置消息和行动。想开展一个全新的本机演示，可指定独立数据文件：

```sh
python run.py --db data/new-rehearsal.db --port 8001
```

Docker 用户可执行 `docker compose up --build`。本交付环境没有 Docker，因此 Docker 路径只提供配置，未声明已运行验证。已执行验证见 [VALIDATION](docs/VALIDATION.md)。

## 2. 十分钟演练

1. 保持高尔夫演示活动，切换到“工作人员”。界面只显示分配给该演示工作人员的任务。
2. 选中接机任务，点击“填入演练汇报”或输入“02 号车故障，当前不可用，请协调备用车辆”。发送后，任务资源与服务进度保持原值。
3. 切回“指挥员”，在该条汇报上生成“规则演示”建议。建议会引用原文、关联对象与对象版本。
4. 查看候选 03 号备用车，打开确认对话框。确认后才修改车辆指派，并生成“系统写入成功”回执。
5. 检查球员登车、饭店交接、入住等进度：它们不会因换车而自动完成。切回工作人员可读取最新资源分配。
6. 切换到会议活动，再次进行同样流程。领域对象变成 Session / Room，具体行动变成更换会议室；不需要另一套前端。
7. 刷新页面或重启服务，确认文字记录与行动结果仍在。

如果在建议生成后先进行了接单等修改，建议中的对象版本将过期，服务器会拒绝执行。当前没有重新生成/驳回旧建议的管理功能；演示可新发一条关联同一任务的汇报，随后生成新建议。正式版要补齐建议过期、刷新、撤回与决策历史。

## 3. 已实现与待实施

| 能力 | 本包状态 |
| --- | --- |
| Web 指挥台与 Android 工作人员端 | 共用活动 API。工作人员可在 Android 端接单、汇报和确认现场节点 |
| 高尔夫与会议两套不同对象类型、字段、状态与领域行动 | 已实现最小 Binding 投影与行动映射；不等于完整 Ontology 平台 |
| 文字汇报与来源证据 | SQLite 持久化；按任务关联 |
| 规则建议与人工确认 | 已实现；没有调用 AI |
| 版本检查、请求幂等、事务写入 | 已实现并测试 |
| 后端角色、任务归属与跨活动引用检查 | 已实现演示约束；身份头可自行切换，不是真实认证 |
| 资源占用与容量 | 已实现整个活动内的独占检查；尚无时间窗和跨活动全局预留 |
| 现场事实和系统写入区分 | 接单和重分配不推进交接事实。现场节点由有权限的操作员确认，每次确认单独记回执 |
| 原生手机 PTT / 视频 / 语音转写 | App 分为频道、任务和记录。视频和语音记录按活动配置 |
| OpenTAKServer / CoT | 试点支持定向共享文字汇报与点位并回读服务端回执。实体终端与反向点位联调待验收 |
| 飞行、GPS、路线、风速、地图 | 未接入 |
| 推送、后台运行、离线队列、消息投递回执 | 未接入 |
| 账户与权限 | 受控试点用两个独立角色访问码。个人账户、OIDC、多活动成员权限和数据保留待实施 |

## 4. 代码组织

| 路径 | 职责 |
| --- | --- |
| `web/` | 同一个构建的通用指挥/工作人员界面，消费能力投影 |
| `app/main.py` | HTTP 路由、行动入口与本机静态资源 |
| `app/bindings.py` | Ontology 到 WorkItem 的投影，候选查询与领域行动映射 |
| `app/models.py` | ObjectRef 与请求模型 |
| `app/store.py` | 持久化与事务边界 |
| `ontologies/golf.json` | TransferLeg、Vehicle、接机服务事实与 Binding |
| `ontologies/conference.json` | Session、Room、会议里程碑与 Binding |
| `tests/test_runtime.py` | 权限、引用、并发、版本、幂等与事实隔离检查 |
| `docs/API-CONTRACT.md` | 当前可调用 API 契约 |
| `docs/KICKOFF-PLAN.md` | 两周启动排程、系统边界、后续验收 |
| `docs/VALIDATION.md` | 本次实际验证与未验证范围 |
| `deploy/README.md` | 应用容器说明和 LiveKit / TAK 联调工作项 |

两套 Ontology 的 ObjectRef 包含 `ontology_id / object_type / id`；API 另带 `event_id`。对象状态版本、Schema 版本、Binding 版本各自独立。前端不判断 TransferLeg 或 Session 类型；它消费 WorkItem 的稳定契约。

当前最小实现主要验证 WorkItem、Evidence 和 Action，尚未实现完整的动态链接查询、分页订阅、Schema 迁移和可视化建模。下一阶段按这些契约扩展。更复杂领域需要编写可复用的 Binding/Handler，不承诺任意业务只改 JSON 就能运行。

## 5. 验证

```sh
python -m pip install -r requirements-dev.txt
python -m unittest discover -s tests -v
```

测试使用临时数据库，不修改演练数据。安装版本已经固定到本次执行环境验证过的直接依赖；正式项目应再生成完整依赖锁与镜像 digest，并验证升级。

## 6. 下一步决策

先指定试点活动、现场负责人、开发负责人，收集工作人员使用的实际手机型号和系统版本。使用本包确认流程和事实定义，同时在第一周并行验证真实 PTT 和 TAK 点位双向通信。跨手机试点前必须替换演示身份并部署可信通信入口。

正式多人版建议保留模块化后端边界，将存储移到 PostgreSQL / PostGIS，增加 Action outbox、设备回执、身份授权与事件订阅。SQLite 本地演示不提供生产容量承诺。本包尚未创建远程代码仓库、部署公网服务或安装任何手机 App。
