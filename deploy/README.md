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

## 一手参考

- LiveKit 本地运行：https://docs.livekit.io/transport/self-hosting/local/
- LiveKit 部署：https://docs.livekit.io/transport/self-hosting/deployment/
- LiveKit 端口：https://docs.livekit.io/transport/self-hosting/ports-firewall/
- LiveKit 文字消息不自带历史持久化：https://docs.livekit.io/transport/data/text-streams/
- TAK Server：https://github.com/TAK-Product-Center/Server
- OpenTAKServer 功能说明：https://docs.opentakserver.io/
- Android 后台前台服务限制：https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

查阅日期：2026-11-22。版本与设备兼容性在实际联调时锁定。
