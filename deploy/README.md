# 从本地样机到联调环境

本包只运行 Ops 业务闭环。`compose.yaml` 也只启动该应用；它不会偷偷把 LiveKit、TAK、AI 当成已经集成。当前执行环境没有 Docker，Compose 路径未实测；已执行的验证见 `docs/VALIDATION.md`。

## 本地应用

安装 Docker Engine / Desktop 与 Compose 后，在项目根目录执行：

```sh
docker compose up --build
```

浏览器打开 `http://127.0.0.1:8000`。数据库保存在命名卷中。普通停止 `docker compose down` 不移除卷。需要新的演示数据时建议使用新的 Compose 项目名；不要在真实联调数据上随意删除数据卷。

## LiveKit 真实通信试验：独立工作项

以 LiveKit 官方安装/发行页选取明确版本并记录镜像 digest、SDK 版本。第一轮可在受控本机使用官方开发模式：

```sh
livekit-server --dev
```

该命令默认监听本机 `127.0.0.1:7880`，开发密钥是公开示例，仅用于本地试验。SDK 客户端接入、短期 Room Token 签发、PTT 控制器、发布权限控制及字幕归档仍要实现。单独启动媒体服务，不等于本应用已有语音。

跨手机联调要另外完成可信 HTTPS/WSS、域名、可达 ICE 候选与 TURN。按实际配置开放媒体端口；不要只开放网页的 443 就宣布音视频可用。LiveKit 官方建议参考部署和端口文档。不要把本包的演示身份头接到公网 Token 签发服务。

## TAK 接入试验：独立工作项

现场试点使用独立的 OpenTAKServer。用 ATAK 实机验证证书、群组和 CoT。在 OpenTAKServer 回读入站事件 UID，再核对同组 ATAK 终端显示的事件。官方 WebTAK 兼容性单独验收。

Ops 的出站 CoT 适配器支持调度员定向共享汇报片段和点位，通过 OpenTAKServer 回读 UID 生成收件回执。入站 Observation 计划保存 CoT UID、时间、活动范围、来源和去重键。 [OpenTAKServer 接入与验收](TAK-ECS.md)

TAK CoT 接通不等于 LiveKit 语音已进入 ATAK。第一阶段专业人员同一支 Android 手机安装 ATAK 和 Staff App；语音由 Staff App 承担。在 ATAK 内直接 PTT 是后续插件/跳转接入任务，需单独验证 SDK、签名、版本兼容与音频焦点。

## 联调部署建议

| 入口 | 后端职责与连接 |
| --- | --- |
| Ops 测试入口 | 真实身份与 API，持久化到 PostgreSQL / PostGIS |
| Media 测试入口 | LiveKit 媒体传输与 TURN 可达性 |
| TAK 测试入口 | OpenTAKServer 接收定向共享的汇报和点位。反向 Observation 待联调 |
| AI Worker | 读取授权证据，提交 Action Proposal，由 Action Runtime 检查和执行 |

各服务可以在同一试点主机上运行。正式性能预算在真实手机、网络和视频码率测试后确定。

## Android 首次登录验证

为试点配置 Passkey 的 DNS 域名及发布版 Android APK 的签名证书 SHA-256。该域名须通过公网 HTTPS 在 `/.well-known/assetlinks.json` 提供 Ops 返回的应用关联 JSON。登录页继续从 App 已配置的 Ops 入口读取登录方式。Android 设备须运行 Android 9 或更新版本，并有可用的 Passkey 凭据提供方。[Android Credential Manager 配置要求](https://developer.android.com/identity/credential-manager/prerequisites)

把这些环境变量注入 Ops 服务。用试点 Compose 时可放在受限的 `deploy/ptt/pilot.env`，并通过 `--env-file` 加载。使用项目批准的公司邮件发件账号。

```dotenv
OPS_PASSKEY_RP_ID=ops.example.com
OPS_PASSKEY_ANDROID_SHA256=<发布版 APK 的签名证书指纹>
OPS_FIELD_EMAIL_ENROLLMENT=true
OPS_FIELD_EMAIL_OTP_SECRET=<至少 32 字节的随机密钥>
OPS_FIELD_SMTP_HOST=smtp.example.com
OPS_FIELD_SMTP_PORT=465
OPS_FIELD_SMTP_USERNAME=ops@example.com
OPS_FIELD_SMTP_PASSWORD=<发件端 SMTP 凭据>
```

可用 `OPS_FIELD_SMTP_FROM` 指定邮件 From 地址，默认使用 SMTP 用户名。确保 Ops 主机能访问发件端，反向代理只采信可信来源提供的客户端地址。配置错误会阻止服务以邮箱验证模式启动。

调度员可在 Android 的「人员与频道 → 批准登录邮箱」或网页的「接待人员」为名单中的成员保存邮箱。一人一个邮箱，项目范围仍由人员的频道权限决定。邮箱验证码 10 分钟有效，最多可试 5 次。验证码通过后请在 10 分钟内完成 Passkey 登记。完成登记后，同一人后续用 Passkey 登录。需要恢复遗失的 Passkey 时，由调度员核实身份，再停用并恢复该人员的登录权限以允许重新登记。

首次切换会撤销旧登录包、未标记为 Passkey 的既有个人会话，并关闭共享工作人员访问码。已登记的 Passkey 可以重新登录。旧网页个人绑定入口随之停用，调度指挥台的运维凭据仍用于管理和接入，应仅由授权的调度管理员保管。

启用前在隔离环境用发布签名的 Android APK 核对完整链路，包括测试邮箱收到验证码、注册、退出后重登，以及账号停用后无法继续登录。启用后从 App 服务入口读取 `GET /api/field/passkeys`，应返回 `enabled` 和 `email_enrollment` 均为 `true`。

## 一手参考

- LiveKit 本地运行：https://docs.livekit.io/transport/self-hosting/local/
- LiveKit 部署：https://docs.livekit.io/transport/self-hosting/deployment/
- LiveKit 端口：https://docs.livekit.io/transport/self-hosting/ports-firewall/
- LiveKit 文字消息不自带历史持久化：https://docs.livekit.io/transport/data/text-streams/
- TAK Server：https://github.com/TAK-Product-Center/Server
- OpenTAKServer 功能说明：https://docs.opentakserver.io/
- Android 后台前台服务限制：https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

查阅日期：2026-11-22。版本与设备兼容性在实际联调时锁定。
