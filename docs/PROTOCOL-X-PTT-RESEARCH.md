# Protocol X 手机对讲与任务分派调研

2026-11-22。调研覆盖通用手机、专业 PTT、调度和移动任务。竞品操作来自厂商手册和产品资料。时延和转写数字来自 Protocol X 的本地对照测试。

## 市面的做法

| 产品 | 官方资料能确认的操作 | 对 Protocol X 的启发 |
| --- | --- | --- |
| Motorola WAVE PTX | 手机端支持一对一、预设组和临时组 PTT。Android Radio 手册给组呼定义了获准、占用、释放等提示，并在通话界面显示发言人。兼容设备可配物理按键。 [手机端资料](https://www.motorolasolutions.com/content/dam/msi/docs/EA_Collaterals/ENGLISH/wave/WAVE_PTX_Mobile_App_DataSheet_ENG.pdf) · [Android Radio 手册](https://woc-support-site-bucket.s3.us-west-2.amazonaws.com/user-guides/WAVE_PTX_Android_PTT_Radio_Application_User_Guide.pdf) | 把当前发给谁、谁占着话权、何时可以说放到同一个屏幕。普通手机和带专用键设备分别验收。 |
| Zello Work | 可把联系人或频道设为默认，打开 App 就进入相应说话页。历史按人或组查看并回听。Dispatch Hub 有待接的来话队列，接起后归到接听的调度员。 [默认频道](https://support.zello.com/zw/default-channel-or-contact-an-overview) · [Android 历史](https://support.zello.com/zw/getting-started-using-zello-work-on-your-android-device) · [调度来话](https://support.zello.com/zw/getting-started-as-a-dispatcher-dispatch-hub) | 开屏直达当前组，最近一句一键回听。来话由谁接，与实际工作由谁执行，是两次不同的确认。 |
| Microsoft Teams Walkie Talkie + Planner | Walkie Talkie 恢复上次频道、显示发言者并用提示声确认可发言。各频道一次一人发言，一对一走 Teams 呼叫。Planner 负责发布、分派和查完成情况。 [Walkie Talkie](https://support.microsoft.com/en-us/teams/platform/communicate-with-your-team-in-walkie-talkie) · [Planner](https://support.microsoft.com/en-us/teams/shifts/publish-task-lists-to-define-and-track-work-in-your-organization) | 即时通话与待办各有固定入口。执行者看到自己的任务，调度看到进度。 |
| TASSTA T.Flex + Task Manager | 调度可以把任务派给人员或组。在手机上接收、接受、更新和确认完成。调度可查看处理进度。 [TASSTA 现场作业](https://tassta.com/solutions-operations/) · [T.Flex](https://tassta.com/t-flex/) | 最接近本项目的组合。任务要有负责人、状态和完成确认。 |
| ESChat | 区分获准振动、占用及优先级行为。启用相应功能后可从近期呼叫回放。 [Android 设置](https://kb.eschat.com/hc/en-us/articles/16972569312155-Android-Settings-Menus) · [回放](https://kb.eschat.com/hc/en-us/articles/8836278827547-Recent-Calls-and-Call-Playback) | 给等待、可发言和被占用不同反馈，把录音放在容易找到的入口。 |
| Zebra Workcloud | PTT Pro 的近期记录按联系人或组组织来话及消息。Workcloud Pinboard 提供从手机分派和查看工作。 [近期记录](https://docs.zebra.com/us/en/solutions/wcc/zebra-enterprise-messaging-service-client-user-guide/c-zems-use-zems-with-zebra-ptt-pro-for-android/t-pttpro-zems-responding-to-messages/r-pttpro-zems-recent-view.html) · [发布者的 Pinboard 页面](https://play.google.com/store/apps/details?id=com.reflexisinc.pinboard17) | 回访一个人或组时容易找到事情的上下文。任务的执行情况有独立读法。 |
| 海能达 HyTalk | 提供语音、现场视频、网页调度，以及语音和视频记录。 [HyTalk 产品页](https://www.hytera.com/eu/products/hytalk-system.html) | 给实时视频单独入口，并清楚展示发布者和可见范围。 |

## 目前的瓶颈

### 发言等待

同一 Android 15 模拟器和隔离的 LiveKit 频道，有一名订阅者。每版测试三次，从 App 写下 `press` 到发送轨解除静音并写下 `ready`。

| APK | 首次 | 连续按键两次 |
| --- | ---: | ---: |
| v0.6.2 | 647 ms | 499、522 ms |
| v0.7.0 本地试验版 | 647 ms | 550、616 ms |

两版样本中，话权 HTTP 请求为 30–113 ms，返回后到静音轨发布为 13–47 ms，静音预备及提示音为 454–566 ms。等待主要来自发布后的保护时间。先前缩短预备时曾发现接收端吃掉开头，因此下一步要用接收端音频验证每次缩短。试验版增加按下触感和「正在申请话权」「正在准备音频」的区分。上表记录的是开麦时间，接收方听到首字的时间要另外测。 [原有发言时序验证](VALIDATION.md#v051-发言等待)

按住录语音可以先在本机录，松手后发送。直播群组要先拿到唯一话权，再让其他人的接收链路准备好。Zello 提供绕过预先检查的 QuickConnect，厂商也说明多人同时按时，后来的人可能已经开口才收到发送失败。Protocol X 应优先预热媒体连接，依据实际接收准备情况逐段缩短等候，并保持同一时刻一个人发言。 [Zello QuickConnect](https://support.zello.com/zw/using-quickconnect-for-faster-channel-communications)

### 转写

香港现用 Whisper base 在独立进程处理归档原音。用现行模型对照三句合成中文口令及其加噪版本，共 6 条。统一简繁、常用数字和标点后，base 默认参数有 8/74 个字的编辑误差，复现了「候车点」被写成「后车点」。同机单线程运行 small、中文语言设置及调度词提示后为 1/74，六条推理约从 11 秒增至 39 秒。这组样本用于筛选候选方案，现场口音和噪声应另外收样。近期语音要先能一键听原音，调度确认的人员、时间和点位进入结构化任务。 [Faster Whisper small 模型](https://huggingface.co/Systran/faster-whisper-small)

### 任务

原生 App 已可处理接单、现场步骤、汇报和本任务回执。服务端支持调度员替换资源以及更新接机安排，版本检查和幂等回执已存在。现行试点登录给同角色使用共享的 `demo-staff` 或 `demo-dispatcher` 身份。按人员分派前，需要能区分真实用户，呼号用于显示和语音辨认。 [Android 任务操作](../android/app/src/main/java/com/mandonothing/ops/MainActivity.kt) · [任务行动 API](../app/main.py) · [试点身份](../app/pilot.py)

## 推荐给 Protocol X 的结构

**开屏「对讲」**。在一屏内看到当前频道、收听或重连状态、正在说话的呼号和固定的大话键。存在已加入的频道时可直接回到该组。把「最近一句」和「回听」放在一伸手的位置。完整语音、原音、转写和汇报从「频道对话」进入。

**独立「任务」**。现场人员看到「待接单」「进行中」「已完成」及当前应做的一步。调度有「发任务」入口，先确定人员或组、事项、地点和时限，能跟踪「已派发」「已接单」「当前步骤」「已完成」。会合、登车、交接等动作继续由活动 Ontology 决定。某条语音可作为派单草稿的来源，调度核对内容后提交。

**画面和私呼**。集体现场画面由频道入口进入二级页，用户明确点「开相机回传」才发布。选择联系人后可发起私呼。需要一对一视讯时，在联系人会话中单独发起并由对方接听。Android Core-Telecom 可管理系统通话状态和音频设备，应用及 LiveKit 负责邀请和媒体。 [Android Core-Telecom](https://developer.android.com/develop/connectivity/telecom/voip-app/telecom)

## 建议的验收顺序

1. 先用 S21 和 S26 验证开屏可直接对讲、返回桌面和息屏后可听、Wi-Fi 与移动网络切换后能恢复。分别量按键响应、获准提示、对端首字是否清楚，并覆盖抢话、提前松手和外放与耳机。
2. 在高尔夫试点用两个独立身份，从调度发出真实的测试任务。验证工作人员签收、按顺序完成现场步骤，调度能够回读对应的时间与回执。会议流程复用任务入口和各自的 Ontology。
3. 收一组同意用于评测的现场短句，再决定模型。把车辆号、时间、人员、地点的准确性与转写耗时分别记录，原音作为核对依据。

已有的 v0.7.0 本地试验验证了按时间组织的对话、原音回放、二级现场画面、返回时停止摄像和任务回执入口。调研建议把这套对话作为频道详情，开屏重新以对讲操作为焦点。
