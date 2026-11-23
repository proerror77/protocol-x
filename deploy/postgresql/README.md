# 香港 Ops PostgreSQL 部署

Ops 与语音转写共同使用香港 ECS 上的 `golf_ops` 数据库。服务以操作系统账号 `golf-ops` 通过 Unix socket 和 PostgreSQL peer 认证连接。OpenTAKServer 使用独立的 `ots` 库。

两个 systemd 服务共用 `/etc/golf-ops-pilot/service.env`。部署时把 `OPS_DATABASE_URL` 和 `OPS_PTT_VOICE_DATABASE_URL` 都指向 PostgreSQL，并移除 SQLite 路径配置。开发环境可以通过显式路径使用本地 SQLite。

## 导入和校验

1. 在独立 PostgreSQL schema 里，用在线快照演练导入。校验工作台、写入与幂等、语音读写和航班建议。
2. 停止 `golf-pilot-transcription` 和 `golf-ops-pilot`，使用 SQLite backup API 生成最终一致性快照。将快照转换为 `journal_mode=DELETE`，使服务账号可以在只读目录中校验完整文件。
3. 用部署的 Python 环境和目标账号运行以下命令。目标表需为空。脚本保留消息、建议及回执的排序键，并比较每张表的记录数和内容 SHA-256。音频按原始字节校验。

```sh
PYTHONPATH=/opt/golf-ops-pilot python deploy/postgresql/migrate.py /path/to/ops.db /path/to/voice.db
```

脚本从环境读取 `OPS_DATABASE_URL`。`--verify-only` 只比较数据。

## 切换和回退

保留切换前的 SQLite 快照、服务环境和代码版本。导入后先启动 Ops，确认健康检查、关联任务、回执和音频回放，再启动转写服务。检查两项服务的进程环境都指向 PostgreSQL。

若发现问题，先停止两项写入服务。仅在 PostgreSQL 数据与最终 SQLite 快照仍完全一致时恢复旧环境和旧代码。有新写入时保留 PostgreSQL 为权威数据源，再修复应用或安排反向迁移。
