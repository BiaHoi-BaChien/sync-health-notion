package net.biahoi.stepnotionsync

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.ArrayAdapter
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Pressure
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    )
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var statusText: TextView
    private lateinit var stepsPhoneDateText: TextView
    private lateinit var stepsNotionDateText: TextView
    private lateinit var vitalsPhoneDateText: TextView
    private lateinit var vitalsNotionDateText: TextView
    private lateinit var autoSyncResultText: TextView
    private lateinit var autoSyncDetailsToggleButton: Button
    private lateinit var autoSyncDetailsContainer: LinearLayout
    private lateinit var autoSyncLastSuccessText: TextView
    private lateinit var autoSyncLastFailureText: TextView
    private lateinit var autoSyncFailureReasonText: TextView
    private lateinit var autoSyncNextRunText: TextView
    private lateinit var tokenInput: EditText
    private lateinit var stepsDataSourceInput: EditText
    private lateinit var stepsDatePropertyInput: EditText
    private lateinit var stepsPropertyInput: EditText
    private lateinit var vitalsDataSourceInput: EditText
    private lateinit var vitalsMeasuredAtPropertyInput: EditText
    private lateinit var systolicPropertyInput: EditText
    private lateinit var diastolicPropertyInput: EditText
    private lateinit var heartRatePropertyInput: EditText
    private lateinit var autoSyncSpinner: Spinner
    private var currentSyncJob: Job? = null
    private var latestDateRefreshJob: Job? = null
    private var syncDialog: Dialog? = null
    private var syncDialogMessageText: TextView? = null
    private var messageDialog: Dialog? = null
    private var latestDateRefreshDialog: Dialog? = null
    private var autoSyncDetailsExpanded = false
    private val lookbackDays = DEFAULT_LOOKBACK_DAYS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            val message = if (granted.containsAll(requiredPermissions)) {
                "Health Connectの権限が許可されました。"
            } else {
                "最新日付の表示にはHealth Connectの歩数、血圧、心拍の読み取り権限が必要です。"
            }
            setStatusMessage(message, floating = true)
            refreshLatestDates()
        }
        showTopPage()
    }

    override fun onDestroy() {
        currentSyncJob?.cancel()
        latestDateRefreshJob?.cancel()
        dismissSyncDialog()
        dismissLatestDateRefreshDialog()
        super.onDestroy()
    }

    private fun showTopPage() {
        val density = resources.displayMetrics.density
        val padding = (18 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.parseColor("#101820"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Health Notion Sync"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(this).apply {
            text = "設定"
            setOnClickListener { showSettingsPage() }
        })
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "スマホとNotionの最新データ日時"
            textSize = 14f
            setTextColor(Color.parseColor("#AAB7C4"))
            setPadding(0, (4 * density).toInt(), 0, (16 * density).toInt())
        })

        root.addSummaryCard("歩数", "同期方向: スマホ → Notion").also { section ->
            stepsPhoneDateText = section.addDateRow("スマホ側", "送信元")
            stepsNotionDateText = section.addDateRow("Notion側", "送信先")
        }
        root.addSummaryCard("バイタル", "同期方向: Notion → スマホ").also { section ->
            vitalsPhoneDateText = section.addDateRow("スマホ側", "送信先")
            vitalsNotionDateText = section.addDateRow("Notion側", "送信元")
        }
        root.addView(TextView(this).apply {
            text = "自動同期: ${autoSyncLabel(loadAutoSyncTime())}"
            textSize = 14f
            setTextColor(Color.parseColor("#AAB7C4"))
            setPadding(0, 0, 0, (4 * density).toInt())
        })
        root.addSummaryCard("自動同期の最終実行結果", "前回の自動同期").also { section ->
            autoSyncResultText = section.addDateRow("最終結果", "状態")
            autoSyncDetailsToggleButton = section.addButton("") { toggleAutoSyncDetails() }
            updateAutoSyncDetailsToggleButton()
            autoSyncDetailsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (autoSyncDetailsExpanded) View.VISIBLE else View.GONE
            }
            section.addView(autoSyncDetailsContainer)
            autoSyncLastSuccessText = autoSyncDetailsContainer.addDateRow("最終成功", "時刻")
            autoSyncLastFailureText = autoSyncDetailsContainer.addDateRow("最終失敗", "時刻")
            autoSyncFailureReasonText = autoSyncDetailsContainer.addDateRow("失敗理由", "直近")
            autoSyncNextRunText = autoSyncDetailsContainer.addDateRow("次回予定", "WorkManager")
        }

        root.addSyncActions()

        statusText = TextView(this).apply {
            text = "設定後に同期してください。歩数データは1日単位で合算してNotionへ同期します。"
            textSize = 16f
            setTextColor(Color.parseColor("#D9E3EA"))
            setPadding(0, (14 * density).toInt(), 0, 0)
        }
        root.addView(statusText)

        setContentView(centeredScrollContent(root, padding) { refreshLatestDates(showProgress = true) })
        refreshAutoSyncStatus()
        refreshLatestDates()
    }

    private fun showSettingsPage() {
        val density = resources.displayMetrics.density
        val padding = (18 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.parseColor("#101820"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(TextView(this).apply {
            text = "設定"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "バージョン ${appVersionName()}"
            textSize = 14f
            setTextColor(Color.parseColor("#AAB7C4"))
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
        })

        tokenInput = root.addInput("Notion Integration Token", password = true)
        root.addSectionTitle("歩数")
        stepsDataSourceInput = root.addInput("歩数 Data Source ID")
        stepsDatePropertyInput = root.addInput("歩数 Date property name")
        stepsPropertyInput = root.addInput("Steps property name")
        root.addSectionTitle("血圧・心拍")
        vitalsDataSourceInput = root.addInput("血圧 Data Source ID")
        vitalsMeasuredAtPropertyInput = root.addInput("測定日時 property name")
        systolicPropertyInput = root.addInput("最高血圧 property name")
        diastolicPropertyInput = root.addInput("最低血圧 property name")
        heartRatePropertyInput = root.addInput("心拍数 property name")
        root.addSectionTitle("自動同期")
        autoSyncSpinner = root.addAutoSyncSpinner()
        root.addSectionTitle("Health Connect")
        root.addButton("Health Connect権限を許可") { requestHealthPermission() }

        root.addButton("設定を保存") {
            saveSettings()
            setStatusMessage("設定を保存しました。", floating = true)
        }
        root.addButton("TOPへ戻る") { showTopPage() }

        statusText = TextView(this).apply {
            text = "NotionのData Source IDとプロパティ名を入力してください。"
            textSize = 16f
            setTextColor(Color.parseColor("#D9E3EA"))
            setPadding(0, (14 * density).toInt(), 0, 0)
        }
        root.addView(statusText)

        setContentView(centeredScrollContent(root, padding))
        loadSettings()
    }

    private fun centeredScrollContent(
        root: LinearLayout,
        basePadding: Int,
        onPullRefresh: (() -> Unit)? = null
    ): ScrollView {
        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#101820"))
            addView(root)
            if (onPullRefresh != null) {
                var pullStartY = 0f
                val threshold = 92 * resources.displayMetrics.density
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            if (scrollY == 0) {
                                pullStartY = event.y
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (scrollY == 0 && event.y - pullStartY > threshold) {
                                view.performClick()
                                onPullRefresh()
                            }
                        }
                    }
                    false
                }
            }
            setOnApplyWindowInsetsListener { _, insets ->
                @Suppress("DEPRECATION")
                root.setPadding(
                    basePadding + insets.systemWindowInsetLeft,
                    basePadding + insets.systemWindowInsetTop,
                    basePadding + insets.systemWindowInsetRight,
                    basePadding + insets.systemWindowInsetBottom
                )
                insets
            }
        }
    }

    private fun LinearLayout.addSummaryCard(title: String, direction: String): LinearLayout {
        val density = resources.displayMetrics.density
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#17232D"))
                setStroke((1 * density).toInt(), Color.parseColor("#2A3A45"))
            }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
        }
        card.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(context).apply {
            text = direction
            textSize = 13f
            setTextColor(Color.parseColor("#7ED7FF"))
            setPadding(0, (4 * density).toInt(), 0, 0)
        })
        addView(card)
        return card
    }

    private fun LinearLayout.addSyncActions() {
        val density = resources.displayMetrics.density
        val section = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#17232D"))
                setStroke((1 * density).toInt(), Color.parseColor("#2A3A45"))
            }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
        }
        section.addView(TextView(context).apply {
            text = "同期"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addActionButton("歩数", R.drawable.ic_footsteps, rightMarginDp = 6) { syncStepsToNotion() }
        row.addActionButton("血圧・心拍", R.drawable.ic_heart_pulse, leftMarginDp = 6) { syncVitalsToNotion() }
        section.addView(row)
        section.addButton("すべて") { syncAllToNotion() }
        addView(section)
    }

    private fun LinearLayout.addDateRow(label: String, role: String): TextView {
        val density = resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        row.addView(TextView(context).apply {
            text = "$label（$role）"
            textSize = 14f
            setTextColor(Color.parseColor("#AAB7C4"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val value = TextView(context).apply {
            text = "確認中..."
            textSize = 18f
            setTextColor(Color.parseColor("#44D7B6"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(value)
        addView(row)
        return value
    }

    private fun LinearLayout.addSectionTitle(title: String) {
        addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 24, 0, 0)
        })
    }

    private fun LinearLayout.addInput(hintText: String, password: Boolean = false): EditText {
        val density = resources.displayMetrics.density
        val input = EditText(context).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#7D8A96"))
            setTextColor(Color.WHITE)
            setSingleLine(true)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.parseColor("#182630"))
                setStroke((1 * density).toInt(), Color.parseColor("#2D3F4D"))
            }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * density).toInt()
            }
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
        }
        addView(input)
        return input
    }

    private fun LinearLayout.addButton(label: String, onClick: () -> Unit): Button {
        val button = createActionButton(label, null, onClick)
        addView(button)
        return button
    }

    private fun LinearLayout.addActionButton(
        label: String,
        iconResId: Int? = null,
        leftMarginDp: Int = 0,
        rightMarginDp: Int = 0,
        onClick: () -> Unit
    ): Button {
        val density = resources.displayMetrics.density
        val button = createActionButton(label, iconResId, onClick).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = (leftMarginDp * density).toInt()
                rightMargin = (rightMarginDp * density).toInt()
                topMargin = (10 * density).toInt()
            }
        }
        addView(button)
        return button
    }

    private fun createActionButton(label: String, iconResId: Int?, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#081018"))
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.parseColor("#44D7B6"))
            }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * density).toInt()
            }
            if (iconResId != null) {
                setCompoundDrawablesWithIntrinsicBounds(iconResId, 0, 0, 0)
                compoundDrawablePadding = (8 * density).toInt()
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#081018"))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun LinearLayout.addAutoSyncSpinner(): Spinner {
        val density = resources.displayMetrics.density
        val choices = autoSyncChoices()
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                choices.map { it.label }
            )
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(Color.parseColor("#F2F7FA"))
            }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
        }
        addView(spinner)
        return spinner
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("notion", Context.MODE_PRIVATE)
        tokenInput.setText(SecureSettingsStore.loadToken(prefs))
        stepsDataSourceInput.setText(
            prefs.getString("stepsDataSource", prefs.getString("dataSource", prefs.getString("database", "")))
        )
        stepsDatePropertyInput.setText(prefs.getString("stepsDateProperty", prefs.getString("dateProperty", "日付")))
        stepsPropertyInput.setText(prefs.getString("stepsProperty", "歩数"))
        vitalsDataSourceInput.setText(prefs.getString("vitalsDataSource", ""))
        vitalsMeasuredAtPropertyInput.setText(prefs.getString("vitalsMeasuredAtProperty", "日付"))
        systolicPropertyInput.setText(prefs.getString("systolicProperty", "収縮期"))
        diastolicPropertyInput.setText(prefs.getString("diastolicProperty", "拡張期"))
        heartRatePropertyInput.setText(prefs.getString("heartRateProperty", "脈拍"))
        val autoSyncTime = prefs.getString(AUTO_SYNC_TIME_KEY, AUTO_SYNC_OFF) ?: AUTO_SYNC_OFF
        autoSyncSpinner.setSelection(autoSyncChoices().indexOfFirst { it.value == autoSyncTime }.coerceAtLeast(0))
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("notion", Context.MODE_PRIVATE)
        SecureSettingsStore.saveToken(prefs, tokenInput.text.toString().trim())
        prefs.edit()
            .putString("stepsDataSource", stepsDataSourceInput.text.toString().trim())
            .putString("stepsDateProperty", stepsDatePropertyInput.text.toString().trim())
            .putString("stepsProperty", stepsPropertyInput.text.toString().trim())
            .putString("vitalsDataSource", vitalsDataSourceInput.text.toString().trim())
            .putString("vitalsMeasuredAtProperty", vitalsMeasuredAtPropertyInput.text.toString().trim())
            .putString("systolicProperty", systolicPropertyInput.text.toString().trim())
            .putString("diastolicProperty", diastolicPropertyInput.text.toString().trim())
            .putString("heartRateProperty", heartRatePropertyInput.text.toString().trim())
            .putString(AUTO_SYNC_TIME_KEY, autoSyncChoices()[autoSyncSpinner.selectedItemPosition].value)
            .apply()
        scheduleAutoSync(this, loadAutoSyncTime())
    }

    private fun currentConfig(): SyncConfig {
        val prefs = getSharedPreferences("notion", Context.MODE_PRIVATE)
        return SyncConfig(
            token = SecureSettingsStore.loadToken(prefs),
            stepsDataSourceId = prefs.getString("stepsDataSource", prefs.getString("dataSource", "")) ?: "",
            stepsDateProperty = prefs.getString("stepsDateProperty", prefs.getString("dateProperty", "日付")) ?: "日付",
            stepsProperty = prefs.getString("stepsProperty", "歩数") ?: "歩数",
            vitalsDataSourceId = prefs.getString("vitalsDataSource", "") ?: "",
            vitalsMeasuredAtProperty = prefs.getString("vitalsMeasuredAtProperty", "日付") ?: "日付",
            systolicProperty = prefs.getString("systolicProperty", "収縮期") ?: "収縮期",
            diastolicProperty = prefs.getString("diastolicProperty", "拡張期") ?: "拡張期",
            heartRateProperty = prefs.getString("heartRateProperty", "脈拍") ?: "脈拍"
        )
    }

    private fun loadAutoSyncTime(): String {
        return getSharedPreferences("notion", Context.MODE_PRIVATE)
            .getString(AUTO_SYNC_TIME_KEY, AUTO_SYNC_OFF) ?: AUTO_SYNC_OFF
    }

    private fun requestHealthPermission() {
        CoroutineScope(Dispatchers.Main).launch {
            val client = healthConnectClientOrNull()
            if (client == null) {
                setStatusMessage(
                    "Health Connectが利用できません。Pixelの設定でHealth Connectを確認してください。",
                    floating = true
                )
                return@launch
            }
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(requiredPermissions)) {
                setStatusMessage("Health Connectの権限は許可済みです。", floating = true)
                refreshLatestDates()
            } else {
                permissionLauncher.launch(requiredPermissions)
            }
        }
    }

    private fun refreshLatestDates(showProgress: Boolean = false) {
        if (latestDateRefreshJob?.isActive == true) {
            return
        }
        val config = currentConfig()
        latestDateRefreshJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                if (showProgress) {
                    showLatestDateRefreshDialog()
                }
                stepsPhoneDateText.text = "確認中..."
                stepsNotionDateText.text = "確認中..."
                vitalsPhoneDateText.text = "確認中..."
                vitalsNotionDateText.text = "確認中..."

                val client = healthConnectClientOrNull()
                if (client == null) {
                    stepsPhoneDateText.text = "利用不可"
                    vitalsPhoneDateText.text = "利用不可"
                } else {
                    val granted = client.permissionController.getGrantedPermissions()
                    if (granted.contains(HealthPermission.getReadPermission(StepsRecord::class))) {
                        stepsPhoneDateText.text = displayDateTime(readLatestStepsTime(client, recentRecordTimeRange()))
                    } else {
                        stepsPhoneDateText.text = "権限未許可"
                    }
                    if (
                        granted.contains(HealthPermission.getReadPermission(BloodPressureRecord::class)) ||
                            granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class))
                    ) {
                        vitalsPhoneDateText.text = displayDateTime(readLatestVitalsTime(client, granted))
                    } else {
                        vitalsPhoneDateText.text = "権限未許可"
                    }
                }

                withContext(Dispatchers.IO) {
                    val stepsDate = runCatching {
                        if (config.hasStepsSettings()) NotionClient(config).latestStepsDate(lookbackDays) else null
                    }.getOrNull()
                    val vitalsDate = runCatching {
                        if (config.hasVitalsSettings()) NotionClient(config).latestVitalsDate(lookbackDays) else null
                    }.getOrNull()
                    withContext(Dispatchers.Main) {
                        stepsNotionDateText.text = if (config.hasStepsSettings()) displayNotionDateTime(stepsDate) else "設定未完了"
                        vitalsNotionDateText.text = if (config.hasVitalsSettings()) displayNotionDateTime(vitalsDate) else "設定未完了"
                    }
                }
            } finally {
                if (showProgress) {
                    dismissLatestDateRefreshDialog()
                }
                latestDateRefreshJob = null
            }
        }
    }

    private fun refreshAutoSyncStatus() {
        val autoSyncTime = loadAutoSyncTime()
        val status = loadAutoSyncStatus(this)
        autoSyncResultText.text = status.topResultLabel()
        autoSyncLastSuccessText.text = displayTimestampMillis(status.lastSuccessAtMillis)
        autoSyncLastFailureText.text = displayTimestampMillis(status.lastFailureAtMillis)
        autoSyncFailureReasonText.text = status.failureReason.takeIf { it.isNotBlank() } ?: "なし"
        autoSyncNextRunText.text = if (autoSyncTime == AUTO_SYNC_OFF) {
            "自動同期しない"
        } else {
            "確認中..."
        }
        if (autoSyncTime == AUTO_SYNC_OFF) {
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val nextRunAtMillis = withContext(Dispatchers.IO) {
                runCatching {
                    WorkManager.getInstance(applicationContext)
                        .getWorkInfosForUniqueWork(AUTO_SYNC_WORK_NAME)
                        .get()
                        .nextScheduleTimeMillisOrNull()
                }.getOrNull()
            } ?: nextAutoSyncTimeMillis(autoSyncTime)
            autoSyncNextRunText.text = displayTimestampMillis(nextRunAtMillis)
        }
    }

    private fun toggleAutoSyncDetails() {
        autoSyncDetailsExpanded = !autoSyncDetailsExpanded
        autoSyncDetailsContainer.visibility = if (autoSyncDetailsExpanded) View.VISIBLE else View.GONE
        updateAutoSyncDetailsToggleButton()
    }

    private fun updateAutoSyncDetailsToggleButton() {
        autoSyncDetailsToggleButton.contentDescription =
            if (autoSyncDetailsExpanded) "詳細を閉じる" else "詳細を表示"
        autoSyncDetailsToggleButton.setCompoundDrawablesWithIntrinsicBounds(
            0,
            if (autoSyncDetailsExpanded) R.drawable.ic_collapse_window_up else R.drawable.ic_expand_window_down,
            0,
            0
        )
        autoSyncDetailsToggleButton.compoundDrawableTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor("#081018"))
    }

    private fun syncStepsToNotion() {
        val config = currentConfig()
        if (!config.hasStepsSettings()) {
            setStatusMessage("歩数データのNotion設定を入力してください。", floating = true)
            return
        }

        startSync(
            syncMessage = "歩数データを同期中...",
            failurePrefix = "歩数データの同期に失敗しました"
        ) { client ->
            val synced = syncUnsyncedSteps(client, config)
            "歩数データを${synced}件同期しました。"
        }
    }

    private fun syncStepsToNotionDebug() {
        val config = currentConfig()
        if (!config.hasStepsSettings()) {
            setStatusMessage("歩数データのNotion設定を入力してください。", floating = true)
            return
        }
        if (currentSyncJob?.isActive == true) {
            return
        }

        val syncMessage = "歩数データをデバッグ同期中..."
        setStatusMessage(syncMessage)
        showSyncDialog(syncMessage)
        currentSyncJob = CoroutineScope(Dispatchers.Main).launch {
            var synced = 0
            try {
                val client = checkedHealthClient() ?: return@launch
                val notion = withContext(Dispatchers.IO) { NotionClient(config) }
                val existingPages = withContext(Dispatchers.IO) {
                    notion.readStepPagesByDate(lookbackDays).toMutableMap()
                }
                val measurements = withContext(Dispatchers.IO) {
                    readDailyStepDebugMeasurements(client)
                }
                if (measurements.isEmpty()) {
                    setStatusMessage("同期対象の歩数データはありません。", floating = true)
                    return@launch
                }

                for ((index, debugMeasurement) in measurements.withIndex()) {
                    coroutineContext.ensureActive()
                    val result = withContext(Dispatchers.IO) {
                        syncOneDebugStepDay(notion, existingPages, debugMeasurement)
                    }
                    if (result.synced) {
                        synced++
                    }
                    dismissSyncDialog()
                    setStatusMessage("${result.measurement.date} の歩数データを確認中...")
                    val shouldContinue = showStepDebugDialog(result)
                    if (!shouldContinue) {
                        setStatusMessage("歩数データのデバッグ同期を終了しました。同期済み: ${synced}件", floating = true)
                        return@launch
                    }
                    if (index < measurements.lastIndex) {
                        showSyncDialog("次の日の歩数データをデバッグ同期中...")
                    }
                }

                setStatusMessage("歩数データのデバッグ同期が完了しました。同期済み: ${synced}件", floating = true)
                refreshLatestDates()
            } catch (_: CancellationException) {
                setStatusMessage("同期を中止しました。", floating = true)
            } catch (e: Exception) {
                setStatusMessage("歩数データのデバッグ同期に失敗しました: ${safeErrorMessage(e)}", floating = true)
            } finally {
                currentSyncJob = null
                dismissSyncDialog()
            }
        }
    }

    private fun syncVitalsToNotion() {
        val config = currentConfig()
        if (!config.hasVitalsSettings()) {
            setStatusMessage("血圧・心拍データのNotion設定を入力してください。", floating = true)
            return
        }

        startSync(
            syncMessage = "血圧・心拍データを同期中...",
            failurePrefix = "血圧・心拍データの同期に失敗しました"
        ) { client ->
            val synced = syncUnsyncedVitals(client, config)
            "血圧・心拍データを${synced}件同期しました。"
        }
    }

    private fun syncAllToNotion() {
        val config = currentConfig()
        if (!config.hasStepsSettings() || !config.hasVitalsSettings()) {
            setStatusMessage("歩数と血圧・心拍データのNotion設定を入力してください。", floating = true)
            return
        }

        startSync(
            syncMessage = "すべてのデータを同期中...",
            failurePrefix = "同期に失敗しました"
        ) { client ->
            val steps = syncUnsyncedSteps(client, config)
            val vitals = syncUnsyncedVitals(client, config)
            "歩数${steps}件、血圧・心拍${vitals}件を同期しました。"
        }
    }

    private fun startSync(
        syncMessage: String,
        failurePrefix: String,
        sync: suspend (HealthConnectClient) -> String
    ) {
        if (currentSyncJob?.isActive == true) {
            return
        }

        setStatusMessage(syncMessage)
        showSyncDialog(syncMessage)
        currentSyncJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val client = checkedHealthClient() ?: return@launch
                val resultMessage = withContext(Dispatchers.IO) { sync(client) }
                setStatusMessage(resultMessage, floating = true)
                refreshLatestDates()
            } catch (_: CancellationException) {
                setStatusMessage("同期を中止しました。", floating = true)
            } catch (e: Exception) {
                setStatusMessage("$failurePrefix: ${safeErrorMessage(e)}", floating = true)
            } finally {
                currentSyncJob = null
                dismissSyncDialog()
            }
        }
    }

    private fun showSyncDialog(message: String) {
        val density = resources.displayMetrics.density
        val dialogMessage = TextView(this).apply {
            text = message
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        syncDialogMessageText = dialogMessage

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(),
                (20 * density).toInt(),
                (20 * density).toInt(),
                (18 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#17232D"))
                setStroke((1 * density).toInt(), Color.parseColor("#44D7B6"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = (28 * density).toInt()
                rightMargin = (28 * density).toInt()
            }
        }
        panel.addView(dialogMessage)
        panel.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#44D7B6"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (6 * density).toInt()
            ).apply {
                topMargin = (14 * density).toInt()
            }
        })
        panel.addView(Button(this).apply {
            text = "同期を中止"
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.parseColor("#B93845"))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (18 * density).toInt()
            }
            setOnClickListener {
                isEnabled = false
                syncDialogMessageText?.text = "同期を中止しています..."
                currentSyncJob?.cancel()
            }
        })

        syncDialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            setContentView(FrameLayout(this@MainActivity).apply {
                addView(panel)
            })
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0.76f)
            show()
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun showLatestDateRefreshDialog() {
        latestDateRefreshDialog?.dismiss()

        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(),
                (18 * density).toInt(),
                (20 * density).toInt(),
                (18 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#17232D"))
                setStroke((1 * density).toInt(), Color.parseColor("#44D7B6"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = (28 * density).toInt()
                rightMargin = (28 * density).toInt()
            }
        }
        panel.addView(TextView(this).apply {
            text = "最新日付を更新中"
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#44D7B6"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (6 * density).toInt()
            ).apply {
                topMargin = (14 * density).toInt()
            }
        })

        latestDateRefreshDialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            setContentView(FrameLayout(this@MainActivity).apply {
                addView(panel)
            })
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0.42f)
            show()
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun showFloatingMessage(message: String) {
        messageDialog?.dismiss()

        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(),
                (18 * density).toInt(),
                (20 * density).toInt(),
                (18 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(Color.parseColor("#17232D"))
                setStroke((1 * density).toInt(), Color.parseColor("#44D7B6"))
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = (28 * density).toInt()
                rightMargin = (28 * density).toInt()
            }
        }
        panel.addView(TextView(this).apply {
            text = message
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })

        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            setContentView(FrameLayout(this@MainActivity).apply {
                addView(panel)
            })
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0.42f)
            setOnDismissListener {
                if (messageDialog === this) {
                    messageDialog = null
                }
            }
            show()
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        messageDialog = dialog

        CoroutineScope(Dispatchers.Main).launch {
            delay(1600)
            if (messageDialog === dialog) {
                dialog.dismiss()
            }
        }
    }

    private suspend fun showStepDebugDialog(result: StepDebugSyncResult): Boolean =
        suspendCancellableCoroutine { continuation ->
            messageDialog?.dismiss()

            val density = resources.displayMetrics.density
            lateinit var dialog: Dialog
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    (20 * density).toInt(),
                    (20 * density).toInt(),
                    (20 * density).toInt(),
                    (18 * density).toInt()
                )
                background = GradientDrawable().apply {
                    cornerRadius = 14 * density
                    setColor(Color.parseColor("#17232D"))
                    setStroke((1 * density).toInt(), Color.parseColor("#44D7B6"))
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                ).apply {
                    leftMargin = (24 * density).toInt()
                    rightMargin = (24 * density).toInt()
                    topMargin = (42 * density).toInt()
                    bottomMargin = (42 * density).toInt()
                }
            }
            panel.addView(TextView(this).apply {
                text = "歩数データ同期デバッグ"
                textSize = 20f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            panel.addView(ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                ).apply {
                    topMargin = (14 * density).toInt()
                    bottomMargin = (16 * density).toInt()
                }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = result.toDebugSummaryText()
                        textSize = 15f
                        setTextColor(Color.parseColor("#D9E3EA"))
                        setLineSpacing(0f, 1.12f)
                    })
                    val detailsText = TextView(this@MainActivity).apply {
                        text = result.toDebugDetailsText()
                        textSize = 15f
                        setTextColor(Color.parseColor("#D9E3EA"))
                        setLineSpacing(0f, 1.12f)
                        visibility = View.GONE
                    }
                    addView(Button(this@MainActivity).apply {
                        text = ""
                        contentDescription = "詳細を表示"
                        setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_expand_window_down, 0, 0)
                        compoundDrawableTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D9E3EA"))
                        background = GradientDrawable().apply {
                            cornerRadius = 10 * density
                            setColor(Color.parseColor("#22313C"))
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (44 * density).toInt()
                        ).apply {
                            topMargin = (12 * density).toInt()
                            bottomMargin = (12 * density).toInt()
                        }
                        setOnClickListener {
                            val isShowingDetails = detailsText.visibility == View.VISIBLE
                            detailsText.visibility = if (isShowingDetails) View.GONE else View.VISIBLE
                            contentDescription = if (isShowingDetails) "詳細を表示" else "詳細を閉じる"
                            setCompoundDrawablesWithIntrinsicBounds(
                                0,
                                if (isShowingDetails) R.drawable.ic_expand_window_down else R.drawable.ic_collapse_window_up,
                                0,
                                0
                            )
                        }
                    })
                    addView(detailsText)
                })
            })

            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            buttons.addView(Button(this).apply {
                text = "終了"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = (8 * density).toInt()
                }
                setOnClickListener {
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                    dialog.dismiss()
                }
            })
            buttons.addView(Button(this).apply {
                text = "再開"
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = (8 * density).toInt()
                }
                setOnClickListener {
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                    dialog.dismiss()
                }
            })
            panel.addView(buttons)

            dialog = Dialog(this).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                setContentView(FrameLayout(this@MainActivity).apply {
                    addView(panel)
                })
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window?.setDimAmount(0.64f)
                setOnDismissListener {
                    if (messageDialog === this) {
                        messageDialog = null
                    }
                }
                show()
                window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            messageDialog = dialog
            continuation.invokeOnCancellation {
                dialog.dismiss()
                if (messageDialog === dialog) {
                    messageDialog = null
                }
            }
        }

    private fun setStatusMessage(message: String, floating: Boolean = false) {
        statusText.text = message
        if (floating) {
            showFloatingMessage(message)
        }
    }

    private fun StepDebugSyncResult.toDebugSummaryText(): String {
        return listOf(
            "対象日: ${measurement.date}",
            "Notion同期結果: ${operation.label}",
            "Notionへ送信した合計歩数: ${measurement.steps}歩",
            "Notionへ保存した測定日時: ${displayDateTime(measurement.recordedAt)}",
            "GoogleHealth明細: ${details.size}件"
        ).joinToString(separator = "\n")
    }

    private fun StepDebugSyncResult.toDebugDetailsText(): String {
        val rawTotal = details.sumOf { it.steps }
        val lines = mutableListOf<String>()
        lines.add("集計対象origin: $GOOGLE_FIT_PACKAGE_NAME")
        lines.add("GoogleHealth明細の合計歩数: ${rawTotal}歩")
        if (rawTotal != measurement.steps) {
            lines.add("差分: ${measurement.steps - rawTotal}歩")
        }
        lines.add("")
        lines.add("dataOrigin.packageName別合計:")
        details
            .groupBy { it.dataOriginPackageName }
            .mapValues { (_, records) -> records.sumOf { it.steps } }
            .toSortedMap()
            .forEach { (packageName, steps) ->
                lines.add("- $packageName: ${steps}歩")
            }
        lines.add("")
        lines.add("recordingMethod別合計:")
        details
            .groupBy { it.recordingMethod }
            .mapValues { (_, records) -> records.sumOf { it.steps } }
            .toSortedMap()
            .forEach { (recordingMethod, steps) ->
                lines.add("- ${recordingMethod.label}: ${steps}歩")
            }
        lines.add("")
        if (details.isEmpty()) {
            lines.add("明細レコードは取得できませんでした。Health Connectの日次集計APIの合計値をNotionへ送信しています。")
        } else {
            details.forEachIndexed { index, detail ->
                lines.add(
                    "${index + 1}. ${displayDateTime(detail.startTime)} - ${displayDateTime(detail.endTime)} / ${detail.steps}歩 / ${detail.dataOriginPackageName} / ${detail.recordingMethod.label}"
                )
            }
        }
        lines.add("")
        lines.add("再開を押すと次の日の同期に進みます。")
        return lines.joinToString(separator = "\n")
    }

    private fun StepDebugSyncResult.toDebugText(): String {
        val rawTotal = details.sumOf { it.steps }
        val lines = mutableListOf<String>()
        lines.add("対象日: ${measurement.date}")
        lines.add("Notion同期結果: ${operation.label}")
        lines.add("集計対象origin: $GOOGLE_FIT_PACKAGE_NAME")
        lines.add("Notionへ送信した合計歩数: ${measurement.steps}歩")
        lines.add("GoogleHealth明細の合計歩数: ${rawTotal}歩")
        if (rawTotal != measurement.steps) {
            lines.add("差分: ${measurement.steps - rawTotal}歩")
        }
        lines.add("Notionへ保存した測定日時: ${displayDateTime(measurement.recordedAt)}")
        lines.add("")
        lines.add("dataOrigin.packageName別合計:")
        details
            .groupBy { it.dataOriginPackageName }
            .mapValues { (_, records) -> records.sumOf { it.steps } }
            .toSortedMap()
            .forEach { (packageName, steps) ->
                lines.add("- $packageName: ${steps}歩")
            }
        lines.add("")
        lines.add("recordingMethod別合計:")
        details
            .groupBy { it.recordingMethod }
            .mapValues { (_, records) -> records.sumOf { it.steps } }
            .toSortedMap()
            .forEach { (recordingMethod, steps) ->
                lines.add("- ${recordingMethod.label}: ${steps}歩")
            }
        lines.add("")
        lines.add("GoogleHealth明細: ${details.size}件")
        if (details.isEmpty()) {
            lines.add("明細レコードは取得できませんでした。Health Connectの日次集計APIの合計値をNotionへ送信しています。")
        } else {
            details.forEachIndexed { index, detail ->
                lines.add(
                    "${index + 1}. ${displayDateTime(detail.startTime)} - ${displayDateTime(detail.endTime)} / ${detail.steps}歩 / ${detail.dataOriginPackageName} / ${detail.recordingMethod.label}"
                )
            }
        }
        lines.add("")
        lines.add("再開を押すと次の日の同期に進みます。")
        return lines.joinToString(separator = "\n")
    }

    private fun dismissSyncDialog() {
        syncDialog?.dismiss()
        syncDialog = null
        syncDialogMessageText = null
    }

    private fun dismissLatestDateRefreshDialog() {
        latestDateRefreshDialog?.dismiss()
        latestDateRefreshDialog = null
    }

    private fun safeErrorMessage(error: Exception): String {
        return when (error) {
            is NotionRequestException -> error.userMessage
            else -> error.message?.takeIf { it.isNotBlank() } ?: "詳細不明のエラー"
        }
    }

    private suspend fun checkedHealthClient(): HealthConnectClient? {
        val client = healthConnectClientOrNull()
        if (client == null) {
            setStatusMessage("Health Connectが利用できません。", floating = true)
            return null
        }
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(requiredPermissions)) {
            permissionLauncher.launch(requiredPermissions)
            setStatusMessage("Health Connectの権限を許可してから再度実行してください。", floating = true)
            return null
        }
        return client
    }

    private fun healthConnectClientOrNull(): HealthConnectClient? {
        return when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(this)
            else -> null
        }
    }

    private suspend fun syncUnsyncedSteps(client: HealthConnectClient, config: SyncConfig): Int {
        return HealthNotionSyncEngine.syncSteps(client, config, lookbackDays)
    }

    private fun syncOneDebugStepDay(
        notion: NotionClient,
        existingPages: MutableMap<LocalDate, NotionStepPage>,
        debugMeasurement: DailyStepDebugMeasurement
    ): StepDebugSyncResult {
        val steps = debugMeasurement.measurement
        val existingPage = existingPages[steps.date]
        val operation = if (existingPage == null) {
            notion.createStepPage(steps)
            existingPages[steps.date] = NotionStepPage(id = "", recordedAt = steps.recordedAt)
            StepDebugOperation.CREATED
        } else if (existingPage.recordedAt != steps.recordedAt) {
            notion.updateStepPage(existingPage.id, steps)
            existingPages[steps.date] = existingPage.copy(recordedAt = steps.recordedAt)
            StepDebugOperation.UPDATED
        } else {
            StepDebugOperation.SKIPPED
        }

        return StepDebugSyncResult(
            measurement = steps,
            details = debugMeasurement.details,
            operation = operation
        )
    }

    private suspend fun syncUnsyncedVitals(client: HealthConnectClient, config: SyncConfig): Int {
        return HealthNotionSyncEngine.syncVitals(client, config, lookbackDays)
    }

    private suspend fun readDailyStepDebugMeasurements(client: HealthConnectClient): List<DailyStepDebugMeasurement> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDate = today.minusDays(lookbackDays)
        val endDate = today.plusDays(1)
        val timeRange = TimeRangeFilter.between(
            startDate.atStartOfDay(zone).toInstant(),
            endDate.atStartOfDay(zone).toInstant()
        )
        val aggregateTimeRange = TimeRangeFilter.between(
            startDate.atStartOfDay(),
            endDate.atStartOfDay()
        )

        val detailsByDate = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = timeRange,
                dataOriginFilter = GOOGLE_FIT_DATA_ORIGIN_FILTER,
                ascendingOrder = true,
                pageSize = 5000
            )
        ).records
            .filter { it.count > 0L }
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
            .mapValues { (_, records) ->
                records.map {
                    StepRecordDetail(
                        startTime = it.startTime,
                        endTime = it.endTime,
                        steps = it.count,
                        dataOriginPackageName = it.metadata.dataOrigin.packageName,
                        recordingMethod = it.metadata.recordingMethod
                    )
                }
            }

        val aggregatedByDay = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = aggregateTimeRange,
                timeRangeSlicer = Period.ofDays(1),
                dataOriginFilter = GOOGLE_FIT_DATA_ORIGIN_FILTER
            )
        )

        return aggregatedByDay.mapNotNull { bucket ->
            val steps = bucket.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
            if (steps <= 0L) {
                return@mapNotNull null
            }
            val date = bucket.startTime.atZone(zone).toLocalDate()
            val details = detailsByDate[date].orEmpty()
            val recordedAt = details.maxOfOrNull { it.endTime } ?: bucket.endTime.atZone(zone).toInstant()
            DailyStepDebugMeasurement(
                measurement = DailyStepMeasurement(
                    date = date,
                    recordedAt = recordedAt,
                    steps = steps
                ),
                details = details
            )
        }.sortedBy { it.measurement.date }
    }

    private suspend fun readLatestStepsTime(client: HealthConnectClient, timeRange: TimeRangeFilter): Instant? {
        return client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = timeRange,
                ascendingOrder = false,
                pageSize = 1
            )
        ).records.firstOrNull()?.endTime
    }

    private suspend fun readLatestVitalsTime(client: HealthConnectClient, granted: Set<String>): Instant? {
        val timestamps = mutableListOf<Instant>()
        if (granted.contains(HealthPermission.getReadPermission(BloodPressureRecord::class))) {
            readLatestBloodPressureTime(client)?.let { timestamps.add(it) }
        }
        if (granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class))) {
            readLatestHeartRateTime(client)?.let { timestamps.add(it) }
        }
        return timestamps.maxOrNull()
    }

    private suspend fun readLatestBloodPressureTime(client: HealthConnectClient): Instant? {
        return client.readRecords(
            ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = recentRecordTimeRange(),
                ascendingOrder = false,
                pageSize = 1
            )
        ).records.firstOrNull()?.time
    }

    private suspend fun readLatestHeartRateTime(client: HealthConnectClient): Instant? {
        return client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = recentRecordTimeRange(),
                ascendingOrder = false,
                pageSize = 1
            )
        ).records.firstOrNull()?.endTime
    }

    private fun recordTimeRangeForDate(date: LocalDate): TimeRangeFilter {
        val zone = ZoneId.systemDefault()
        return TimeRangeFilter.between(
            date.atStartOfDay(zone).toInstant(),
            date.plusDays(1).atStartOfDay(zone).toInstant()
        )
    }

    private fun recentRecordTimeRange(): TimeRangeFilter {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return TimeRangeFilter.between(
            today.minusDays(lookbackDays).atStartOfDay(zone).toInstant(),
            today.plusDays(1).atStartOfDay(zone).toInstant()
        )
    }

    private fun displayDateTime(timestamp: Instant?): String {
        return timestamp
            ?.atZone(ZoneId.systemDefault())
            ?.format(DISPLAY_DATE_TIME_FORMATTER)
            ?: "データなし"
    }

    private fun displayNotionDateTime(value: NotionDateValue?): String {
        if (value == null) {
            return "データなし"
        }
        return value.timestamp?.let { displayDateTime(it) } ?: "${value.date} (日付のみ)"
    }

    private fun appVersionName(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"

}

private data class SyncConfig(
    val token: String,
    val stepsDataSourceId: String,
    val stepsDateProperty: String,
    val stepsProperty: String,
    val vitalsDataSourceId: String,
    val vitalsMeasuredAtProperty: String,
    val systolicProperty: String,
    val diastolicProperty: String,
    val heartRateProperty: String
) {
    fun hasStepsSettings(): Boolean =
        token.isNotBlank() &&
            stepsDataSourceId.isNotBlank() &&
            stepsDateProperty.isNotBlank() &&
            stepsProperty.isNotBlank()

    fun hasVitalsSettings(): Boolean =
        token.isNotBlank() &&
            vitalsDataSourceId.isNotBlank() &&
            vitalsMeasuredAtProperty.isNotBlank() &&
            systolicProperty.isNotBlank() &&
            diastolicProperty.isNotBlank() &&
            heartRateProperty.isNotBlank()
}

private data class AutoSyncChoice(
    val label: String,
    val value: String
)

private data class AutoSyncRunStatus(
    val resultLabel: String,
    val lastSuccessAtMillis: Long,
    val lastFailureAtMillis: Long,
    val failureReason: String
) {
    fun topResultLabel(): String =
        when (resultLabel) {
            "成功", "未実行" -> resultLabel
            else -> "失敗"
        }
}

private fun autoSyncChoices(): List<AutoSyncChoice> {
    return listOf(AutoSyncChoice("自動同期しない", AUTO_SYNC_OFF)) +
        (0..23).map { hour ->
            val time = "%02d:00".format(hour)
            AutoSyncChoice("毎日 $time", time)
        }
}

private fun autoSyncLabel(value: String): String {
    return autoSyncChoices().firstOrNull { it.value == value }?.label ?: autoSyncChoices().first().label
}

private fun loadAutoSyncStatus(context: Context): AutoSyncRunStatus {
    val prefs = context.getSharedPreferences("notion", Context.MODE_PRIVATE)
    return AutoSyncRunStatus(
        resultLabel = prefs.getString(AUTO_SYNC_RESULT_KEY, "未実行") ?: "未実行",
        lastSuccessAtMillis = prefs.getLong(AUTO_SYNC_LAST_SUCCESS_AT_KEY, 0L),
        lastFailureAtMillis = prefs.getLong(AUTO_SYNC_LAST_FAILURE_AT_KEY, 0L),
        failureReason = prefs.getString(AUTO_SYNC_FAILURE_REASON_KEY, "") ?: ""
    )
}

private fun recordAutoSyncSuccess(context: Context) {
    context.getSharedPreferences("notion", Context.MODE_PRIVATE)
        .edit()
        .putString(AUTO_SYNC_RESULT_KEY, "成功")
        .putLong(AUTO_SYNC_LAST_SUCCESS_AT_KEY, System.currentTimeMillis())
        .apply()
}

private fun recordAutoSyncFailure(context: Context, resultLabel: String, reason: String) {
    context.getSharedPreferences("notion", Context.MODE_PRIVATE)
        .edit()
        .putString(AUTO_SYNC_RESULT_KEY, resultLabel)
        .putLong(AUTO_SYNC_LAST_FAILURE_AT_KEY, System.currentTimeMillis())
        .putString(AUTO_SYNC_FAILURE_REASON_KEY, reason.take(200))
        .apply()
}

private fun List<WorkInfo>.nextScheduleTimeMillisOrNull(): Long? {
    return asSequence()
        .map { it.nextScheduleTimeMillis }
        .filter { it > 0L }
        .minOrNull()
}

private fun nextAutoSyncTimeMillis(autoSyncTime: String): Long {
    return System.currentTimeMillis() + initialAutoSyncDelayMillis(autoSyncTime)
}

private fun displayTimestampMillis(timestampMillis: Long): String {
    if (timestampMillis <= 0L) {
        return "なし"
    }
    return Instant.ofEpochMilli(timestampMillis)
        .atZone(ZoneId.systemDefault())
        .format(DISPLAY_DATE_TIME_FORMATTER)
}

private fun workerErrorMessage(error: Exception): String {
    return when (error) {
        is NotionRequestException -> error.userMessage
        else -> error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
    }
}

private fun loadSyncConfig(context: Context): SyncConfig {
    val prefs = context.getSharedPreferences("notion", Context.MODE_PRIVATE)
    return SyncConfig(
        token = SecureSettingsStore.loadToken(prefs),
        stepsDataSourceId = prefs.getString("stepsDataSource", prefs.getString("dataSource", "")) ?: "",
        stepsDateProperty = prefs.getString("stepsDateProperty", prefs.getString("dateProperty", "日付")) ?: "日付",
        stepsProperty = prefs.getString("stepsProperty", "歩数") ?: "歩数",
        vitalsDataSourceId = prefs.getString("vitalsDataSource", "") ?: "",
        vitalsMeasuredAtProperty = prefs.getString("vitalsMeasuredAtProperty", "Date") ?: "Date",
        systolicProperty = prefs.getString("systolicProperty", "収縮期") ?: "収縮期",
        diastolicProperty = prefs.getString("diastolicProperty", "拡張期") ?: "拡張期",
        heartRateProperty = prefs.getString("heartRateProperty", "脈拍") ?: "脈拍"
    )
}

private fun scheduleAutoSync(context: Context, autoSyncTime: String) {
    val workManager = WorkManager.getInstance(context.applicationContext)
    if (autoSyncTime == AUTO_SYNC_OFF) {
        workManager.cancelUniqueWork(AUTO_SYNC_WORK_NAME)
        return
    }

    val request = PeriodicWorkRequestBuilder<AutoSyncWorker>(1, TimeUnit.DAYS)
        .setInitialDelay(initialAutoSyncDelayMillis(autoSyncTime), TimeUnit.MILLISECONDS)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .build()
    workManager.enqueueUniquePeriodicWork(
        AUTO_SYNC_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request
    )
}

private fun initialAutoSyncDelayMillis(autoSyncTime: String): Long {
    val targetTime = runCatching { LocalTime.parse(autoSyncTime) }.getOrDefault(LocalTime.MIDNIGHT)
    val now = LocalDateTime.now()
    var nextRun = now.toLocalDate().atTime(targetTime)
    if (!nextRun.isAfter(now)) {
        nextRun = nextRun.plusDays(1)
    }
    return java.time.Duration.between(now, nextRun).toMillis().coerceAtLeast(0L)
}

class AutoSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val client = when (HealthConnectClient.getSdkStatus(applicationContext)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(applicationContext)
            else -> {
                recordAutoSyncFailure(applicationContext, "再試行", "Health Connectが利用できません")
                return Result.retry()
            }
        }

        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(AUTO_SYNC_REQUIRED_PERMISSIONS)) {
            recordAutoSyncFailure(applicationContext, "失敗", "Health Connectの自動同期権限が不足しています")
            return Result.failure()
        }

        val config = loadSyncConfig(applicationContext)
        if (!config.hasStepsSettings() && !config.hasVitalsSettings()) {
            recordAutoSyncFailure(applicationContext, "失敗", "Notion同期設定が未完了です")
            return Result.failure()
        }

        return try {
            HealthNotionSyncEngine.syncConfigured(client, config, DEFAULT_LOOKBACK_DAYS)
            recordAutoSyncSuccess(applicationContext)
            Result.success()
        } catch (_: CancellationException) {
            recordAutoSyncFailure(applicationContext, "再試行", "自動同期がキャンセルされました")
            Result.retry()
        } catch (e: Exception) {
            recordAutoSyncFailure(applicationContext, "再試行", workerErrorMessage(e))
            Result.retry()
        }
    }
}

private object HealthNotionSyncEngine {
    suspend fun syncConfigured(client: HealthConnectClient, config: SyncConfig, lookbackDays: Long): Pair<Int, Int> {
        val steps = if (config.hasStepsSettings()) syncSteps(client, config, lookbackDays) else 0
        val vitals = if (config.hasVitalsSettings()) syncVitals(client, config, lookbackDays) else 0
        return steps to vitals
    }

    suspend fun syncSteps(client: HealthConnectClient, config: SyncConfig, lookbackDays: Long): Int {
        val notion = NotionClient(config)
        val existingPages = notion.readStepPagesByDate(lookbackDays).toMutableMap()
        var synced = 0
        for (steps in readDailyStepMeasurements(client, lookbackDays)) {
            coroutineContext.ensureActive()
            val existingPage = existingPages[steps.date]
            if (existingPage == null) {
                notion.createStepPage(steps)
                existingPages[steps.date] = NotionStepPage(id = "", recordedAt = steps.recordedAt)
                synced++
            } else if (existingPage.recordedAt != steps.recordedAt) {
                notion.updateStepPage(existingPage.id, steps)
                existingPages[steps.date] = existingPage.copy(recordedAt = steps.recordedAt)
                synced++
            }
        }
        return synced
    }

    suspend fun syncVitals(client: HealthConnectClient, config: SyncConfig, lookbackDays: Long): Int {
        val notion = NotionClient(config)
        val notionMeasurements = notion.readVitalMeasurements(lookbackDays)
        val existingTimes = readVitalMeasurementTimes(client, lookbackDays)
        val measurementsToInsert = notionMeasurements.filter { it.measuredAt !in existingTimes }
        coroutineContext.ensureActive()
        if (measurementsToInsert.isEmpty()) {
            return 0
        }

        client.insertRecords(measurementsToInsert.flatMap { it.toHealthConnectRecords() })
        return measurementsToInsert.size
    }

    private suspend fun readDailyStepMeasurements(
        client: HealthConnectClient,
        lookbackDays: Long
    ): List<DailyStepMeasurement> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDate = today.minusDays(lookbackDays)
        val endDate = today.plusDays(1)
        val timeRange = TimeRangeFilter.between(
            startDate.atStartOfDay(zone).toInstant(),
            endDate.atStartOfDay(zone).toInstant()
        )
        val aggregateTimeRange = TimeRangeFilter.between(
            startDate.atStartOfDay(),
            endDate.atStartOfDay()
        )

        val latestRecordTimeByDate = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = timeRange,
                dataOriginFilter = GOOGLE_FIT_DATA_ORIGIN_FILTER,
                ascendingOrder = true,
                pageSize = 5000
            )
        ).records
            .filter { it.count > 0L }
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
            .mapValues { (_, records) -> records.maxOf { it.endTime } }

        val aggregatedByDay = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = aggregateTimeRange,
                timeRangeSlicer = Period.ofDays(1),
                dataOriginFilter = GOOGLE_FIT_DATA_ORIGIN_FILTER
            )
        )

        return aggregatedByDay.mapNotNull { bucket ->
            val steps = bucket.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
            if (steps <= 0L) {
                return@mapNotNull null
            }
            val date = bucket.startTime.atZone(zone).toLocalDate()
            val recordedAt = latestRecordTimeByDate[date] ?: bucket.endTime.atZone(zone).toInstant()
            DailyStepMeasurement(
                date = date,
                recordedAt = recordedAt,
                steps = steps
            )
        }.sortedBy { it.date }
    }

    private suspend fun readVitalMeasurementTimes(
        client: HealthConnectClient,
        lookbackDays: Long
    ): Set<Instant> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val start = today.minusDays(lookbackDays).atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        return client.readRecords(
            ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        ).records.mapTo(mutableSetOf()) { it.time }
    }
}

private data class DailyStepMeasurement(
    val date: LocalDate,
    val recordedAt: Instant,
    val steps: Long
)

private data class DailyStepDebugMeasurement(
    val measurement: DailyStepMeasurement,
    val details: List<StepRecordDetail>
)

private data class StepRecordDetail(
    val startTime: Instant,
    val endTime: Instant,
    val steps: Long,
    val dataOriginPackageName: String,
    val recordingMethod: Int
)

private data class StepDebugSyncResult(
    val measurement: DailyStepMeasurement,
    val details: List<StepRecordDetail>,
    val operation: StepDebugOperation
) {
    val synced: Boolean
        get() = operation != StepDebugOperation.SKIPPED
}

private enum class StepDebugOperation(val label: String) {
    CREATED("作成"),
    UPDATED("更新"),
    SKIPPED("変更なし")
}

private data class NotionStepPage(
    val id: String,
    val recordedAt: Instant?
)

private data class NotionDateValue(
    val date: LocalDate,
    val timestamp: Instant?
)

private data class VitalMeasurement(
    val measuredAt: Instant,
    val systolic: Double,
    val diastolic: Double,
    val heartRate: Long?
)

private fun VitalMeasurement.toHealthConnectRecords(): List<androidx.health.connect.client.records.Record> {
    val zoneOffset = measuredAt.atZone(ZoneId.systemDefault()).offset
    return buildList {
        add(
            BloodPressureRecord(
                time = measuredAt,
                zoneOffset = zoneOffset,
                metadata = Metadata.manualEntry("notion-blood-pressure-$measuredAt"),
                systolic = Pressure.millimetersOfMercury(systolic),
                diastolic = Pressure.millimetersOfMercury(diastolic)
            )
        )
        if (heartRate != null) {
            add(
                HeartRateRecord(
                    startTime = measuredAt,
                    startZoneOffset = zoneOffset,
                    endTime = measuredAt.plusSeconds(1),
                    endZoneOffset = zoneOffset,
                    samples = listOf(HeartRateRecord.Sample(measuredAt, heartRate)),
                    metadata = Metadata.manualEntry("notion-heart-rate-$measuredAt")
                )
            )
        }
    }
}

private class NotionClient(private val config: SyncConfig) {
    fun latestStepsDate(lookbackDays: Long): NotionDateValue? =
        latestDateInSyncWindow(validDataSourceId(config.stepsDataSourceId), config.stepsDateProperty, lookbackDays)

    fun latestVitalsDate(lookbackDays: Long): NotionDateValue? =
        latestDateInSyncWindow(validDataSourceId(config.vitalsDataSourceId), config.vitalsMeasuredAtProperty, lookbackDays)

    fun readStepPagesByDate(lookbackDays: Long): Map<LocalDate, NotionStepPage> {
        return readDatePagesByDate(
            dataSourceId = validDataSourceId(config.stepsDataSourceId),
            dateProperty = config.stepsDateProperty,
            lookbackDays = lookbackDays
        )
    }

    fun createStepPage(steps: DailyStepMeasurement) {
        val body = JSONObject()
            .put("parent", dataSourceParent(validDataSourceId(config.stepsDataSourceId)))
            .put("properties", stepProperties(steps))
        request("POST", "https://api.notion.com/v1/pages", body)
    }

    fun updateStepPage(pageId: String, steps: DailyStepMeasurement) {
        if (pageId.isBlank()) {
            createStepPage(steps)
            return
        }
        val body = JSONObject()
            .put("properties", stepProperties(steps))
        request("PATCH", "https://api.notion.com/v1/pages/$pageId", body)
    }

    private fun stepProperties(steps: DailyStepMeasurement): JSONObject {
        return JSONObject()
            .put(
                config.stepsDateProperty,
                JSONObject().put("date", JSONObject().put("start", steps.recordedAt.toNotionDateTime()))
            )
            .put(config.stepsProperty, JSONObject().put("number", steps.steps))
    }

    fun readVitalMeasurements(lookbackDays: Long): List<VitalMeasurement> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val windowStart = today.minusDays(lookbackDays).atStartOfDay(zone).toInstant().toNotionDateTime()
        val windowEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toNotionDateTime()
        val measurements = mutableListOf<VitalMeasurement>()
        var cursor: String? = null

        do {
            val body = JSONObject()
                .put(
                    "filter",
                    JSONObject().put(
                        "and",
                        JSONArray()
                            .put(JSONObject().put("property", config.vitalsMeasuredAtProperty).put("date", JSONObject().put("on_or_after", windowStart)))
                            .put(JSONObject().put("property", config.vitalsMeasuredAtProperty).put("date", JSONObject().put("before", windowEnd)))
                    )
                )
                .put("page_size", 100)
            cursor?.let { body.put("start_cursor", it) }

            val response = request(
                "POST",
                "https://api.notion.com/v1/data_sources/${validDataSourceId(config.vitalsDataSourceId)}/query",
                body
            )
            val results = response.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                results.optJSONObject(i)?.toVitalMeasurement()?.let { measurements.add(it) }
            }
            cursor = response.optString("next_cursor").takeIf {
                response.optBoolean("has_more") && it.isNotBlank()
            }
        } while (cursor != null)

        return measurements.sortedBy { it.measuredAt }
    }

    private fun latestDateInSyncWindow(dataSourceId: String, dateProperty: String, lookbackDays: Long): NotionDateValue? {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val windowStart = today.minusDays(lookbackDays).atStartOfDay(zone).toInstant().toNotionDateTime()
        val windowEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toNotionDateTime()
        val body = JSONObject()
            .put(
                "filter",
                JSONObject()
                    .put("and", JSONArray()
                        .put(JSONObject().put("property", dateProperty).put("date", JSONObject().put("on_or_after", windowStart)))
                        .put(JSONObject().put("property", dateProperty).put("date", JSONObject().put("before", windowEnd)))
                    )
            )
            .put(
                "sorts",
                JSONArray().put(
                    JSONObject()
                        .put("property", dateProperty)
                        .put("direction", "descending")
                )
            )
            .put("page_size", 1)
        val response = request("POST", "https://api.notion.com/v1/data_sources/$dataSourceId/query", body)
        val latestStart = response.optJSONArray("results")
            ?.optJSONObject(0)
            ?.optJSONObject("properties")
            ?.optJSONObject(dateProperty)
            ?.optJSONObject("date")
            ?.optString("start")
            ?.takeIf { it.isNotBlank() }
        return latestStart?.toNotionDateValue()
    }

    private fun readDatePagesByDate(
        dataSourceId: String,
        dateProperty: String,
        lookbackDays: Long
    ): Map<LocalDate, NotionStepPage> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.minusDays(lookbackDays).atStartOfDay(zone).toInstant().toNotionDateTime()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toNotionDateTime()
        val pages = mutableMapOf<LocalDate, NotionStepPage>()
        var cursor: String? = null

        do {
            val body = JSONObject()
                .put(
                    "filter",
                    JSONObject().put(
                        "and",
                        JSONArray()
                            .put(JSONObject().put("property", dateProperty).put("date", JSONObject().put("on_or_after", start)))
                            .put(JSONObject().put("property", dateProperty).put("date", JSONObject().put("before", end)))
                    )
                )
                .put("page_size", 100)
            cursor?.let { body.put("start_cursor", it) }

            val response = request("POST", "https://api.notion.com/v1/data_sources/$dataSourceId/query", body)
            val results = response.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                val page = results.optJSONObject(i) ?: continue
                val value = page
                    ?.optJSONObject("properties")
                    ?.optJSONObject(dateProperty)
                    ?.optJSONObject("date")
                    ?.optString("start")
                    ?.takeIf { it.isNotBlank() }
                    ?.toNotionDateValue()
                    ?: continue
                val pageId = page.optString("id").takeIf { it.isNotBlank() } ?: continue
                val existing = pages[value.date]
                if (existing == null || notionTimestampSortValue(existing.recordedAt) < notionTimestampSortValue(value.timestamp)) {
                    pages[value.date] = NotionStepPage(id = pageId, recordedAt = value.timestamp)
                }
            }
            cursor = response.optString("next_cursor").takeIf {
                response.optBoolean("has_more") && it.isNotBlank()
            }
        } while (cursor != null)

        return pages
    }

    private fun JSONObject.toVitalMeasurement(): VitalMeasurement? {
        val properties = optJSONObject("properties") ?: return null
        val measuredAt = properties.optJSONObject(config.vitalsMeasuredAtProperty)
            ?.optJSONObject("date")
            ?.optString("start")
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return null
        val systolic = properties.notionNumber(config.systolicProperty) ?: return null
        val diastolic = properties.notionNumber(config.diastolicProperty) ?: return null
        return VitalMeasurement(
            measuredAt = measuredAt,
            systolic = systolic,
            diastolic = diastolic,
            heartRate = properties.notionNumber(config.heartRateProperty)?.toLong()
        )
    }

    private fun dataSourceParent(dataSourceId: String): JSONObject {
        return JSONObject()
            .put("type", "data_source_id")
            .put("data_source_id", dataSourceId)
    }

    private fun validDataSourceId(dataSourceId: String): String {
        val normalized = dataSourceId.trim()
        require(DATA_SOURCE_ID_PATTERN.matches(normalized)) {
            "Data Source IDの形式が正しくありません。NotionのData Source IDだけを入力してください。"
        }
        return normalized
    }

    private fun request(method: String, endpoint: String, body: JSONObject): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${config.token}")
            setRequestProperty("Notion-Version", "2026-03-11")
            setRequestProperty("Content-Type", "application/json")
        }
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        if (status !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
            throw NotionRequestException(status, message)
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}

private fun JSONObject.notionNumber(property: String): Double? =
    optJSONObject(property)
        ?.takeIf { !it.isNull("number") }
        ?.optDouble("number")

private fun Instant.toNotionDateTime(): String =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(atZone(ZoneId.systemDefault()))

private fun String.toNotionDateValue(): NotionDateValue =
    NotionDateValue(
        date = LocalDate.parse(take(10)),
        timestamp = runCatching { Instant.parse(this) }.getOrNull()
    )

private fun notionTimestampSortValue(timestamp: Instant?): Instant =
    timestamp ?: Instant.EPOCH

private const val GOOGLE_FIT_PACKAGE_NAME = "com.google.android.apps.fitness"
private val GOOGLE_FIT_DATA_ORIGIN_FILTER = setOf(DataOrigin(GOOGLE_FIT_PACKAGE_NAME))
private const val AUTO_SYNC_WORK_NAME = "health_notion_auto_sync"
private const val AUTO_SYNC_TIME_KEY = "autoSyncTime"
private const val AUTO_SYNC_OFF = "off"
private const val AUTO_SYNC_RESULT_KEY = "autoSyncResult"
private const val AUTO_SYNC_LAST_SUCCESS_AT_KEY = "autoSyncLastSuccessAt"
private const val AUTO_SYNC_LAST_FAILURE_AT_KEY = "autoSyncLastFailureAt"
private const val AUTO_SYNC_FAILURE_REASON_KEY = "autoSyncFailureReason"
private const val DEFAULT_LOOKBACK_DAYS = 30L
private val AUTO_SYNC_REQUIRED_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(BloodPressureRecord::class),
    HealthPermission.getWritePermission(BloodPressureRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getWritePermission(HeartRateRecord::class),
    HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
)

private val Int.label: String
    get() = when (this) {
        Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> "ACTIVELY_RECORDED"
        Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED -> "AUTOMATICALLY_RECORDED"
        Metadata.RECORDING_METHOD_MANUAL_ENTRY -> "MANUAL_ENTRY"
        Metadata.RECORDING_METHOD_UNKNOWN -> "UNKNOWN"
        else -> "UNKNOWN($this)"
    }

private val DISPLAY_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private val DATA_SOURCE_ID_PATTERN = Regex("^[A-Za-z0-9-]{16,128}$")

private class NotionRequestException(
    status: Int,
    private val notionMessage: String?
) : RuntimeException("Notion API request failed with HTTP $status") {
    val userMessage: String = buildString {
        append("Notion APIのリクエストに失敗しました(HTTP ")
        append(status)
        append(")")
        val publicMessage = notionMessage
            ?.takeIf { it.isNotBlank() }
            ?.take(120)
        if (publicMessage != null) {
            append(": ")
            append(publicMessage)
        }
    }
}
