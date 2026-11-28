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

生产 Passkey 域名为 `protocolx.mandonothing.com`，与 Android App Links 共用。该域名通过 HTTPS 在 `/.well-known/assetlinks.json` 提供应用包名与发布版 APK 的签名证书指纹。Android 继续从已配置的 Ops API 请求服务器签发的登录链接和 Passkey。测试环境可用 Gradle 属性 `protocolXPasskeyHost` 配置独立域名。Android 设备须运行 Android 9 或更新版本，并有可用的 Passkey 凭据提供方。[Android App Links 配置要求](https://developer.android.com/training/app-links/about) · [Android Credential Manager 配置要求](https://developer.android.com/identity/credential-manager/prerequisites)

在香港 ECS 为该域名通过 Let’s Encrypt Webroot 签发 TLS 证书，沿用 `/var/www/golf-pilot-acme`。安装 `deploy/ops-live/protocolx-passkeys.conf` 到 Nginx 的 `conf.d`。将同目录的关联 JSON 和提示页分别安装到 `/var/www/protocolx-passkeys/.well-known/assetlinks.json` 和 `/var/www/protocolx-passkeys/activate.html`。检查 Nginx 配置后重载，并安装和启用 `protocolx-passkey-cert-renew.timer`。Cloudflare 代理该子域名，来源规则 `Protocol X Passkey HTTPS` 将匹配 `http.host eq "protocolx.mandonothing.com" and cf.edge.server_port eq 443` 的请求转到香港入口的 9443。应用签名变化时，同步更新关联文件和 Ops 的证书指纹配置。

把这些环境变量注入 Ops 服务。用试点 Compose 时可放在受限的 `deploy/ptt/pilot.env`，并通过 `--env-file` 加载。使用项目批准的公司邮件发件账号。

```dotenv
OPS_PASSKEY_RP_ID=protocolx.mandonothing.com
OPS_PASSKEY_ANDROID_SHA256=<发布版 APK 的签名证书指纹>
OPS_FIELD_EMAIL_ENROLLMENT=true
OPS_FIELD_SMTP_HOST=smtp.feishu.cn
OPS_FIELD_SMTP_PORT=465
OPS_FIELD_SMTP_USERNAME=protocolx@mandonothing.com
OPS_FIELD_SMTP_PASSWORD=<发件端 SMTP 凭据>
```

可用 `OPS_FIELD_SMTP_FROM` 指定邮件 From 地址，默认使用 SMTP 用户名。确保 Ops 主机能访问发件端，配置错误会阻止服务以邮箱验证模式启动。

反向代理与限流配置：

- **Nginx 覆盖客户端 IP 头**：`POST /api/field/email/request`（每 IP 10 分钟 60 次）与 `POST /api/field/passkeys/*/options`（每 IP 4 分钟 40 次）均按提取后的客户端 IP 限流。请在代理 Ops（`127.0.0.1:8000`）的 Nginx `location` 中显式覆盖 `X-Forwarded-For` 与 `X-Real-IP`，避免客户端伪造请求头绕过限流或被误信：
  ```nginx
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $remote_addr;
  proxy_set_header X-Forwarded-Proto $scheme;
  ```
  若前端还经过 Cloudflare 等上游代理，需先在 Nginx 通过 `set_real_ip_from` 仅信任上游代理网段还原真实客户端地址，再覆盖上述转发头。
- **Docker / 反向代理信任地址（`OPS_FORWARDED_ALLOW_IPS`）**：Uvicorn 默认只信任 `127.0.0.1` 的代理头。若 Ops 运行在 `deploy/ptt/compose.yaml` 等 Docker 网桥后方，容器看到的连接对端是网桥网关（如 `172.18.0.1`）而非 `127.0.0.1`，所有现场设备会落入同一个限流桶并在集体领码或注册时触发 `429`。请在 `deploy/ptt/pilot.env` 或容器环境变量中设置 `OPS_FORWARDED_ALLOW_IPS` 为可信反代 / Docker 网桥网关 IP 或网段（例如用 `docker network inspect` 确认网关后设为 `172.18.0.1`；不要在对公网直接暴露的端口上设为 `*`）。直接命令行启动 `uvicorn` 时，可对应使用 `--forwarded-allow-ips` 或 `FORWARDED_ALLOW_IPS`。
- **Cloudflare 放行 App Links 与激活页**：在 Cloudflare 代理 `protocolx.mandonothing.com` 时，需确保 Bot Fight Mode、Super Bot Fight Mode 与 WAF 不会对 `/.well-known/assetlinks.json` 或 `/activate` 返回质询页（Challenge）或拦截响应（例如为 `http.host eq "protocolx.mandonothing.com" and (http.request.uri.path eq "/.well-known/assetlinks.json" or http.request.uri.path eq "/activate")` 配置 WAF Skip / 放行规则），使 `/.well-known/assetlinks.json` 直接返回 `200 application/json`，保障 Android App Links 与 Credential Manager 校验成功。

香港服务的登录配置可先保存在仅 root 可读的 `/etc/golf-ops-pilot/passkeys.env`。在新版 App 和首批人员验证完成后，再将它加入 Ops 的生效环境并启用邮箱登记。

调度员可在 Android 的「人员与频道 → 批准登录邮箱」或网页的「接待人员」为名单中的成员保存邮箱。一人一个邮箱，项目范围由人员的频道权限决定。成员在 Android 输入邮箱后，服务器发出 10 分钟有效的登录链接。打开链接后，App 从 Ops 获取该人员的登记信息，并发起 Passkey 创建。第一次登记成功后，该人员的待用登录链接失效。需要恢复遗失的 Passkey 时，由调度员核实身份，再停用并恢复该人员的登录权限以允许重新登记。

首次切换会撤销旧登录包、未标记为 Passkey 的既有个人会话，并关闭共享工作人员访问码。已登记的 Passkey 可以重新登录。旧网页个人绑定入口随之停用，调度指挥台的运维凭据仍用于管理和接入，应仅由授权的调度管理员保管。

启用前在隔离环境用发布签名的 Android APK 核对完整链路，包括测试邮箱收到链接、点击后打开 App、注册、退出后重登，以及账号停用后无法继续登录。启用后从 App 服务入口读取 `GET /api/field/passkeys`，应返回 `enabled` 和 `email_enrollment` 均为 `true`。

## 一手参考

- LiveKit 本地运行：https://docs.livekit.io/transport/self-hosting/local/
- LiveKit 部署：https://docs.livekit.io/transport/self-hosting/deployment/
- LiveKit 端口：https://docs.livekit.io/transport/self-hosting/ports-firewall/
- LiveKit 文字消息不自带历史持久化：https://docs.livekit.io/transport/data/text-streams/
- TAK Server：https://github.com/TAK-Product-Center/Server
- OpenTAKServer 功能说明：https://docs.opentakserver.io/
- Android 后台前台服务限制：https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

查阅日期：2026-11-22。版本与设备兼容性在实际联调时锁定。
