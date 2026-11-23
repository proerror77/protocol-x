package com.mandonothing.ops

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import androidx.core.widget.doAfterTextChanged
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.view.ViewTreeObserver
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nearbyPanel by lazy { NearbyPanel(this, scope) }
    private val appUpdates by lazy { AppUpdates(this) }
    private var channelReceiveMuted = false
    private var flightNoticeObserver: Job? = null
    private val unreadChannelText = mutableMapOf<String, View>()
    private val pendingReadReceipts = mutableSetOf<String>()
    private var channelReadCheckScheduled = false
    private val channelReadCheck = Runnable {
        channelReadCheckScheduled = false
        markVisibleChannelTextRead()
    }
    private lateinit var channelComposer: LinearLayout
    private lateinit var channelWrite: Button
    private lateinit var channelEditor: LinearLayout
    private lateinit var channelOptions: LinearLayout
    private lateinit var channelText: EditText
    private lateinit var channelSend: Button
    private lateinit var channelSendStatus: TextView
    private lateinit var channelRadioPermission: Button
    private lateinit var channelRecipientPicker: Button
    private lateinit var channelAttach: Button
    private var channelEditorOpen = false
    private var channelOptionsOpen = false
    private var channelComposerScope: String? = null
    private var channelRecipientActor: String? = null
    private var channelPhoto: ByteArray? = null
    private var channelPhotoRequest: Pair<String, String>? = null
    private var privateReader: Pair<String, NearbyPrivateIdentity>? = null
    private val privateContent = mutableMapOf<String, NearbyPrivateContent>()
    private var channelDraftKey: String? = null
    private val channelDrafts = mutableMapOf<String, String>()
    private val nearbySettings by lazy { NearbySettings(this) }
    private var nearbyProvision: Job? = null
    private var nearbyObserver: Job? = null
    private var nearbyLastSync = 0L
    private var onlineVoiceRequested = false
    private var onlineVoiceEverJoined = false
    private var onlineVoiceAttemptAt = 0L
    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var workspaceHeader: LinearLayout
    private lateinit var voiceDock: LinearLayout
    private lateinit var replayBar: LinearLayout
    private lateinit var feedback: TextView
    private lateinit var tasksList: LinearLayout
    private lateinit var taskDetails: LinearLayout
    private lateinit var qrfDock: LinearLayout
    private lateinit var qrfButton: Button
    private lateinit var taskFilters: Map<TaskStage, Button>
    private var taskFilter = TaskStage.ALL
    private var taskDetailKey: String? = null
    private lateinit var taskContext: TextView
    private lateinit var messagesList: LinearLayout
    private lateinit var connectionStatus: TextView
    private lateinit var actionFeedback: TextView
    private lateinit var reportFeedback: TextView
    private lateinit var taskSelector: Spinner
    private lateinit var reportField: EditText
    private lateinit var joinButton: Button
    private lateinit var leaveButton: Button
    private lateinit var pttButton: Button
    private lateinit var voiceLabel: TextView
    private lateinit var speakerLabel: TextView
    private lateinit var dockTarget: TextView
    private lateinit var dockSummary: LinearLayout
    private lateinit var pttHint: TextView
    private lateinit var channelName: TextView
    private lateinit var channelSearch: EditText
    private lateinit var channelRows: LinearLayout
    private lateinit var channelDirectorySubtitle: TextView
    private lateinit var directoryCall: Button
    private var directoryRenderKey = ""
    private var incomingPoll: Job? = null
    private var lastIncomingId: String? = null
    private var placingCall = false
    private lateinit var channelMeta: Button
    private lateinit var channelMute: Button
    private lateinit var memberChannelTitle: TextView
    private lateinit var memberRows: LinearLayout
    private lateinit var memberSearch: EditText
    private var memberListKey = ""
    private lateinit var channelConnection: TextView
    private lateinit var channelState: TextView
    private lateinit var conversation: LinearLayout
    private lateinit var radioHeader: LinearLayout
    private var dispatchInstructions = emptyList<JSONObject>()
    private var agentIncidents = emptyList<JSONObject>()
    private var agentActivity = emptyList<JSONObject>()
    private var channelPoll: Job? = null
    private var channelLoad: Job? = null
    private var agentDialog: AlertDialog? = null
    private val shownIncidents = mutableSetOf<String>()
    private lateinit var recordHeading: TextView
    private lateinit var recordContext: TextView
    private lateinit var workspaceMenu: Button
    private lateinit var reportButton: Button
    private lateinit var sections: Map<Section, View>
    private lateinit var sectionTabs: Map<Section, Button>
    private lateinit var videoCard: LinearLayout
    private lateinit var videoFrame: FrameLayout
    private lateinit var videoLabel: TextView
    private lateinit var videoRetry: Button
    private lateinit var videoSend: Button
    private lateinit var replayFeedback: TextView
    private lateinit var replayStop: Button
    private var client: OpsClient? = null
    private val fieldSession by lazy { FieldSessionStore(this) }
    private val fieldOffline by lazy { FieldOfflineStore(this, fieldSession) }
    private val voiceCache by lazy { VoiceClipCache.forApp(this) }
    private var localVoiceClips = emptyList<VoiceLocalClip>()
    private var captionDownload: Job? = null
    private var voiceWarmup: Job? = null
    private val warmAttempts = mutableMapOf<String, Long>()
    private var queuedField = emptyList<FieldOfflineStore.Queued>()
    private var fieldSync: Job? = null
    private var workspaceOnline = false
    private val photoOutbox by lazy { PhotoOutbox(this) }
    private var radio: RadioTarget? = null
    private var groupBeforeDirect: RadioTarget? = null
    private var directoryData = JSONObject()
    private var olderBefore: String? = null
    private var historyJob: Job? = null
    private var loginJob: Job? = null
    private var photoJob: Job? = null
    private var pendingPhoto: PhotoRequest? = null
    private var photoFile: File? = null
    private var qrFile: File? = null
    private var qrReceiver: ((String) -> Unit)? = null
    private var fieldNotice: Intent? = null
    private var arrivalCards = emptyMap<String, JSONObject>()
    private var arrivalView: ArrivalCardView? = null
    private var pagedVoice = false
    private lateinit var photoStatus: TextView
    private var conference: DirectCall? = null
    private var callDialog: AlertDialog? = null
    private var callWait: Job? = null
    private var callMedia: ConferenceMedia? = null
    private var callPermission: (() -> Unit)? = null
    private var restoreRadioAfterCall = false
    private lateinit var dispatchButton: Button
    private lateinit var responseButton: Button
    private val voiceOwner = Any()
    private var voiceService: VoiceSessionService? = null
    private var voiceBound = false
    private var voiceObserver: Job? = null
    private var cueObserver: Job? = null
    private var video: VideoChannel? = null
    private var videoState = VideoState("")
    private val returnToParent = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback {
        parentSection()?.let(::showSection)
    } else null
    private var backRegistered = false
    private var cameraPermissionEvent: String? = null
    private var startCameraOnConnect = false
    private var player: VoicePlayback? = null
    private var replay: Job? = null
    private var load: Job? = null
    private var workspaceSync: Job? = null
    private var events = emptyList<JSONObject>()
    private var tasks = emptyList<JSONObject>()
    private var messages = emptyList<JSONObject>()
    private var voiceMessages = emptyList<JSONObject>()
    private var channelCursor: String? = null
    private var channelIncidentTracks = JSONObject()
    private var receipts = emptyList<JSONObject>()
    private var eventId: String? = null
    private var voiceEventId: String? = null
    private var videoEnabled = false
    private var archiveEnabled = false
    private var section = Section.LIVE
    private var receiptScope: ReceiptScope? = null
    private var callsign = ""
    private var remoteSpeaker: VoiceSpeaker? = null
    private var lastHeard: VoiceSpeaker? = null
    private var voiceState = VoiceState.OFFLINE
    private var role = "staff"
    private var hasWorkspace = false
    private var timelineLimit = 20
    private val drafts = mutableMapOf<String, Draft>()
    private val pendingActions = mutableMapOf<String, String>()
    private val pendingReports = mutableMapOf<Triple<String?, String, String?>, String>()
    private var visible = false
    private var keyboardVisible = false
    private var mutationPending = false

    private val voiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!visible) return
            val source = (binder as VoiceSessionService.LocalBinder).session
            voiceService = source
            source.attachUi(voiceOwner)
            val workspace = source.state.value.workspace
            if (client == null && workspace != null) {
                loginJob?.cancel()
                restoreVoiceWorkspace(workspace)
            }
            voiceObserver?.cancel()
            voiceObserver = scope.launch { source.state.collect(::applyVoiceSession) }
            cueObserver?.cancel()
            cueObserver = scope.launch { source.cues.collect(::playHapticCue) }
            if (restoreRadioAfterCall && conference == null && voiceAvailable()) {
                restoreRadioAfterCall = false
                connectVoice()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            voiceService = null
            voiceObserver?.cancel()
            cueObserver?.cancel()
            applyVoiceSession(VoiceSessionState(message = "收听服务已断开，请重新加入"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.hasExtra("field_event")) fieldNotice = intent
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        callsign = getPreferences(MODE_PRIVATE).getString("callsign", deviceCallsign()).orEmpty()
        root = column().apply { setBackgroundColor(BACKGROUND) }
        workspaceHeader = column().apply { visibility = View.GONE }
        root.addView(workspaceHeader, LinearLayout.LayoutParams(MATCH, WRAP))
        radioHeader = column().apply {
            visibility = View.GONE
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        root.addView(radioHeader, LinearLayout.LayoutParams(MATCH, WRAP))
        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setOnScrollChangeListener { _, _, _, _, _ -> scheduleVisibleChannelReads(restart = true) }
            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                    scheduleVisibleChannelReads(restart = true)
                }
            }
        }
        content = column().apply { setPadding(dp(16), dp(4), dp(16), dp(16)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        replayBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            setBackgroundColor(PANEL)
            visibility = View.GONE
        }
        root.addView(replayBar, LinearLayout.LayoutParams(MATCH, WRAP))
        voiceDock = column().apply { visibility = View.GONE }
        qrfDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(6))
            visibility = View.GONE
        }
        qrfButton = button("", primary = true)
        qrfDock.addView(qrfButton, LinearLayout.LayoutParams(0, dp(48), 2f))
        root.addView(qrfDock, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(voiceDock, LinearLayout.LayoutParams(MATCH, WRAP))
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, keyboard.bottom))
                keyboardVisible = insets.isVisible(WindowInsets.Type.ime())
                updateBottomControls()
                insets
            }
        }
        setContentView(root)
        val previous = lastNonConfigurationInstance as? Session
        if (previous == null) {
            showConnectionForm()
            val packageUri = FieldLoginPackage.intentUri(intent)
            if (packageUri != null && fieldSession.read() == null) openLoginPackage(packageUri)
            else {
                restoreFieldLogin()
                if (packageUri != null) explainAccountSwitch()
            }
        } else {
            client = previous.client
            events = previous.events
            voiceEventId = previous.voiceEventId
            videoEnabled = previous.videoEnabled
            archiveEnabled = previous.archiveEnabled
            section = previous.section
            receiptScope = previous.receiptScope
            taskFilter = previous.taskFilter
            taskDetailKey = previous.taskDetailKey
            radio = previous.radio
            groupBeforeDirect = previous.groupBeforeDirect
            lastHeard = previous.lastHeard
            role = previous.role
            drafts.putAll(previous.drafts)
            pendingActions.putAll(previous.pendingActions)
            pendingReports.putAll(previous.pendingReports)
            showWorkspace(previous.eventId)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        FieldLoginPackage.intentUri(intent)?.let { uri ->
            if (client == null && fieldSession.read() == null) openLoginPackage(uri) else explainAccountSwitch()
            return
        }
        if (intent.hasExtra("field_event")) {
            fieldNotice = intent
            if (intent.action == ArrivalWatchService.OPEN_ARRIVAL) openArrivalActivityIfNeeded()
            refresh(automatic = true)
        }
    }

    private fun openArrivalActivityIfNeeded(): Boolean {
        val notice = fieldNotice ?: return false
        if (notice.action != ArrivalWatchService.OPEN_ARRIVAL || !visible) return false
        val active = client ?: return false
        val target = notice.getStringExtra("field_event") ?: return false
        if (!active.bound || active.origin != notice.getStringExtra("arrival_origin")) {
            fieldNotice = null
            return false
        }
        if (target == eventId) return false
        if (events.any { it.optString("id") == target }) {
            selectEvent(target)
        } else {
            fieldNotice = null
            scope.launch {
                try {
                    val available = active.bootstrap().getJSONArray("events").objects()
                    if (active !== client || !visible) return@launch
                    events = available
                    if (available.any { it.optString("id") == target }) {
                        fieldNotice = notice
                        selectEvent(target)
                    } else showConnectionStatus("该接待任务已取消或改派")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (active === client && visible) showConnectionStatus(errorMessage(error))
                }
            }
        }
        return true
    }

    private fun followFieldNotice() {
        val notice = fieldNotice ?: return
        if (!visible) return
        if (notice.action == ArrivalWatchService.OPEN_ARRIVAL) {
            if (openArrivalActivityIfNeeded()) return
            if (fieldNotice == null) return
        }
        fieldNotice = null
        if (client?.bound != true || notice.getStringExtra("field_event") != eventId) return
        val taskId = notice.getStringExtra("field_task")
        if (notice.action == ArrivalWatchService.OPEN_ARRIVAL && taskId !in arrivalCards) {
            showConnectionStatus("该接待任务已取消或改派")
            return
        }
        val current = tasks.firstOrNull { it.getJSONObject("ref").optString("id") == taskId }
        if (current != null) {
            taskDetailKey = current.getJSONObject("ref").refKey()
            showSection(Section.TASK_DETAIL)
        }
        val targetId = notice.getStringExtra("field_channel")
        if (notice.hasExtra("field_instruction")) {
            showSection(Section.LIVE)
            syncChannel()
            return
        }
        if (notice.hasExtra("field_incident")) {
            groups().firstOrNull { it.optString("id") == targetId }?.let { activateRadio(RadioTarget(it)) }
            showSection(Section.LIVE)
            return
        }
        if (notice.hasExtra("field_conference")) {
            fieldCall { active, event ->
                val call = active.incomingPrivateCall(event).optJSONObject("call")
                if (call != null && active === client && event == eventId) offerIncoming(active, event, call)
            }
            return
        }
        val target = groups().firstOrNull { it.optString("id") == targetId && it.optString("kind") == "direct" }
        if (target != null) {
            val selected = RadioTarget(target)
            AlertDialog.Builder(this).setTitle("私呼 · " + selected.name)
                .setNegativeButton("稍后", null)
                .setPositiveButton("进入私呼") { _, _ ->
                    if (groups().any { it.optString("id") == selected.id }) activateRadio(selected)
                }.show()
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        flightNoticeObserver = scope.launch {
            ArrivalWatchService.events.collect { (origin, event) ->
                if (visible && event == eventId && origin == client?.origin) refresh(automatic = true)
            }
        }
        enableArrivalWatch()
        NearbyService.uiActive = true
        nearbyPanel.resume()
        nearbyObserver = scope.launch {
            launch { NearbyService.radios.collect { updateChannelComposer() } }
            launch { NearbyService.channelCredential.collect { updateChannelComposer() } }
            launch { NearbyService.diagnostics.collect { updateChannelComposer() } }
            NearbyService.session.collectLatest { local ->
                if (local == null) renderConversation()
                else local.messages.collect { renderConversation() }
            }
        }
        updateBackHandler()
        if (section == Section.VIDEO) connectVideo()
        voiceBound = bindService(Intent(this, VoiceSessionService::class.java), voiceConnection, BIND_AUTO_CREATE)
        if (!openArrivalActivityIfNeeded() && load?.isActive != true) refresh(automatic = true)
        observeWorkspace()
        channelPoll = scope.launch {
            while (true) {
                syncChannel(wait = 18)
                channelLoad?.join()
                delay(if (client?.channelFeedSupported != false || section == Section.LIVE) 1_000 else 3_000)
            }
        }
        incomingPoll = scope.launch {
            while (true) {
                val active = client
                val event = eventId
                if (active?.bound == true && active.privateCallsEnabled && event != null &&
                    conference == null && callDialog == null && !placingCall) {
                    try {
                        val call = active.incomingPrivateCall(event).optJSONObject("call")
                        if (call != null && visible && active === client && event == eventId)
                            offerIncoming(active, event, call)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Resume polling after a transient connection error.
                    }
                }
                delay(2_500)
            }
        }
    }

    override fun onStop() {
        visible = false
        flightNoticeObserver?.cancel()
        scheduleVisibleChannelReads()
        NearbyService.uiActive = false
        nearbyPanel.pause()
        nearbyObserver?.cancel()
        nearbyProvision?.cancel()
        if (conference != null && callPermission == null) finishConference(returnToRadio = false)
        updateBackHandler()
        workspaceSync?.cancel()
        channelPoll?.cancel()
        incomingPoll?.cancel()
        if (conference == null) callDialog?.dismiss()
        channelLoad?.cancel()
        voiceWarmup?.cancel()
        agentDialog?.dismiss()
        voiceService?.detachUi(voiceOwner)
        voiceObserver?.cancel()
        cueObserver?.cancel()
        voiceService = null
        if (voiceBound) {
            unbindService(voiceConnection)
            voiceBound = false
        }
        if (::pttButton.isInitialized) pttButton.isEnabled = false
        if (::joinButton.isInitialized) joinButton.isEnabled = false
        disconnectVideo()
        stopReplay()
        super.onStop()
    }

    override fun onRetainNonConfigurationInstance(): Any? {
        val connected = client ?: return null
        saveDraft()
        return Session(connected, events, voiceEventId, videoEnabled, archiveEnabled, section, role, eventId, drafts.toMap(),
            pendingActions.toMap(), pendingReports.toMap(), receiptScope, taskFilter, taskDetailKey, lastHeard, radio, groupBeforeDirect)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) voiceService?.stopPress(voiceOwner, cancel = true)
        scheduleVisibleChannelReads(restart = true)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val parent = parentSection()
        if (parent != null) showSection(parent) else super.onBackPressed()
    }

    private fun parentSection() = when (section) {
        Section.VIDEO, Section.MEMBERS -> Section.LIVE
        Section.LIVE -> if (client?.bound == true) Section.CHANNELS else null
        Section.TASK_DETAIL -> Section.TASKS
        Section.REPORT -> if (taskDetailKey == null) Section.TASKS else Section.TASK_DETAIL
        Section.RECORDS -> if (receiptScope == null) Section.LIVE else Section.TASK_DETAIL
        else -> null
    }

    private fun updateBackHandler() {
        if (Build.VERSION.SDK_INT < 33) return
        val callback = returnToParent ?: return
        val active = visible && client != null && parentSection() != null
        if (active == backRegistered) return
        if (active) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        }
        backRegistered = active
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NearbyPanel.RADIO_PERMISSION || requestCode == NearbyPanel.MICROPHONE_PERMISSION) {
            nearbyPanel.permissionResult(requestCode)
            updateChannelComposer()
            return
        }
        if (requestCode == BLUETOOTH_ROUTE_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                showConnectionStatus("蓝牙通话设备已授权。耳机连接后可在音频输出中选择。")
                chooseOutput()
            }
            return
        }
        if (requestCode == ARRIVAL_NOTIFICATION_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startArrivalWatch()
            else showConnectionStatus("允许通知后可开启航班值班提醒")
            return
        }
        if (requestCode == CONFERENCE_PERMISSION) {
            val handler = callPermission
            callPermission = null
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) handler?.invoke()
            else {
                finishConference()
                showConnectionStatus("允许所需设备权限后可接通")
            }
            return
        }
        if (requestCode == QR_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) launchQrCamera()
            return
        }
        if (requestCode == PHOTO_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) launchPhotoCamera()
            else pendingPhoto = null
            return
        }
        if (requestCode != CAMERA_PERMISSION_REQUEST) return
        val requestedEvent = cameraPermissionEvent ?: return
        cameraPermissionEvent = null
        if (requestedEvent != eventId || !canUseVideo()) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startVideoCamera()
        } else {
            updateVideo(videoState.copy(text = "未获得相机权限，仍可观看画面"))
        }
    }

    override fun onDestroy() {
        video?.close()
        player?.pause()
        scope.cancel()
        super.onDestroy()
    }

    private fun showConnectionForm(legacy: Boolean = false, advanced: Boolean = false) {
        workspaceHeader.visibility = View.GONE
        radioHeader.visibility = View.GONE
        qrfDock.visibility = View.GONE
        voiceDock.visibility = View.GONE
        replayBar.visibility = View.GONE
        qrReceiver = null
        content.removeAllViews()
        content.setPadding(dp(20), dp(20), dp(20), dp(20))
        scroll.scrollTo(0, 0)
        addHeading("现场对讲与任务")
        val login = card(content)
        login.addView(text(if (legacy) "连接试点服务" else if (advanced) "手动绑定" else "登录", 22, INK, true))
        if (!legacy && !advanced) {
            put(login, text("首次登录请打开调度员发给你的个人登录包。", 14, MUTED), 8)
            put(login, button("打开个人登录包", primary = true).apply {
                setOnClickListener { chooseLoginPackage() }
            }, 16)
            val passkey = button("使用 Passkey 登录").apply {
                visibility = View.GONE
                setOnClickListener { signInWithPasskey() }
            }
            put(login, passkey, 8)
            if (Build.VERSION.SDK_INT >= 28) scope.launch {
                try {
                    val status = OpsClient(DEFAULT_ADDRESS, "", false, "").passkeyStatus()
                    if (passkey.isAttachedToWindow && status.optBoolean("enabled")) passkey.visibility = View.VISIBLE
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A login package still carries the server and enrollment instructions.
                }
            }
            feedback = liveText()
            put(login, feedback, 5)
            put(login, linkButton("其他登录方式").apply {
                setOnClickListener { if (loginJob?.isActive != true) showConnectionForm(advanced = true) }
            }, 7)
            return
        }
        val address = input("服务器地址", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply {
            setText(getPreferences(MODE_PRIVATE).getString("server", DEFAULT_ADDRESS))
            isSingleLine = true
        }
        val radioName = input("对讲呼号", InputType.TYPE_CLASS_TEXT).apply {
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(32))
            setText(callsign)
        }
        val code = input(if (legacy) "试点访问码" else "一次性绑定码",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            isSingleLine = true
            transformationMethod = PasswordTransformationMethod.getInstance()
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        put(login, address, 10)
        address.visibility = if (legacy) View.VISIBLE else View.GONE
        if (legacy) put(login, radioName, 8)
        put(login, code, 8)
        val connect = button(if (legacy) "连接" else "核对人员", primary = true)
        put(login, connect, 12)
        if (!legacy) {
            qrReceiver = { value ->
                if (client == null && connect.isEnabled) {
                    code.setText(value)
                    connect.performClick()
                }
            }
            val qrControls = row(login)
            addWeighted(qrControls, button("扫码绑定").apply {
                setOnClickListener {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                        launchQrCamera()
                    else requestPermissions(arrayOf(Manifest.permission.CAMERA), QR_PERMISSION)
                }
            })
            addWeighted(qrControls, button("读取二维码图片").apply {
                setOnClickListener {
                    try {
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), QR_IMAGE)
                    } catch (_: android.content.ActivityNotFoundException) {
                        feedback.text = "设备没有可用的图片选择器"
                    }
                }
            }, 8)
        }
        feedback = liveText()
        put(login, feedback, 5)
        val controls = row(login)
        if (!legacy) addWeighted(controls, button("服务器设置").apply {
            setOnClickListener { address.visibility = if (address.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        })
        addWeighted(controls, button(if (legacy) "绑定人员" else "试点连接").apply {
            setOnClickListener {
                loginJob?.cancel()
                showConnectionForm(!legacy, advanced = !legacy)
            }
        }, if (legacy) 0 else 8)
        put(login, linkButton("返回登录").apply {
            setOnClickListener { if (loginJob?.isActive != true) showConnectionForm() }
        }, 7)
        connect.setOnClickListener {
            val server = address.text.toString().trim()
            val credential = code.text.toString().trim()
            val nextCallsign = radioName.text.toString().trim().ifBlank(::deviceCallsign)
            val next = try {
                OpsClient(server, if (legacy) credential else "", BuildConfig.DEBUG, nextCallsign)
            } catch (_: IllegalArgumentException) {
                feedback.text = "请输入有效的 HTTPS 服务地址"
                return@setOnClickListener
            }
            if (credential.isBlank()) {
                feedback.text = "请输入访问码"
                return@setOnClickListener
            }
            connect.isEnabled = false
            loginJob = scope.launch {
                try {
                    if (legacy) {
                        val bootstrap = next.bootstrap()
                        code.text.clear()
                        enterWorkspace(next, bootstrap, nextCallsign)
                    } else {
                        confirmInvitation(next, credential)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    feedback.text = errorMessage(error)
                } finally {
                    connect.isEnabled = true
                }
            }
        }
    }

    private fun explainAccountSwitch() {
        AlertDialog.Builder(this).setTitle("当前已有登录账号")
            .setMessage("如需切换人员，请先退出当前账号，再打开登录包。")
            .setPositiveButton("知道了", null).show()
    }

    @Suppress("DEPRECATION")
    private fun chooseLoginPackage() {
        if (loginJob?.isActive == true) return
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE), LOGIN_PACKAGE)
        } catch (_: android.content.ActivityNotFoundException) {
            feedback.text = "设备没有可用的文件选择器"
        }
    }

    private fun openLoginPackage(uri: Uri) {
        if (loginJob?.isActive == true || client != null) return
        loginJob = scope.launch {
            feedback.text = "正在核对登录包…"
            try {
                val servers = setOfNotNull(DEFAULT_ADDRESS, getPreferences(MODE_PRIVATE).getString("server", null))
                val login = withContext(Dispatchers.IO) {
                    require(uri.scheme == "content") { "请选择设备上的登录包文件" }
                    checkNotNull(contentResolver.openInputStream(uri)) { "无法读取登录包" }.use {
                        FieldLoginPackage.read(it, servers)
                    }
                }
                confirmInvitation(OpsClient(login.server, "", BuildConfig.DEBUG, ""), login.invite)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                feedback.text = when (error) {
                    is IllegalArgumentException -> error.message ?: "登录包无效"
                    is org.json.JSONException, is java.nio.charset.CharacterCodingException -> "此文件不是 Protocol X 登录包"
                    else -> errorMessage(error)
                }
            }
        }
    }

    private suspend fun confirmInvitation(service: OpsClient, invite: String) {
        val preview = service.inspectInvite(invite)
        val person = preview.getJSONObject("identity")
        val groups = preview.getJSONArray("groups").let { items ->
            (0 until items.length()).joinToString("、") { items.getString(it) }
        }
        val passkey = preview.optBoolean("passkey_required")
        feedback.text = ""
        AlertDialog.Builder(this).setTitle("确认登录人员")
            .setMessage(person.getString("name") + " · " + person.getString("callsign") + "\n" + groups)
            .setNegativeButton("取消", null)
            .setPositiveButton(if (passkey) "创建 Passkey" else "确认登录") { _, _ ->
                runFieldLogin(service, if (passkey) "正在登记 Passkey…" else "正在登录…") {
                    if (passkey) {
                        check(Build.VERSION.SDK_INT >= 28) { "此设备系统版本不支持 Passkey" }
                        FieldPasskey.signIn(this@MainActivity, service, invite)
                    } else service.bind(invite)
                }
            }.show()
    }

    private fun signInWithPasskey() {
        val service = OpsClient(DEFAULT_ADDRESS, "", false, "")
        runFieldLogin(service, "正在打开 Passkey…") {
            check(Build.VERSION.SDK_INT >= 28) { "此设备系统版本不支持 Passkey" }
            check(service.passkeyStatus().optBoolean("enabled")) { "服务器尚未启用 Passkey，请使用登录包" }
            FieldPasskey.signIn(this@MainActivity, service)
        }
    }

    private fun runFieldLogin(service: OpsClient, message: String, authenticate: suspend () -> JSONObject?) {
        if (loginJob?.isActive == true || client != null) return
        loginJob = scope.launch {
            feedback.text = message
            try {
                val result = authenticate()
                if (result == null) {
                    feedback.text = ""
                    return@launch
                }
                val token = result.getString("session_token")
                val bound = OpsClient(service.origin, token, BuildConfig.DEBUG, "")
                val bootstrap = bound.bootstrap()
                fieldSession.save(service.origin, token)
                enterWorkspace(bound, bootstrap, "")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                feedback.text = errorMessage(error)
            }
        }
    }

    private fun restoreFieldLogin() {
        val record = fieldSession.read() ?: return
        loginJob = scope.launch {
            try {
                val restored = OpsClient(record.first, record.second, BuildConfig.DEBUG, "")
                val cached = fieldOffline.bootstrap(restored)
                if (cached != null && client == null && fieldNotice?.action != ArrivalWatchService.OPEN_ARRIVAL) {
                    restored.applyBootstrap(cached)
                    enterWorkspace(restored, cached, "", cachedOnly = true)
                } else {
                    val bootstrap = restored.bootstrap()
                    if (client == null) enterWorkspace(restored, bootstrap, "")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is OpsException && error.status == 401) fieldSession.clear()
                if (client == null) feedback.text = errorMessage(error)
            }
        }
    }

    private fun enterWorkspace(connected: OpsClient, bootstrap: JSONObject, fallbackCallsign: String, cachedOnly: Boolean = false) {
        check(bootstrap.getString("mode") == "pilot") { "请连接已配置访问码的试点服务" }
        val available = bootstrap.getJSONArray("events").objects()
        check(available.isNotEmpty()) { "当前没有已分配活动" }
        events = available
        voiceEventId = bootstrap.optString("ptt_event_id").takeUnless { it.isBlank() || it == "null" }
        videoEnabled = bootstrap.optBoolean("video_enabled")
        archiveEnabled = bootstrap.optBoolean("voice_archive_enabled")
        role = connected.profile.getString("role")
        callsign = connected.profile.optString("callsign", fallbackCallsign)
        client = connected
        qrReceiver = null
        if (connected.bound) {
            if (!cachedOnly) runCatching { fieldOffline.saveBootstrap(connected, bootstrap) }
            queuedField = fieldOffline.pending(connected)
        }
        getPreferences(MODE_PRIVATE).edit().putString("server", connected.origin)
            .putString("callsign", callsign).apply()
        showWorkspace()
        enableArrivalWatch()
    }

    private fun showWorkspace(previousEvent: String? = null) {
        content.removeAllViews()
        content.setPadding(dp(16), dp(4), dp(16), dp(16))
        buildWorkspaceHeader()
        val livePanel = column()
        val channelsPanel = column()
        val tasksPanel = column()
        val detailPanel = column()
        val reportPanel = column()
        val recordsPanel = column()
        val videoPanel = column()
        val membersPanel = column()
        sections = mapOf(
            Section.LIVE to livePanel,
            Section.CHANNELS to channelsPanel,
            Section.TASKS to tasksPanel,
            Section.TASK_DETAIL to detailPanel,
            Section.REPORT to reportPanel,
            Section.RECORDS to recordsPanel,
            Section.VIDEO to videoPanel,
            Section.MEMBERS to membersPanel,
        )
        sections.values.forEach { content.addView(it) }

        buildVoicePage(livePanel)
        buildChannelDirectory(channelsPanel)
        createVideoPage(videoPanel)
        buildMembersPage(membersPanel)
        buildVoiceDock()

        val taskHeader = row(tasksPanel)
        addWeighted(taskHeader, text(if (role == "dispatcher") "调度任务" else "我的任务", 20, INK, true))
        dispatchButton = button("发任务", primary = true).apply {
            visibility = if (role == "dispatcher" && client?.fieldEnabled == true) View.VISIBLE else View.GONE
            setOnClickListener { showDispatch() }
        }
        taskHeader.addView(dispatchButton, LinearLayout.LayoutParams(WRAP, WRAP))
        taskHeader.addView(button("现场汇报").apply {
            setOnClickListener { openReport(null) }
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        taskContext = text("", 13, MUTED)
        put(tasksPanel, taskContext, 3)
        val filterBar = row(tasksPanel)
        taskFilters = TaskStage.entries.associateWith { stage ->
            button(stage.label).apply {
                setTextSize(12f)
                setPadding(dp(2), 0, dp(2), 0)
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    taskFilter = stage
                    renderTasks()
                }
                addWeighted(filterBar, this, if (stage == TaskStage.ALL) 0 else 4)
            }
        }
        tasksList = column()
        put(tasksPanel, tasksList, 5)

        put(detailPanel, backButton("返回任务"), 4)
        taskDetails = column()
        put(detailPanel, taskDetails, 3)
        actionFeedback = liveText()
        put(detailPanel, actionFeedback, 5)

        put(reportPanel, backButton("返回任务"), 4)
        val reporting = card(reportPanel)

        reporting.addView(text("现场汇报", 21, INK, true))
        taskSelector = dropdown(listOf("未关联任务"))
        put(reporting, taskSelector, 6)
        reportField = input("填写现场情况", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 2
            maxLines = 5
            filters = arrayOf(InputFilter.LengthFilter(4000))
        }
        put(reporting, reportField, 8)
        reportButton = button("发送汇报", primary = true)
        reportButton.setOnClickListener { report() }
        put(reporting, reportButton, 8)
        reportFeedback = liveText()
        put(reporting, reportFeedback, 6)
        put(recordsPanel, backButton("返回"), 4)
        val timeline = card(recordsPanel)
        recordHeading = text("", 22, INK, true)
        timeline.addView(recordHeading)
        recordContext = text("", 14, MUTED)
        put(timeline, recordContext, 4)
        messagesList = column()
        put(timeline, messagesList, 6)

        replayBar.removeAllViews()
        replayFeedback = liveText()
        addWeighted(replayBar, replayFeedback)
        replayStop = button("停止回放").apply {
            setOnClickListener { stopReplay() }
        }
        replayBar.addView(replayStop, LinearLayout.LayoutParams(WRAP, WRAP))
        showReplayStatus("")

        val requestedArrival = fieldNotice?.takeIf { it.action == ArrivalWatchService.OPEN_ARRIVAL &&
            it.getStringExtra("arrival_origin") == client?.origin }?.getStringExtra("field_event")
        val lastActivity = client?.takeIf { it.bound }?.let {
            getPreferences(MODE_PRIVATE).getString("event-" + it.localSessionKey, null)
        }
        val candidates = listOfNotNull(requestedArrival, previousEvent, lastActivity, voiceEventId)
        val initial = candidates.firstNotNullOfOrNull { candidate ->
            events.indexOfFirst { it.getString("id") == candidate }.takeIf { it >= 0 }
        } ?: 0
        val selected = events[initial].getString("id")
        if (previousEvent == null) section = if (client?.bound == true) Section.CHANNELS
            else if (selected != voiceEventId) Section.TASKS else Section.LIVE
        selectEvent(selected, keepContext = selected == previousEvent)
    }

    private fun buildWorkspaceHeader() {
        workspaceHeader.removeAllViews()
        workspaceHeader.setPadding(dp(16), dp(8), dp(16), dp(8))
        workspaceHeader.setBackgroundColor(BACKGROUND)
        val top = row(workspaceHeader)
        val mark = text("X", 18, ON_ACCENT, true).apply {
            gravity = Gravity.CENTER
            background = panelColor(ACTIVE, 9, ACTIVE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        top.addView(mark, LinearLayout.LayoutParams(dp(28), dp(28)))
        val identity = column()
        identity.addView(text(getString(R.string.app_name), 20, INK, true).apply { letterSpacing = -0.025f })
        val roleName = if (role == "dispatcher") "中控调度" else "现场人员"
        val person = client?.profile?.optString("name").orEmpty()
        identity.addView(text(if (person.isBlank() || person == callsign) "$callsign · $roleName"
            else "$person · $callsign · $roleName", 12, MUTED))
        addWeighted(top, identity, 8)
        workspaceMenu = button("更多").apply {
            setOnClickListener { showWorkspaceMenu() }
        }
        top.addView(workspaceMenu, LinearLayout.LayoutParams(WRAP, WRAP))
        val navigation = row(workspaceHeader).apply {
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = panelColor(PANEL, 13, CARD_BORDER)
        }
        sectionTabs = listOf(Section.LIVE, Section.TASKS).associateWith { area ->
            button(area.label).apply {
                setOnClickListener { showSection(if (area == Section.LIVE && client?.bound == true) Section.CHANNELS else area) }
                addWeighted(navigation, this, if (area == Section.LIVE) 0 else 8)
            }
        }
        connectionStatus = liveText().apply { visibility = View.GONE }
        put(workspaceHeader, connectionStatus, 5)
        workspaceHeader.visibility = View.VISIBLE
    }

    private fun showWorkspaceMenu() {
        if (mutationPending) return
        val output = voiceService?.state?.value?.takeIf { it.workspace?.eventId == eventId && eventId != null }
            ?.let { "音频输出 · " + it.output }
        val manage = if (role == "dispatcher" && client?.fieldEnabled == true) "人员与频道" else null
        val exitRadio = if (voiceState != VoiceState.OFFLINE) "离开语音" else null
        val retrySync = if (workspaceOnline) null else "重试同步"
        val choices = listOfNotNull("切换活动", manage, output, exitRadio, retrySync, "关于 Protocol X", "退出登录").toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setItems(choices) { _, index ->
                when (choices[index]) {
                    "切换活动" -> showActivityPicker()
                    "人员与频道" -> showFieldManagement()
                    "离开语音" -> disconnectVoice("可随时接入语音")
                    "关于 Protocol X" -> showAbout()
                    "重试同步" -> refresh()
                    "退出登录" -> logout()
                    else -> chooseOutput()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showAbout() {
        val panel = column().apply { setPadding(dp(22), dp(8), dp(22), dp(10)) }
        put(panel, text("版本 ${BuildConfig.VERSION_NAME} · 构建 ${BuildConfig.VERSION_CODE}", 16, INK, true), 2)
        val status = liveText()
        put(panel, status, 8)
        var pendingRelease: AppRelease? = null
        var downloaded: File? = null
        var operation: Job? = null
        val dialog = AlertDialog.Builder(this).setTitle("关于 Protocol X").setView(panel)
            .setNegativeButton("关闭", null).setPositiveButton("检查更新", null).create()
        dialog.setOnDismissListener { operation?.cancel() }
        dialog.show()
        val action = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        action.setOnClickListener {
            val local = downloaded
            if (local != null) {
                runCatching {
                    if (appUpdates.canInstall()) startActivity(appUpdates.installIntent(local))
                    else {
                        status.text = "在系统设置允许 Protocol X 安装应用，返回后点安装更新。"
                        startActivity(appUpdates.permissionIntent())
                    }
                }.onFailure { status.text = "系统安装页无法打开" }
                return@setOnClickListener
            }
            val release = pendingRelease
            action.isEnabled = false
            status.text = if (release == null) "正在检查 GitHub Releases…" else "正在下载 0%"
            operation = scope.launch {
                try {
                    if (release == null) {
                        pendingRelease = appUpdates.latest()?.takeIf { it.isNewerThan(BuildConfig.VERSION_CODE.toLong()) }
                        val available = pendingRelease
                        if (available == null) status.text = "当前版本已是最新"
                        else {
                            val size = (available.bytes + 1_048_575) / 1_048_576
                            status.text = "发现 ${available.version} · 约 ${size} MB"
                            action.text = "下载更新"
                        }
                    } else {
                        downloaded = appUpdates.download(release) { percent ->
                            withContext(Dispatchers.Main) { status.text = "正在下载 $percent%" }
                        }
                        status.text = "下载完成，已验证应用签名。"
                        action.text = "安装更新"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    status.text = if (release == null) "暂时无法检查更新，请重试" else "下载或校验失败，请重试"
                } finally {
                    if (dialog.isShowing) action.isEnabled = true
                }
            }
        }
    }

    private fun enableArrivalWatch() {
        if (!visible || client?.bound != true || role != "staff" || ArrivalWatchService.running) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            val settings = getPreferences(MODE_PRIVATE)
            if (settings.getBoolean("asked_flight_notifications", false)) return
            settings.edit().putBoolean("asked_flight_notifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), ARRIVAL_NOTIFICATION_PERMISSION)
        } else startArrivalWatch()
    }

    private fun startArrivalWatch() {
        if (!visible || client?.bound != true || role != "staff" || ArrivalWatchService.running) return
        ArrivalWatchService.createChannels(getSystemService(android.app.NotificationManager::class.java))
        if (!ArrivalWatchService.canNotify(this)) return
        runCatching { startForegroundService(Intent(this, ArrivalWatchService::class.java)) }
    }

    private fun showActivityPicker() {
        val current = events.indexOfFirst { it.optString("id") == eventId }
        AlertDialog.Builder(this)
            .setTitle("切换活动")
            .setSingleChoiceItems(events.map { it.getString("title") }.toTypedArray(), current) { dialog, index ->
                val target = events[index].getString("id")
                if (target != eventId) selectEvent(target)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildVoicePage(container: LinearLayout) {
        radioHeader.removeAllViews()
        val channel = column().apply {
            background = panelColor(CHANNEL, 12, CARD_BORDER)
            setPadding(dp(10), dp(2), dp(10), dp(3))
            put(radioHeader, this, 4)
        }
        val header = row(channel)
        channelName = linkButton("").apply {
            setTextSize(20f)
            setTextColor(INK)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            letterSpacing = -0.02f
            setOnClickListener {
                if (!mutationPending) {
                    if (client?.bound == true) showChannelPicker() else showActivityPicker()
                }
            }
        }
        addWeighted(header, channelName)
        channelMeta = linkButton("").apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextSize(13f)
            setOnClickListener { showMembers() }
        }
        header.addView(channelMeta, LinearLayout.LayoutParams(WRAP, WRAP))
        val statusRow = row(channel)
        channelConnection = text("", 12, ACCENT, true).apply {
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = panelColor(DOCK, 8, CARD_BORDER)
        }
        statusRow.addView(channelConnection, LinearLayout.LayoutParams(WRAP, WRAP))
        channelState = text("", 13, MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        addWeighted(statusRow, channelState, 8)
        joinButton = linkButton("接入语音").apply {
            setTextSize(13f)
            setOnClickListener { connectVoice() }
        }
        statusRow.addView(joinButton)
        channelMute = linkButton("静音").apply {
            setTextSize(13f)
            setOnClickListener { voiceService?.toggleReceiveMute(voiceOwner) }
        }
        statusRow.addView(channelMute)
        leaveButton = linkButton("离开语音").apply {
            setTextSize(13f)
            setOnClickListener { disconnectVoice("可随时接入语音") }
        }
        statusRow.addView(leaveButton)
        val tools = row(radioHeader)
        addWeighted(tools, text("频道动态", 19, INK, true))
        val channelTools = linkButton("频道工具 ›").apply { setOnClickListener { showRadioTools() } }
        tools.addView(channelTools, LinearLayout.LayoutParams(WRAP, WRAP))
        conversation = column()
        put(container, conversation, 3)
        if (::channelText.isInitialized) saveChannelDraft()
        channelComposer = column()
        channelWrite = linkButton("写消息").apply { setOnClickListener { setChannelEditorOpen(true) } }
        put(channelComposer, channelWrite, 4)
        channelEditor = column()
        val editorTools = row(channelEditor)
        val channelOptionsButton = linkButton("消息选项").apply {
            setOnClickListener {
                channelOptionsOpen = !channelOptionsOpen
                updateChannelComposer()
            }
        }
        addWeighted(editorTools, channelOptionsButton)
        editorTools.addView(linkButton("收起").apply {
            setOnClickListener { setChannelEditorOpen(false) }
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        channelOptions = column()
        val choices = row(channelOptions)
        channelRecipientPicker = linkButton("整个频道  ›").apply { setOnClickListener { chooseChannelRecipient() } }
        addWeighted(choices, channelRecipientPicker)
        channelAttach = linkButton("附照片").apply { setOnClickListener { pickNearbyPhoto() } }
        choices.addView(channelAttach, LinearLayout.LayoutParams(WRAP, WRAP))
        put(channelEditor, channelOptions, 0)
        val inputRow = row(channelEditor)
        channelText = input("发给当前频道", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            filters = arrayOf(InputFilter.LengthFilter(NEARBY_TEXT_LIMIT))
            setSingleLine(false)
            maxLines = 2
        }
        addWeighted(inputRow, channelText)
        channelSend = button("发送", primary = true).apply { setOnClickListener { sendChannelText() } }
        inputRow.addView(channelSend, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        channelSendStatus = text("", 13, MUTED)
        put(channelEditor, channelSendStatus, 4)
        channelRadioPermission = linkButton("允许附近设备访问").apply {
            setOnClickListener { nearbyPanel.grantRadios() }
        }
        put(channelEditor, channelRadioPermission, 2)
        put(channelComposer, channelEditor, 0)
        channelEditorOpen = false
        channelOptionsOpen = false
        channelDraftKey = null
        channelComposerScope = null
        channelText.doAfterTextChanged { updateChannelComposer() }
        put(container, channelComposer, 8)
        updateChannelComposer()
        responseButton = button("当前任务").apply {
            visibility = View.GONE
            maxLines = 2
            setTextSize(14f)
        }
        put(container, responseButton, 8)
        photoStatus = liveText()
    }

    private fun showRadioTools() {
        val actions = mutableListOf<Pair<String, () -> Unit>>("活动记录" to { openRecords() })
        if (radio?.kind == "group" && client?.bound == true)
            actions.add("通信诊断" to { showNearbyDiagnostics() })
        val previous = groupBeforeDirect
        if (client?.fieldEnabled == true) {
            if (radio?.kind == "direct" && previous != null && groups().any { it.optString("id") == previous.id }) {
                actions.add("返回群组" to { activateRadio(previous) })
            }
            if (radio != null) actions.add("频道成员" to { showMembers() })
            if (role == "dispatcher" && radio?.kind == "group") {
                actions.add("频道访问权限" to { showChannelAccess() })
                actions.add("设置频道语音" to { chooseChannelVoice() })
                actions.add("语音操作日志" to { showVoiceLog() })
            }
        }
        if (client?.bound == true && radio?.kind == "direct" && client?.privateCallsEnabled == true)
            actions.add("语音／视讯" to { showConferenceOptions() })
        if (videoAvailable()) actions.add("现场画面" to { showSection(Section.VIDEO) })
        if (voiceAvailable()) actions.add("音频输出" to { chooseOutput() })
        if (voiceAvailable()) {
            val captionName = if (OfflineCaptions.forApp(this).installed) "离线字幕 · 已安装" else "下载离线字幕包"
            actions.add(captionName to { manageOfflineCaptions() })
        } else if (client?.bound == true) {
            actions.add("现场汇报" to { openReport(null) })
        }
        AlertDialog.Builder(this).setTitle("频道工具")
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second() }
            .setNegativeButton("关闭", null).show()
    }

    private fun buildVoiceDock() {
        voiceDock.removeAllViews()
        voiceDock.setPadding(dp(16), dp(6), dp(16), dp(12))
        voiceDock.setBackgroundColor(DOCK)
        dockSummary = row(voiceDock)
        dockTarget = text("", 14, INK, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addWeighted(dockSummary, dockTarget)
        speakerLabel = text("", 13, ACCENT)
        addWeighted(dockSummary, speakerLabel, 8)
        voiceLabel = liveText()
        pttHint = text("", 13, MUTED).apply { gravity = Gravity.CENTER }
        put(voiceDock, pttHint, 3)
        pttButton = createPushToTalkButton()
        put(voiceDock, pttButton, 6)
    }

    private fun createPushToTalkButton() = button("按住说话 · PTT", primary = true).apply {
        minimumHeight = dp(92)
        minHeight = dp(92)
        background = RippleDrawable(ColorStateList.valueOf(0x32FFFFFF), panelColor(ACTIVE, 46, ACTIVE), null)
        setTypeface(typeface, Typeface.BOLD)
        setTextSize(22f)
        contentDescription = "按住说话，松开结束"
        var activePointer = MotionEvent.INVALID_POINTER_ID
        var nearbyHold = false
        setOnTouchListener { view, motion ->
            when (motion.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!view.isEnabled) return@setOnTouchListener false
                    activePointer = motion.getPointerId(0)
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    nearbyHold = nearbyVoiceFallback()
                    if (nearbyHold) {
                        stopReplay()
                        nearbyPanel.startHold(pttHint)
                    } else startPress()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                    if (motion.actionMasked != MotionEvent.ACTION_POINTER_UP ||
                        motion.getPointerId(motion.actionIndex) == activePointer) {
                        activePointer = MotionEvent.INVALID_POINTER_ID
                        val cancelled = motion.actionMasked == MotionEvent.ACTION_CANCEL
                        if (nearbyHold) nearbyPanel.stopHold(pttHint, cancelled)
                        else voiceService?.stopPress(voiceOwner, cancel = cancelled)
                        nearbyHold = false
                        view.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
            true
        }
        setOnKeyListener { _, key, press ->
            if (key !in setOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER)) {
                return@setOnKeyListener false
            }
            if (press.action == KeyEvent.ACTION_DOWN && press.repeatCount == 0) {
                nearbyHold = nearbyVoiceFallback()
                if (nearbyHold) {
                    stopReplay()
                    nearbyPanel.startHold(pttHint)
                } else startPress()
            }
            if (press.action == KeyEvent.ACTION_UP) {
                if (nearbyHold) nearbyPanel.stopHold(pttHint, press.isCanceled)
                else voiceService?.stopPress(voiceOwner)
                nearbyHold = false
            }
            true
        }
    }

    private fun addHeading(subtitle: String) {
        val rule = View(this).apply { setBackgroundColor(ACCENT) }
        content.addView(rule, LinearLayout.LayoutParams(dp(30), dp(3)))
        put(content, text(getString(R.string.app_name), 34, INK, true), 9)
        put(content, text(subtitle, 14, MUTED), 3)
    }

    private fun showSection(next: Section) {
        val open = if (next == Section.VIDEO && !videoAvailable()) Section.LIVE else next
        if (open != Section.VIDEO) disconnectVideo()
        if (section != open) stopReplay()
        section = open
        updateBackHandler()
        sections.forEach { (area, panel) -> panel.visibility = if (area == open) View.VISIBLE else View.GONE }
        val selectedTab = when (open) {
            Section.RECORDS -> if (receiptScope == null) Section.LIVE else Section.TASKS
            Section.VIDEO, Section.MEMBERS, Section.CHANNELS -> Section.LIVE
            Section.TASK_DETAIL, Section.REPORT -> Section.TASKS
            else -> open
        }
        sectionTabs.forEach { (area, tab) ->
            tab.isSelected = area == selectedTab
            tab.backgroundTintList = ColorStateList.valueOf(if (tab.isSelected) CHANNEL else PANEL)
            tab.setTextColor(if (tab.isSelected) ACCENT else MUTED)
        }
        renderSpeaker()
        updateBottomControls()
        if (open == Section.TASK_DETAIL) renderTaskDetail() else qrfDock.visibility = View.GONE
        radioHeader.visibility = if (open == Section.LIVE) View.VISIBLE else View.GONE
        if (open == Section.LIVE) {
            syncChannel()
            scrollToLatestConversation()
        } else scroll.scrollTo(0, 0)
        if (open == Section.VIDEO) connectVideo()
        if (open == Section.MEMBERS) renderMembers()
        if (open == Section.CHANNELS) renderChannelDirectory()
        scheduleVisibleChannelReads(restart = true)
    }

    private fun selectEvent(target: String, keepContext: Boolean = false) {
        saveDraft()
        load?.cancel()
        val activeVoice = voiceService?.state?.value?.workspace
        if (activeVoice != null && (activeVoice.eventId != target || activeVoice.client !== client)) {
            disconnectVoice("未加入频道")
        }
        disconnectVideo()
        stopReplay()
        if (!keepContext) {
            lastHeard = null
            if (section == Section.RECORDS) section = if (receiptScope == null) Section.LIVE else Section.TASKS
            if (section == Section.TASK_DETAIL || section == Section.REPORT) section = Section.TASKS
            receiptScope = null
            taskFilter = TaskStage.ALL
            taskDetailKey = null
        }
        eventId = target
        client?.takeIf { it.bound }?.let {
            getPreferences(MODE_PRIVATE).edit().putString("event-" + it.localSessionKey, target).apply()
        }
        nearbyPanel.serverSupportsNearby = true
        nearbyProvision?.cancel()
        nearbyLastSync = 0L
        if (!keepContext) {
            onlineVoiceRequested = false
            onlineVoiceEverJoined = false
        }
        localVoiceClips = emptyList()
        channelLoad?.cancel()
        voiceWarmup?.cancel()
        dispatchInstructions = emptyList()
        agentIncidents = emptyList()
        agentActivity = emptyList()
        agentDialog?.dismiss()
        arrivalCards = emptyMap()
        arrivalView?.releasePhotos()
        arrivalView = null
        if (!keepContext) {
            radio = null
            groupBeforeDirect = null
            directoryData = client?.takeIf { it.bound }?.let { fieldOffline.directory(it, target) } ?: JSONObject()
            val selected = client?.let { getPreferences(MODE_PRIVATE)
                .getString("channel-" + fingerprint(it.localSessionKey + "|" + target), null) }
            radio = groups().map(::RadioTarget).firstOrNull { it.id == selected && it.kind == "group" }
                ?: groups().map(::RadioTarget).firstOrNull { it.kind == "group" }
        }
        selectNearbyChannel()
        historyJob?.cancel()
        olderBefore = null
        pagedVoice = false
        tasks = emptyList()
        messages = emptyList()
        voiceMessages = emptyList()
        clearChannelCursor()
        receipts = emptyList()
        hasWorkspace = false
        workspaceOnline = false
        client?.takeIf { it.bound }?.let { bound ->
            fieldOffline.work(bound, target)?.let { cached ->
                tasks = cached.getJSONArray("tasks").objects()
                receipts = cached.getJSONArray("receipts").objects()
                hasWorkspace = true
                showConnectionStatus("离线任务 · " + recordTime(cached.getString("saved_at")))
            }
            queuedField = fieldOffline.pending(bound)
        }
        timelineLimit = 20
        setTaskChoices()
        reportField.setText(drafts[target]?.text.orEmpty())
        reportButton.isEnabled = false
        actionFeedback.text = ""
        reportFeedback.text = ""
        tasksList.removeAllViews()
        messagesList.removeAllViews()
        updateRadioLabels()
        taskContext.text = currentEventTitle()
        applyVoiceSession(voiceService?.state?.value ?: VoiceSessionState())
        updateVideo(VideoState(VIDEO_IDLE_TEXT))
        renderTasks()
        renderConversation()
        renderTimeline()
        showSection(section)
        refresh()
        observeWorkspace()
    }

    private fun observeWorkspace() {
        workspaceSync?.cancel()
        val active = client ?: return
        val selected = eventId ?: return
        if (!visible) return
        workspaceSync = scope.launch {
            var revision: String? = null
            var lastSyncAt = android.os.SystemClock.elapsedRealtime()
            while (visible && active === client && selected == eventId) {
                val next = try {
                    active.workspaceRevision(selected, revision)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (next == null || next != revision || !workspaceOnline || now - lastSyncAt >= WORKSPACE_RECONCILE_MS) {
                    load?.join()
                    if (!visible || active !== client || selected != eventId) return@launch
                    refresh(automatic = true)
                    lastSyncAt = now
                }
                revision = next
                if (next == null) delay(WORKSPACE_RETRY_MS)
            }
        }
    }

    private fun refresh(automatic: Boolean = false) {
        val connected = client ?: return
        val selected = eventId ?: return
        if (mutationPending) return
        load?.cancel()
        if ((!automatic || !hasWorkspace) && (workspaceOnline || !hasWorkspace)) showConnectionStatus("正在同步活动…")
        load = scope.launch {
            try {
                val data = connected.workspace(selected)
                if (eventId != selected || client !== connected) return@launch
                val recovered = !workspaceOnline
                workspaceOnline = true
                if (recovered) updateVoice(voiceState, voiceLabel.text.toString())
                if (connected.bound) runCatching { fieldOffline.saveWork(connected, selected, data) }
                var directoryChanged = false
                if (connected.fieldEnabled && (connected.bound || role == "dispatcher")) {
                    val refreshedDirectory = connected.directory(selected)
                    if (eventId != selected || client !== connected) return@launch
                    directoryChanged = directoryData.toString() != refreshedDirectory.toString()
                    directoryData = refreshedDirectory
                    if (connected.bound) {
                        if (directoryChanged) runCatching { fieldOffline.saveDirectory(connected, selected, refreshedDirectory) }
                        updateRadioSelection()
                        provisionNearby(connected, selected, directoryChanged || recovered)
                    }
                }
                if (eventId != selected || client !== connected) return@launch
                if (onlineVoiceRequested && voiceState == VoiceState.OFFLINE && radio?.kind == "group" &&
                    android.os.SystemClock.elapsedRealtime() - onlineVoiceAttemptAt >= 30_000) connectVoice()
                syncChannel()
                flushPhotoOutbox()
                flushFieldQueue(connected, selected)
                val selectedRef = if (hasWorkspace) selectedTask()?.getJSONObject("ref")?.refKey() else drafts[selected]?.subjectKey
                val newTasks = data.getJSONArray("tasks").objects()
                val cards = data.optJSONObject("arrival_desk")?.optJSONArray("cards")?.objects().orEmpty()
                    .associateBy { it.getString("id") }
                val receptionChanged = cards.mapValues { it.value.toString() } != arrivalCards.mapValues { it.value.toString() }
                arrivalCards = cards
                val newMessages = data.getJSONArray("messages").objects()
                val newVoiceMessages = if (connected.bound) voiceMessages else data.optJSONArray("voice_messages")?.objects().orEmpty()
                if (!connected.bound && archiveEnabled)
                    voiceService?.observeCaptionServer(connected, selected, null, newVoiceMessages)
                val newReceipts = data.getJSONArray("receipts").objects()
                val tasksChanged = !hasWorkspace || newTasks.map(JSONObject::toString) != tasks.map(JSONObject::toString)
                val timelineChanged = tasksChanged || newMessages.map(JSONObject::toString) != messages.map(JSONObject::toString) ||
                    newVoiceMessages.map(JSONObject::toString) != voiceMessages.map(JSONObject::toString) ||
                    newReceipts.map(JSONObject::toString) != receipts.map(JSONObject::toString)
                hasWorkspace = true
                if (tasksChanged || receptionChanged || directoryChanged) {
                    tasks = newTasks
                    setTaskChoices(selectedRef)
                    renderTasks()
                }
                if (timelineChanged || directoryChanged) {
                    messages = newMessages
                    voiceMessages = newVoiceMessages
                    receipts = newReceipts
                    renderTimeline()
                    renderConversation()
                }
                if ((timelineChanged || receptionChanged || directoryChanged) && section == Section.TASK_DETAIL) renderTaskDetail()
                reportButton.isEnabled = true
                showConnectionStatus("")
                followFieldNotice()
                if (connected.bound && role == "staff") {
                    for (task in newTasks) {
                        val response = task.optJSONObject("field_response") ?: continue
                        if (response.isNull("received_at")) {
                            connected.markReceived(selected, task.getJSONObject("ref").getString("id"))
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (connected.bound && error is OpsException && error.status == 401) {
                    logout()
                    feedback.text = "绑定已失效，请重新绑定"
                    return@launch
                }
                if (connected.bound && error is OpsException && error.status == 403) {
                    val latest = try {
                        connected.bootstrap().getJSONArray("events").objects()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (latest != null && activeEventRemoved(latest, selected)) return@launch
                }
                workspaceOnline = false
                if (!connected.bound && archiveEnabled)
                    voiceService?.observeCaptionServer(connected, selected, null, null)
                updateVoice(voiceState, voiceLabel.text.toString())
                val message = errorMessage(error)
                showConnectionStatus(if (hasWorkspace) "离线任务 · 显示上次确认记录\n$message" else message)
            }
        }
    }

    private fun activeEventRemoved(available: List<JSONObject>, selected: String): Boolean {
        if (available.any { it.optString("id") == selected }) return false
        events = available
        if (available.isEmpty()) {
            logout()
            feedback.text = "你目前没有可进入的工作频道"
        } else {
            selectEvent(available.first().getString("id"))
            showConnectionStatus("原活动访问权限已更新")
        }
        return true
    }

    private fun showConnectionStatus(message: String) {
        connectionStatus.text = message
        connectionStatus.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    private fun setTaskChoices(previous: String? = null) {
        setChoices(taskSelector, listOf("未关联任务") + tasks.map { it.getString("title") })
        val index = tasks.indexOfFirst { it.getJSONObject("ref").refKey() == previous }
        taskSelector.setSelection(index + 1)
    }

    private fun renderTasks() {
        val current = tasks.firstOrNull {
            it.optJSONObject("field_response") != null && it.optString("owner") == client?.profile?.optString("actor")
                && taskStage(it) in setOf(TaskStage.WAITING, TaskStage.ACTIVE)
        }
        responseButton.visibility = if (current == null) View.GONE else View.VISIBLE
        if (current != null) {
            responseButton.text = "当前任务 · " + current.getString("title")
            responseButton.setOnClickListener {
                taskDetailKey = current.getJSONObject("ref").refKey()
                showSection(Section.TASK_DETAIL)
            }
        }
        taskFilters.forEach { (stage, control) ->
            control.isSelected = taskFilter == stage
            control.backgroundTintList = ColorStateList.valueOf(if (control.isSelected) ACTIVE else PANEL)
            control.setTextColor(if (control.isSelected) ON_ACCENT else INK)
        }
        tasksList.removeAllViews()
        val filtered = tasks.filter { taskFilter == TaskStage.ALL || taskStage(it) == taskFilter }
        if (filtered.isEmpty()) {
            val message = if (!hasWorkspace) "正在同步任务…" else if (tasks.isEmpty()) "当前没有可见任务" else "暂无该状态的任务"
            put(tasksList, text(message, 15, MUTED), 10)
        }
        filtered.forEach { task ->
            val item = column().apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = RippleDrawable(ColorStateList.valueOf(0x22F6CA97), panelColor(PANEL, 10, CARD_BORDER), null)
                isFocusable = true
                setOnClickListener {
                    taskDetailKey = task.getJSONObject("ref").refKey()
                    actionFeedback.text = ""
                    showSection(Section.TASK_DETAIL)
                }
            }
            put(tasksList, item, 9)
            taskHeading(item, task, 16)
            put(item, text(taskMetadata(task), 12, MUTED), 4)
            val next = nextTaskAction(task)?.getString("label")
            val plan = arrivalCards[task.getJSONObject("ref").optString("id")]
            if (plan != null) {
                val players = plan.optJSONArray("players")?.objects().orEmpty().joinToString("、") { it.optString("name") }
                put(item, text("球员接待 · " + plan.optString("flight") + " · " + players, 13, ONLINE), 4)
            }
            put(item, text((if (plan != null) "查看接待卡" else next ?: "查看任务详情") + "  ›", 13, ACCENT), 6)
        }
    }

    private fun taskStage(task: JSONObject) = TaskStage.classify(
        task.optString("status") in setOf("assigned", "scheduled") ||
            task.getJSONArray("allowed_actions").objects().any { it.getString("id") == "acknowledge" },
        task.getJSONArray("progress").objects().map { it.getString("status") },
        declined = task.optString("status") == "declined",
    )

    private fun nextTaskAction(task: JSONObject) = task.getJSONArray("allowed_actions").objects().firstOrNull {
        it.getString("id") == "acknowledge" || it.getString("id") == "confirm_progress"
    }

    private fun taskMetadata(task: JSONObject) = listOf(
        fieldPersonLabel(task.optString("owner")).takeUnless(String::isBlank),
        task.optString("resource_label").takeUnless(String::isBlank),
        task.optString("pickup_at").takeUnless { it.isBlank() || it == "null" }?.let(::recordTime),
    ).filterNotNull().joinToString(" · ")

    private fun taskHeading(container: LinearLayout, task: JSONObject, size: Int) {
        val header = row(container)
        addWeighted(header, text(task.getString("title"), size, INK, true))
        val stage = taskStage(task)
        val style = when (stage) {
            TaskStage.WAITING, TaskStage.DECLINED -> Color.rgb(246, 216, 150) to Color.rgb(61, 48, 28)
            TaskStage.DONE -> ONLINE to Color.rgb(23, 57, 49)
            else -> ACCENT to CHANNEL
        }
        header.addView(text(stage.label, 12, style.first, true).apply {
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = panelColor(style.second, 5, style.second)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
    }

    private fun renderTaskDetail() {
        taskDetails.removeAllViews()
        qrfDock.visibility = View.GONE
        val task = tasks.firstOrNull { it.getJSONObject("ref").refKey() == taskDetailKey }
        if (task == null) {
            put(taskDetails, text(if (hasWorkspace) "任务当前不可见" else "正在同步任务…", 15, MUTED), 8)
            return
        }
        val ref = task.getJSONObject("ref")
        val key = ref.refKey()
        val arrival = arrivalCards[ref.optString("id")]
        val active = client
        val event = eventId
        if (arrival != null && active != null && event != null) {
            val planId = arrival.getString("id")
            val view = arrivalView?.takeIf { it.planId == planId } ?: ArrivalCardView(this, scope, active, event, planId)
                .also {
                    arrivalView?.releasePhotos()
                    arrivalView = it
                }
            view.background = panelColor(CHANNEL, 10, CARD_BORDER)
            view.bind(arrival)
            put(taskDetails, view, 6)
            if (role == "dispatcher" && active.bound && canReleaseArrival(arrival)) {
                put(taskDetails, button("发布接机任务", primary = true).apply {
                    setOnClickListener { confirmArrivalDispatch(arrival) }
                }, 6)
            }
        } else if (arrivalView != null) {
            arrivalView?.releasePhotos()
            arrivalView = null
        }
        val waiting = queuedField.filter { it.event == eventId && it.ref.refKey() == key }
        taskHeading(taskDetails, task, 20)
        put(taskDetails, text(taskMetadata(task), 13, MUTED), 5)
        task.optString("description").takeUnless(String::isBlank)?.let {
            put(taskDetails, text(it, 14, INK), 7)
        }
        val stage = taskStage(task)
        addTaskStep(taskDetails, if (task.optString("status") in setOf("scheduled", "accepted")) "确认负责" else "接单",
            when (stage) {
                TaskStage.WAITING -> "当前步骤"
                TaskStage.DECLINED -> "未接单"
                else -> "已确认"
            })
        task.getJSONArray("progress").objects().forEach { step ->
            val status = when {
                TaskStage.isCompleted(step.getString("status")) -> "已确认"
                stage !in setOf(TaskStage.WAITING, TaskStage.DECLINED) && step.getString("status") in setOf("current", "active", "in_progress") -> "当前步骤"
                else -> "待处理"
            }
            addTaskStep(taskDetails, step.getString("label"), status)
        }
        val qrfTask = task.has("field_response") && role == "staff"
        if (client?.bound == true && qrfTask) put(taskDetails, linkButton("附近求助 ›").apply {
            setOnClickListener {
                val selected = eventId ?: return@setOnClickListener
                val version = task.getInt("version")
                nearbyPanel.openAssistance { note ->
                    if (eventId == selected) submitQueued(selected, ref, version, HELP,
                        JSONObject().put("note", note), nearby = true)
                }
            }
        }, 4)
        val canReportArrival = arrival != null && role == "staff" && client?.bound == true
        val next = nextTaskAction(task)
        if (qrfTask) {
            qrfButton.text = next?.getString("label") ?: "当前无待确认动作"
            val version = task.getInt("version")
            val awaitingRefresh = receipts.any {
                it.optJSONObject("target_ref")?.refKey() == key &&
                    (it.optJSONObject("result")?.optInt("object_version") ?: 0) > version
            }
            qrfButton.isEnabled = next != null && !mutationPending && !awaitingRefresh && waiting.none { it.action != HELP }
            qrfButton.setOnClickListener {
                val selected = eventId
                if (next != null && selected != null && !mutationPending) {
                    submitAction(selected, task, next)
                }
            }
            qrfDock.visibility = View.VISIBLE
        } else if (next != null) {
            put(taskDetails, button(next.getString("label"), primary = true).apply {
                setOnClickListener { confirmAction(task, next) }
            }, 8)
        }
        val replacementAction = task.getJSONArray("allowed_actions").objects().firstOrNull { it.getString("id") == "replace_resource" }
        val replacements = task.optJSONArray("replacement_options")?.objects().orEmpty()
        if (replacementAction != null && replacements.isNotEmpty()) {
            put(taskDetails, button(replacementAction.getString("label")).apply {
                setOnClickListener { chooseReplacement(task, replacementAction, replacements) }
            }, 6)
        }
        receipts.filter { it.optJSONObject("target_ref")?.refKey() == key }
            .maxByOrNull { it.optString("created_at") }?.let { receipt ->
                val card = card(taskDetails)
                card.addView(text("最近回执", 14, INK, true))
                val result = receipt.optJSONObject("result")
                val description = when (receipt.optString("action_type")) {
                    "confirm_progress" -> "已确认 " + result?.optString("progress_label").orEmpty()
                    "dispatch_task" -> "已派给 " + result?.optString("owner_label").orEmpty()
                    "dispatch_arrival_task" -> "已发布接机任务"
                    "approve_pickup_scenario" -> "已确认并下达接机方案"
                    "receive_dispatch_instruction" -> "现场已确认收到指示"
                    "decline_response" -> "已标记无法响应"
                    "replace_resource" -> "已指派 " + result?.optString("resource_label").orEmpty()
                    "reschedule_pickup" -> "已调整接机时间"
                    "ack_qrf_exception" -> "中台已确认收到异常"
                    else -> "已确认接单"
                }
                put(card, text(receiptActor(receipt) + " · " + description +
                    " · " + recordTime(receipt.optString("created_at")), 13, MUTED), 4)
            }
        task.optJSONObject("field_response")?.let { response ->
            val delivery = response.optString("received_at").takeUnless { it.isBlank() || it == "null" }
            val values = listOf(response.optString("contact"), response.optString("due"),
                if (delivery == null) "已派发" else "终端已收到 " + recordTime(delivery)).filter(String::isNotBlank)
            put(taskDetails, text(values.joinToString(" · "), 14, ACCENT), 5)
            val place = response.optString("location")
            if (place.isNotBlank()) put(taskDetails, button("打开任务地点").apply {
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                            "geo:0,0?q=" + android.net.Uri.encode(place))))
                    } catch (_: android.content.ActivityNotFoundException) {
                        showConnectionStatus("设备没有可用的地图应用")
                    }
                }
            }, 5)
        }
        val decline = task.getJSONArray("allowed_actions").objects().firstOrNull { it.optString("id") == "decline_response" }
        if (decline != null) put(taskDetails, button("无法响应").apply {
            setOnClickListener { confirmAction(task, decline) }
        }, 5)
        if (client?.bound == true) {
            val photos = row(taskDetails)
            addWeighted(photos, button("拍照汇报").apply { setOnClickListener { takeTaskPhoto(task) } })
            addWeighted(photos, button("任务照片").apply { setOnClickListener { showTaskPhotos(task) } }, 8)
            (photoStatus.parent as? LinearLayout)?.removeView(photoStatus)
            put(taskDetails, photoStatus, 4)
        }
        for (entry in waiting) {
            val label = if (entry.action == HELP) "求助" else "任务确认"
            put(taskDetails, text(if (entry.conflict.isBlank()) "$label · 待同步" else "$label · 待核对 · " + entry.conflict,
                14, ACCENT), 5)
            if (entry.conflict.isNotBlank()) put(taskDetails, linkButton("放弃本机待核对操作").apply {
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity).setTitle("放弃本机待核对操作？")
                        .setNegativeButton("取消", null).setPositiveButton("放弃") { _, _ ->
                            fieldOffline.complete(entry)
                            client?.let { queuedField = fieldOffline.pending(it) }
                            renderTaskDetail()
                        }.show()
                }
            }, 0)
        }
        if (qrfTask || canReportArrival) put(taskDetails, button("打开工作频道").apply {
            setOnClickListener { showSection(Section.LIVE) }
        }, 8)
        if (qrfTask || canReportArrival || role == "dispatcher") {
            messages.asReversed().firstOrNull {
                it.optString("source") == "qrf_exception" && it.optJSONObject("subject_ref")?.refKey() == key
            }?.let { message ->
                val report = card(taskDetails)
                val reply = receipts.firstOrNull {
                    it.optString("action_type") == "ack_qrf_exception" &&
                        it.optJSONObject("result")?.optString("message_id") == message.optString("id")
                }
                val label = if (message.optString("request_kind") == "assistance") "请求协助" else "异常已提交"
                report.addView(text(if (reply == null) "$label · 等待调度确认"
                    else "调度已确认 · ${receiptActor(reply)}", 14, INK, true))
                put(report, text(message.getString("text"), 14, MUTED), 4)
                if (role == "dispatcher" && reply == null) put(report, button("确认收到回报", primary = true).apply {
                    setOnClickListener {
                        mutate(actionFeedback) { active, event ->
                            rememberReceipt(active.acknowledgeAssistance(event, message.getString("id")))
                            "已确认收到回报"
                        }
                    }
                }, 4)
            }
        }
        val controls = row(taskDetails)
        addWeighted(controls, button("本任务回执").apply {
            setOnClickListener { openRecords(ReceiptScope(key, task.getString("title"))) }
        })
        if (!qrfTask) addWeighted(controls, button("汇报此任务").apply {
            setOnClickListener { openReport(key) }
        }, 8)
    }

    private fun addTaskStep(container: LinearLayout, label: String, status: String) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = panelColor(if (status == "当前步骤") CHANNEL else PANEL, 7, CARD_BORDER)
        }
        addWeighted(line, text(label, 14, INK, status == "当前步骤"))
        line.addView(text(status, 12, if (status == "已确认") ONLINE else MUTED),
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
        put(container, line, 5)
    }

    private fun openReport(taskKey: String?) {
        if (mutationPending) return
        taskDetailKey = taskKey
        taskKey?.let(::setTaskChoices)
        showSection(Section.REPORT)
    }

    private fun chooseReplacement(task: JSONObject, action: JSONObject, options: List<JSONObject>) {
        if (mutationPending) return
        val selectedEvent = eventId ?: return
        val title = action.getString("label")
        AlertDialog.Builder(this).setTitle(title)
            .setItems(options.map { it.getString("label") }.toTypedArray()) { _, index ->
                val option = options[index]
                AlertDialog.Builder(this).setTitle(title)
                    .setMessage(task.getString("title") + "\n" + option.getString("label"))
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认") { _, _ ->
                        if (eventId == selectedEvent) {
                            submitAction(selectedEvent, task, action,
                                JSONObject().put("replacement_ref", option.getJSONObject("ref")))
                        }
                    }.show()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun openRecords(scope: ReceiptScope? = null) {
        receiptScope = scope
        timelineLimit = 20
        renderTimeline()
        showSection(Section.RECORDS)
    }

    private fun currentEventTitle(): String = events.firstOrNull { it.optString("id") == eventId }
        ?.optString("title").orEmpty()

    private fun renderTimeline() {
        messagesList.removeAllViews()
        val task = receiptScope
        recordHeading.text = if (task != null) "本任务回执" else "活动记录"
        recordContext.text = task?.title ?: currentEventTitle()
        val scopedRecords = if (task != null) {
            receipts.filter { it.optJSONObject("target_ref")?.refKey() == task.key }
        } else messages + if (client?.bound == true) emptyList() else voiceMessages
        val records = scopedRecords.sortedByDescending { it.optString("created_at") }
        if (records.isEmpty()) {
            val empty = if (!hasWorkspace) "正在同步记录…" else if (task != null) "这项任务还没有回执" else "暂无记录"
            put(messagesList, text(empty, 14, MUTED), 8)
        }
        val titles = tasks.associate { it.getJSONObject("ref").refKey() to it.getString("title") }
        records.take(timelineLimit).forEach { item ->
            val block = column().apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = panelColor(BACKGROUND, 10, CARD_BORDER)
            }
            put(messagesList, block, 12)
            if (item.has("track_sid")) {
                renderVoiceRecord(block, item)
                return@forEach
            }
            val type = item.optString("action_type")
            val result = item.optJSONObject("result")
            val headline = when (type) {
                "acknowledge" -> "接单回执"
                "confirm_progress" -> "现场确认回执"
                "replace_resource" -> "资源调整回执"
                "reschedule_pickup" -> "时间调整回执"
                "dispatch_task" -> "派单回执"
                "decline_response" -> "响应回执"
                "ack_qrf_exception" -> "中台回执"
                else -> if (item.optString("source") == "qrf_exception") "异常回报" else "文字汇报"
            }
            val actor = receiptActor(item, if (result == null) "author" else "actor")
            val timestamp = item.getString("created_at")
            val time = runCatching { RECORD_TIME.format(Instant.parse(timestamp)) }.getOrDefault(timestamp)
            block.addView(text("$headline · $actor · $time", 13, MUTED, true))
            val ref = item.optJSONObject(if (result == null) "subject_ref" else "target_ref")
            if (task == null && ref != null) put(block, text(titles[ref.refKey()] ?: ref.getString("id"), 14, MUTED), 2)
            val description = when (type) {
                "acknowledge" -> "已确认接收任务"
                "confirm_progress" -> "已确认 ${result?.optString("progress_label").orEmpty()}"
                "replace_resource" -> "已指派 ${result?.optString("resource_label").orEmpty()}"
                "reschedule_pickup" -> "已调整接机时间"
                "dispatch_task" -> "已派给 " + result?.optString("owner_label").orEmpty()
                "decline_response" -> "已标记无法响应"
                "ack_qrf_exception" -> "中台已确认收到异常"
                else -> item.getString("text")
            }
            put(block, text(description, 16, INK), 2)
        }
        if (records.size > timelineLimit) {
            val more = button("查看更早记录")
            more.setOnClickListener {
                timelineLimit += 20
                renderTimeline()
            }
            put(messagesList, more, 8)
        }
    }

    private fun clearChannelCursor() {
        channelCursor = null
        channelIncidentTracks = JSONObject()
    }

    private fun syncChannel(wait: Int = 0) {
        val connected = client ?: return
        val event = eventId ?: return
        val target = radio ?: return
        if (!visible || !connected.bound || channelLoad?.isActive == true) return
        channelLoad = scope.launch {
            try {
                val includeAudio = archiveEnabled
                val delta = connected.channelChanges(event, target, channelCursor, wait, includeAudio)
                val page = delta ?: if (includeAudio) connected.history(event, target)
                    else connected.channelDispatch(event, target)
                if (client !== connected || eventId != event || radio != target || !visible) return@launch
                if (includeAudio != archiveEnabled) {
                    clearChannelCursor()
                    return@launch
                }
                if (delta != null) {
                    channelCursor = delta.getString("cursor")
                    if (!delta.optBoolean("changed")) {
                        if (includeAudio) voiceService?.observeCaptionServer(connected, event, target, emptyList())
                        showNewestDispatchPrompt()
                        return@launch
                    }
                    delta.optJSONObject("incident_tracks")?.let { channelIncidentTracks = it }
                }
                val incoming = page.optJSONArray("items")?.objects().orEmpty()
                if (includeAudio) {
                    voiceService?.observeCaptionServer(connected, event, target,
                        if (page.optBoolean("enabled", true)) incoming else null)
                }
                val reset = delta?.optBoolean("reset") == true || delta?.optBoolean("voice_reset") == true
                if (reset) {
                    historyJob?.cancel()
                    pagedVoice = false
                }
                val removed = delta?.optJSONArray("removed")
                val removedIds = (0 until (removed?.length() ?: 0)).map { removed!!.getString(it) }.toSet()
                val speech = (incoming + if (reset) emptyList() else voiceMessages)
                    .distinctBy { it.optString("track_sid") }
                    .filterNot { it.optString("track_sid") in removedIds }
                    .map { item ->
                        if (delta == null) item else {
                            val incident = channelIncidentTracks.optJSONObject(item.optString("track_sid"))
                            if (incident?.toString() == item.optJSONObject("incident")?.toString()) item
                            else JSONObject(item.toString()).put("incident", incident)
                        }
                    }
                val instructions = page.optJSONArray("instructions")?.objects() ?: dispatchInstructions
                val suggestions = page.optJSONArray("incidents")?.objects() ?: agentIncidents
                val agentUpdates = page.optJSONArray("agent_activity")?.objects() ?: agentActivity
                if (!pagedVoice && page.has("next_before")) {
                    olderBefore = page.optString("next_before").takeUnless { it.isBlank() || it == "null" }
                }
                val changed = speech.map(JSONObject::toString) != voiceMessages.map(JSONObject::toString) ||
                    instructions.map(JSONObject::toString) != dispatchInstructions.map(JSONObject::toString) ||
                    suggestions.map(JSONObject::toString) != agentIncidents.map(JSONObject::toString) ||
                    agentUpdates.map(JSONObject::toString) != agentActivity.map(JSONObject::toString)
                voiceMessages = speech
                dispatchInstructions = instructions
                agentIncidents = suggestions
                agentActivity = agentUpdates
                if (changed) renderConversation()
                warmRecentOriginals(connected, event, target, incoming)
                showNewestDispatchPrompt()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (client === connected && eventId == event && radio == target && archiveEnabled)
                    voiceService?.observeCaptionServer(connected, event, target, null)
                // Existing channel records and cached audio stay readable.
            }
        }
    }

    private fun warmRecentOriginals(
        connected: OpsClient, event: String, target: RadioTarget, items: List<JSONObject>,
    ) {
        if (section != Section.LIVE || voiceWarmup?.isActive == true) return
        val now = android.os.SystemClock.elapsedRealtime()
        warmAttempts.entries.removeAll { now - it.value > 60_000 }
        val ready = items.filter { it.optInt("has_audio") == 1 && it.optInt("duration_ms") in 1..90_000 }
            .take(8).map { it.getString("track_sid") }
            .map { it to voiceCache.key(connected, event, target, it) }
            .filterNot { warmAttempts.containsKey(it.second) }
        if (ready.isEmpty()) return
        voiceWarmup = scope.launch(Dispatchers.IO) {
            for ((track, key) in ready) {
                withContext(Dispatchers.Main) { warmAttempts[key] = now }
                if (voiceCache.read(key) != null) continue
                try {
                    connected.streamOriginal(event, track, target) { input ->
                        val capture = CapturedClip(input)
                        val buffer = ByteArray(8192)
                        while (capture.read(buffer) != -1) coroutineContext.ensureActive()
                        val original = capture.bytes()
                        val pcm = PttWav.length(java.io.DataInputStream(original.inputStream()))
                        require(original.size == pcm + 44)
                        coroutineContext.ensureActive()
                        voiceCache.save(key, original)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A cold manual play can still open the stream immediately.
                }
            }
        }
    }

    private fun showNewestDispatchPrompt() {
        if (section != Section.LIVE || agentDialog != null || callDialog != null || player != null ||
            voiceState in setOf(VoiceState.REQUESTING, VoiceState.SPEAKING)) return
        val item = (if (role == "dispatcher") agentIncidents.firstOrNull()
                    else dispatchInstructions.lastOrNull { !it.optBoolean("received") }) ?: return
        val at = runCatching { Instant.parse(item.getString("created_at")) }.getOrNull() ?: return
        if (at.isBefore(Instant.now().minusSeconds(20 * 60))) return
        val seen = client?.localSessionKey.orEmpty() + ":" + item.getString("id")
        if (!shownIncidents.add(seen)) return
        if (role == "dispatcher") showAgentSuggestion(item) else showInstructionPrompt(item)
    }

    private fun showAgentSuggestion(item: JSONObject) {
        val active = client ?: return
        val event = eventId ?: return
        val target = radio ?: return
        if (role != "dispatcher" || agentDialog != null) return
        scope.launch {
            try {
                val current = active.channelDispatch(event, target).optJSONArray("incidents")?.objects()
                    ?.firstOrNull { it.optString("id") == item.optString("id") }
                if (active !== client || eventId != event || radio != target || !visible) return@launch
                if (current != null) showAgentSuggestionDetails(current)
                else showConnectionStatus("这条调度建议已更新，请查看频道动态")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (active === client && eventId == event && radio == target && visible) showConnectionStatus(errorMessage(error))
            }
        }
    }

    private fun showAgentSuggestionDetails(item: JSONObject) {
        val active = client ?: return
        val event = eventId ?: return
        val target = radio ?: return
        if (role != "dispatcher" || agentDialog != null) return
        val panel = column().apply { setPadding(dp(20), dp(4), dp(20), dp(8)) }
        put(panel, text(item.optString("text"), 15, INK), 4)
        val facts = listOf(
            item.optString("task_title").takeUnless { it.isBlank() || it == "null" },
            item.optString("status").takeUnless { it.isBlank() || it == "null" },
            item.optString("flight").takeUnless { it.isBlank() || it == "null" },
            item.optString("flight_state").takeUnless { it.isBlank() || it == "null" },
            item.optString("vehicle").takeUnless { it.isBlank() || it == "null" },
            item.optString("meeting_point").takeUnless { it.isBlank() || it == "null" },
        ).filterNotNull()
        if (facts.isNotEmpty()) put(panel, text(facts.joinToString(" · "), 13, MUTED), 8)
        val verified = item.optString("review_note")
        if (verified.isNotBlank()) put(panel, text(verified, 13, MUTED), 4)
        if (item.optBoolean("pickup_scenario")) {
            enterPickupComparison(active, event, target, item, panel)
            return
        }
        val dispatchable = item.optBoolean("dispatchable")
        val proposal = item.optJSONObject("vehicle_change")
        val draft = input("给一线的指示", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            setText(item.optString("next"))
            filters = arrayOf(InputFilter.LengthFilter(500))
            minLines = 2
            maxLines = 4
        }
        if (dispatchable) {
            val names = item.optJSONArray("recipients")
            val recipients = names?.let { (0 until it.length()).joinToString("、") { i -> it.getString(i) } }.orEmpty()
            put(panel, text("发送给 $recipients", 13, ACCENT, true), 9)
            put(panel, draft, 4)
        } else put(panel, text(item.optString("next"), 15, INK), 9)
        proposal?.let { put(panel, text(it.optString("title"), 14, ACCENT, true), 7) }
        val status = liveText()
        put(panel, status, 4)
        val dialog = AlertDialog.Builder(this).setTitle("Agent · " + item.optString("label"))
            .setView(panel).setNegativeButton("稍后", null).apply {
                if (dispatchable) setPositiveButton("发送指示", null)
                if (proposal != null) setNeutralButton("确认换车", null)
            }.create()
        agentDialog = dialog
        dialog.setOnDismissListener { if (agentDialog === dialog) agentDialog = null }
        dialog.show()
        val instructionKey = UUID.randomUUID().toString()
        val actionKey = UUID.randomUUID().toString()

        fun send(changeVehicle: Boolean) {
            if (active !== client || event != eventId || target != radio) return
            val instructions = draft.text.toString().trim()
            if (!changeVehicle && instructions.isBlank()) {
                status.text = "请填写指示"
                return
            }
            val controls = listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL)
            controls.forEach { dialog.getButton(it)?.isEnabled = false }
            status.text = "正在发送给现场…"
            scope.launch {
                try {
                    if (changeVehicle && proposal != null) {
                        active.action(event, proposal.getString("action_type"), proposal.getJSONObject("target_ref"),
                            proposal.getInt("expected_version"), actionKey, proposal.getJSONObject("parameters"), proposal.getString("id"))
                    } else {
                        active.sendInstruction(event, target, item.getString("id"), instructions, item.getInt("version"), instructionKey)
                    }
                    dialog.dismiss()
                    syncChannel()
                    refresh(automatic = true)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (dialog.isShowing) {
                        status.text = errorMessage(error)
                        controls.forEach { dialog.getButton(it)?.isEnabled = true }
                    }
                    if (error is OpsException && error.status == 409) syncChannel()
                }
            }
        }
        if (dispatchable) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { send(false) }
        if (proposal != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { send(true) }
    }

    private fun enterPickupComparison(active: OpsClient, event: String, target: RadioTarget,
                                      incident: JSONObject, panel: LinearLayout, previous: String = "") {
        val alternate = input("填写一个可通行的备选会合点", InputType.TYPE_CLASS_TEXT).apply {
            filters = arrayOf(InputFilter.LengthFilter(120))
            setText(previous)
        }
        put(panel, alternate, 8)
        val status = liveText()
        put(panel, status, 4)
        val dialog = AlertDialog.Builder(this).setTitle("Agent · " + incident.optString("label"))
            .setView(panel).setNegativeButton("稍后", null).setPositiveButton("比较接机方案", null).create()
        agentDialog = dialog
        dialog.setOnDismissListener { if (agentDialog === dialog) agentDialog = null }
        dialog.show()
        val compare = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        compare.setOnClickListener {
            val point = alternate.text.toString().trim()
            if (point.isBlank()) {
                alternate.error = "请填备选会合点"
                return@setOnClickListener
            }
            compare.isEnabled = false
            status.text = "正在比较…"
            scope.launch {
                try {
                    val run = active.pickupScenario(event, target, incident.getString("id"), point)
                    if (active !== client || event != eventId || target != radio || !dialog.isShowing) return@launch
                    agentDialog = null
                    dialog.dismiss()
                    showPickupComparison(active, event, target, incident, point, run)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    status.text = errorMessage(error)
                    compare.isEnabled = true
                }
            }
        }
    }

    private fun showPickupComparison(active: OpsClient, event: String, target: RadioTarget, incident: JSONObject,
                                     point: String, run: JSONObject) {
        val panel = column().apply { setPadding(dp(20), dp(8), dp(20), dp(14)) }
        val airportStatus = when (run.optString("flight_status")) {
            "at_gate" -> "已到机位"
            "landed" -> "已落地"
            "estimated" -> "已更新预计到达"
            else -> "按机场计划到达"
        }
        put(panel, text(run.getString("flight") + " · " + airportStatus + " · 机场 " +
            recordTime(run.getString("source_time")), 14, ACCENT, true), 3)
        put(panel, text("同一份状态 · " + recordTime(run.getString("at")) + " · 司机 " + run.getString("driver"),
            13, MUTED), 5)
        val choices = run.getJSONArray("options").objects()
        val buttons = android.widget.RadioGroup(this)
        val choiceById = mutableMapOf<Int, JSONObject>()
        for (option in choices) {
            val name = option.getString("label")
            val location = option.getString("meeting_point")
            val meet = recordTime(option.getString("meeting_from")) + "–" + recordTime(option.getString("meeting_to"))
            val vehicle = if (option.isNull("vehicle")) "无可用车辆" else {
                option.getString("vehicle") + " · " + option.getInt("vehicle_minutes") + " 分钟（" +
                    recordTime(option.getString("vehicle_from")) + "–" + recordTime(option.getString("vehicle_to")) + "）"
            }
            val impacts = option.getJSONArray("impacts").objects().joinToString("\n") {
                val overlap = if (it.isNull("overlap_minutes")) "时间待核对" else "重叠 " + it.getInt("overlap_minutes") + " 分钟"
                val shared = it.getJSONArray("shared").let { items ->
                    (0 until items.length()).joinToString("、") { index -> items.getString(index) }
                }
                it.getString("title") + " · " + shared + " · " + overlap
            }.ifBlank { "未发现冲突" }
            val blocked = option.optString("blocked").takeUnless { it.isBlank() || it == "null" }
            val condition = option.optString("condition").takeUnless { it.isBlank() || it == "null" }
            val radioButton = android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = listOfNotNull(name, "会合 $meet · $location", "车辆 $vehicle", "后续 $impacts", blocked, condition)
                    .joinToString("\n")
                textSize = 14f
                setTextColor(if (blocked == null) INK else MUTED)
                buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
                isEnabled = blocked == null
                setPadding(dp(3), dp(9), dp(3), dp(10))
            }
            choiceById[radioButton.id] = option
            buttons.addView(radioButton)
        }
        put(panel, buttons, 5)
        val assumptions = run.getJSONArray("assumptions").let { items ->
            (0 until items.length()).joinToString("\n") { items.getString(it) }
        }
        put(panel, text(assumptions, 12, MUTED), 8)
        val status = liveText()
        put(panel, status, 4)
        val dialog = AlertDialog.Builder(this).setTitle("接机方案")
            .setView(ScrollView(this).apply { addView(panel) }).setNegativeButton("取消", null)
            .setNeutralButton("重新比较", null).setPositiveButton("确认下达", null).create()
        agentDialog = dialog
        dialog.setOnDismissListener { if (agentDialog === dialog) agentDialog = null }
        dialog.show()
        val approve = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val retry = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        approve.isEnabled = false
        val requestId = UUID.randomUUID().toString()
        buttons.setOnCheckedChangeListener { _, choice -> approve.isEnabled = choice in choiceById }
        retry.setOnClickListener {
            val newPanel = column().apply { setPadding(dp(20), dp(4), dp(20), dp(8)) }
            put(newPanel, text(incident.optString("text"), 15, INK), 4)
            agentDialog = null
            dialog.dismiss()
            enterPickupComparison(active, event, target, incident, newPanel, point)
        }
        approve.setOnClickListener {
            val option = choiceById[buttons.checkedRadioButtonId] ?: return@setOnClickListener
            approve.isEnabled = false
            retry.isEnabled = false
            for (index in 0 until buttons.childCount) buttons.getChildAt(index).isEnabled = false
            status.text = "正在下达…"
            scope.launch {
                try {
                    active.approvePickupScenario(event, target, incident.getString("id"), run.getString("id"),
                        option.getString("id"), requestId)
                    dialog.dismiss()
                    syncChannel()
                    refresh(automatic = true)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    status.text = errorMessage(error)
                    approve.isEnabled = error !is OpsException || error.status != 409
                    retry.isEnabled = true
                }
            }
        }
    }

    private fun showInstructionPrompt(item: JSONObject) {
        if (role == "dispatcher" || agentDialog != null) return
        val dialog = AlertDialog.Builder(this).setTitle("中台指示").setMessage(item.getString("text"))
            .setNegativeButton("稍后", null).setPositiveButton("收到", null).create()
        agentDialog = dialog
        dialog.setOnDismissListener { if (agentDialog === dialog) agentDialog = null }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { acknowledgeInstruction(item, dialog) }
    }

    private fun acknowledgeInstruction(item: JSONObject, dialog: AlertDialog? = null) {
        fieldCall { active, event ->
            val control = dialog?.getButton(AlertDialog.BUTTON_POSITIVE)
            control?.isEnabled = false
            try {
                active.receiveInstruction(event, item.getString("id"))
                dialog?.dismiss()
                syncChannel()
            } finally {
                control?.isEnabled = true
            }
        }
    }

    private fun renderInstruction(block: LinearLayout, item: JSONObject) {
        block.background = panelColor(CHANNEL, 12, ACCENT)
        val dispatcher = item.optString("speaker").takeIf { it.isNotBlank() && it != "null" }
        val heading = listOfNotNull("中控调度", dispatcher, recordTime(item.getString("created_at")), "网络")
            .joinToString(" · ")
        put(block, text(heading, 13, NETWORK_ROUTE, true), 2)
        put(block, text(item.getString("text"), 16, INK), 6)
        if (role == "dispatcher") put(block, text(
            "已确认 ${item.optInt("received_count")}/${item.optJSONArray("recipients")?.length() ?: 0}",
            13, MUTED), 6)
        else if (item.optBoolean("received")) put(block, text("已确认收到", 13, ONLINE), 6)
        else put(block, button("收到").apply { setOnClickListener { acknowledgeInstruction(item) } }, 5)
    }

    private fun renderAgentActivity(bubble: LinearLayout, item: JSONObject) {
        bubble.addView(text("AI Agent · " + recordTime(item.getString("created_at")), 13, ACCENT, true))
        val suggestion = agentIncidents.firstOrNull { it.optString("id") == item.optString("id") }
        val label = item.optString("label")
        val update = if (item.optBoolean("dispatched")) "调度员已下达指示" else "待调度核对"
        put(bubble, text("$label · $update", 15, INK, true), 5)
        if (suggestion != null) {
            put(bubble, text(suggestion.optString("next"), 14, MUTED), 4)
            put(bubble, linkButton("查看调度建议").apply { setOnClickListener { showAgentSuggestion(suggestion) } }, 3)
        }
    }

    private fun renderConversation() {
        if (!::conversation.isInitialized) return
        updateChannelComposer()
        val followLatest = section == Section.LIVE && !scroll.canScrollVertically(1)
        unreadChannelText.clear()
        conversation.removeAllViews()
        if (olderBefore != null && client?.bound == true) {
            put(conversation, button("加载更早语音").apply { setOnClickListener { loadOlderVoice() } }, 5)
        }
        val activityText = if (client?.bound == true) emptyList() else messages.filterNot { it.has("voice_channel_id") }
        val recorded = voiceMessages.map { it.optString("track_sid") }.toSet()
        val localPreviews = if (archiveEnabled && workspaceOnline) emptyList() else localVoiceClips
        val previews = localPreviews.filter { it.trackSid !in recorded }.map { clip ->
            JSONObject().put("track_sid", clip.trackSid).put("created_at", clip.at)
                .put("speaker_name", if (clip.mine) "$callsign · " + if (role == "dispatcher") "调度员" else "工作人员" else clip.speaker)
                .put("status", "pending").put("duration_ms", clip.durationMs).put("has_audio", 1)
        }
        val agentRows = agentActivity.map { JSONObject(it.toString()).put("source", "agent_activity") }
        val current = NearbyService.session.value
        val active = client
        val event = eventId
        val target = radio
        val validNearby = active?.bound == true && event != null && target?.kind == "group" &&
            NearbyService.activeScope == nearbyScope(active.localSessionKey, event, target.id)
        val nearby = if (validNearby && current != null) current.messages.value.asSequence()
            .filterNot { it.isControl }
            .filter { !it.isPrivate || it.sender == current.sender || current.isPrivateAddressedToMe(it) }
            .toList().takeLast(24).mapNotNull { message ->
                val privateMessage = if (message.isPrivate) readPrivateMessage(message) ?: return@mapNotNull null else null
                val receipts = current.readReceipts(message.id)
                JSONObject().put("source", "nearby").put("created_at", Instant.ofEpochMilli(message.time).toString())
                    .put("id", message.id).put("name", message.name).put("mine", message.sender == current.sender)
                    .put("text", privateMessage?.text ?: message.text)
                    .put("audio", message.audio).put("assistance", message.assistance)
                    .put("delivery", message.delivery.name).put("synced", message.synced)
                    .put("private", message.isPrivate)
                    .put("photo", privateMessage?.photo?.let(java.util.Base64.getEncoder()::encodeToString))
                    .put("needs_receipt", message.confirmationRequired)
                    .put("read_by_me", receipts.any { it.actor == current.actor })
                    .put("read_count", receipts.size)
                    .put("read_by", receipts.map { it.name }.distinct().joinToString("、"))
                    .put("route", (if (message.received || message.sender != current.sender)
                        message.deliveredVia else message.attemptedVia)?.name)
            } else emptyList()
        val records = (activityText + voiceMessages + previews + dispatchInstructions + agentRows + nearby).sortedBy { item ->
            runCatching { Instant.parse(item.getString("created_at")) }.getOrDefault(Instant.EPOCH)
        }.let { if (client?.bound == true) it else it.takeLast(14) }
        if (records.isEmpty()) {
            val empty = if (hasWorkspace) "暂无频道动态" else "正在同步频道…"
            put(conversation, text(empty, 14, MUTED), 10)
        }
        val myCallsign = "$callsign · " + if (role == "dispatcher") "调度员" else "工作人员"
        records.forEach { item ->
            val name = item.optString("speaker_name")
            val mine = item.optBoolean("mine") || name.isNotEmpty() && name == myCallsign
            val bubble = conversationBubble(mine)
            if (item.optString("source") == "nearby") {
                renderNearbyRecord(bubble, item, mine)
            } else if (item.has("track_sid")) {
                renderVoiceRecord(bubble, item)
            } else if (item.optString("source") == "dispatch_instruction") {
                renderInstruction(bubble, item)
            } else if (item.optString("source") == "agent_activity") {
                renderAgentActivity(bubble, item)
            } else {
                val author = fieldPersonLabel(item.optString("author"))
                bubble.addView(text("$author · " + recordTime(item.getString("created_at")), 13, ACCENT, true))
                put(bubble, text(item.getString("text"), 16, INK), 5)
            }
        }
        if (followLatest) scrollToLatestConversation()
        scheduleVisibleChannelReads()
    }

    private fun scheduleVisibleChannelReads(restart: Boolean = false) {
        if (!::scroll.isInitialized) return
        val eligible = visible && section == Section.LIVE && hasWindowFocus() && unreadChannelText.isNotEmpty()
        if (restart || !eligible) {
            scroll.removeCallbacks(channelReadCheck)
            channelReadCheckScheduled = false
        }
        if (eligible && !channelReadCheckScheduled) {
            channelReadCheckScheduled = scroll.postDelayed(channelReadCheck, CHANNEL_READ_DWELL_MS)
        }
    }

    private fun markVisibleChannelTextRead() {
        if (!visible || section != Section.LIVE || !hasWindowFocus() ||
            scroll.windowVisibility != View.VISIBLE) return
        val viewport = Rect()
        if (!scroll.getGlobalVisibleRect(viewport)) return
        val visibleBody = Rect()
        val ids = unreadChannelText.filterValues { body ->
            body.isShown && body.getGlobalVisibleRect(visibleBody) && visibleBody.intersect(viewport) &&
                channelTextFullyVisible(visibleBody.height(), body.height, viewport.height())
        }.keys.toList()
        saveChannelReadReceipts(ids)
    }

    private fun saveChannelReadReceipts(ids: List<String>) {
        val session = nearbyPanel.currentSession() ?: return
        val fresh = ids.filter { pendingReadReceipts.add(it) }
        if (fresh.isEmpty()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) { fresh.forEach { session.markRead(it) } }
            } finally {
                pendingReadReceipts.removeAll(fresh)
            }
        }
    }

    private fun readPrivateMessage(message: NearbyMessage): NearbyPrivateContent? {
        val config = currentNearbyCredential() ?: return null
        val cacheKey = config.generation + "|" + message.id
        privateContent[cacheKey]?.let { return it }
        return runCatching {
            val identity = privateReader?.takeIf { it.first == config.account }?.second
                ?: NearbyPrivateIdentity(this, config.account).also { privateReader = config.account to it }
            NearbyPrivate.open(config, identity, message.actor, message.recipient, message.id, message.sealedPayload)
                ?.also {
                    if (privateContent.size >= 28) privateContent.remove(privateContent.keys.first())
                    privateContent[cacheKey] = it
                }
        }.getOrNull()
    }

    private fun renderNearbyRecord(bubble: LinearLayout, item: JSONObject, mine: Boolean) {
        val name = if (mine) "我" else item.optString("name")
        val time = recordTime(item.getString("created_at"))
        val route = nearbyRadio(item.optString("route"))
        val routeLabel = when (route) {
            NearbyRadio.WIFI -> "Wi-Fi Aware"
            NearbyRadio.BLE -> "蓝牙"
            null -> if (mine) null else "附近"
        }
        val routeColor = when (route) {
            NearbyRadio.WIFI -> WIFI_ROUTE
            NearbyRadio.BLE -> BLE_ROUTE
            null -> MUTED
        }
        val kind = when {
            item.optBoolean("assistance") -> "求助"
            item.optBoolean("private") -> "定向"
            else -> null
        }
        val heading = listOfNotNull(name, kind, time, routeLabel).joinToString(" · ")
        bubble.addView(text(heading, 13, routeColor, true))
        val id = item.getString("id")
        val needsRead = !mine && item.optBoolean("needs_receipt") && !item.optBoolean("read_by_me")
        val messageText = item.optString("text")
        if (messageText.isNotBlank()) {
            val body = text(messageText, 16, INK)
            put(bubble, body, 5)
            if (needsRead) unreadChannelText[id] = body
        }
        item.optString("audio").takeIf { it.isNotBlank() }?.let { audio ->
            put(bubble, linkButton("播放语音留言").apply {
                setOnClickListener { stopReplay(); nearbyPanel.play(audio, pttHint) }
            }, 5)
        }
        item.optString("photo").takeIf { it.isNotBlank() }?.let { photo ->
            put(bubble, linkButton("查看定向照片").apply {
                setOnClickListener {
                    val bitmap = runCatching { decodeNearbyPhoto(java.util.Base64.getDecoder().decode(photo)) }.getOrNull()
                    if (bitmap != null) {
                        val image = ImageView(this@MainActivity).apply {
                            adjustViewBounds = true
                            setImageBitmap(bitmap)
                            contentDescription = "定向照片"
                        }
                        AlertDialog.Builder(this@MainActivity).setTitle("定向照片").setView(image)
                            .setPositiveButton("关闭", null).create().apply {
                                if (needsRead && messageText.isBlank()) {
                                    setOnShowListener { saveChannelReadReceipts(listOf(id)) }
                                }
                                show()
                            }
                    }
                }
            }, 5)
        }
        if (mine && item.optBoolean("needs_receipt")) {
            val readers = item.optString("read_by")
            val count = item.optInt("read_count")
            put(bubble, text(if (count == 0) "未读" else "$count 人已读 · $readers", 12,
                if (count == 0) ACCENT else ONLINE), 3)
        }
        if (mine) {
            val (status, color) = when (item.optString("delivery")) {
                NearbyDelivery.EXPIRED.name -> "已过期" to SEND_ERROR
                NearbyDelivery.DELIVERED.name -> "组员已收到" to ONLINE
                NearbyDelivery.AWAITING_RECEIPT.name -> "已发出 · 等回执" to ACCENT
                else -> "待发送" to MUTED
            }
            val sync = if (item.optBoolean("synced")) " · 指挥台已同步" else ""
            put(bubble, text(status + sync, 12, color), 3)
        }
    }

    private fun conversationBubble(mine: Boolean) = column().apply {
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = panelColor(if (mine) CHANNEL else PANEL, 12, CARD_BORDER)
        conversation.addView(this, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            marginStart = if (mine) dp(32) else 0
            marginEnd = if (mine) 0 else dp(24)
            topMargin = dp(8)
        })
    }

    private fun scrollToLatestConversation() {
        scroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                if (section == Section.LIVE && client != null) scroll.scrollTo(0, content.height)
                return true
            }
        })
    }

    private fun recordTime(timestamp: String) =
        runCatching { RECORD_TIME.format(Instant.parse(timestamp)) }.getOrDefault(timestamp)

    private fun renderVoiceRecord(block: LinearLayout, item: JSONObject) {
        val time = recordTime(item.getString("created_at"))
        val name = item.optString("speaker_name").takeUnless { it.isBlank() || it == "null" }
            ?: actorLabel(item.getString("actor"))
        block.addView(text("$name · $time · 网络对讲", 13, NETWORK_ROUTE, true))
        val clip = localVoiceClips.firstOrNull { it.trackSid == item.optString("track_sid") }
        if (item.optInt("has_audio") == 1 || clip != null) {
            val length = if (item.optInt("duration_ms") > 0) item.optInt("duration_ms") else clip?.durationMs ?: 0
            val seconds = (length + 999) / 1_000
            put(block, button("▶  原音   $seconds 秒").apply {
                contentDescription = "播放原音，$seconds 秒"
                setOnClickListener { playOriginal(item.getString("track_sid")) }
            }, 5)
        }
        val status = item.getString("status")
        val serverText = item.optString("text").takeIf {
            serverCaption(status, it) == ServerCaption.READY
        }
        val draft = clip?.caption
        val transcript = serverText ?: draft ?: when (status) {
            "recording" -> if (clip == null) "正在接收发言…" else "等待服务器复核…"
            "pending" -> "等待服务器复核…"
            "no_speech" -> "未识别到清晰语音"
            "failed", "ready" -> "转写暂不可用"
            else -> "未收到可回放的原音"
        }
        put(block, text(transcript, 16, INK), 5)
        val engine = item.optString("review_engine")
        if (serverText != null) {
            val label = when (engine) {
                "aliyun_qwen3_asr" -> "香港复核 · 百炼 ASR"
                "sensevoice" -> "香港复核 · SenseVoice"
                else -> "频道转写"
            }
            put(block, text(label, 12, MUTED), 4)
        } else if (draft != null) {
            put(block, text("本机字幕 · 待服务器复核", 12, MUTED), 4)
        }
        item.optJSONObject("incident")?.let { incident ->
            val label = incident.getString("label")
            val acknowledged = incident.optBoolean("acknowledged")
            val dispatched = incident.optBoolean("dispatched")
            val linked = !incident.isNull("task_id")
            val state = when {
                dispatched -> "中台已下达指示"
                acknowledged -> "中台已确认"
                linked -> "已登记 · 等待调度确认"
                else -> "已提醒调度 · 请再报异常并带上航班号或会合点"
            }
            put(block, text("$label · $state", 14, if (acknowledged || dispatched) ONLINE else ACCENT, true), 8)
            val suggestion = agentIncidents.firstOrNull { it.optString("id") == incident.optString("id") }
            if (suggestion != null && role == "dispatcher")
                put(block, linkButton("查看调度建议").apply { setOnClickListener { showAgentSuggestion(suggestion) } }, 4)
        }
        if (item.optInt("partial") == 1) put(block, text("本条语音只保留了部分内容", 12, MUTED), 4)
    }

    private fun playOriginal(trackSid: String) {
        if (voiceState in setOf(VoiceState.REQUESTING, VoiceState.SPEAKING)) return
        val connected = client ?: return
        val selected = eventId ?: return
        val target = radio
        stopReplay()
        val output = VoicePlayback()
        player = output
        showReplayStatus("正在缓冲原音…", canStop = true)
        replay = scope.launch {
            val key = voiceCache.key(connected, selected, target, trackSid)
            suspend fun playing(local: Boolean) = withContext(Dispatchers.Main.immediate) {
                if (player === output) showReplayStatus(
                    if (local) "正在播放原音 · 已缓存" else "正在播放原音", canStop = true)
            }
            try {
                val saved = withContext(Dispatchers.IO) { voiceCache.read(key) }
                if (saved != null) {
                    withContext(Dispatchers.IO) { output.play(saved.inputStream()) { playing(true) } }
                } else {
                    // A pending warmup must not compete with a manual cold play.
                    voiceWarmup?.cancel()
                    connected.streamOriginal(selected, trackSid, target) { stream ->
                        val capture = CapturedClip(stream)
                        output.play(capture) { playing(false) }
                        coroutineContext.ensureActive()
                        runCatching { voiceCache.save(key, capture.bytes()) }
                    }
                }
                if (player === output) showReplayStatus("")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (player === output) showReplayStatus("这条原音暂时无法播放")
            } finally {
                if (player === output) {
                    player = null
                    replay = null
                }
            }
        }
    }

    private fun stopReplay(message: String = "") {
        replay?.cancel()
        replay = null
        player?.pause()
        player = null
        showReplayStatus(message)
    }

    private fun showReplayStatus(message: String, canStop: Boolean = false) {
        if (!::replayFeedback.isInitialized) return
        replayFeedback.text = message
        replayStop.visibility = if (canStop) View.VISIBLE else View.GONE
        updateBottomControls()
    }

    private fun fieldCall(action: suspend (OpsClient, String) -> Unit) {
        val active = client ?: return
        val event = eventId ?: return
        scope.launch {
            try {
                action(active, event)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (active === client && event == eventId) showConnectionStatus(errorMessage(error))
            }
        }
    }

    private fun groups() = directoryData.optJSONArray("channels")?.objects().orEmpty()
    private fun people() = directoryData.optJSONArray("people")?.objects().orEmpty()

    private fun updateRadioSelection() {
        val allowed = groups().map(::RadioTarget)
        if (radio != null && !channelVoice().optBoolean("enabled", true) && voiceState != VoiceState.OFFLINE)
            disconnectVoice("行动语音已结束")
        if (radio == null || allowed.none { it.id == radio?.id }) {
            if (radio != null) {
                disconnectVoice("原频道访问已取消")
                channelLoad?.cancel()
                stopReplay()
                agentDialog?.dismiss()
            }
            radio = allowed.firstOrNull { it.kind == "group" }
            voiceMessages = emptyList()
            clearChannelCursor()
            localVoiceClips = emptyList()
            dispatchInstructions = emptyList()
            agentIncidents = emptyList()
            agentActivity = emptyList()
            olderBefore = null
            pagedVoice = false
            lastHeard = null
            renderConversation()
        }
        selectNearbyChannel()
        updateRadioLabels()
        if (section == Section.MEMBERS) renderMembers()
        if (section == Section.CHANNELS) renderChannelDirectory()
    }

    private fun selectNearbyChannel() {
        val active = client
        val event = eventId
        val target = radio?.takeIf { it.kind == "group" }
        nearbyPanel.select(if (active?.bound == true && event != null && target != null)
            NearbyPanel.Area(active.localSessionKey, event, target.id, target.name) else null)
        val key = if (active?.bound == true && event != null && target != null)
            nearbyScope(active.localSessionKey, event, target.id) else null
        if (key != channelComposerScope && ::channelText.isInitialized) {
            saveChannelDraft()
            setChannelEditorOpen(false)
            channelComposerScope = key
            channelRecipientActor = null
            channelPhoto = null
            channelDraftKey = key
            privateContent.clear()
            channelText.setText(channelDrafts[key].orEmpty())
        }
        updateChannelComposer()
    }

    private fun saveChannelDraft() {
        val key = channelDraftKey ?: return
        val value = channelText.text.toString()
        if (value.isBlank()) channelDrafts.remove(key) else channelDrafts[key] = value
    }

    private fun currentNearbyCredential(): NearbyConfig? = NearbyService.channelCredential.value
        ?.takeIf { it.scope == channelComposerScope && it.live() }

    private fun chooseChannelRecipient() {
        val config = currentNearbyCredential() ?: return
        val recipients = config.recipients.filter { it.actor != config.actor }.sortedBy { it.name }
        val roster = currentChannel()?.optJSONArray("members")?.objects().orEmpty()
        val labels = listOf("整个频道") + recipients.map { member ->
            val callsign = roster.firstOrNull { it.optString("actor") == member.actor }?.optString("callsign")
            listOfNotNull(member.name, callsign?.takeIf { it.isNotBlank() && it != member.name })
                .joinToString(" · ")
        }
        if (recipients.isEmpty() && channelRecipientActor == null) {
            AlertDialog.Builder(this).setTitle("收件人")
                .setMessage(if (config.ownAgreementKey == null) "当前服务器尚未提供定向通信密钥。"
                    else "组员首次联网同步后会显示在这里。")
                .setPositiveButton("关闭", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("发送给")
            .setItems(labels.toTypedArray()) { _, index ->
                if (config.scope != channelComposerScope) return@setItems
                val target = recipients.getOrNull(index - 1)?.actor
                if (target == channelRecipientActor) return@setItems
                saveChannelDraft()
                channelRecipientActor = target
                channelDraftKey = if (target == null) config.scope else config.scope + "|" + target
                channelPhoto = null
                channelText.setText(channelDrafts[channelDraftKey].orEmpty())
                updateChannelComposer()
            }.setNegativeButton("取消", null).show()
    }

    @Suppress("DEPRECATION")
    private fun pickNearbyPhoto() {
        if (channelPhoto != null) {
            channelPhoto = null
            updateChannelComposer()
            return
        }
        val config = currentNearbyCredential() ?: return
        val actor = channelRecipientActor ?: return
        channelPhotoRequest = config.scope to actor
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE), NEARBY_PHOTO)
        } catch (_: android.content.ActivityNotFoundException) {
            channelPhotoRequest = null
        }
    }

    private fun readNearbyPhoto(uri: Uri) {
        val request = channelPhotoRequest ?: return
        channelPhotoRequest = null
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                runCatching { compressNearbyPhoto { contentResolver.openInputStream(uri) } }.getOrNull()
            }
            if (request != (channelComposerScope to channelRecipientActor)) return@launch
            if (data == null) channelError("无法压缩这张照片，请选择其他照片")
            else {
                channelPhoto = data
                updateChannelComposer()
            }
        }
    }

    private fun setChannelEditorOpen(open: Boolean) {
        if (channelEditorOpen == open) return
        channelEditorOpen = open
        if (!open) {
            channelOptionsOpen = false
            channelText.clearFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(channelText.windowToken, 0)
        }
        updateChannelComposer()
        if (open) {
            channelText.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .showSoftInput(channelText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun updateChannelComposer() {
        if (!::channelComposer.isInitialized) return
        val group = client?.bound == true && radio?.kind == "group"
        channelComposer.visibility = if (group) View.VISIBLE else View.GONE
        if (!group) return
        channelWrite.visibility = if (channelEditorOpen) View.GONE else View.VISIBLE
        channelWrite.text = if (channelText.text.isNullOrBlank() && channelPhoto == null) "写消息" else "继续写消息"
        channelEditor.visibility = if (channelEditorOpen) View.VISIBLE else View.GONE
        channelOptions.visibility = if (channelOptionsOpen) View.VISIBLE else View.GONE
        val session = nearbyPanel.currentSession()
        val config = currentNearbyCredential()
        val recipient = config?.recipients?.firstOrNull { it.actor == channelRecipientActor }
        val permission = session != null && nearbyPanel.permissionsMissing()
        channelRadioPermission.visibility = if (permission) View.VISIBLE else View.GONE
        channelRecipientPicker.text = when {
            channelRecipientActor == null -> "整个频道  ›"
            recipient != null -> recipient.name + "  ›"
            else -> "收件人待同步  ›"
        }
        channelRecipientPicker.isEnabled = config != null
        channelAttach.visibility = if (recipient != null) View.VISIBLE else View.GONE
        channelAttach.text = if (channelPhoto == null) "附照片" else "移除照片"
        channelSend.isEnabled = session != null && (channelRecipientActor == null || recipient != null) &&
            (!channelText.text.isNullOrBlank() || recipient != null && channelPhoto != null)
        val links = NearbyService.diagnostics.value
        val hint = when {
            session == null -> nearbyPanel.unavailableMessage()
            channelRecipientActor != null && recipient == null -> "收件人离线密钥待同步"
            channelPhoto != null && links.wifiPeers == 0 -> "照片将等待 Wi-Fi Aware 送达"
            links.reachablePeers == 0 && permission -> "等待附近设备权限 · 消息会排队"
            links.reachablePeers == 0 -> "等待可达组员 · 消息会排队"
            else -> ""
        }
        channelSendStatus.text = hint
        channelSendStatus.setTextColor(MUTED)
        channelSendStatus.visibility = if (hint.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun sendChannelText() {
        val session = nearbyPanel.currentSession() ?: return
        val message = channelText.text.toString().trim()
        val actor = channelRecipientActor
        val photo = channelPhoto
        if (message.isEmpty() && photo == null) return
        try {
            if (actor == null) {
                check(photo == null)
                session.send(message)
            } else {
                val config = checkNotNull(currentNearbyCredential())
                val recipient = config.recipients.first { it.actor == actor }
                val id = UUID.randomUUID().toString()
                val envelope = NearbyPrivate.seal(config, recipient, id, message, photo)
                session.send("", id = id, recipient = actor, sealedPayload = envelope)
            }
            channelPhoto = null
            channelText.text.clear()
            saveChannelDraft()
            setChannelEditorOpen(false)
        } catch (_: Exception) {
            channelError("未发出 · 暂时无法保存，请重试")
        }
    }

    private fun channelError(message: String) {
        channelSendStatus.text = message
        channelSendStatus.setTextColor(SEND_ERROR)
        channelSendStatus.visibility = View.VISIBLE
    }

    private fun showNearbyDiagnostics() {
        val panel = text("", 15, INK).apply { setPadding(dp(24), dp(12), dp(24), dp(20)) }
        val shown = AlertDialog.Builder(this).setTitle("通信诊断").setView(panel)
            .setPositiveButton("关闭", null).create()
        fun update() {
            val radios = NearbyService.radios.value
            val now = NearbyService.diagnostics.value
            val live = NearbyService.liveVoice
            val delivered = if (now.lastDeliveryMillis == 0L) "尚无送达回执"
                else recordTime(Instant.ofEpochMilli(now.lastDeliveryMillis).toString())
            panel.text = buildString {
                appendLine("可达组员  ${now.reachablePeers}")
                appendLine("Wi-Fi Aware  ${radios[NearbyRadio.WIFI] ?: "未启用"} · ${now.wifiPeers} 人")
                appendLine("蓝牙  ${radios[NearbyRadio.BLE] ?: "未启用"} · ${now.bluetoothPeers} 人")
                append("最近送达  $delivered")
                when (now.lastDeliveryRoute) {
                    NearbyRadio.WIFI -> append(" · Wi-Fi Aware")
                    NearbyRadio.BLE -> append(" · 蓝牙")
                    null -> Unit
                }
                if (now.liveVoicePeers > 0 || live?.transmitting == true) {
                    appendLine()
                    if (live?.transmitting == true) append("正在附近发言")
                    else append("支持附近实时语音  ${now.liveVoicePeers} 人")
                }
            }
        }
        shown.show()
        update()
        val refresh = scope.launch {
            launch { NearbyService.radios.collect { update() } }
            NearbyService.diagnostics.collect { update() }
        }
        shown.setOnDismissListener { refresh.cancel() }
    }

    private fun provisionNearby(active: OpsClient, event: String, changed: Boolean) {
        if (Build.VERSION.SDK_INT < 29 || nearbyProvision?.isActive == true) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!changed && nearbyLastSync != 0L && now - nearbyLastSync < 60_000) return
        nearbyLastSync = now
        nearbyProvision = scope.launch {
            try {
                val (publicKey, agreement) = withContext(Dispatchers.IO) {
                    NearbyIdentity(active.localSessionKey).publicKey to
                        runCatching { NearbyPrivateIdentity(this@MainActivity, active.localSessionKey).publicKey }.getOrNull()
                }
                val leases = active.nearbyCredentials(event, publicKey, agreement)
                if (active !== client || event != eventId) return@launch
                nearbySettings.save(active.localSessionKey, event, leases)
                nearbyPanel.serverSupportsNearby = true
                if (visible) nearbyPanel.resume(refresh = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: OpsException) {
                if (active !== client || event != eventId) return@launch
                if (error.status == 404) {
                    nearbyPanel.serverSupportsNearby = false
                    updateChannelComposer()
                }
                if (error.status in setOf(401, 403)) {
                    nearbySettings.clear(active.localSessionKey, event)
                    NearbyService.stop(this@MainActivity)
                }
            } catch (_: Exception) {
                // Keep the previously issued, unexpired lease on a transient failure.
            }
        }
    }

    private fun currentChannel() = groups().firstOrNull { it.optString("id") == radio?.id }

    private fun channelVoice() = currentChannel()?.optJSONObject("voice") ?: JSONObject()

    private fun updateRadioLabels() {
        val current = radio
        val fieldUser = client?.bound == true
        val title = current?.name ?: if (fieldUser) "工作频道" else currentEventTitle()
        val roster = currentChannel()
        channelName.text = "$title  ▾"
        channelMeta.text = if (fieldUser && current != null) {
            val count = roster?.optJSONArray("members")?.length()
            if (count != null) "成员  $count  ›" else "成员  ›"
        } else ""
        channelMeta.visibility = if (channelMeta.text.isEmpty()) View.GONE else View.VISIBLE
        dockTarget.text = title
        updateVoice(voiceState, voiceLabel.text.toString())
    }

    private fun buildChannelDirectory(panel: LinearLayout) {
        val heading = row(panel)
        addWeighted(heading, text("工作频道", 27, INK, true))
        directoryCall = linkButton("私呼  ›").apply {
            contentDescription = "查找联系人发起私呼"
            setOnClickListener { showPrivateContacts() }
        }
        heading.addView(directoryCall, LinearLayout.LayoutParams(WRAP, WRAP))
        channelDirectorySubtitle = text("", 13, MUTED)
        put(panel, channelDirectorySubtitle, 2)
        channelSearch = input("搜索频道", InputType.TYPE_CLASS_TEXT).apply {
            isSingleLine = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = panelColor(PANEL, 12, CARD_BORDER)
            doAfterTextChanged { if (section == Section.CHANNELS) renderChannelDirectory() }
        }
        put(panel, channelSearch, 14)
        channelRows = column()
        put(panel, channelRows, 7)
        directoryRenderKey = ""
    }

    private fun renderChannelDirectory() {
        val allowed = groups().filter { it.optString("kind") == "group" }
        val term = channelSearch.text.toString().trim()
        val filtered = allowed.filter { it.optString("name").contains(term, ignoreCase = true) }
        val listening = voiceState in setOf(VoiceState.LISTENING, VoiceState.REQUESTING, VoiceState.SPEAKING)
        val canVoice = voiceConfigured()
        val canCall = client?.privateCallsEnabled == true
        val key = listOf(filtered, radio?.id, listening, canVoice, canCall, workspaceOnline, term, eventId).toString()
        if (key == directoryRenderKey) return
        directoryRenderKey = key
        directoryCall.visibility = if (canCall) View.VISIBLE else View.GONE
        channelDirectorySubtitle.text = currentEventTitle()
        channelRows.removeAllViews()
        for (entry in filtered.sortedBy { it.optString("id") != radio?.id }) {
            val target = RadioTarget(entry)
            val current = radio == target
            val enabled = canVoice && entry.optJSONObject("voice")?.optBoolean("enabled", true) != false
            val state = when {
                !enabled -> "语音未开启"
                current && listening -> "收听中"
                current -> "当前频道"
                else -> "可接入"
            }
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(72)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = RippleDrawable(ColorStateList.valueOf(0x29F6CA97),
                    panelColor(if (current) CHANNEL else PANEL, 14, if (current) ACTIVE else CARD_BORDER), null)
                isFocusable = true
                setOnClickListener {
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(channelSearch.windowToken, 0)
                    activateRadio(target)
                }
            }
            val icon = text(if (current && listening) "◖" else "◉", 20, if (enabled) ACCENT else MUTED).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = panelColor(DOCK, 10, CARD_BORDER)
            }
            line.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
            val labels = column()
            labels.addView(text(target.name, 17, INK, true))
            put(labels, text("${entry.optJSONArray("members")?.length() ?: 0} 位成员", 13, MUTED), 3)
            addWeighted(line, labels, 11)
            line.addView(text(state, 12, if (current && listening) ONLINE else MUTED).apply {
                setPadding(dp(8), 0, dp(4), 0)
            })
            line.addView(text("›", 19, ACCENT))
            put(channelRows, line, 7)
        }
        if (filtered.isEmpty()) {
            val empty = when {
                term.isNotEmpty() -> "没有匹配的频道"
                !directoryData.has("channels") -> "正在同步频道…"
                else -> "尚未分配工作频道"
            }
            put(channelRows, text(empty, 15, MUTED), 14)
        }
    }

    private fun activateRadio(target: RadioTarget) {
        if (target == radio) {
            showSection(Section.LIVE)
            return
        }
        val moveListening = voiceState != VoiceState.OFFLINE
        if (target.kind == "direct" && radio?.kind == "group") groupBeforeDirect = radio
        load?.cancel()
        historyJob?.cancel()
        stopReplay()
        disconnectVoice("正在切换频道…")
        disconnectVideo()
        radio = target
        val active = client
        val event = eventId
        if (target.kind == "group" && active?.bound == true && event != null)
            getPreferences(MODE_PRIVATE).edit().putString("channel-" + fingerprint(active.localSessionKey + "|" + event),
                target.id).apply()
        selectNearbyChannel()
        localVoiceClips = emptyList()
        channelLoad?.cancel()
        voiceWarmup?.cancel()
        agentIncidents = emptyList()
        agentActivity = emptyList()
        dispatchInstructions = emptyList()
        agentDialog?.dismiss()
        lastHeard = null
        voiceMessages = emptyList()
        clearChannelCursor()
        olderBefore = null
        pagedVoice = false
        updateRadioLabels()
        renderConversation()
        showSection(Section.LIVE)
        refresh(automatic = true)
        if (moveListening && voiceAvailable()) connectVoice()
    }

    private fun showChannelPicker() {
        channelSearch.text.clear()
        showSection(Section.CHANNELS)
    }

    private fun showMembers() {
        if (client?.bound != true || radio == null) return
        memberSearch.text.clear()
        showSection(Section.MEMBERS)
    }

    private fun buildMembersPage(panel: LinearLayout) {
        put(panel, backButton("返回频道"), 4)
        val heading = card(panel)
        heading.addView(text("频道成员", 24, INK, true))
        memberChannelTitle = text("", 14, MUTED)
        put(heading, memberChannelTitle, 6)
        memberSearch = input("搜索姓名或呼号", InputType.TYPE_CLASS_TEXT).apply {
            isSingleLine = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = panelColor(BACKGROUND, 9, CARD_BORDER)
            doAfterTextChanged { if (section == Section.MEMBERS) renderMembers() }
        }
        put(heading, memberSearch, 14)
        memberRows = column()
        put(panel, memberRows, 2)
        memberListKey = ""
    }

    private fun renderMembers() {
        val target = radio
        val roster = currentChannel()?.optJSONArray("members")?.objects().orEmpty()
        val term = memberSearch.text.toString().trim()
        val canCall = client?.privateCallsEnabled == true
        val key = listOf(target?.id, roster, term, canCall).toString()
        if (key == memberListKey) return
        memberListKey = key
        memberChannelTitle.text = if (target == null) "频道暂不可访问" else "${target.name} · ${roster.size} 人"
        val mine = client?.profile?.optString("actor")
        val filtered = roster.filter { it.optString("name").contains(term, ignoreCase = true) ||
            it.optString("callsign").contains(term, ignoreCase = true) }.sortedWith(
            compareBy<JSONObject> { it.optString("actor") != mine }
                .thenBy { it.optString("role") != "dispatcher" }.thenBy { it.optString("name") })
        memberRows.removeAllViews()
        for (person in filtered) {
            val name = person.getString("name")
            val own = person.optString("actor") == mine
            val dispatcher = person.optString("role") == "dispatcher"
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(66)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = panelColor(PANEL, 11, CARD_BORDER)
            }
            val initial = text(name.take(1), 17, ACCENT, true).apply {
                gravity = Gravity.CENTER
                background = panelColor(CHANNEL, 11, CARD_BORDER)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            line.addView(initial, LinearLayout.LayoutParams(dp(38), dp(38)))
            val labels = column()
            labels.addView(text(if (own) "$name（你）" else name, 16, INK, true))
            put(labels, text(person.getString("callsign") + if (dispatcher) " · 调度员" else " · 工作人员", 13, MUTED), 3)
            addWeighted(line, labels, 11)
            if (canCall && !own) line.addView(linkButton("私呼").apply {
                contentDescription = "私呼 $name"
                setPadding(dp(10), 0, dp(4), 0)
                setOnClickListener { confirmPrivateCall(person) }
            }, LinearLayout.LayoutParams(WRAP, WRAP))
            put(memberRows, line, 7)
        }
        if (filtered.isEmpty()) put(memberRows, text(if (term.isEmpty()) "暂无成员" else "未找到匹配的成员", 14, MUTED), 8)
    }

    private fun showPrivateContacts() {
        val own = client?.profile?.optString("actor")
        val contacts = groups().filter { it.optString("kind") == "group" }
            .flatMap { it.optJSONArray("members")?.objects().orEmpty() }
            .filter { it.optString("actor") != own }.distinctBy { it.getString("actor") }.sortedBy { it.getString("name") }
        pickPeople("私呼联系人", contacts, { it.getString("name") + " · " + it.getString("callsign") }, ::confirmPrivateCall)
    }

    private fun confirmPrivateCall(person: JSONObject) {
        val active = client ?: return
        val event = eventId ?: return
        if (!active.privateCallsEnabled || conference != null || callDialog != null || placingCall) return
        val peer = person.getString("name")
        val form = callPanel(peer, person.optString("callsign"), "准备发起一对一语音通话")
        AlertDialog.Builder(this).setView(form.first)
            .setNegativeButton("取消", null)
            .setPositiveButton("拨打语音") { _, _ ->
                if (client !== active || eventId != event || conference != null || placingCall) return@setPositiveButton
                placingCall = true
                scope.launch {
                    try {
                        val result = active.callPerson(event, person.getString("actor"), "audio", UUID.randomUUID().toString())
                        val session = DirectCall(active, event, result.getString("id"), peer, false)
                        if (visible && client === active && eventId == event && conference == null) waitForAnswer(session)
                        else active.conferenceDecision(event, session.id, "end")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (client === active && eventId == event) showConnectionStatus(errorMessage(error))
                    } finally {
                        placingCall = false
                    }
                }
            }.show()
    }

    private fun showChannelAccess() {
        val channel = radio?.takeIf { it.kind == "group" } ?: return
        fieldCall { service, event ->
            val access = service.channelAccess(event, channel.id)
            if (client !== service || eventId != event || radio != channel) return@fieldCall
            fun values(key: String): Set<String> = access.getJSONArray(key).let { array ->
                (0 until array.length()).map { array.getString(it) }.toSet()
            }
            val sources = values("sources")
            val direct = values("direct")
            val excluded = values("excluded")
            AlertDialog.Builder(this).setTitle(channel.name + " · 访问权限").setItems(arrayOf(
                "小组权限 · ${sources.size} 组", "个人权限 · ${direct.size} 人直接授权 · ${excluded.size} 人排除"
            )) { _, option ->
                if (option == 0) {
                    val teams = groups().filter { it.optString("kind") == "group" && it.optString("id") != channel.id }
                    if (teams.isEmpty()) {
                        showConnectionStatus("请先创建或加入另一个小组")
                        return@setItems
                    }
                    val labels = teams.map {
                        it.getString("name") + if (it.getString("id") in sources) " · 已授权" else ""
                    }.toTypedArray()
                    AlertDialog.Builder(this).setTitle("选择授权小组").setItems(labels) { _, index ->
                        val team = teams[index]
                        val id = team.getString("id")
                        val grant = id !in sources
                        AlertDialog.Builder(this).setTitle(if (grant) "授权小组" else "移除小组授权")
                            .setMessage(team.getString("name") + " · " + channel.name)
                            .setNegativeButton("取消", null).setPositiveButton("确认") { _, _ ->
                                fieldCall { active, current ->
                                    if (radio == channel && current == event) {
                                        active.setTeamAccess(current, channel.id, id, grant)
                                        refresh()
                                        showChannelAccess()
                                    }
                                }
                            }.show()
                    }.setNegativeButton("取消", null).show()
                } else {
                    pickPeople("个人权限", people(), { person ->
                        val actor = person.getString("actor")
                        person.getString("name") + " · " + when (actor) {
                            in direct -> "直接授权"
                            in excluded -> "已排除"
                            else -> "跟随小组"
                        }
                    }) { person ->
                        val actor = person.getString("actor")
                        val options = arrayOf("直接授权", "排除", "跟随小组")
                        val requests = listOf("allow", "deny", "inherit")
                        AlertDialog.Builder(this).setTitle(person.getString("name")).setItems(options) { _, selected ->
                            fieldCall { active, current ->
                                if (radio == channel && event == current) {
                                    active.setIndividualAccess(current, channel.id, actor, requests[selected])
                                    refresh()
                                    showChannelAccess()
                                }
                            }
                        }.setNegativeButton("取消", null).show()
                    }
                }
            }.setNegativeButton("关闭", null).show()
        }
    }

    private fun chooseChannelVoice() {
        val target = radio?.takeIf { it.kind == "group" } ?: return
        var chosen = when {
            channelVoice().optString("mode") != "operation" -> 0
            channelVoice().optBoolean("enabled") -> 1
            else -> 2
        }
        val key = UUID.randomUUID().toString()
        AlertDialog.Builder(this).setTitle(target.name + " · 语音模式")
            .setSingleChoiceItems(arrayOf("常开", "行动语音 · 开启", "行动语音 · 关闭"), chosen) { _, i -> chosen = i }
            .setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                fieldCall { active, event ->
                    val next = active.setChannelVoice(event, target, if (chosen == 0) "always" else "operation", chosen != 2, key)
                    if (active === client && event == eventId) {
                        groups().firstOrNull { it.optString("id") == target.id }?.put("voice", next)
                        updateRadioSelection()
                        refresh()
                    }
                }
            }.show()
    }

    private fun showVoiceLog(before: String = "") {
        val target = radio ?: return
        fieldCall { active, event ->
            val page = active.channelVoiceLog(event, target, before)
            if (active !== client || event != eventId || radio != target) return@fieldCall
            val panel = column().apply { setPadding(dp(20), dp(6), dp(20), dp(12)) }
            val entries = page.getJSONArray("items").objects()
            for (entry in entries) {
                val state = entry.optJSONObject("detail")?.optJSONObject("to")
                val label = when (entry.optString("action")) {
                    "voice_policy" -> when {
                        state?.optString("mode") == "always" -> "设为常开"
                        state?.optBoolean("enabled") == true -> "开启行动语音"
                        else -> "关闭行动语音"
                    }
                    "join_authorized" -> "获准接入语音"
                    "ptt_claim" -> "开始发言"
                    "ptt_release" -> "结束发言"
                    else -> entry.optString("action")
                }
                put(panel, text(entry.getString("speaker") + " · " + label, 15, INK, true), 8)
                put(panel, text(recordTime(entry.getString("recorded_at")), 13, MUTED), 3)
            }
            if (entries.isEmpty()) put(panel, text("暂无语音操作记录", 14, MUTED), 8)
            val cursor = page.optString("next_before").takeUnless { it.isBlank() || it == "null" }
            AlertDialog.Builder(this).setTitle("语音操作日志")
                .setView(ScrollView(this).apply { addView(panel) }).setNegativeButton("关闭", null)
                .apply { if (cursor != null) setPositiveButton("更早记录") { _, _ -> showVoiceLog(cursor) } }.show()
        }
    }

    private data class DirectCall(
        val client: OpsClient, val event: String, val id: String, val peer: String, val video: Boolean,
    )

    private fun showConferenceOptions() {
        val peer = radio?.takeIf { it.kind == "direct" } ?: return
        if (conference != null) return
        fieldCall { active, event ->
            val current = active.openConference(event, peer).optJSONObject("call")
            if (active !== client || event != eventId || peer != radio) return@fieldCall
            if (current != null) {
                if (current.optBoolean("incoming") && current.optString("state") == "ringing")
                    offerIncoming(active, event, JSONObject(current.toString()).put("name", peer.name))
                else showConnectionStatus("已有通话邀请，等待对方操作")
                return@fieldCall
            }
            val modes = if (videoEnabled) arrayOf("语音通话", "视讯通话") else arrayOf("语音通话")
            AlertDialog.Builder(this).setTitle("呼叫 " + peer.name).setItems(modes) { _, index ->
                fieldCall { source, selected ->
                    if (source !== active || selected != event || peer != radio) return@fieldCall
                    val result = source.offerConference(selected, peer, if (index == 0) "audio" else "video",
                        UUID.randomUUID().toString())
                    if (source === client && selected == eventId) waitForAnswer(
                        DirectCall(source, selected, result.getString("id"), peer.name, index != 0))
                }
            }.setNegativeButton("取消", null).show()
        }
    }

    private fun offerIncoming(active: OpsClient, event: String, call: JSONObject) {
        if (!visible || conference != null || callDialog != null ||
            call.optString("state") != "ringing" || !call.optBoolean("incoming")) return
        val id = call.getString("id")
        if (lastIncomingId == id) return
        lastIncomingId = id
        val session = DirectCall(active, event, id, call.optString("name", "联系人"), call.optString("mode") == "video")
        val form = callPanel(session.peer, call.optString("callsign"), if (session.video) "视频来电" else "语音来电")
        val dialog = AlertDialog.Builder(this).setView(form.first)
            .setNegativeButton("拒绝") { _, _ ->
                fieldCall { source, selected ->
                    if (source === active && selected == event) source.conferenceDecision(event, session.id, "decline")
                }
            }.setPositiveButton("接听") { _, _ ->
                if (client !== active || eventId != event || conference != null) return@setPositiveButton
                conference = session
                requireConferencePermissions(session) {
                    callWait = scope.launch {
                        try {
                            val state = active.conferenceDecision(event, session.id, "accept").getString("state")
                            if (state == "accepted" && conference === session) startConference(session)
                            else {
                                finishConference()
                                showConnectionStatus("来电已结束")
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            finishConference()
                            showConnectionStatus(errorMessage(error))
                        }
                    }
                }
            }.create()
        callDialog = dialog
        dialog.setOnDismissListener { if (callDialog === dialog) callDialog = null }
        dialog.show()
        scope.launch {
            while (dialog.isShowing && visible && conference == null) {
                delay(2_500)
                try {
                    if (active.conferenceStatus(event, id).optString("state") != "ringing") {
                        dialog.dismiss()
                        showConnectionStatus("来电已结束")
                        break
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Retry the state lookup while the invitation is visible.
                }
            }
        }
    }

    private fun callPanel(name: String, callsign: String, label: String): Pair<LinearLayout, TextView> {
        val panel = column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(12))
        }
        val avatar = text(name.take(1), 25, ACCENT, true).apply {
            gravity = Gravity.CENTER
            background = panelColor(CHANNEL, 20, CARD_BORDER)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        panel.addView(avatar, LinearLayout.LayoutParams(dp(56), dp(56)))
        put(panel, text(name, 23, INK, true).apply { gravity = Gravity.CENTER }, 13)
        if (callsign.isNotBlank()) put(panel, text(callsign, 13, MUTED).apply { gravity = Gravity.CENTER }, 5)
        val status = liveText().apply { text = label; gravity = Gravity.CENTER }
        put(panel, status, 12)
        return panel to status
    }

    private fun waitForAnswer(session: DirectCall) {
        if (conference != null) return
        conference = session
        val form = callPanel(session.peer, "", "正在呼叫 · 等待接听…")
        val dialog = AlertDialog.Builder(this).setView(form.first).setNegativeButton("取消呼叫", null).create()
        callDialog = dialog
        dialog.setOnDismissListener { if (conference === session) finishConference() }
        dialog.show()
        callWait = scope.launch {
            repeat(31) {
                delay(1_500)
                if (!visible || conference !== session) return@launch
                try {
                    when (session.client.conferenceStatus(session.event, session.id).getString("state")) {
                        "accepted" -> {
                            dialog.setOnDismissListener(null)
                            dialog.dismiss()
                            callDialog = null
                            requireConferencePermissions(session) { startConference(session) }
                            return@launch
                        }
                        "declined" -> {
                            finishConference()
                            showConnectionStatus("对方已拒绝")
                            return@launch
                        }
                        "ended" -> {
                            finishConference()
                            showConnectionStatus("通话未接通")
                            return@launch
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    form.second.text = "正在恢复连接…"
                }
            }
            finishConference()
        }
    }

    private fun requireConferencePermissions(session: DirectCall, proceed: () -> Unit) {
        val permissions = listOfNotNull(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA.takeIf { session.video })
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isEmpty()) proceed()
        else {
            callPermission = { if (visible && conference === session) proceed() else finishConference() }
            requestPermissions(permissions.toTypedArray(), CONFERENCE_PERMISSION)
        }
    }

    private fun startConference(session: DirectCall) {
        if (!visible || client !== session.client || eventId != session.event || conference !== session) {
            finishConference()
            return
        }
        restoreRadioAfterCall = voiceService?.state?.value?.workspace != null
        disconnectVoice("通话中")
        disconnectVideo()
        stopReplay()
        val (body, status) = callPanel(session.peer, "", "正在接通…")
        val remote = if (session.video) TextureViewRenderer(this) else null
        val local = if (session.video) TextureViewRenderer(this) else null
        if (remote != null && local != null) {
            val frame = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
            frame.addView(remote, FrameLayout.LayoutParams(MATCH, MATCH))
            frame.addView(local, FrameLayout.LayoutParams(dp(100), dp(135), Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = dp(8)
                bottomMargin = dp(8)
            })
            body.addView(frame, LinearLayout.LayoutParams(MATCH, dp(315)).apply { topMargin = dp(9) })
        }
        val dialog = AlertDialog.Builder(this).setView(body).setPositiveButton("结束通话", null).create()
        callDialog = dialog
        dialog.setOnDismissListener { if (conference === session) finishConference() }
        dialog.show()
        callMedia = ConferenceMedia(this, scope, session.client, session.event, session.id, session.video, remote, local,
            status = { status.text = it },
            failed = {
                if (conference === session) {
                    finishConference()
                    showConnectionStatus(it)
                }
            }).also { it.start() }
    }

    private fun finishConference(returnToRadio: Boolean = true) {
        val session = conference ?: return
        conference = null
        callPermission = null
        callWait?.cancel()
        callWait = null
        callMedia?.close()
        callMedia = null
        callDialog?.setOnDismissListener(null)
        callDialog?.dismiss()
        callDialog = null
        scope.launch { runCatching { session.client.conferenceDecision(session.event, session.id, "end") } }
        if (returnToRadio && visible && restoreRadioAfterCall && client === session.client) {
            restoreRadioAfterCall = false
            connectVoice()
        }
    }

    private fun showFieldManagement() {
        AlertDialog.Builder(this).setTitle("人员与频道")
            .setItems(arrayOf("登录名单", "批量导入人员", "添加人员", "发登录包", "撤销设备", "新建频道", "把人员加入频道")) { _, index ->
                when (index) {
                    0 -> showLoginRoster()
                    1 -> showRosterImport()
                    2 -> showCreatePerson()
                    3 -> choosePerson("发登录包", activeOnly = true) { person ->
                        fieldCall { active, event -> showLoginPackage(active, active.invitePerson(event, person.getString("actor")), person.getString("name")) }
                    }
                    4 -> choosePerson("撤销人员设备") { person ->
                        AlertDialog.Builder(this).setTitle("撤销 " + person.getString("name") + " 的设备授权？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("撤销") { _, _ ->
                                fieldCall { active, event ->
                                    active.revokePerson(event, person.getString("actor"))
                                    showConnectionStatus("设备授权已撤销")
                                }
                            }.show()
                    }
                    5 -> {
                        val name = input("频道名称", InputType.TYPE_CLASS_TEXT)
                        AlertDialog.Builder(this).setTitle("新建频道").setView(name)
                            .setNegativeButton("取消", null)
                            .setPositiveButton("创建") { _, _ ->
                                fieldCall { active, event ->
                                    active.createChannel(event, name.text.toString().trim())
                                    refresh()
                                }
                            }.show()
                    }
                    6 -> choosePerson("选择人员") { person ->
                        chooseGroup("加入频道") { group ->
                            fieldCall { active, event ->
                                active.addChannelMember(event, group.getString("id"), person.getString("actor"))
                                refresh()
                            }
                        }
                    }
                }
            }.setNegativeButton("关闭", null).show()
    }

    private fun showLoginRoster() {
        fieldCall { active, event ->
            val roster = active.directory(event).getJSONArray("people").objects()
            pickPeople("登录名单", roster, ::rosterLabel) { person ->
                val name = person.getString("name")
                val suspended = person.optString("login_status") == "suspended"
                AlertDialog.Builder(this).setTitle(if (suspended) "恢复 $name 登录？" else "停用 $name 登录？")
                    .setMessage(if (suspended) "恢复后可以为本人发新的登录包。" else "此人现有设备、登录包及 Passkey 将失效。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(if (suspended) "恢复" else "停用") { _, _ ->
                        fieldCall { service, current ->
                            service.setAccountStatus(current, person.getString("actor"), !suspended)
                            refresh()
                            showLoginRoster()
                        }
                    }.show()
            }
        }
    }

    private fun rosterLabel(person: JSONObject): String {
        val state = when {
            person.optString("login_status") == "suspended" -> "已停用"
            person.optBoolean("passkey_registered") -> "已登记 Passkey"
            else -> "待登记 Passkey"
        }
        val roleLabel = if (person.optString("role") == "dispatcher") "调度员" else "工作人员"
        return person.getString("name") + " · " + person.getString("callsign") + "\n" + roleLabel + " · " + state
    }

    private fun pickPeople(title: String, candidates: List<JSONObject>, label: (JSONObject) -> String, choice: (JSONObject) -> Unit) {
        val panel = column().apply { setPadding(dp(18), dp(8), dp(18), 0) }
        val search = input("搜索姓名或呼号", InputType.TYPE_CLASS_TEXT).apply { isSingleLine = true }
        put(panel, search, 3)
        val empty = text("", 14, MUTED)
        put(panel, empty, 3)
        val list = ListView(this)
        panel.addView(list, LinearLayout.LayoutParams(MATCH, dp(288)))
        var matches = candidates
        fun filter() {
            val term = search.text.toString().trim()
            matches = candidates.filter { it.getString("name").contains(term, ignoreCase = true) ||
                it.getString("callsign").contains(term, ignoreCase = true) }
            list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, matches.map(label))
            empty.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
            empty.text = if (term.isEmpty()) "当前活动暂无人员" else "没有找到匹配的人员"
        }
        search.doAfterTextChanged { filter() }
        filter()
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(panel).setNegativeButton("关闭", null).create()
        list.setOnItemClickListener { _, _, index, _ ->
            val selected = matches[index]
            dialog.dismiss()
            choice(selected)
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
    }

    private fun showRosterImport() = chooseGroup("导入到哪个工作频道？") { channel ->
        val panel = column().apply { setPadding(dp(18), dp(8), dp(18), 0) }
        put(panel, text("每行一位，格式为「姓名,呼号」。也可从表格复制这两列，一次最多 100 位。", 13, MUTED), 0)
        val source = input("姓名,呼号", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP
            filters = arrayOf(InputFilter.LengthFilter(10_000))
        }
        put(panel, source, 6)
        val status = liveText()
        put(panel, status, 3)
        val active = client ?: return@chooseGroup
        val event = eventId ?: return@chooseGroup
        val dialog = AlertDialog.Builder(this).setTitle("导入 · " + channel.getString("name"))
            .setView(panel).setNegativeButton("取消", null).setPositiveButton("导入", null).create()
        dialog.show()
        val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        submit.setOnClickListener {
            val rows = source.text.toString().lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            if (rows.size !in 1..100) {
                status.text = "请粘贴 1–100 行人员资料"
                return@setOnClickListener
            }
            val people = JSONArray()
            for ((index, row) in rows.withIndex()) {
                val fields = row.split(',', '，', '\t').map(String::trim)
                if (fields.size != 2 || fields[0].length !in 1..32 || fields[1].length !in 1..24) {
                    status.text = "第 ${index + 1} 行应为姓名和呼号"
                    return@setOnClickListener
                }
                people.put(JSONObject().put("name", fields[0]).put("callsign", fields[1]))
            }
            submit.isEnabled = false
            status.text = "正在导入…"
            scope.launch {
                try {
                    val result = active.importPeople(event, channel.getString("id"), people)
                    dialog.dismiss()
                    if (client === active && eventId == event) {
                        refresh()
                        AlertDialog.Builder(this@MainActivity).setTitle("名单已更新")
                            .setMessage("新增 ${result.getInt("created")} 人，加入频道 ${result.getInt("assigned")} 人，已有 ${result.getInt("unchanged")} 人。")
                            .setPositiveButton("完成", null).show()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    status.text = errorMessage(error)
                    submit.isEnabled = true
                }
            }
        }
    }

    private fun choosePerson(title: String, staffOnly: Boolean = false, activeOnly: Boolean = false, choice: (JSONObject) -> Unit) {
        val candidates = people().filter {
            (!staffOnly || it.optString("role") == "staff") &&
                (!(staffOnly || activeOnly) || it.optString("login_status") != "suspended")
        }
        if (candidates.isEmpty()) {
            showConnectionStatus("请先添加人员")
            return
        }
        pickPeople(title, candidates, { it.getString("name") + " · " + it.getString("callsign") }, choice)
    }

    private fun chooseGroup(title: String, choice: (JSONObject) -> Unit) {
        val channels = groups().filter { it.optString("kind") == "group" }
        if (channels.isEmpty()) {
            showConnectionStatus("请先创建频道")
            return
        }
        AlertDialog.Builder(this).setTitle(title)
            .setItems(channels.map { it.getString("name") }.toTypedArray()) { _, i -> choice(channels[i]) }
            .setNegativeButton("取消", null).show()
    }

    private fun showCreatePerson() = chooseGroup("为新人选择频道") { group ->
        val form = column().apply { setPadding(dp(18), dp(8), dp(18), 0) }
        val name = input("姓名", InputType.TYPE_CLASS_TEXT)
        val handle = input("呼号", InputType.TYPE_CLASS_TEXT)
        val roles = dropdown(listOf("工作人员", "调度员"))
        put(form, name, 5)
        put(form, handle, 5)
        put(form, roles, 5)
        AlertDialog.Builder(this).setTitle("添加人员").setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("生成登录包") { _, _ ->
                fieldCall { active, event ->
                    val body = JSONObject().put("name", name.text.toString()).put("callsign", handle.text.toString())
                        .put("role", if (roles.selectedItemPosition == 1) "dispatcher" else "staff")
                        .put("channel_ids", JSONArray().put(group.getString("id")))
                    showLoginPackage(active, active.createPerson(event, body), name.text.toString().trim())
                    refresh(automatic = true)
                }
            }.show()
    }

    private fun showLoginPackage(active: OpsClient, data: JSONObject, name: String) {
        AlertDialog.Builder(this).setTitle(name + "的登录包")
            .setMessage("有效 15 分钟。请单独发给本人，打开后核对姓名即可登录。")
            .setNegativeButton("关闭", null)
            .setNeutralButton("显示二维码") { _, _ -> showBindingCode(data) }
            .setPositiveButton("分享登录包") { _, _ ->
                val loggedIn = FieldLoginPackage(active.origin, data.getString("binding_code"))
                val directory = File(cacheDir, "login-packages").apply { mkdirs() }
                directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 15 * 60_000 }
                    ?.forEach(File::delete)
                val file = File.createTempFile("Protocol-X-", ".protocolx", directory)
                file.writeText(loggedIn.json())
                val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".capture", file)
                val intent = Intent(Intent.ACTION_SEND).setType(FieldLoginPackage.MIME).putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newRawUri("Protocol X 登录包", uri)
                startActivity(Intent.createChooser(intent, "分享 Protocol X 登录包"))
            }.show()
    }

    private fun showBindingCode(data: JSONObject) {
        val value = data.getString("binding_code")
        val code = text(value, 17, INK, true).apply {
            setPadding(dp(18), dp(14), dp(18), dp(8))
            setTextIsSelectable(true)
        }
        val content = column().apply { setPadding(dp(18), dp(12), dp(18), dp(8)) }
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = dp(245)
            setImageBitmap(BindingQr.render(value))
            contentDescription = "一次性绑定二维码"
        }
        put(content, image, 0)
        put(content, code, 4)
        AlertDialog.Builder(this).setTitle("绑定人员 · 15 分钟")
            .setView(content)
            .setPositiveButton("完成", null).show().window?.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun canReleaseArrival(plan: JSONObject): Boolean {
        val task = plan.getJSONObject("task")
        return !task.has("arrival_dispatched_at") &&
            task.getJSONArray("progress").getJSONObject(0).optString("status") != "done"
    }

    private fun confirmArrivalDispatch(plan: JSONObject) {
        val task = plan.getJSONObject("task")
        val id = plan.getString("id")
        val key = UUID.randomUUID().toString()
        val recipients = listOf(plan.optString("driver_name"), plan.optString("greeter_name")).distinct().joinToString("、")
        val dialog = AlertDialog.Builder(this).setTitle("发布 " + plan.getString("flight") + " 接机任务")
            .setMessage("发给 $recipients\n会合点 " + plan.optString("meeting_point"))
            .setNegativeButton("取消", null).setPositiveButton("发布", null).create()
        dialog.show()
        val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        submit.setOnClickListener {
            submit.isEnabled = false
            fieldCall { active, event ->
                try {
                    active.dispatchArrival(event, id, task.getInt("version"), key)
                    dialog.dismiss()
                    refresh()
                } finally {
                    submit.isEnabled = true
                }
            }
        }
    }

    private fun showDispatch() {
        val drafts = arrivalCards.values.filter(::canReleaseArrival)
        if (drafts.isEmpty()) {
            showGenericDispatch()
            return
        }
        val labels = arrayOf("新建现场任务", *drafts.map { it.getString("flight") + " · " +
            it.getJSONObject("task").getString("title") }.toTypedArray())
        AlertDialog.Builder(this).setTitle("派单").setItems(labels) { _, index ->
            if (index == 0) showGenericDispatch() else confirmArrivalDispatch(drafts[index - 1])
        }.setNegativeButton("取消", null).show()
    }

    private fun showGenericDispatch() = choosePerson("选择负责人", staffOnly = true) { person ->
        val form = column().apply { setPadding(dp(18), dp(8), dp(18), 0) }
        val title = input("任务事项", InputType.TYPE_CLASS_TEXT)
        val location = input("到场地点", InputType.TYPE_CLASS_TEXT)
        val contact = input("接应人（可选）", InputType.TYPE_CLASS_TEXT)
        val due = input("时限（可选）", InputType.TYPE_CLASS_TEXT)
        listOf(title, location, contact, due).forEach { put(form, it, 5) }
        val requestId = UUID.randomUUID().toString()
        val dialog = AlertDialog.Builder(this).setTitle("发给 " + person.getString("name")).setView(form)
            .setNegativeButton("取消", null).setPositiveButton("派发", null).create()
        dialog.setOnShowListener {
            val submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            submit.setOnClickListener {
                val name = title.text.toString().trim()
                val place = location.text.toString().trim()
                if (name.isEmpty() || place.isEmpty()) {
                    if (name.isEmpty()) title.error = "请填写事项"
                    if (place.isEmpty()) location.error = "请填写地点"
                    return@setOnClickListener
                }
                submit.isEnabled = false
                fieldCall { active, event ->
                    try {
                        val payload = JSONObject().put("person_id", person.getString("actor")).put("request_id", requestId)
                            .put("title", name).put("location", place).put("contact", contact.text.toString())
                            .put("due", due.text.toString())
                        active.dispatch(event, payload)
                        dialog.dismiss()
                        refresh()
                    } finally {
                        submit.isEnabled = true
                    }
                }
            }
        }
        dialog.show()
    }

    private fun loadOlderVoice() {
        val target = radio ?: return
        val before = olderBefore ?: return
        val active = client ?: return
        val event = eventId ?: return
        if (historyJob?.isActive == true) return
        historyJob = scope.launch {
            try {
                val page = active.history(event, target, before)
                if (active !== client || event != eventId || target != radio) return@launch
                pagedVoice = true
                voiceMessages = (voiceMessages + page.getJSONArray("items").objects()).distinctBy { it.getString("track_sid") }
                olderBefore = page.optString("next_before").takeUnless { it.isBlank() || it == "null" }
                renderConversation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showConnectionStatus(errorMessage(error))
            }
        }
    }

    private fun takeTaskPhoto(task: JSONObject) {
        val active = client?.takeIf { it.bound } ?: return
        val selected = eventId ?: return
        pendingPhoto = PhotoRequest(active, selected, task.getJSONObject("ref").getString("id"), task.getString("title"))
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchPhotoCamera()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), PHOTO_PERMISSION_REQUEST)
    }

    @Suppress("DEPRECATION")
    private fun launchPhotoCamera() {
        val capture = pendingPhoto ?: return
        if (capture.client !== client || capture.event != eventId) {
            pendingPhoto = null
            return
        }
        try {
            val (file, intent) = FieldImages.capture(this)
            photoFile?.delete()
            photoFile = file
            startActivityForResult(intent, PHOTO_CAPTURE)
        } catch (error: Exception) {
            pendingPhoto = null
            photoFile?.delete()
            photoFile = null
            showConnectionStatus("无法打开相机")
        }
    }

    @Suppress("DEPRECATION")
    private fun launchQrCamera() {
        if (qrReceiver == null) return
        try {
            val (file, intent) = FieldImages.capture(this)
            qrFile?.delete()
            qrFile = file
            startActivityForResult(intent, QR_CAPTURE)
        } catch (_: Exception) {
            qrFile?.delete()
            qrFile = null
            feedback.text = "无法打开相机，可读取二维码图片或输入绑定码"
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == NEARBY_PHOTO) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) readNearbyPhoto(uri)
            else channelPhotoRequest = null
            return
        }
        if (requestCode == LOGIN_PACKAGE) {
            if (resultCode == RESULT_OK) data?.data?.let(::openLoginPackage)
            return
        }
        if (requestCode == QR_CAPTURE || requestCode == QR_IMAGE) {
            val file = qrFile
            qrFile = null
            val handler = qrReceiver
            if (resultCode != RESULT_OK || handler == null) {
                file?.delete()
                return
            }
            val uri = data?.data
            val thumbnail = data?.extras?.get("data") as? Bitmap
            scope.launch {
                try {
                    val code = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val image = if (requestCode == QR_IMAGE && uri != null)
                            FieldImages.read({ contentResolver.openInputStream(uri) }, 1800)
                        else file?.takeIf { it.length() > 0 }?.let { FieldImages.read(it::inputStream, 1800) } ?: thumbnail
                        image?.let(BindingQr::read)
                    }
                    if (handler === qrReceiver) {
                        if (code == null) feedback.text = "未识别到有效的 Protocol X 绑定码"
                        else handler(code)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (handler === qrReceiver) feedback.text = "二维码图片无法读取"
                } finally {
                    file?.delete()
                }
            }
            return
        }
        if (requestCode != PHOTO_CAPTURE) return
        val request = pendingPhoto
        val file = photoFile
        photoFile = null
        pendingPhoto = null
        val image = try {
            if (resultCode == RESULT_OK && request?.client === client && request?.event == eventId)
                file?.takeIf { it.length() > 0 }?.let { FieldImages.read(it::inputStream, 1400) }
                    ?: data?.extras?.get("data") as? Bitmap
            else null
        } finally {
            file?.delete()
        }
        if (request == null || image == null) return
        val view = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = dp(310)
            setImageBitmap(image)
        }
        AlertDialog.Builder(this).setTitle("发给任务「" + request.title + "」").setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("发送照片") { _, _ ->
                if (request.client === client && request.event == eventId) {
                    try {
                        val jpeg = ByteArrayOutputStream().use { output ->
                            for (quality in listOf(88, 74, 58)) {
                                output.reset()
                                check(image.compress(Bitmap.CompressFormat.JPEG, quality, output))
                                if (output.size() <= 1_000_000) break
                            }
                            check(output.size() <= 1_000_000) { "照片超过上传大小限制，试着减少画面细节" }
                            output.toByteArray()
                        }
                        photoOutbox.add(request.client.profile.getString("actor"), request.client.origin,
                            request.event, request.task, jpeg)
                        photoStatus.text = "照片待发送"
                        flushPhotoOutbox()
                    } catch (error: Exception) {
                        photoStatus.text = errorMessage(error)
                    }
                }
            }.show()
    }

    private fun flushPhotoOutbox() {
        val active = client?.takeIf { it.bound } ?: return
        if (photoJob?.isActive == true || !visible) return
        val owner = active.profile.getString("actor")
        val entries = photoOutbox.pending(owner, active.origin)
        if (entries.isEmpty()) return
        photoJob = scope.launch {
            for (entry in entries) {
                if (active !== client) break
                try {
                    active.uploadPhoto(entry)
                    photoOutbox.complete(entry)
                    if (active === client) photoStatus.text = "照片已送达任务"
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (active === client) photoStatus.text = "照片待发送，可在恢复联网后重试"
                    // A rejected entry should not prevent other tasks from syncing.
                }
            }
        }
    }

    private fun showTaskPhotos(task: JSONObject) = fieldCall { active, event ->
        val key = task.getJSONObject("ref").getString("id")
        val list = active.photos(event, key).getJSONArray("items").objects()
        if (active !== client || event != eventId) return@fieldCall
        val dialog = AlertDialog.Builder(this).setTitle("任务照片")
        if (list.isEmpty()) dialog.setMessage("还没有送达的照片")
        else dialog.setItems(list.map { it.getString("speaker") + " · " + recordTime(it.getString("sent_at")) }.toTypedArray()) { _, index ->
            fieldCall { source, selected ->
                if (source !== active || selected != event) return@fieldCall
                val jpeg = source.photo(selected, key, list[index].getString("id"))
                if (source !== client || selected != eventId) return@fieldCall
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 1400) sample *= 2
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
                    inSampleSize = sample
                }) ?: error("照片无法读取")
                val image = ImageView(this).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bitmap)
                }
                AlertDialog.Builder(this).setTitle("现场照片").setView(image).setPositiveButton("关闭", null).show()
            }
        }
        dialog.setNegativeButton("关闭", null).show()
    }

    private data class PhotoRequest(val client: OpsClient, val event: String, val task: String, val title: String)

    private fun createVideoPage(container: LinearLayout) {
        videoCard = card(container)
        val header = row(videoCard)
        addWeighted(header, text("现场画面", 22, INK, true))
        val back = button("返回对话").apply { setOnClickListener { showSection(Section.LIVE) } }
        header.addView(back, LinearLayout.LayoutParams(WRAP, WRAP))
        videoLabel = liveText()
        put(videoCard, videoLabel, 7)
        videoFrame = FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
            visibility = View.GONE
        }
        val previewHeight = (resources.configuration.screenHeightDp * 0.46f).toInt().coerceIn(160, 470)
        videoCard.addView(videoFrame, LinearLayout.LayoutParams(MATCH, dp(previewHeight)).apply { topMargin = dp(10) })
        val controls = row(videoCard)
        videoSend = button("开相机回传", primary = true).apply {
            setOnClickListener { toggleVideoCamera() }
        }
        videoRetry = button("重试观看").apply {
            setOnClickListener { connectVideo() }
        }
        addWeighted(controls, videoSend)
        addWeighted(controls, videoRetry, 8)
    }

    private fun voiceConfigured(): Boolean {
        val event = eventId ?: return false
        return if (client?.bound == true) voiceEventId != null else event == voiceEventId
    }

    private fun voiceAvailable() = voiceConfigured() &&
        (client?.bound != true || (radio?.kind == "group" && channelVoice().optBoolean("enabled", true)))


    private fun videoAvailable() = videoEnabled && voiceAvailable() && radio?.kind != "direct"

    private fun canUseVideo() = visible && section == Section.VIDEO && videoAvailable() && client != null

    private fun toggleVideoCamera() {
        if (!canUseVideo() || cameraPermissionEvent != null) return
        when {
            videoState.cameraRequested -> video?.stopCamera()
            startCameraOnConnect -> {
                startCameraOnConnect = false
                updateVideo(videoState)
            }
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED -> {
                cameraPermissionEvent = eventId
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
            }
            else -> startVideoCamera()
        }
    }

    private fun startVideoCamera() {
        if (videoState.ready) {
            video?.startCamera()
            return
        }
        startCameraOnConnect = true
        if (video == null) connectVideo() else updateVideo(videoState)
    }

    private fun connectVideo() {
        val selected = eventId ?: return
        val connected = client ?: return
        if (!canUseVideo() || video != null) return
        val renderer = TextureViewRenderer(this).apply { contentDescription = "现场视频" }
        videoFrame.addView(renderer, FrameLayout.LayoutParams(MATCH, MATCH))
        val next = VideoChannel(this, renderer, scope, connected, selected, BuildConfig.DEBUG, radio) { state ->
            if (state.failed) {
                startCameraOnConnect = false
                video = null
                videoFrame.removeAllViews()
            }
            if (state.ready && startCameraOnConnect && canUseVideo()) {
                startCameraOnConnect = false
                video?.startCamera()
            } else {
                updateVideo(state)
            }
        }
        video = next
        next.connect()
    }

    private fun disconnectVideo() {
        cameraPermissionEvent = null
        startCameraOnConnect = false
        video?.close()
        video = null
        if (::videoCard.isInitialized) {
            videoFrame.removeAllViews()
            updateVideo(VideoState(VIDEO_IDLE_TEXT))
        }
    }

    private fun updateVideo(state: VideoState) {
        videoState = state
        videoLabel.text = if (startCameraOnConnect) "正在连接，随后开启相机…" else state.text
        videoFrame.visibility = if (state.showingTrack) View.VISIBLE else View.GONE
        videoRetry.visibility = if (video == null && state.failed) View.VISIBLE else View.GONE
        videoSend.text = when {
            state.sharing -> "停止回传"
            state.cameraRequested || startCameraOnConnect -> "取消回传"
            else -> "开相机回传"
        }
    }

    private fun confirmAction(task: JSONObject, action: JSONObject) {
        if (mutationPending) return
        val selected = eventId ?: return
        val label = action.getString("label")
        AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage(task.getString("title"))
            .setNegativeButton("取消", null)
            .setPositiveButton("确认") { _, _ ->
                if (eventId == selected) submitAction(selected, task, action)
            }
            .show()
    }

    private fun submitAction(selected: String, task: JSONObject, action: JSONObject, parameters: JSONObject = JSONObject()) {
        val ref = task.getJSONObject("ref")
        val version = task.getInt("version")
        val type = action.getString("id")
        if (type == "confirm_progress") parameters.put("progress_index", action.getInt("progress_index"))
        if (client?.bound == true && role == "staff" && task.has("field_response") &&
            type in setOf("acknowledge", "confirm_progress", "decline_response")) {
            submitQueued(selected, ref, version, type, parameters)
            return
        }
        val operation = "$selected:${ref.refKey()}:$version:$type:$parameters"
        val key = pendingActions.getOrPut(operation) { UUID.randomUUID().toString() }
        mutate(actionFeedback) { service, activeEvent ->
            val receipt = service.action(activeEvent, type, ref, version, key, parameters)
            val receiptId = receipt.getString("id")
            receipts = receipts.filterNot { it.optString("id") == receiptId } + receipt
            if (section == Section.RECORDS) renderTimeline()
            if (section == Section.TASK_DETAIL) renderTaskDetail()
            pendingActions.remove(operation)
            "确认已保存，点本任务回执查看"
        }
    }

    private fun rememberReceipt(receipt: JSONObject) {
        receipts = receipts.filterNot { it.optString("id") == receipt.getString("id") } + receipt
    }

    private suspend fun deliverQueued(active: OpsClient, item: FieldOfflineStore.Queued): JSONObject =
        if (item.action == HELP) active.requestAssistance(item.event, item.ref, item.id, item.parameters.optString("note"))
        else active.action(item.event, item.action, item.ref, item.version, item.id, item.parameters)

    private fun applyQueued(item: FieldOfflineStore.Queued, result: JSONObject) {
        if (item.action == HELP) {
            messages = messages.filterNot { it.optString("id") == result.getString("id") } + result
            val active = client
            val channel = item.parameters.optString("nearby_channel")
            if (active != null && channel.isNotEmpty() &&
                NearbyService.activeScope == nearbyScope(active.localSessionKey, item.event, channel))
                runCatching { NearbyService.session.value?.syncAssistance(item.id) }
        } else rememberReceipt(result)
        fieldOffline.complete(item)
    }

    private fun shareAssistance(active: OpsClient, item: FieldOfflineStore.Queued): Boolean {
        val channel = item.parameters.optString("nearby_channel")
        if (item.action != HELP || channel.isBlank()) return false
        if (NearbyService.activeScope != nearbyScope(active.localSessionKey, item.event, channel)) return false
        val local = NearbyService.session.value ?: return false
        val note = item.parameters.optString("note").ifBlank { "现场需要协助" }
        return runCatching { local.send(note, assistance = true, id = item.id) }.isSuccess
    }

    private fun retryable(error: Exception) = when (error) {
        is OpsException -> error.status >= 500 || error.status in setOf(408, 429)
        is java.io.IOException -> true
        else -> false
    }

    private fun submitQueued(event: String, ref: JSONObject, version: Int, kind: String, parameters: JSONObject,
                             nearby: Boolean = false) {
        if (queuedField.any { it.event == event && it.ref.refKey() == ref.refKey() && (it.action == HELP) == (kind == HELP) }) {
            actionFeedback.text = "本任务有待同步或待核对的操作"
            return
        }
        mutate(actionFeedback) { active, current ->
            if (nearby && kind == HELP) radio?.takeIf { it.kind == "group" }?.let {
                parameters.put("nearby_channel", it.id)
            }
            val entry = fieldOffline.stage(active, current, ref, version, kind, parameters)
            queuedField = fieldOffline.pending(active)
            val saved = shareAssistance(active, entry)
            if (!workspaceOnline) return@mutate if (nearby && !saved)
                "求助已保存待同步 · 附近通信暂不可用" else "本机已保存 · 待同步"
            try {
                applyQueued(entry, deliverQueued(active, entry))
                "服务器已确认，查看回执"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (retryable(error) || error is OpsException && error.status == 401) {
                    workspaceOnline = false
                    "本机已保存 · 待同步"
                } else {
                    fieldOffline.conflict(entry, errorMessage(error))
                    "提交待核对"
                }
            } finally {
                queuedField = fieldOffline.pending(active)
            }
        }
    }

    private fun flushFieldQueue(active: OpsClient, event: String) {
        if (!active.bound || fieldSync?.isActive == true || !visible) return
        val entries = queuedField.filter { it.event == event && it.conflict.isBlank() && it.belongsTo(active) }
        if (entries.isEmpty()) return
        fieldSync = scope.launch {
            var changed = false
            for (entry in entries) {
                if (active !== client || event != eventId) break
                try {
                    shareAssistance(active, entry)
                    val result = deliverQueued(active, entry)
                    if (active === client && event == eventId) applyQueued(entry, result)
                    else fieldOffline.complete(entry)
                    changed = true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (retryable(error) || error is OpsException && error.status == 401) break
                    fieldOffline.conflict(entry, errorMessage(error))
                    changed = true
                }
            }
            if (active === client && event == eventId) {
                queuedField = fieldOffline.pending(active)
                if (section == Section.TASK_DETAIL) renderTaskDetail()
                if (changed && load?.isActive != true) refresh(automatic = true)
            }
        }
    }

    private fun report() {
        val observed = reportField.text.toString().trim()
        if (observed.isEmpty()) {
            reportFeedback.text = "请输入现场情况"
            return
        }
        val subject = selectedTask()?.getJSONObject("ref")
        val identity = Triple(eventId, observed, subject?.refKey())
        val key = pendingReports.getOrPut(identity) { UUID.randomUUID().toString() }
        mutate(reportFeedback) { service, activeEvent ->
            service.report(activeEvent, observed, subject, key)
            pendingReports.remove(identity)
            if (reportField.text.toString().trim() == observed) reportField.text.clear()
            "汇报已写入"
        }
    }

    private fun mutate(status: TextView, action: suspend (OpsClient, String) -> String) {
        val connected = client ?: return
        val selected = eventId ?: return
        if (mutationPending) return
        mutationPending = true
        qrfButton.isEnabled = false
        load?.cancel()
        reportButton.isEnabled = false
        taskSelector.isEnabled = false
        workspaceMenu.isEnabled = false
        dispatchButton.isEnabled = false
        status.text = "正在提交…"
        scope.launch {
            try {
                status.text = action(connected, selected)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status.text = errorMessage(error)
            } finally {
                mutationPending = false
                reportButton.isEnabled = hasWorkspace
                taskSelector.isEnabled = true
                workspaceMenu.isEnabled = true
                dispatchButton.isEnabled = true
                if (section == Section.TASK_DETAIL) renderTaskDetail()
                if (visible) refresh(automatic = true)
            }
        }
    }

    private fun logout() {
        if (mutationPending) return
        finishConference(returnToRadio = false)
        restoreRadioAfterCall = false
        val session = client
        nearbyProvision?.cancel()
        nearbyPanel.select(null)
        channelDraftKey = null
        channelComposerScope = null
        channelRecipientActor = null
        channelPhoto = null
        channelPhotoRequest = null
        privateReader = null
        privateContent.clear()
        channelDrafts.clear()
        if (session != null) events.forEach { event ->
            runCatching { nearbySettings.clear(session.localSessionKey, event.getString("id")) }
        }
        disconnectVoice("已退出频道")
        disconnectVideo()
        stopReplay()
        workspaceSync?.cancel()
        load?.cancel()
        historyJob?.cancel()
        photoJob?.cancel()
        fieldSync?.cancel()
        queuedField = emptyList()
        workspaceOnline = false
        if (session != null) {
            voiceWarmup?.cancel()
            warmAttempts.clear()
            voiceCache.clear(session)
        }
        if (session?.bound == true) fieldOffline.clearCache(session)
        fieldSession.clear()
        channelLoad?.cancel()
        agentDialog?.dismiss()
        clearChannelCursor()
        dispatchInstructions = emptyList()
        agentIncidents = emptyList()
        agentActivity = emptyList()
        shownIncidents.clear()
        ArrivalWatchService.end(this, clearAlerts = true)
        arrivalCards = emptyMap()
        arrivalView?.releasePhotos()
        arrivalView = null
        fieldNotice = null
        if (session?.bound == true) scope.launch { runCatching { session.fieldLogout() } }
        radio = null
        groupBeforeDirect = null
        directoryData = JSONObject()
        olderBefore = null
        eventId = null
        client = null
        lastHeard = null
        receiptScope = null
        taskDetailKey = null
        taskFilter = TaskStage.ALL
        drafts.clear()
        pendingActions.clear()
        pendingReports.clear()
        section = Section.LIVE
        showConnectionForm()
    }

    private fun connectVoice() {
        val connected = client ?: return
        val selected = eventId ?: return
        val service = voiceService ?: return
        if (!visible || !voiceAvailable()) return
        onlineVoiceRequested = true
        onlineVoiceAttemptAt = android.os.SystemClock.elapsedRealtime()
        service.join(VoiceWorkspace(connected, events, selected, radio?.name ?: currentEventTitle(), role,
            callsign, videoEnabled, archiveEnabled, radio, voiceEventId), voiceOwner)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getPreferences(MODE_PRIVATE)
            if (!prefs.getBoolean("voice_notification_requested", false)) {
                prefs.edit().putBoolean("voice_notification_requested", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
            }
        }
    }

    private fun restoreVoiceWorkspace(workspace: VoiceWorkspace) {
        client = workspace.client
        events = workspace.events
        voiceEventId = workspace.voiceEventId
        radio = workspace.target
        role = workspace.role
        callsign = workspace.callsign
        videoEnabled = workspace.videoEnabled
        archiveEnabled = workspace.archiveEnabled
        showWorkspace(workspace.eventId)
    }

    private fun applyVoiceSession(snapshot: VoiceSessionState) {
        if (client == null || !::voiceLabel.isInitialized) return
        val previousSpeaker = remoteSpeaker
        val previousHeard = lastHeard
        val previousClips = localVoiceClips
        val joinedHere = snapshot.workspace?.let { it.eventId == eventId && it.client === client && it.target == radio } == true
        channelReceiveMuted = joinedHere && snapshot.receiveMuted
        remoteSpeaker = if (joinedHere) snapshot.speaker else null
        if (joinedHere && snapshot.localClips.isNotEmpty())
            localVoiceClips = (snapshot.localClips + localVoiceClips).distinctBy { it.trackSid }.take(16)
        if (joinedHere) snapshot.lastHeard?.let { lastHeard = it }
        if (joinedHere) {
            if (snapshot.phase == VoiceState.LISTENING) {
                onlineVoiceRequested = true
                onlineVoiceEverJoined = true
            }
            updateVoice(snapshot.phase, snapshot.message)
        }
        else updateVoice(VoiceState.OFFLINE, if (snapshot.workspace == null) snapshot.message else "未加入频道")
        val latestChanged = previousSpeaker != remoteSpeaker || previousHeard != lastHeard
        if (latestChanged || previousClips != localVoiceClips) renderConversation()
        if (joinedHere && latestChanged && remoteSpeaker == null && archiveEnabled && visible && hasWorkspace && load?.isActive != true) {
            syncChannel()
        }
    }

    private fun disconnectVoice(message: String) {
        onlineVoiceRequested = false
        onlineVoiceEverJoined = false
        voiceService?.leave(message)
        remoteSpeaker = null
        if (::voiceLabel.isInitialized) updateVoice(VoiceState.OFFLINE, message)
    }

    private fun nearbyVoiceFallback() = Build.VERSION.SDK_INT >= 29 && client?.bound == true &&
        radio?.kind == "group" && channelVoice().optBoolean("enabled", true) && section == Section.LIVE &&
        voiceState in setOf(VoiceState.OFFLINE, VoiceState.CONNECTING) &&
        (!workspaceOnline || (onlineVoiceRequested && (onlineVoiceEverJoined || voiceState == VoiceState.OFFLINE)))

    private fun updateVoice(state: VoiceState, message: String) {
        voiceState = state
        val available = voiceAvailable()
        val joined = available && state in setOf(VoiceState.LISTENING, VoiceState.REQUESTING, VoiceState.SPEAKING)
        voiceLabel.text = message
        val sendingTail = message.startsWith("正在发送尾音")
        joinButton.visibility = if (state == VoiceState.OFFLINE && available) View.VISIBLE else View.GONE
        joinButton.isEnabled = voiceService != null && available && state == VoiceState.OFFLINE
        leaveButton.visibility = if (state == VoiceState.OFFLINE) View.GONE else View.VISIBLE
        leaveButton.text = if (state == VoiceState.CONNECTING) "取消连接" else "离开语音"
        channelMute.visibility = if (joined) View.VISIBLE else View.GONE
        channelMute.text = if (channelReceiveMuted) "取消静音" else "静音"
        val fallback = nearbyVoiceFallback()
        pttButton.isEnabled = fallback || available && voiceService != null && joined && !sendingTail
        pttButton.text = when (state) {
            VoiceState.REQUESTING -> "按住说话"
            VoiceState.SPEAKING -> if (sendingTail) "正在发送尾音…" else "松开结束"
            else -> if (fallback) "按住录离线留言" else "按住说话 · PTT"
        }
        pttButton.contentDescription = when (state) {
            VoiceState.REQUESTING -> "正在接通，可以说话，松开取消"
            VoiceState.SPEAKING -> "正在发言，松开结束"
            else -> when {
                fallback -> "按住录离线留言，松开发送"
                joined -> "按住说话，松开结束"
                else -> "按住说话，加入频道后可用"
            }
        }
        volumeControlStream = if (joined) AudioManager.STREAM_VOICE_CALL else AudioManager.USE_DEFAULT_STREAM_TYPE
        pttButton.backgroundTintList = ColorStateList.valueOf(
            if (!pttButton.isEnabled) CARD_BORDER else if (state == VoiceState.SPEAKING) ACCENT else ACTIVE
        )
        pttButton.setTextColor(if (pttButton.isEnabled) ON_ACCENT else MUTED)
        renderSpeaker()
        if (section == Section.CHANNELS) renderChannelDirectory()
        updateBottomControls()
    }

    private fun renderSpeaker() {
        val speaker = remoteSpeaker?.takeUnless { voiceState in setOf(VoiceState.OFFLINE, VoiceState.SPEAKING) }
        val available = voiceAvailable()
        speakerLabel.text = speaker?.let { "${it.name} 正在发言" } ?: when (voiceState) {
            VoiceState.REQUESTING -> "申请话权"
            VoiceState.SPEAKING -> "发言中"
            else -> "收听中"
        }
        channelConnection.text = if (!available) {
            when {
                client?.bound == true && radio == null -> "频道"
                radio?.kind == "direct" -> "一对一会话"
                else -> "语音未开启"
            }
        } else when (voiceState) {
            VoiceState.OFFLINE -> "语音就绪"
            VoiceState.CONNECTING -> "连接中"
            VoiceState.LISTENING -> if (channelReceiveMuted) "已静音" else "收听中"
            VoiceState.REQUESTING -> "申请话权"
            VoiceState.SPEAKING -> "发言中"
        }
        channelConnection.setTextColor(if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.SPEAKING) ONLINE else MUTED)
        channelState.text = when {
            client?.bound == true && radio == null -> when {
                directoryData.has("channels") -> "暂无分配的频道"
                workspaceOnline -> "正在同步频道…"
                else -> "联网后同步频道"
            }
            radio?.kind == "direct" -> "从私呼联系人发起通话"
            !voiceConfigured() -> "服务器未启用实时对讲"
            !available -> "行动语音尚未开启"
            voiceState == VoiceState.OFFLINE && voiceLabel.text.toString() in setOf("", "未加入频道", "已退出频道") -> ""
            speaker != null -> "${speaker.name} 正在发言"
            voiceState == VoiceState.LISTENING -> ""
            voiceState == VoiceState.SPEAKING -> "$callsign 正在发言"
            else -> voiceLabel.text
        }
        pttHint.text = when {
            nearbyVoiceFallback() -> "附近离线 · 松开发送语音留言"
            voiceState == VoiceState.SPEAKING -> voiceLabel.text
            voiceState == VoiceState.REQUESTING -> voiceLabel.text
            voiceState == VoiceState.CONNECTING -> "正在加入频道"
            pttButton.isEnabled -> ""
            available -> "加入频道后可发言"
            else -> "频道就绪后可发言"
        }
    }

    private fun updateBottomControls() {
        val workspaceVisible = client != null && !keyboardVisible
        val radioPage = section == Section.LIVE
        val joined = voiceAvailable() && voiceState in setOf(VoiceState.LISTENING, VoiceState.REQUESTING, VoiceState.SPEAKING)
        voiceDock.visibility = if (workspaceVisible && ((radioPage && voiceAvailable()) || joined || nearbyVoiceFallback()))
            View.VISIBLE else View.GONE
        if (::dockSummary.isInitialized) {
            dockSummary.visibility = if (radioPage) View.GONE else View.VISIBLE
            pttHint.visibility = if (radioPage && pttHint.text.isNotEmpty()) View.VISIBLE else View.GONE
        }
        replayBar.visibility = if (workspaceVisible && ::replayFeedback.isInitialized && replayFeedback.text.isNotBlank())
            View.VISIBLE else View.GONE
    }

    private fun manageOfflineCaptions() {
        val engine = OfflineCaptions.forApp(this)
        if (engine.installed) {
            showConnectionStatus("离线字幕包已安装。说完 PTT 后，手机可先给出字幕。")
            return
        }
        val active = client ?: return
        if (captionDownload?.isActive == true) return
        AlertDialog.Builder(this).setTitle("下载离线字幕包")
            .setMessage("只需下载一次，约 240 MB。下载后，频道字幕可先在手机本机生成。")
            .setNegativeButton("取消", null).setPositiveButton("下载") { _, _ ->
                val dialog = AlertDialog.Builder(this).setTitle("正在下载离线字幕包")
                    .setMessage("0%").setNegativeButton("暂停") { _, _ -> captionDownload?.cancel() }.create()
                dialog.show()
                var displayed = -1
                captionDownload = scope.launch {
                    try {
                        engine.install(active) { percent ->
                            if (percent != displayed) {
                                displayed = percent
                                scope.launch { if (dialog.isShowing) dialog.setMessage("$percent%") }
                            }
                        }
                        dialog.dismiss()
                        val ready = engine.warm()
                        showConnectionStatus(if (ready) "离线字幕已就绪" else "模型已下载，本机暂时无法加载")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        dialog.dismiss()
                        showConnectionStatus(errorMessage(error))
                    }
                }
                dialog.setOnCancelListener { captionDownload?.cancel() }
            }.show()
    }

    private fun chooseOutput() {
        val current = voiceService ?: return
        val outputs = current.audioOutputs()
        val needsBluetooth = Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        if (outputs.isEmpty() && !needsBluetooth) return
        val labels = outputs.map(::outputLabel) + if (needsBluetooth) listOf("授权蓝牙通话设备") else emptyList()
        AlertDialog.Builder(this).setTitle("音频输出")
            .setSingleChoiceItems(labels.toTypedArray(), outputs.indexOf(current.currentOutput())) { dialog, index ->
                dialog.dismiss()
                if (index == outputs.size && needsBluetooth && Build.VERSION.SDK_INT >= 31)
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), BLUETOOTH_ROUTE_PERMISSION)
                else outputs.getOrNull(index)?.let(current::selectOutput)
            }
            .setNegativeButton("关闭", null).show()
    }

    private fun playHapticCue(cue: VoiceCue) {
        if (!::pttButton.isInitialized) return
        val feedback = when (cue) {
            VoiceCue.ENDED -> HapticFeedbackConstants.KEYBOARD_TAP
            VoiceCue.BUSY -> HapticFeedbackConstants.REJECT
        }
        pttButton.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) feedback else HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun deviceCallsign() = when (Build.MODEL.uppercase().replace('_', '-')) {
        "SM-G9980" -> "S21"
        "SM-S9480" -> "S26"
        else -> Build.MODEL.take(32)
    }

    private fun startPress() {
        if (!visible || !hasWindowFocus() || voiceState != VoiceState.LISTENING) return
        stopReplay()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pttButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            stopReplay()
        voiceService?.startPress(voiceOwner)
        } else {
            pttHint.text = "允许麦克风权限后，再按住说话"
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
        }
    }

    private fun saveDraft() {
        val selected = eventId ?: return
        val previousKey = drafts[selected]?.subjectKey
        val subjectKey = if (hasWorkspace) selectedTask()?.getJSONObject("ref")?.refKey() else previousKey
        drafts[selected] = Draft(reportField.text.toString(), subjectKey)
    }

    private fun selectedTask() = tasks.getOrNull(taskSelector.selectedItemPosition - 1)

    private fun JSONObject.refKey() = listOf(getString("ontology_id"), getString("object_type"), getString("id")).joinToString("\u0000")

    private fun fieldPersonLabel(actor: String): String {
        val profile = client?.profile
        if (profile?.optString("actor") == actor) {
            profile.optString("name").takeIf(String::isNotBlank)?.let { return it }
        }
        return people().firstOrNull { it.optString("actor") == actor }?.optString("name")
            ?: if (actor.startsWith("person-")) "工作人员" else actorLabel(actor)
    }

    private fun receiptActor(record: JSONObject, key: String = "actor") =
        record.optString("speaker").takeUnless { it.isBlank() || it == "null" } ?: fieldPersonLabel(record.optString(key))

    private fun actorLabel(actor: String) = when (actor) {
        "demo-dispatcher" -> "调度员"
        "demo-staff" -> "工作人员"
        else -> actor
    }

    private fun liveText() = text("", 14, MUTED).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun input(label: String, type: Int) = EditText(this).apply {
        hint = label
        inputType = type
        setTextColor(INK)
        setHintTextColor(MUTED)
        setTextSize(16f)
    }

    private fun dropdown(values: List<String>) = Spinner(this).apply {
        minimumHeight = dp(48)
        setChoices(this, values)
    }

    private fun setChoices(spinner: Spinner, values: List<String>) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
    }

    private fun card(container: LinearLayout, color: Int = PANEL) = column().apply {
        background = panelColor(color, 14, CARD_BORDER)
        setPadding(dp(16), dp(15), dp(16), dp(15))
        put(container, this, 14)
    }

    private fun panelColor(color: Int, radius: Int, border: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), border)
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(size.toFloat())
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(label: String, primary: Boolean = false) = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        setTextSize(16f)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(MUTED, INK)))
        val fill = if (primary) ACTIVE else CARD_BORDER
        if (primary) setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(MUTED, ON_ACCENT)))
        background = RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), panelColor(fill, 10, fill), null)
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(BACKGROUND, fill),
        )
    }

    private fun linkButton(label: String) = button(label).apply {
        backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(0x22F6CA97), null, GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(6).toFloat()
        })
        setTextColor(ACCENT)
        setPadding(0, 0, dp(4), 0)
        minWidth = 0
        minimumWidth = 0
    }

    private fun backButton(label: String) = linkButton("‹  " + label).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setOnClickListener { parentSection()?.let(::showSection) }
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    private fun row(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        put(parent, this, 6)
    }

    private fun put(parent: LinearLayout, view: View, top: Int) {
        parent.addView(view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) })
    }

    private fun addWeighted(parent: LinearLayout, view: View, start: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(start) })
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun errorMessage(error: Exception) = when (error) {
        is OpsException, is IllegalStateException -> error.message ?: "请求未完成"
        else -> "连接失败，请检查服务地址和网络"
    }

    private data class Draft(val text: String, val subjectKey: String?)
    private data class ReceiptScope(val key: String, val title: String)

    private enum class Section(val label: String) {
        LIVE("频道"), CHANNELS("工作频道"), TASKS("任务"), TASK_DETAIL("任务详情"), REPORT("现场汇报"),
        RECORDS("记录"), VIDEO("现场画面"), MEMBERS("频道成员"),
    }

    private data class Session(
        val client: OpsClient,
        val events: List<JSONObject>,
        val voiceEventId: String?,
        val videoEnabled: Boolean,
        val archiveEnabled: Boolean,
        val section: Section,
        val role: String,
        val eventId: String?,
        val drafts: Map<String, Draft>,
        val pendingActions: Map<String, String>,
        val pendingReports: Map<Triple<String?, String, String?>, String>,
        val receiptScope: ReceiptScope?,
        val taskFilter: TaskStage,
        val taskDetailKey: String?,
        val lastHeard: VoiceSpeaker?,
        val radio: RadioTarget?,
        val groupBeforeDirect: RadioTarget?,
    )

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val CAMERA_PERMISSION_REQUEST = 11
        private const val PHOTO_PERMISSION_REQUEST = 15
        private const val PHOTO_CAPTURE = 16
        private const val QR_PERMISSION = 17
        private const val QR_CAPTURE = 18
        private const val QR_IMAGE = 19
        private const val LOGIN_PACKAGE = 23
        private const val CONFERENCE_PERMISSION = 21
        private const val BLUETOOTH_ROUTE_PERMISSION = 46
        private const val ARRIVAL_NOTIFICATION_PERMISSION = 22
        private const val WORKSPACE_RECONCILE_MS = 60_000L
        private const val WORKSPACE_RETRY_MS = 10_000L
        private const val CHANNEL_READ_DWELL_MS = 250L
        private const val NEARBY_PHOTO = 4285
        private const val HELP = "request_assistance"
        private const val VIDEO_IDLE_TEXT = "等待现场画面"
        private const val DEFAULT_ADDRESS = "https://47.238.64.250:9443"
        private val BACKGROUND = Color.rgb(16, 18, 26)
        private val PANEL = Color.rgb(26, 29, 41)
        private val CHANNEL = Color.rgb(35, 39, 55)
        private val DOCK = Color.rgb(20, 22, 32)
        private val CARD_BORDER = Color.rgb(53, 57, 75)
        private val INK = Color.rgb(247, 243, 239)
        private val MUTED = Color.rgb(184, 186, 201)
        private val ONLINE = Color.rgb(150, 224, 194)
        private val NETWORK_ROUTE = Color.rgb(139, 189, 252)
        private val WIFI_ROUTE = Color.rgb(114, 216, 182)
        private val BLE_ROUTE = Color.rgb(204, 177, 249)
        private val SEND_ERROR = Color.rgb(255, 161, 161)
        private val ACCENT = Color.rgb(246, 202, 151)
        private val ACTIVE = Color.rgb(240, 187, 126)
        private val ON_ACCENT = Color.rgb(38, 24, 18)
        private val RECORD_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}

internal fun channelTextFullyVisible(visibleHeight: Int, bodyHeight: Int, viewportHeight: Int): Boolean {
    val required = minOf(bodyHeight, viewportHeight)
    return required > 0 && visibleHeight >= required
}
