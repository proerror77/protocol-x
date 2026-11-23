# 香港试点同屏总览

在香港 ECS 上，Ops 监听 `127.0.0.1:8000`，原生 OpenTAKServer 的专用地图入口监听 `127.0.0.1:8180`。将 `tak-map.conf` 安装在 Nginx 的 `conf.d` 目录，并将 `embed.js` 与 `embed.css` 安装在 `/opt/golf-ops-live/`。原生界面仍使用 OpenTAKServer 自己的登录与 Socket.IO。

首次连接时，用 SSH 同时转发两个入口。

```sh
ssh -N -L 127.0.0.1:18080:127.0.0.1:8000 \
  -L 127.0.0.1:18081:127.0.0.1:8180 \
  -i ~/.ssh/opentak-hk-ed25519 root@47.238.64.250
```

打开 `http://127.0.0.1:18080/`。Ops 使用试点访问码，地图使用 TAK 账号。地图对当前 PTT 试点活动显示。业务视图在可见时每四秒拉取工作区。连接中断时保留已有数据并自动重试。最近汇报和回执均来自 Ops API。

地图初始视野由私有入口的 Nginx 替换指定。更新 OpenTAKServer UI 后，检查 `Map-*.js` 仍能匹配该规则，并打开地图确认香港视野。验证时从另一会话提交合成汇报，确认主页面自动收到且保留未提交的草稿。

## 任务定位演练

香港演练任务按当前车辆资源 ID 和负责人匹配 TAK UID。点击任务默认定位车辆，卡片也提供人员定位。点击地图上带「演练」的标记会打开任务。只有当前账号可见的任务会生成关联标记。点位随原生 TAK Socket.IO 事件更新，超过两分钟的最后已知位置会标为陈旧。

在 ECS 上使用仅能发布到 `Golf Ops HK Demo` 的 CoT 证书，把 `replay-hong-kong.py` 放到 `/opt/golf-ops-live/`。运行下面的命令可以重放合成轨迹。

```sh
sudo python3 /opt/golf-ops-live/replay-hong-kong.py --steps 8
```

更新原生 TAK Web UI 时，需要重新验证地图脚本中的接入点、Socket.IO 兼容回退和点位时间戳。该实例的 PostgreSQL 时区是 Asia/Shanghai，当前版本导出的点位时间带有错误的 Z 后缀，所以私有地图入口提供 480 分钟的偏移。只有演练 UID 使用当前的绑定表。正式车辆和人员的设备绑定需要单独配置。

## 手机 ATAK 气象与航空

S21 和 S26 使用 Google - Hybrid 底图，两个终端账号拥有 `ADS-B` 和 `Golf Ops HK Weather` 的接收权限。ADS-B 沿用香港实例的既有采集频率。飞机标记在两分钟后过期，采集批次之间可能暂时没有飞机图标。

`hk-weather-cot.py` 通过 TLS 把六个香港天文台站点的观测发布到 `Golf Ops HK Weather`。点位以 `HKO WX` 开头，详情显示站点有数据的十分钟风况、十分钟能见度或过去一小时雨量，以及对应的观测时间。超过 45 分钟的观测会被过滤。香港天气网页继续使用独立的气象图层。

服务器的 `golf-hk-weather.timer` 每五分钟运行一次。发布账号只向气象组写入，根用户专用的 `/etc/golf-hk-weather` 保存 TLS 凭据。查看定时器和发布结果时运行 `systemctl list-timers golf-hk-weather.timer` 和 `journalctl -u golf-hk-weather.service`。

## 球员接待

用 `/arrivals?event=golf-hkg-arrivals-demo` 打开香港演练。调度员可从航班到港页带入主航班号和香港服务日期，也可先登记未来航班。接待安排保存后由后台跟踪机场状态。酒店最晚入住时间填写香港本地时间，计算酒店到达范围时加入调度员填写的车程。

正式资料由活动内已绑定的个人账号处理。调度员在「接待人员」登记成员或补发绑定码。接机员和司机以个人绑定码登录，查看本人任务及照片。实际会合、登车、酒店交接和入住依次记录。酒店预订状态由调度员根据已取得的订房确认填写。

公网手机入口沿用 `golf-public-pilot.conf` 的 TLS 9443。在此 server 内将精确路径 `/arrivals` 与前缀 `/static/` 转发至回环 Ops 服务，并在现有 `/api/` 转发内为 `/api/events/{event_id}/arrival-plans/{plan_id}/players/{player_id}/photo` 单独设置 `client_max_body_size 704k`。照片和工作区响应需要保留 `Cache-Control: no-store`。
