package com.mandonothing.ops

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.mandonothing.ops.comm.LiveKitPttTransportAdapter
import com.mandonothing.ops.comm.PttClip
import com.mandonothing.ops.comm.PttCue
import com.mandonothing.ops.comm.PttPhase
import com.mandonothing.ops.comm.PttSessionTransport
import com.mandonothing.ops.comm.PttSpeaker
import com.mandonothing.ops.comm.PttTransportMode
import com.mandonothing.ops.comm.network.RnsNetworkManager
import com.mandonothing.ops.comm.ptt.RnsPttHandoffCoordinator
import com.mandonothing.ops.comm.ptt.RnsPttSessionTransport
import com.mandonothing.ops.comm.ptt.RnsPttTransportBridge
import com.mandonothing.ops.comm.rns.RnsCoreEvent
import com.mandonothing.ops.comm.rns.RnsCorePttBridge
import com.mandonothing.ops.comm.rns.RnsRuntimeService
import com.twilio.audioswitch.AudioDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import org.json.JSONObject

internal data class VoiceWorkspace(
    val client: OpsClient,
    val events: List<JSONObject>,
    val eventId: String,
    val title: String,
    val role: String,
    val callsign: String,
    val videoEnabled: Boolean,
    val archiveEnabled: Boolean,
    val target: RadioTarget? = null,
    val voiceEventId: String? = eventId,
)

internal data class VoiceSessionState(
    val workspace: VoiceWorkspace? = null,
    val phase: PttPhase = PttPhase.OFFLINE,
    val message: String = "未加入频道",
    val speaker: PttSpeaker? = null,
    val lastHeard: PttSpeaker? = null,
    val output: String = "系统默认",
    val receiveMuted: Boolean = false,
    val transportMode: PttTransportMode = PttTransportMode.NONE,
    val localClips: List<PttClip> = emptyList(),
)

class VoiceSessionService : Service() {
    internal inner class LocalBinder : Binder() {
        val session: VoiceSessionService get() = this@VoiceSessionService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(VoiceSessionState())
    private val mutableCues = MutableSharedFlow<PttCue>(extraBufferCapacity = 4)
    internal val state = mutableState.asStateFlow()
    internal val cues = mutableCues.asSharedFlow()
    private var transport: PttSessionTransport? = null
    private var handoffCoordinator: RnsPttHandoffCoordinator? = null
    private var handoffJob: Job? = null
    private var pttInboundJob: Job? = null
    @Volatile private var handoffSwitching = false
    private var rnsNetworkManager: RnsNetworkManager? = null
    private var rnsPttBridge: RnsPttTransportBridge? = null
    private var rnsPttFrames: SharedFlow<RnsCoreEvent.PttFrameReceived>? = null
    private var rnsPttFloorEvents: SharedFlow<RnsCoreEvent.PttFloorEvent>? = null
    internal var pttTransportFactory: ((VoiceWorkspace, Int) -> PttSessionTransport)? = null
    internal var onReceiveMuteChanged: ((String, Boolean) -> Unit) = { _, _ -> }
    private var uiOwner: Any? = null
    private var tone: ToneGenerator? = null
    private val pttSpeech by lazy { PttSpeech(assets) }
    private var foreground = false
    private var microphoneType = false
    private var generation = 0
    private var hasJoined = false
    private var nextRetry = 0
    private var retry: Job? = null
    private var fieldWatcher: Job? = null
    private val clipJobs = mutableSetOf<Job>()
    private val captionRoute = MutableStateFlow(CaptionOnlineState())
    private val networkManager by lazy { getSystemService(ConnectivityManager::class.java) }
    private var defaultNetwork: Network? = null
    private var networkWatchRegistered = false

    private val networkWatch = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch {
                defaultNetwork = network
                // onCapabilitiesChanged follows on supported Android versions.
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            scope.launch {
                if (defaultNetwork != network) return@launch
                captionRoute.value = captionRoute.value.copy(
                    hasNetwork = true,
                    validatedInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
            }
        }

        override fun onLost(network: Network) {
            scope.launch {
                if (defaultNetwork != network) return@launch
                defaultNetwork = null
                captionRoute.value = captionRoute.value.copy(hasNetwork = false, validatedInternet = false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        defaultNetwork = networkManager.activeNetwork
        val initial = defaultNetwork?.let(networkManager::getNetworkCapabilities)
        captionRoute.value = CaptionOnlineState(
            hasNetwork = defaultNetwork != null,
            validatedInternet = initial?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        )
        networkWatchRegistered = runCatching { networkManager.registerDefaultNetworkCallback(networkWatch) }.isSuccess
    }

    internal fun observeCaptionServer(client: OpsClient, eventId: String, target: RadioTarget?,
                                      items: List<JSONObject>?) {
        val workspace = mutableState.value.workspace ?: return
        if (workspace.client !== client || workspace.eventId != eventId || workspace.target != target) return
        val old = captionRoute.value
        val received = items.orEmpty().mapNotNull { item ->
            val track = item.optString("track_sid").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            track to serverCaption(item.optString("status"), item.optString("text"))
        }.toMap()
        // An older response must not discard a final transcript already observed.
        val recent = (received + old.reviews.filterValues { it == ServerCaption.READY }).entries
            .take(80).associate { it.toPair() }
        captionRoute.value = old.copy(archiveReachable = items != null, reviews = recent)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    internal fun attachUi(owner: Any) {
        uiOwner = owner
    }

    internal fun attachRnsCoordination(
        networkManager: RnsNetworkManager?,
        pttBridge: RnsPttTransportBridge?,
        inboundFrames: SharedFlow<RnsCoreEvent.PttFrameReceived>?,
        inboundFloor: SharedFlow<RnsCoreEvent.PttFloorEvent>?,
    ) {
        rnsNetworkManager = networkManager
        rnsPttBridge = pttBridge
        rnsPttFrames = inboundFrames
        rnsPttFloorEvents = inboundFloor
    }

    internal fun detachUi(owner: Any) {
        if (uiOwner !== owner) return
        uiOwner = null
        transport?.stopPress(cancel = true)
        tone?.stopTone()
    }

    internal fun join(workspace: VoiceWorkspace, owner: Any) {
        if (uiOwner !== owner || mutableState.value.workspace != null) return
        captionRoute.value = captionRoute.value.copy(archiveReachable = true, reviews = emptyMap())
        mutableState.value = VoiceSessionState(workspace, PttPhase.CONNECTING, "正在加入频道…")
        try {
            startForegroundService(Intent(this, VoiceSessionService::class.java).setAction(START_LISTENING))
        } catch (_: IllegalStateException) {
            leave("请在前台重新加入频道")
        } catch (_: SecurityException) {
            leave("无法开启后台收听，请重新加入频道")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_LISTENING) {
            leave()
            return START_NOT_STICKY
        }
        if (intent?.action != START_LISTENING) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (foreground) return START_NOT_STICKY
        // A start can reach us after the user cancels. Finish the promotion before stopping it.
        try {
            promote(withMicrophone = false)
        } catch (_: IllegalStateException) {
            leave("请在前台重新加入频道")
            return START_NOT_STICKY
        } catch (_: SecurityException) {
            leave("无法开启后台收听，请重新加入频道")
            return START_NOT_STICKY
        }
        val workspace = mutableState.value.workspace
        if (workspace == null) leave() else {
            connect(workspace)
            if (workspace.client.bound) startFieldWatch(workspace)
        }
        return START_NOT_STICKY
    }

    private fun startFieldWatch(workspace: VoiceWorkspace) {
        fieldWatcher?.cancel()
        val seen = getSharedPreferences("field_alerts", MODE_PRIVATE)
        val who = workspace.client.profile.optString("actor")
        val key = workspace.client.origin + ":" + who + ":" + workspace.eventId
        val ids = seen.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        fieldWatcher = scope.launch {
            var channelCursor: String? = null
            while (mutableState.value.workspace === workspace) {
                try {
                    val target = workspace.target
                    if (target != null) {
                        val updates = try {
                            val delta = workspace.client.channelChanges(workspace.eventId, target, channelCursor, audio = false)
                            if (delta == null) workspace.client.channelDispatch(workspace.eventId, target)
                            else delta.also { channelCursor = it.getString("cursor") }
                        } catch (error: OpsException) {
                            if (error.status != 404) throw error
                            null
                        }
                        val field = if (workspace.role == "dispatcher") "incidents" else "instructions"
                        val notices = updates?.optJSONArray(field)
                        if (notices != null) for (index in 0 until notices.length()) {
                            val item = notices.getJSONObject(index)
                            val id = item.getString("id")
                            val at = runCatching { java.time.Instant.parse(item.getString("created_at")) }.getOrNull()
                            if (at == null || at.isBefore(java.time.Instant.now().minusSeconds(20 * 60))) continue
                            if (item.optBoolean("received") || !ids.add(id)) continue
                            if (workspace.role == "dispatcher") {
                                postFieldAlert(id, "频道异常 · " + item.getString("label"),
                                    workspace.eventId, channelId = target.id, incidentId = id)
                            } else {
                                postFieldAlert(id, "收到中台指示", workspace.eventId, instructionId = id)
                            }
                        }
                        seen.edit().putStringSet(key, ids.toList().takeLast(256).toSet()).apply()
                    }
                    val payload = workspace.client.inbox(workspace.eventId)
                    for (index in 0 until payload.getJSONArray("tasks").length()) {
                        val task = payload.getJSONArray("tasks").getJSONObject(index)
                        val id = task.getJSONObject("ref").getString("id")
                        if (id in ids) continue
                        if (task.optJSONObject("field_response")?.isNull("received_at") != false) {
                            workspace.client.markReceived(workspace.eventId, id)
                        }
                        if (ids.add(id)) postFieldAlert(id, "新任务 · " + task.getString("title"), workspace.eventId,
                            taskId = id)
                    }
                    for (index in 0 until payload.getJSONArray("calls").length()) {
                        val call = payload.getJSONArray("calls").getJSONObject(index)
                        val id = call.getString("id")
                        if (ids.add(id)) postFieldAlert(id, "私呼 · " + call.getString("name"), workspace.eventId,
                            channelId = call.getString("channel_id"))
                    }
                    val assistance = payload.optJSONArray("assistance")
                    for (index in 0 until (assistance?.length() ?: 0)) {
                        val report = assistance!!.getJSONObject(index)
                        val id = report.getString("id")
                        val subject = report.getJSONObject("subject_ref").getString("id")
                        if (ids.add(id)) postFieldAlert(id, "求助 · " + report.optString("speaker", "现场人员"),
                            workspace.eventId, taskId = subject)
                    }
                    val conferences = payload.optJSONArray("conferences")
                    if (conferences != null) for (index in 0 until conferences.length()) {
                        val call = conferences.getJSONObject(index)
                        val id = call.getString("id")
                        val label = if (call.optString("mode") == "video") "视讯来电" else "语音来电"
                        if (ids.add(id)) postFieldAlert(id, "$label · " + call.getString("name"), workspace.eventId,
                            channelId = call.getString("channel_id"), conferenceId = id)
                    }
                    seen.edit().putStringSet(key, ids.toList().takeLast(256).toSet()).apply()
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: OpsException) {
                    if (error.status == 401) {
                        leave("绑定已失效，请重新登录")
                        return@launch
                    }
                } catch (_: Exception) {
                    // The foreground radio connection has its own reconnect state.
                }
                delay(6_000)
            }
        }
    }

    private fun postFieldAlert(id: String, subtitle: String, eventId: String,
                               taskId: String? = null, channelId: String? = null, conferenceId: String? = null,
                               incidentId: String? = null, instructionId: String? = null) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(FIELD_ALERT_CHANNEL, "派单、私呼与求助", NotificationManager.IMPORTANCE_HIGH)
            .apply { enableVibration(true) })
        val resume = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("field_event", eventId).putExtra("field_task", taskId).putExtra("field_channel", channelId)
            .putExtra("field_conference", conferenceId)
            .apply {
                if (incidentId != null) putExtra("field_incident", incidentId)
                if (instructionId != null) putExtra("field_instruction", instructionId)
            }
        val target = PendingIntent.getActivity(this, id.hashCode(), resume, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val alert = Notification.Builder(this, FIELD_ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Protocol X")
            .setContentText(subtitle).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(target).setAutoCancel(true).build()
        manager.notify(id, 1, alert)
    }

    private fun createDefaultPttTransport(workspace: VoiceWorkspace, activeGeneration: Int): PttSessionTransport =
        LiveKitPttTransportAdapter(
            context = applicationContext,
            scope = scope,
            client = workspace.client,
            eventId = workspace.eventId,
            allowLocal = BuildConfig.DEBUG,
            target = workspace.target,
            onSpeaker = { speaker ->
                if (generation == activeGeneration) {
                    if (speaker != null && BuildConfig.DEBUG) Log.d(TAG, "Incoming audio subscribed")
                    val current = mutableState.value
                    mutableState.value = current.copy(speaker = speaker, lastHeard = speaker ?: current.lastHeard)
                }
            },
            onOutput = { output ->
                if (generation == activeGeneration) {
                    mutableState.value = mutableState.value.copy(output = output)
                    notifyStatus()
                }
            },
            onClip = { clip -> handleClip(workspace, activeGeneration, clip) },
            onCue = ::playCue,
            onState = { phase, message ->
                if (generation == activeGeneration) onChannelState(workspace, phase, message)
            },
        )

    private fun createRnsPttTransport(
        workspace: VoiceWorkspace,
        activeGeneration: Int,
        bridge: RnsPttTransportBridge,
    ): RnsPttSessionTransport =
        RnsPttSessionTransport.create(
            context = applicationContext,
            channelId = workspace.target?.id ?: workspace.eventId,
            localDisplayName = workspace.callsign,
            bridge = bridge,
            priority = if (workspace.role == "dispatcher") 2 else 1,
            onSpeaker = { speaker ->
                if (generation == activeGeneration && !handoffSwitching) {
                    val current = mutableState.value
                    mutableState.value = current.copy(speaker = speaker, lastHeard = speaker ?: current.lastHeard)
                }
            },
            onOutput = { output ->
                if (generation == activeGeneration && !handoffSwitching) {
                    mutableState.value = mutableState.value.copy(output = output)
                    notifyStatus()
                }
            },
            onClip = { clip -> handleClip(workspace, activeGeneration, clip) },
            onCue = ::playCue,
            onState = { phase, message ->
                if (generation == activeGeneration && !handoffSwitching) onChannelState(workspace, phase, message)
            },
        )

    private fun connect(workspace: VoiceWorkspace) {
        val activeGeneration = ++generation
        handoffJob?.cancel()
        pttInboundJob?.cancel()
        handoffCoordinator?.close()
        handoffCoordinator = null

        val customFactory = pttTransportFactory
        val netMgr = rnsNetworkManager
        val bridge = rnsPttBridge ?: RnsRuntimeService.pttBridge
        if (customFactory == null && netMgr != null && bridge != null && workspace.target != null) {
            val handoff = RnsPttHandoffCoordinator(
                liveKitFactory = { createDefaultPttTransport(workspace, activeGeneration) },
                rnsPttFactory = { createRnsPttTransport(workspace, activeGeneration, bridge) },
            )
            handoffCoordinator = handoff
            handoffSwitching = true
            val initial = handoff.reconcile(netMgr.state.value)
            handoffSwitching = false
            transport = initial
            mutableState.value = mutableState.value.copy(
                receiveMuted = initial.receiveMuted,
                transportMode = initial.mode,
            )
            handoffJob = scope.launch {
                netMgr.state.collect { state ->
                    if (generation != activeGeneration) return@collect
                    val current = handoff.currentTransport()
                    if (current != null && (state.shouldUseLiveKitPtt == (current.mode == PttTransportMode.LIVEKIT))) {
                        return@collect
                    }
                    handoffSwitching = true
                    val next = handoff.reconcile(state)
                    handoffSwitching = false
                    transport = next
                    mutableState.value = mutableState.value.copy(
                        receiveMuted = next.receiveMuted,
                        transportMode = next.mode,
                    )
                }
            }
            val framesFlow = rnsPttFrames ?: RnsRuntimeService.pttFrames
            val floorFlow = rnsPttFloorEvents ?: RnsRuntimeService.pttFloorEvents
            pttInboundJob = scope.launch {
                launch {
                    framesFlow.collect { frameEvent ->
                        if (generation != activeGeneration) return@collect
                        val rnsTransport = transport as? RnsPttSessionTransport ?: return@collect
                        rnsTransport.onInboundFrame(RnsCorePttBridge.toPttFrame(frameEvent))
                    }
                }
                floorFlow.collect { floorEvent ->
                    if (generation != activeGeneration) return@collect
                    val rnsTransport = transport as? RnsPttSessionTransport ?: return@collect
                    rnsTransport.onInboundFloorControl(RnsCorePttBridge.toFloorControl(floorEvent))
                }
            }
            return
        }

        val next = customFactory?.invoke(workspace, activeGeneration)
            ?: createDefaultPttTransport(workspace, activeGeneration)
        transport = next
        mutableState.value = mutableState.value.copy(
            receiveMuted = next.receiveMuted,
            transportMode = next.mode,
        )
        next.connect()
    }

    private fun handleClip(workspace: VoiceWorkspace, expected: Int, capture: VoiceCapturedClip) {
        if (generation != expected) return
        val job = scope.launch {
            val samples = withContext(Dispatchers.Default) { capture.pcm.mono16().takeIf(pttSpeech::containsSpeech) }
                ?: return@launch
            if (generation != expected) return@launch
            val cache = VoiceClipCache.forApp(this@VoiceSessionService)
            val key = cache.key(workspace.client, workspace.eventId, workspace.target, capture.trackSid)
            val saved = withContext(Dispatchers.IO) {
                val wav = wave16(samples)
                ensureActive()
                runCatching { cache.save(key, wav) }.isSuccess
            }
            if (generation != expected || !saved) return@launch
            val local = PttClip(capture.trackSid, capture.speaker, capture.mine, Instant.now().toString(), samples.size / 16)
            mutableState.value = mutableState.value.copy(localClips =
                (listOf(local) + mutableState.value.localClips.filterNot { it.trackSid == local.trackSid }).take(16))
            if (BuildConfig.DEBUG) Log.d(TAG, "PTT PCM cached (${if (capture.mine) "send" else "receive"})")
            val shouldCaption = (capture.mine || uiOwner != null) && OfflineCaptions.forApp(this@VoiceSessionService).installed
            if (!shouldCaption || !needsLocalCaption(
                    captionRoute, capture.trackSid, workspace.archiveEnabled,
                    requireValidatedInternet = workspace.client.origin.startsWith("https://"),
                )) return@launch
            if (generation != expected || captionRoute.value.reviews[capture.trackSid] == ServerCaption.READY) return@launch
            if (BuildConfig.DEBUG) Log.d(TAG, "PTT ASR fallback selected")
            val text = OfflineCaptions.forApp(this@VoiceSessionService).decode(samples) ?: return@launch
            if (generation != expected || captionRoute.value.reviews[capture.trackSid] == ServerCaption.READY) return@launch
            mutableState.value = mutableState.value.copy(localClips = mutableState.value.localClips.map {
                if (it.trackSid == local.trackSid) it.copy(caption = text) else it
            })
            if (BuildConfig.DEBUG) Log.d(TAG, "On-device PTT caption ready")
        }
        clipJobs.add(job)
        job.invokeOnCompletion { scope.launch { clipJobs.remove(job) } }
    }

    private fun onChannelState(workspace: VoiceWorkspace, phase: PttPhase, message: String) {
        when (phase) {
            PttPhase.LISTENING -> {
                hasJoined = true
                nextRetry = 0
                if (OfflineCaptions.forApp(this).installed) scope.launch { OfflineCaptions.forApp(this@VoiceSessionService).warm() }
                if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 55) }.getOrNull()
                playbackOnly()
            }
            PttPhase.CONNECTING -> playbackOnly()
            PttPhase.OFFLINE -> {
                transport = null
                if (hasJoined && nextRetry < RETRY_DELAY_MS.size) {
                    playbackOnly()
                    mutableState.value = mutableState.value.copy(phase = PttPhase.CONNECTING, message = "正在恢复收听…", speaker = null)
                    notifyStatus()
                    val waitMs = RETRY_DELAY_MS[nextRetry++]
                    retry = scope.launch {
                        delay(waitMs)
                        connect(workspace)
                    }
                } else {
                    leave(message)
                }
                return
            }
            else -> Unit
        }
        mutableState.value = mutableState.value.copy(phase = phase, message = message)
        if (phase == PttPhase.CONNECTING || phase == PttPhase.LISTENING) notifyStatus()
    }

    internal fun startPress(owner: Any) {
        val current = transport ?: return
        if (uiOwner !== owner || !foreground || mutableState.value.phase != PttPhase.LISTENING ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        try {
            if (!microphoneType && Build.VERSION.SDK_INT >= 30) promote(withMicrophone = true)
        } catch (_: IllegalStateException) {
            mutableState.value = mutableState.value.copy(message = "请回到 Protocol X 后再发言")
            return
        } catch (_: SecurityException) {
            mutableState.value = mutableState.value.copy(message = "请在前台允许麦克风后发言")
            return
        }
        if (!current.startPress()) playbackOnly()
    }

    internal fun stopPress(owner: Any, cancel: Boolean = false) {
        if (uiOwner === owner) transport?.stopPress(cancel)
    }

    internal fun audioOutputs(): List<AudioDevice> = transport?.audioOutputs().orEmpty()
    internal fun currentOutput(): AudioDevice? = transport?.currentOutput()
    internal fun selectOutput(device: AudioDevice) { transport?.selectOutput(device) }

    internal fun toggleReceiveMute(owner: Any) {
        if (uiOwner !== owner) return
        val listening = transport ?: return
        listening.setReceiveMuted(!listening.receiveMuted)
        mutableState.value = mutableState.value.copy(receiveMuted = listening.receiveMuted)
        mutableState.value.workspace?.target?.id?.let { onReceiveMuteChanged(it, listening.receiveMuted) }
    }

    internal fun leave(message: String = "已退出频道") {
        closeChannel()
        mutableState.value = VoiceSessionState(message = message)
        stopSelf()
    }

    private fun closeChannel() {
        fieldWatcher?.cancel()
        fieldWatcher = null
        ++generation
        clipJobs.toList().forEach { it.cancel() }
        clipJobs.clear()
        retry?.cancel()
        retry = null
        nextRetry = 0
        hasJoined = false
        handoffJob?.cancel()
        handoffJob = null
        pttInboundJob?.cancel()
        pttInboundJob = null
        handoffSwitching = true
        handoffCoordinator?.close()
        handoffCoordinator = null
        handoffSwitching = false
        transport?.close()
        transport = null
        tone?.release()
        tone = null
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        microphoneType = false
    }

    override fun onDestroy() {
        closeChannel()
        if (networkWatchRegistered) runCatching { networkManager.unregisterNetworkCallback(networkWatch) }
        uiOwner = null
        scope.cancel()
        super.onDestroy()
    }

    private fun playbackOnly() {
        if (foreground && microphoneType) promote(withMicrophone = false)
    }

    private fun playCue(cue: PttCue) {
        if (uiOwner == null) return
        val (sound, duration) = when (cue) {
            PttCue.ENDED -> ToneGenerator.TONE_PROP_BEEP2 to 65
            PttCue.BUSY -> ToneGenerator.TONE_PROP_NACK to 120
        }
        tone?.startTone(sound, duration)
        mutableCues.tryEmit(cue)
    }

    private fun promote(withMicrophone: Boolean) {
        val notification = listeningNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            val microphone = if (Build.VERSION.SDK_INT >= 30 && withMicrophone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or microphone)
            microphoneType = microphone != 0
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground = true
    }

    private fun notifyStatus() {
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, listeningNotification())
    }

    private fun listeningNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(NOTIFICATION_CHANNEL, "频道收听", NotificationManager.IMPORTANCE_LOW))
        val options = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val resume = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), options)
        val end = PendingIntent.getService(this, 1, Intent(this, VoiceSessionService::class.java).setAction(STOP_LISTENING), options)
        val snapshot = mutableState.value
        val title = snapshot.workspace?.title ?: "对讲频道"
        val statusText = if (snapshot.phase == PttPhase.CONNECTING) "正在连接" else "持续收听 · ${snapshot.output}"
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("$title · $statusText")
            .setContentIntent(resume)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "退出频道", end).build())
            .build()
    }

    private companion object {
        const val FIELD_ALERT_CHANNEL = "protocol_x_field_alerts"
        const val TAG = "ProtocolXVoiceService"
        const val START_LISTENING = "com.mandonothing.ops.START_LISTENING"
        const val STOP_LISTENING = "com.mandonothing.ops.STOP_LISTENING"
        const val NOTIFICATION_CHANNEL = "protocol_x_voice"
        const val NOTIFICATION_ID = 7811
        val RETRY_DELAY_MS = longArrayOf(2_000, 4_000, 8_000)
    }
}
