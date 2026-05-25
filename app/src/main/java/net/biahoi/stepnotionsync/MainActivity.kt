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
import android.view.Window
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Pressure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext

class MainActivity : ComponentActivity() {
    private val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class)
    )
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var statusText: TextView
    private lateinit var stepsPhoneDateText: TextView
    private lateinit var stepsNotionDateText: TextView
    private lateinit var vitalsPhoneDateText: TextView
    private lateinit var vitalsNotionDateText: TextView
    private lateinit var tokenInput: EditText
    private lateinit var stepsDataSourceInput: EditText
    private lateinit var stepsDatePropertyInput: EditText
    private lateinit var stepsPropertyInput: EditText
    private lateinit var vitalsDataSourceInput: EditText
    private lateinit var vitalsMeasuredAtPropertyInput: EditText
    private lateinit var systolicPropertyInput: EditText
    private lateinit var diastolicPropertyInput: EditText
    private lateinit var heartRatePropertyInput: EditText
    private var currentSyncJob: Job? = null
    private var syncDialog: Dialog? = null
    private var syncDialogMessageText: TextView? = null
    private val lookbackDays = 30L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            statusText.text = if (granted.containsAll(requiredPermissions)) {
                "Health Connectの権限が許可されました。"
            } else {
                "最新日付の表示にはHealth Connectの歩数、血圧、心拍の読み取り権限が必要です。"
            }
            refreshLatestDates()
        }
        showTopPage()
    }

    override fun onDestroy() {
        currentSyncJob?.cancel()
        dismissSyncDialog()
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

        root.addButton("Health Connect権限を許可") { requestHealthPermission() }
        root.addButton("歩数データを同期") { syncStepsToNotion() }
        root.addButton("血圧・心拍データを同期") { syncVitalsToNotion() }
        root.addButton("すべて同期") { syncAllToNotion() }
        root.addButton("最新日付を更新") { refreshLatestDates() }

        statusText = TextView(this).apply {
            text = "設定後に同期してください。歩数データはHealth Connectの記録単位でNotionへ同期します。"
            textSize = 16f
            setTextColor(Color.parseColor("#D9E3EA"))
            setPadding(0, (14 * density).toInt(), 0, 0)
        }
        root.addView(statusText)

        setContentView(centeredScrollContent(root, padding))
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

        root.addButton("設定を保存") {
            saveSettings()
            statusText.text = "設定を保存しました。"
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

    private fun centeredScrollContent(root: LinearLayout, basePadding: Int): ScrollView {
        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#101820"))
            addView(root)
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
        val density = resources.displayMetrics.density
        val button = Button(context).apply {
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
            setOnClickListener { onClick() }
        }
        addView(button)
        return button
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("notion", Context.MODE_PRIVATE)
        tokenInput.setText(SecureSettingsStore.loadToken(prefs))
        stepsDataSourceInput.setText(
            prefs.getString("stepsDataSource", prefs.getString("dataSource", prefs.getString("database", "")))
        )
        stepsDatePropertyInput.setText(prefs.getString("stepsDateProperty", prefs.getString("dateProperty", "Date")))
        stepsPropertyInput.setText(prefs.getString("stepsProperty", "Steps"))
        vitalsDataSourceInput.setText(prefs.getString("vitalsDataSource", ""))
        vitalsMeasuredAtPropertyInput.setText(prefs.getString("vitalsMeasuredAtProperty", "Measured At"))
        systolicPropertyInput.setText(prefs.getString("systolicProperty", "Systolic"))
        diastolicPropertyInput.setText(prefs.getString("diastolicProperty", "Diastolic"))
        heartRatePropertyInput.setText(prefs.getString("heartRateProperty", "Heart Rate"))
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
            .apply()
    }

    private fun currentConfig(): SyncConfig {
        val prefs = getSharedPreferences("notion", Context.MODE_PRIVATE)
        return SyncConfig(
            token = SecureSettingsStore.loadToken(prefs),
            stepsDataSourceId = prefs.getString("stepsDataSource", prefs.getString("dataSource", "")) ?: "",
            stepsDateProperty = prefs.getString("stepsDateProperty", prefs.getString("dateProperty", "Date")) ?: "Date",
            stepsProperty = prefs.getString("stepsProperty", "Steps") ?: "Steps",
            vitalsDataSourceId = prefs.getString("vitalsDataSource", "") ?: "",
            vitalsMeasuredAtProperty = prefs.getString("vitalsMeasuredAtProperty", "Measured At") ?: "Measured At",
            systolicProperty = prefs.getString("systolicProperty", "Systolic") ?: "Systolic",
            diastolicProperty = prefs.getString("diastolicProperty", "Diastolic") ?: "Diastolic",
            heartRateProperty = prefs.getString("heartRateProperty", "Heart Rate") ?: "Heart Rate"
        )
    }

    private fun requestHealthPermission() {
        CoroutineScope(Dispatchers.Main).launch {
            val client = healthConnectClientOrNull()
            if (client == null) {
                statusText.text = "Health Connectが利用できません。Pixelの設定でHealth Connectを確認してください。"
                return@launch
            }
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(requiredPermissions)) {
                statusText.text = "Health Connectの権限は許可済みです。"
                refreshLatestDates()
            } else {
                permissionLauncher.launch(requiredPermissions)
            }
        }
    }

    private fun refreshLatestDates() {
        val config = currentConfig()
        CoroutineScope(Dispatchers.Main).launch {
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
        }
    }

    private fun syncStepsToNotion() {
        val config = currentConfig()
        if (!config.hasStepsSettings()) {
            statusText.text = "歩数データのNotion設定を入力してください。"
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

    private fun syncVitalsToNotion() {
        val config = currentConfig()
        if (!config.hasVitalsSettings()) {
            statusText.text = "血圧・心拍データのNotion設定を入力してください。"
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
            statusText.text = "歩数と血圧・心拍データのNotion設定を入力してください。"
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

        statusText.text = syncMessage
        showSyncDialog(syncMessage)
        currentSyncJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val client = checkedHealthClient() ?: return@launch
                val resultMessage = withContext(Dispatchers.IO) { sync(client) }
                statusText.text = resultMessage
                refreshLatestDates()
            } catch (_: CancellationException) {
                statusText.text = "同期を中止しました。"
            } catch (e: Exception) {
                statusText.text = "$failurePrefix: ${safeErrorMessage(e)}"
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

    private fun dismissSyncDialog() {
        syncDialog?.dismiss()
        syncDialog = null
        syncDialogMessageText = null
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
            statusText.text = "Health Connectが利用できません。"
            return null
        }
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(requiredPermissions)) {
            permissionLauncher.launch(requiredPermissions)
            statusText.text = "Health Connectの権限を許可してから再度実行してください。"
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
        val notion = NotionClient(config)
        val existingTimes = notion.readStepTimes(lookbackDays).toMutableSet()
        var synced = 0
        for (steps in readStepRecords(client)) {
            coroutineContext.ensureActive()
            if (steps.recordedAt !in existingTimes) {
                notion.createStepPage(steps)
                existingTimes.add(steps.recordedAt)
                synced++
            }
        }
        return synced
    }

    private suspend fun syncUnsyncedVitals(client: HealthConnectClient, config: SyncConfig): Int {
        val notion = NotionClient(config)
        val notionMeasurements = notion.readVitalMeasurements(lookbackDays)
        val existingTimes = readVitalMeasurementTimes(client)
        val measurementsToInsert = notionMeasurements.filter { it.measuredAt !in existingTimes }
        coroutineContext.ensureActive()
        if (measurementsToInsert.isEmpty()) {
            return 0
        }

        client.insertRecords(measurementsToInsert.flatMap { it.toHealthConnectRecords() })
        return measurementsToInsert.size
    }

    private suspend fun readStepRecords(client: HealthConnectClient): List<StepMeasurement> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.minusDays(lookbackDays).atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        return client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                ascendingOrder = true,
                pageSize = 5000
            )
        ).records
            .filter { it.count > 0L }
            .map { StepMeasurement(recordedAt = it.startTime, steps = it.count) }
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

    private suspend fun readVitalMeasurementTimes(client: HealthConnectClient): Set<Instant> {
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

private data class StepMeasurement(val recordedAt: Instant, val steps: Long)

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

    fun readStepTimes(lookbackDays: Long): Set<Instant> {
        return readDateTimes(
            dataSourceId = validDataSourceId(config.stepsDataSourceId),
            dateProperty = config.stepsDateProperty,
            lookbackDays = lookbackDays,
            includeToday = true
        )
    }

    fun createStepPage(steps: StepMeasurement) {
        val body = JSONObject()
            .put("parent", dataSourceParent(validDataSourceId(config.stepsDataSourceId)))
            .put(
                "properties",
                JSONObject()
                    .put(config.stepsDateProperty, JSONObject().put("date", JSONObject().put("start", steps.recordedAt.toNotionDateTime())))
                    .put(config.stepsProperty, JSONObject().put("number", steps.steps))
            )
        request("POST", "https://api.notion.com/v1/pages", body)
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

    private fun readDateTimes(
        dataSourceId: String,
        dateProperty: String,
        lookbackDays: Long,
        includeToday: Boolean
    ): Set<Instant> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.minusDays(lookbackDays).atStartOfDay(zone).toInstant().toNotionDateTime()
        val endDate = if (includeToday) today.plusDays(1) else today
        val end = endDate.atStartOfDay(zone).toInstant().toNotionDateTime()
        val times = mutableSetOf<Instant>()
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
                results.optJSONObject(i)
                    ?.optJSONObject("properties")
                    ?.optJSONObject(dateProperty)
                    ?.optJSONObject("date")
                    ?.optString("start")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?.let { times.add(it) }
            }
            cursor = response.optString("next_cursor").takeIf {
                response.optBoolean("has_more") && it.isNotBlank()
            }
        } while (cursor != null)

        return times
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
