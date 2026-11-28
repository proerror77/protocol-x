package com.mandonothing.ops

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object ArrivalCountdown {
    private val zone = ZoneId.of("Asia/Hong_Kong")
    private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    private val dayTime = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone)

    fun instant(value: String): Instant? = runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    fun hkt(value: Instant) = dayTime.format(value) + " HKT"

    fun lines(
        windowStart: Instant?, windowEnd: Instant?, validUntil: Instant?,
        met: Instant?, cancelled: Boolean, now: Instant,
    ): Pair<String, String> {
        if (met != null) return "已会合" to hkt(met)
        if (cancelled) return "航班已取消" to "请等候调度安排"
        if (windowStart == null || windowEnd == null) return "等待会合估计" to "机场提供到达时间后更新"
        val range = if (windowStart.atZone(zone).toLocalDate() == windowEnd.atZone(zone).toLocalDate())
            "${clock.format(windowStart)}–${clock.format(windowEnd)} HKT"
        else "${hkt(windowStart)} – ${hkt(windowEnd)}"
        if (validUntil != null && now.isAfter(validUntil)) return "机场预计时间待更新" to "上次估计 · $range"
        fun minutes(time: Instant) = ((Duration.between(now, time).seconds.coerceAtLeast(0) + 59) / 60)
        val from = minutes(windowStart)
        val to = minutes(windowEnd)
        return when {
            to == 0L -> "已超过估算区间" to "$range · 等待现场确认"
            from == 0L -> "预计 $to 分钟内可会合" to "$range · 已进入估算区间"
            else -> "预计还有 $from–$to 分钟" to "$range · 包含入境及行李预留"
        }
    }
}

internal class ArrivalCardView(
    context: Context,
    private val scope: CoroutineScope,
    private val client: OpsClient,
    private val event: String,
    val planId: String,
) : LinearLayout(context) {
    private val label = label("", 19, true)
    private val portraits = LinearLayout(context)
    private val flight = label("", 16, true)
    private val countdown = label("", 25, true, Color.rgb(154, 235, 205))
    private val estimate = label("", 13, color = MUTED)
    private val facts = label("", 15)
    private val maps = Button(context).apply { text = "Google Maps 路线" }
    private val uber = Button(context)
    private var data = JSONObject()
    private var photoSignature: String? = null
    private val photoJobs = mutableListOf<Job>()
    private val timer = object : Runnable {
        override fun run() {
            updateCountdown()
            postDelayed(this, 15_000)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(13), dp(13), dp(13), dp(13))
        addView(label)
        val strip = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(portraits)
        }
        addView(strip, item(10))
        addView(flight, item(12))
        addView(countdown, item(12))
        addView(estimate, item(3))
        addView(facts, item(12))
        addView(maps, item(8))
        addView(uber, item(4))
    }

    fun bind(card: JSONObject) {
        data = card
        label.text = if (data.optBoolean("synthetic")) "球员接待 · 演练" else "球员接待"
        val status = card.optJSONObject("observation")
        val statusName = when (status?.optString("status")) {
            "scheduled" -> "计划到达"
            "estimated" -> "预计到达"
            "delayed" -> "机场通报延误"
            "landed" -> "已落地"
            "at_gate" -> "已到达机位"
            "cancelled" -> "航班取消"
            else -> "等待香港机场数据"
        }
        val time = status?.let { it.value("actual").ifEmpty { it.value("estimated") }.ifEmpty { it.value("scheduled") } }.orEmpty()
        val delay = status?.optInt("delay_minutes") ?: 0
        flight.text = listOf(card.optString("flight"), statusName, time, if (delay > 0) "延误 $delay 分钟" else "")
            .filter(String::isNotBlank).joinToString(" · ")
        val hotel = card.optJSONObject("hotel")
        facts.text = buildList {
            add("会合点  " + card.optString("meeting_point"))
            status?.value("arrivals_hall")?.takeIf(String::isNotBlank)?.let { add("到港厅  $it") }
            status?.value("baggage_belt")?.takeIf(String::isNotBlank)?.let { add("行李转盘  $it") }
            add("接机员  " + card.optString("greeter_name"))
            add("司机及车辆  " + card.optString("driver_name") + " · " + card.optJSONObject("task")?.optString("resource_label").orEmpty())
            if (hotel != null) {
                add("酒店  " + hotel.optString("name") + if (hotel.optBoolean("confirmed")) " · 订房已确认" else " · 订房待确认")
                hotel.value("address").takeIf(String::isNotBlank)?.let { add("酒店地址  $it") }
                hotel.value("instruction").takeIf(String::isNotBlank)?.let { add("交接说明  $it") }
            }
            card.value("notes").takeIf(String::isNotBlank)?.let { add("接待备注  $it") }
            status?.value("source_time")?.let(ArrivalCountdown::instant)?.let {
                add("香港机场更新  " + ArrivalCountdown.hkt(it))
            }
        }.joinToString("\n")
        val links = card.optJSONObject("travel_links")
        bindLink(maps, links?.value("google_directions"), "www.google.com")
        uber.text = if (links?.optBoolean("uber_prefilled") == true) "在 Uber 核对路线及叫车"
            else "打开 Uber · 手动填写地址"
        bindLink(uber, links?.value("uber"), "m.uber.com")
        val signature = card.optJSONArray("players")?.toString().orEmpty() + "|" + card.optJSONObject("photos")?.toString().orEmpty()
        if (signature != photoSignature) {
            photoSignature = signature
            showPortraits(card)
        }
        updateCountdown()
    }

    private fun bindLink(button: Button, value: String?, host: String) {
        val uri = value?.takeIf(String::isNotBlank)?.let(Uri::parse)
            ?.takeIf { it.scheme == "https" && it.host == host && it.port == -1 && it.userInfo == null }
        button.visibility = if (uri == null) View.GONE else View.VISIBLE
        button.setOnClickListener {
            if (uri != null) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "找不到可打开链接的应用", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showPortraits(card: JSONObject) {
        releasePhotos()
        portraits.removeAllViews()
        val records = card.optJSONArray("players") ?: return
        val photos = card.optJSONObject("photos")
        for (index in 0 until records.length()) {
            val player = records.getJSONObject(index)
            val id = player.getString("id")
            val name = player.getString("name")
            val tile = LinearLayout(context).apply { orientation = VERTICAL }
            portraits.addView(tile, LayoutParams(dp(116), LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
            val frame = FrameLayout(context).apply { setBackgroundColor(Color.rgb(53, 57, 75)) }
            tile.addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, dp(113)))
            val hasPhoto = photos?.has(id) == true
            val fallback = label(if (hasPhoto) "照片加载中" else "待补照片", 13, color = MUTED).apply { gravity = Gravity.CENTER }
            frame.addView(fallback, FrameLayout.LayoutParams(-1, -1))
            tile.addView(label(name, 14, true).apply { gravity = Gravity.CENTER }, item(4))
            if (hasPhoto) photoJobs += scope.launch {
                try {
                    val bytes = client.arrivalPhoto(event, planId, id)
                    val bitmap = withContext(Dispatchers.Default) {
                        val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, info)
                        require(info.outWidth in 1..16384 && info.outHeight in 1..16384)
                        val sample = Integer.highestOneBit((maxOf(info.outWidth, info.outHeight) / 480).coerceAtLeast(1))
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: error("Image decode failed")
                    frame.addView(ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = "${name}的接机识别照"
                        setImageBitmap(bitmap)
                    }, FrameLayout.LayoutParams(-1, -1))
                    fallback.visibility = View.GONE
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    fallback.text = "照片暂不可用 · 点按重试"
                    fallback.setOnClickListener {
                        photoSignature = null
                        bind(data)
                    }
                }
            }
        }
    }

    fun releasePhotos() {
        photoJobs.forEach { it.cancel() }
        photoJobs.clear()
    }

    private fun updateCountdown() {
        val window = data.optJSONObject("window")
        val met = data.optJSONObject("task")?.optJSONArray("progress")?.optJSONObject(0)?.value("confirmed_at")
        val (headline, detail) = ArrivalCountdown.lines(
            window?.value("from")?.let(ArrivalCountdown::instant), window?.value("to")?.let(ArrivalCountdown::instant),
            window?.value("valid_until")?.let(ArrivalCountdown::instant), met?.let(ArrivalCountdown::instant),
            data.optJSONObject("observation")?.optString("status") == "cancelled", Instant.now(),
        )
        countdown.text = headline
        estimate.text = detail
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        timer.run()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(timer)
        super.onDetachedFromWindow()
    }

    private fun JSONObject.value(key: String) = if (isNull(key)) "" else optString(key)
    private fun item(top: Int) = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5).toInt()
    private fun label(value: String, size: Int, bold: Boolean = false, color: Int = Color.WHITE) = TextView(context).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private companion object {
        val MUTED = Color.rgb(184, 186, 201)
    }
}
