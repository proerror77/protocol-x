package com.mandonothing.ops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Follows assignments during an explicitly started field shift, independently of PTT. */
class ArrivalWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        createChannels(manager)
        val stop = PendingIntent.getBroadcast(
            this, 0, Intent(this, ArrivalWatchStopReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val home = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val tracking = Notification.Builder(this, STATUS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("航班值班提醒已开启")
            .setContentText("后台跟进本人接待任务的航班动态")
            .setContentIntent(home)
            .addAction(Notification.Action.Builder(null, "结束值班", stop).build())
            .setOngoing(true).setShowWhen(false).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(SERVICE_ID, tracking, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(SERVICE_ID, tracking)
        } catch (_: IllegalStateException) {
            stopSelf(startId)
            return START_NOT_STICKY
        } catch (_: SecurityException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val stored = FieldSessionStore(this).read()
        val active = stored?.let {
            runCatching { OpsClient(it.first, it.second, BuildConfig.DEBUG, "") }.getOrNull()
        }?.takeIf { it.bound }
        if (active == null || !canNotify(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        scope.launch { watch(active, manager) }
        return START_NOT_STICKY
    }

    private suspend fun watch(client: OpsClient, manager: NotificationManager) {
        val cursors = getSharedPreferences("arrival_notice_cursor", MODE_PRIVATE)
        var cursor = cursors.getLong(client.localSessionKey, 0)
        var backoff = POLL_MS
        while (true) {
            try {
                if (!canNotify(this)) {
                    stopSelf()
                    return
                }
                val payload = client.arrivalNotices(cursor)
                val items = payload.getJSONArray("items")
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    post(manager, client.origin, item)
                    cursor = item.getLong("seq")
                    cursors.edit().putLong(client.localSessionKey, cursor).apply()
                }
                backoff = POLL_MS
                if (payload.optBoolean("has_more")) continue
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: OpsException) {
                if (error.status in setOf(401, 403, 404)) {
                    stopSelf()
                    return
                }
                backoff = (backoff * 2).coerceAtMost(60_000)
            } catch (_: Exception) {
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
            delay(backoff)
        }
    }

    private fun post(manager: NotificationManager, origin: String, item: JSONObject) {
        val event = item.getString("event_id")
        val task = item.getString("task_id")
        val seq = item.getLong("seq")
        val resume = Intent(this, MainActivity::class.java)
            .setAction(OPEN_ARRIVAL)
            .setData(Uri.Builder().scheme("protocolx").authority("arrival")
                .appendPath(event).appendPath(task).appendPath(seq.toString()).build())
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("field_event", event)
            .putExtra("field_task", task)
            .putExtra("arrival_origin", origin)
        val target = PendingIntent.getActivity(this, 0, resume, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val publicNotice = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("有新的接机航班动态").build()
        val notification = Notification.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(item.getString("flight") + " · " + item.getString("label"))
            .setContentText("点按查看球员接待卡")
            .setShowWhen(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotice)
            .setContentIntent(target)
            .setAutoCancel(true)
            .build()
        manager.notify("arrival-$seq", ALERT_ID, notification)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        internal const val OPEN_ARRIVAL = "com.mandonothing.ops.OPEN_ARRIVAL"
        private const val STATUS_CHANNEL = "arrival_shift"
        private const val ALERT_CHANNEL = "arrival_updates"
        private const val SERVICE_ID = 731
        private const val ALERT_ID = 732
        private const val POLL_MS = 12_000L
        @Volatile internal var running = false
            private set

        internal fun createChannels(manager: NotificationManager) {
            manager.createNotificationChannel(NotificationChannel(STATUS_CHANNEL, "航班值班", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "接机航班动态", NotificationManager.IMPORTANCE_HIGH))
        }

        internal fun canNotify(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(ALERT_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        internal fun end(context: Context, clearAlerts: Boolean = false) {
            context.stopService(Intent(context, ArrivalWatchService::class.java))
            if (clearAlerts) {
                val manager = context.getSystemService(NotificationManager::class.java)
                for (notice in manager.activeNotifications) {
                    if (notice.id == ALERT_ID) manager.cancel(notice.tag, ALERT_ID)
                }
            }
        }
    }
}

/** Notification action can stop an active watch without relaunching the app. */
class ArrivalWatchStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ArrivalWatchService.end(context)
    }
}
