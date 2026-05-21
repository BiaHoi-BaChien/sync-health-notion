package net.biahoi.stepnotionsync

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
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
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Pressure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>
    private var pendingPermissionRequest: PermissionRequest? = null
    private lateinit var statusText: TextView
    private lateinit var stepPendingText: TextView
    private lateinit var vitalsPendingText: TextView
    private lateinit var tokenInput: EditText
    private lateinit var stepsDataSourceInput: EditText
    private lateinit var stepsDatePropertyInput: EditText
    private lateinit var stepsPropertyInput: EditText
    private lateinit var vitalsDataSourceInput: EditText
    private lateinit var vitalsMeasuredAtPropertyInput: EditText
    private lateinit var systolicPropertyInput: EditText
    private lateinit var diastolicPropertyInput: EditText
    private lateinit var heartRatePropertyInput: EditText
    private val lookbackDays = 30L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            statusText.text = if (granted.containsAll(requiredPermissions)) {
                "Health Connectの権限が許可されました。"
            } else {
                "Health Connectの歩数、血圧、心拍の読み取り権限が必要です。"
            }
            refreshPendingCounts()
        }
        showTopPage()
    }

    private fun showTopPage() {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
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
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(this).apply {
            text = "設定"
            setOnClickListener { showSettingsPage() }
        })
        root.addView(header)

        stepPendingText = root.addMetric("歩数データ", "未同期データを確認中...")
        vitalsPendingText = root.addMetric("血圧データ", "未同期データを確認中...")

        root.addButton("Health Connect権限を許可") { requestHealthPermission() }
        root.addButton("歩数データを同期") { syncStepsToNotion() }
        root.addButton("血圧・心拍データを同期") { syncVitalsToNotion() }
        root.addButton("すべて同期") { syncAllToNotion() }
        root.addButton("未同期件数を更新") { refreshPendingCounts() }

        statusText = TextView(this).apply {
            text = "本日の歩数データは同期対象外です。設定後に同期してください。"
            textSize = 16f
            setPadding(0, padding, 0, 0)
        }
        root.addView(statusText)

        setContentView(scrollableContent(root))
        refreshPendingCounts()
    }

    private fun showSettingsPage() {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val smallPadding = (8 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(TextView(this).apply {
            text = "設定"
            textSize = 24f
        })

        tokenInput = root.addInput("Notion Integration Token", password = true)
        root.addSectionTitle("歩数データ")
        stepsDataSourceInput = root.addInput("歩数 Data Source ID")
        stepsDatePropertyInput = root.addInput("歩数 日付カラム名")
        stepsPropertyInput = root.addInput("歩数カラム名")
        root.addSectionTitle("血圧・心拍データ")
        vitalsDataSourceInput = root.addInput("血圧 Data Source ID")
        vitalsMeasuredAtPropertyInput = root.addInput("測定日時カラム名")
        systolicPropertyInput = root.addInput("最高血圧カラム名")
        diastolicPropertyInput = root.addInput("最低血圧カラム名")
        heartRatePropertyInput = root.addInput("心拍カラム名")

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, smallPadding, 0, smallPadding)
        }
        stepTabButton = tabRow.addTabButton("歩数") { showTab(AppTab.STEPS) }
        bloodPressureTabButton = tabRow.addTabButton("血圧") { showTab(AppTab.BLOOD_PRESSURE) }
        root.addView(tabRow)

        stepsContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stepDataSourceInput = stepsContent.addInput("歩数 Notion Data Source ID")
        stepDatePropertyInput = stepsContent.addInput("歩数 Date property name")
        stepsPropertyInput = stepsContent.addInput("Steps property name")
        stepsContent.addButton("歩数設定を保存") {
            saveSettings()
            statusText.text = "歩数設定を保存しました。"
        }
        stepsContent.addButton("歩数読み取り権限を許可") {
            requestHealthPermissions(
                PermissionRequest(
                    permissions = stepPermissions,
                    grantedMessage = "Health Connectの歩数読み取り権限が許可されました。",
                    deniedMessage = "Health Connectの歩数読み取り権限が必要です。"
                )
            )
        }
        stepsContent.addButton("歩数をNotionへ同期") { syncStepsToNotion() }
        root.addView(stepsContent)

        bloodPressureContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bloodPressureDataSourceInput = bloodPressureContent.addInput("血圧 Notion Data Source ID")
        bloodPressureDatePropertyInput = bloodPressureContent.addInput("血圧 Date property name")
        systolicPropertyInput = bloodPressureContent.addInput("最高血圧 property name")
        diastolicPropertyInput = bloodPressureContent.addInput("最低血圧 property name")
        heartRatePropertyInput = bloodPressureContent.addInput("心拍数 property name")
        bloodPressureContent.addButton("血圧設定を保存") {
            saveSettings()
            statusText.text = "血圧設定を保存しました。"
        }
        bloodPressureContent.addButton("血圧/心拍書き込み権限を許可") {
            requestHealthPermissions(
                PermissionRequest(
                    permissions = bloodPressurePermissions,
                    grantedMessage = "Health Connectの血圧/心拍書き込み権限が許可されました。",
                    deniedMessage = "Health Connectの血圧/心拍書き込み権限が必要です。"
                )
            )
        }
        root.addButton("TOPへ戻る") { showTopPage() }

        statusText = TextView(this).apply {
            text = "NotionのData Source IDとカラム名を入力してください。"
            textSize = 16f
            setPadding(0, padding, 0, 0)
        }
        root.addView(statusText)

        setContentView(scrollableContent(root))
        loadSettings()
    }

    private fun scrollableContent(root: LinearLayout): ScrollView {
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        return ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root)
            setOnApplyWindowInsetsListener { view, insets ->
                val systemBars = insets.systemBarInsets()
                root.setPadding(
                    baseLeft + systemBars.left,
                    baseTop + systemBars.top,
                    baseRight + systemBars.right,
                    baseBottom + systemBars.bottom
                )
                view.setPadding(0, 0, 0, 0)
                insets
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun WindowInsets.systemBarInsets(): InsetsCompat {
        return InsetsCompat(
            left = systemWindowInsetLeft,
            top = systemWindowInsetTop,
            right = systemWindowInsetRight,
            bottom = systemWindowInsetBottom
        )
    }

    private fun LinearLayout.addMetric(title: String, value: String): TextView {
        addView(TextView(context).apply {
            text = title
            textSize = 18f
            setPadding(0, 24, 0, 0)
        })
        val valueView = TextView(context).apply {
            text = value
            textSize = 28f
        }
        addView(valueView)
        return valueView
    }

    private fun LinearLayout.addSectionTitle(title: String) {
        addView(TextView(context).apply {
            text = title
            textSize = 18f
            setPadding(0, 24, 0, 0)
        })
    }

    private fun LinearLayout.addInput(hintText: String, password: Boolean = false): EditText {
        val input = EditText(context).apply {
            hint = hintText
            setSingleLine(true)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        addView(input)
        return input
    }

    private fun LinearLayout.addButton(label: String, onClick: () -> Unit): Button {
        val button = Button(context).apply {
            text = label
            setOnClickListener { onClick() }
        }
        addView(button)
        return button
    }

    private fun LinearLayout.addTabButton(label: String, onClick: () -> Unit): Button {
        val button = Button(context).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        addView(button)
        return button
    }

    private fun showTab(tab: AppTab) {
        stepsContent.visibility = if (tab == AppTab.STEPS) View.VISIBLE else View.GONE
        bloodPressureContent.visibility = if (tab == AppTab.BLOOD_PRESSURE) View.VISIBLE else View.GONE
        stepTabButton.isEnabled = tab != AppTab.STEPS
        bloodPressureTabButton.isEnabled = tab != AppTab.BLOOD_PRESSURE
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("notion", Context.MODE_PRIVATE)
        tokenInput.setText(prefs.getString("token", ""))
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
        getSharedPreferences("notion", Context.MODE_PRIVATE).edit()
            .putString("token", tokenInput.text.toString().trim())
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
            token = prefs.getString("token", "") ?: "",
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
            } else {
                pendingPermissionRequest = request
                permissionLauncher.launch(request.permissions)
            }
        }
    }

    private fun refreshPendingCounts() {
        val config = currentConfig()
        if (!config.hasStepsSettings()) {
            stepPendingText.text = "設定が必要です"
        }
        if (!config.hasVitalsSettings()) {
            vitalsPendingText.text = "設定が必要です"
        }
        if (!config.hasStepsSettings() && !config.hasVitalsSettings()) {
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val client = healthConnectClientOrNull()
                    ?: error("Health Connectが利用できません。")
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.containsAll(requiredPermissions)) {
                    statusText.text = "未同期件数の確認にはHealth Connect権限が必要です。"
                    return@launch
                }

                if (config.hasStepsSettings()) {
                    val count = withContext(Dispatchers.IO) { countUnsyncedSteps(client, config) }
                    stepPendingText.text = "${count}件"
                }
                if (config.hasVitalsSettings()) {
                    val count = withContext(Dispatchers.IO) { countUnsyncedVitals(client, config) }
                    vitalsPendingText.text = "${count}件"
                }
                statusText.text = "未同期件数を更新しました。本日の歩数データは含めていません。"
            } catch (e: Exception) {
                statusText.text = "未同期件数の確認に失敗しました: ${e.message}"
            }
        }
    }

    private fun syncStepsToNotion() {
        val config = currentConfig()
        if (!config.hasStepsSettings()) {
            statusText.text = "歩数データのNotion設定を入力してください。"
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            statusText.text = "歩数データを同期中..."
            try {
                val client = checkedHealthClient() ?: return@launch
                val synced = withContext(Dispatchers.IO) { syncUnsyncedSteps(client, config) }
                statusText.text = "歩数データを${synced}件同期しました。本日の歩数データは同期していません。"
                refreshPendingCounts()
            } catch (e: Exception) {
                statusText.text = "歩数データの同期に失敗しました: ${e.message}"
            }
        }
    }

    private fun syncVitalsToNotion() {
        val config = currentConfig()
        if (!config.hasVitalsSettings()) {
            statusText.text = "血圧・心拍データのNotion設定を入力してください。"
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            statusText.text = "血圧・心拍データを同期中..."
            try {
                val client = checkedHealthClient() ?: return@launch
                val synced = withContext(Dispatchers.IO) { syncUnsyncedVitals(client, config) }
                statusText.text = "血圧・心拍データを${synced}件同期しました。"
                refreshPendingCounts()
            } catch (e: Exception) {
                statusText.text = "血圧・心拍データの同期に失敗しました: ${e.message}"
            }
        }
    }

    private fun syncAllToNotion() {
        val config = currentConfig()
        if (!config.hasStepsSettings() || !config.hasVitalsSettings()) {
            statusText.text = "歩数データと血圧・心拍データのNotion設定を入力してください。"
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            statusText.text = "すべて同期中..."
            try {
                val client = checkedHealthClient() ?: return@launch
                val result = withContext(Dispatchers.IO) {
                    val steps = syncUnsyncedSteps(client, config)
                    val vitals = syncUnsyncedVitals(client, config)
                    steps to vitals
                }
                statusText.text = "歩数${result.first}件、血圧・心拍${result.second}件を同期しました。本日の歩数データは同期していません。"
                refreshPendingCounts()
            } catch (e: Exception) {
                statusText.text = "歩数同期に失敗しました: ${e.message}"
            }
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

    private suspend fun countUnsyncedSteps(client: HealthConnectClient, config: SyncConfig): Int {
        val notion = NotionClient(config)
        return readStepDays(client).count { !notion.hasStepPage(it.date) }
    }

    private suspend fun syncUnsyncedSteps(client: HealthConnectClient, config: SyncConfig): Int {
        val notion = NotionClient(config)
        var synced = 0
        for (dailySteps in readStepDays(client)) {
            if (!notion.hasStepPage(dailySteps.date)) {
                notion.createStepPage(dailySteps.date, dailySteps.steps)
                synced++
            }
        }
        return synced
    }

    private suspend fun countUnsyncedVitals(client: HealthConnectClient, config: SyncConfig): Int {
        val notion = NotionClient(config)
        return readVitalMeasurements(client).count { !notion.hasVitalPage(it.measuredAt) }
    }

    private suspend fun syncUnsyncedVitals(client: HealthConnectClient, config: SyncConfig): Int {
        val notion = NotionClient(config)
        var synced = 0
        for (measurement in readVitalMeasurements(client)) {
            if (!notion.hasVitalPage(measurement.measuredAt)) {
                notion.createVitalPage(measurement)
                synced++
            }
        }
        return synced
    }

    private suspend fun readStepDays(client: HealthConnectClient): List<DailySteps> {
        val today = LocalDate.now()
        return ((lookbackDays downTo 1).mapNotNull { daysAgo ->
            val date = today.minusDays(daysAgo)
            val steps = readStepsForDate(client, date)
            if (steps > 0L) DailySteps(date, steps) else null
        })
    }

    private suspend fun readStepsForDate(client: HealthConnectClient, date: LocalDate): Long {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val response = client.aggregate(
            AggregateRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
            response.mapNotNullTo(dailySteps) { result ->
                val steps = result.result[StepsRecord.COUNT_TOTAL] ?: 0L
                if (steps <= 0L) {
                    null
                } else {
                    DailySteps(result.startTime.toLocalDate(), steps)
                }
            }
            chunkStart = chunkEnd
        }
        return dailySteps
    }

    private fun LocalDate.coerceAtMost(maximumValue: LocalDate): LocalDate {
        return if (isAfter(maximumValue)) maximumValue else this
    }

    private companion object {
        const val MAX_HEALTH_CONNECT_GROUPS_PER_REQUEST = 4_999L
    }

    private suspend fun writeBloodPressureEntries(
        client: HealthConnectClient,
        entries: List<BloodPressureEntry>
    ): BloodPressureSyncResult {
        val records = entries.flatMap { entry ->
            val zoneOffset = ZoneId.systemDefault().rules.getOffset(entry.time)
            val version = entry.lastEditedTime.toEpochMilli()
            val bloodPressureRecord = BloodPressureRecord(
                time = entry.time,
                zoneOffset = zoneOffset,
                metadata = Metadata.manualEntry("notion-bp-${entry.pageId}", version),
                systolic = Pressure.millimetersOfMercury(entry.systolic),
                diastolic = Pressure.millimetersOfMercury(entry.diastolic),
                bodyPosition = BloodPressureRecord.BODY_POSITION_UNKNOWN,
                measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_UNKNOWN
            )
            val heartRateRecord = entry.heartRate?.let { heartRate ->
                HeartRateRecord(
                    startTime = entry.time,
                    startZoneOffset = zoneOffset,
                    endTime = entry.time.plusSeconds(1),
                    endZoneOffset = zoneOffset,
                    samples = listOf(HeartRateRecord.Sample(entry.time, heartRate)),
                    metadata = Metadata.manualEntry("notion-hr-${entry.pageId}", version)
                )
            }
            listOfNotNull(bloodPressureRecord, heartRateRecord)
        }
        client.insertRecords(records)
        return BloodPressureSyncResult(
            bloodPressureCount = entries.size,
            heartRateCount = entries.count { it.heartRate != null }
        )
    }

    private suspend fun readVitalMeasurements(client: HealthConnectClient): List<VitalMeasurement> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val start = today.minusDays(lookbackDays).atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        val bloodPressureRecords = readBloodPressureRecords(client, start, end)
        val heartRateSamples = readHeartRateSamples(client, start, end)
        return bloodPressureRecords.map { record ->
            val nearestHeartRate = heartRateSamples
                .filter { it.time.atZone(zone).toLocalDate() == record.time.atZone(zone).toLocalDate() }
                .minByOrNull { abs(Duration.between(record.time, it.time).toMillis()) }
            VitalMeasurement(
                measuredAt = record.time,
                systolic = record.systolic.inMillimetersOfMercury,
                diastolic = record.diastolic.inMillimetersOfMercury,
                heartRate = nearestHeartRate?.beatsPerMinute
            )
        }.sortedBy { it.measuredAt }
    }

    private suspend fun readBloodPressureRecords(
        client: HealthConnectClient,
        start: Instant,
        end: Instant
    ): List<BloodPressureRecord> {
        return client.readRecords(
            ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        ).records
    }

    private suspend fun readHeartRateSamples(
        client: HealthConnectClient,
        start: Instant,
        end: Instant
    ): List<HeartRateRecord.Sample> {
        return client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        ).records.flatMap { it.samples }
    }
}

private data class InsetsCompat(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

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

private data class DailySteps(val date: LocalDate, val steps: Long)

private data class VitalMeasurement(
    val measuredAt: Instant,
    val systolic: Double,
    val diastolic: Double,
    val heartRate: Long?
)

private class NotionClient(private val config: SyncConfig) {
    fun hasStepPage(date: LocalDate): Boolean {
        return findPage(
            dataSourceId = config.stepsDataSourceId,
            property = config.stepsDateProperty,
            dateValue = date.toString()
        ) != null
    }

    fun createStepPage(date: LocalDate, steps: Long) {
        val body = JSONObject()
            .put(
                "parent",
                JSONObject()
                    .put("type", "data_source_id")
                    .put("data_source_id", config.stepsDataSourceId)
            )
            .put(
                "properties",
                JSONObject()
                    .put(config.stepsDateProperty, JSONObject().put("date", JSONObject().put("start", date.toString())))
                    .put(config.stepsProperty, JSONObject().put("number", steps))
            )
        request("POST", "https://api.notion.com/v1/pages", body)
    }

    fun hasVitalPage(measuredAt: Instant): Boolean {
        return findPage(
            dataSourceId = config.vitalsDataSourceId,
            property = config.vitalsMeasuredAtProperty,
            dateValue = measuredAt.toNotionDateTime()
        ) != null
    }

    fun createVitalPage(measurement: VitalMeasurement) {
        val properties = JSONObject()
            .put(
                config.vitalsMeasuredAtProperty,
                JSONObject().put("date", JSONObject().put("start", measurement.measuredAt.toNotionDateTime()))
            )
            .put(config.systolicProperty, JSONObject().put("number", measurement.systolic))
            .put(config.diastolicProperty, JSONObject().put("number", measurement.diastolic))
        if (measurement.heartRate != null) {
            properties.put(config.heartRateProperty, JSONObject().put("number", measurement.heartRate))
        }

        val body = JSONObject()
            .put(
                "parent",
                JSONObject()
                    .put("type", "data_source_id")
                    .put("data_source_id", config.vitalsDataSourceId)
            )
            .put("properties", properties)
        request("POST", "https://api.notion.com/v1/pages", body)
    }

    private fun findPage(dataSourceId: String, property: String, dateValue: String): String? {
        val body = JSONObject()
            .put(
                "filter",
                JSONObject()
                    .put("property", property)
                    .put("date", JSONObject().put("equals", dateValue))
            )
            .put("page_size", 1)
        val response = request("POST", "https://api.notion.com/v1/data_sources/$dataSourceId/query", body)
        return response.optJSONArray("results")?.optJSONObject(0)?.optString("id")
    }

    private fun parseBloodPressureEntry(
        page: JSONObject,
        config: BloodPressureNotionConfig
    ): BloodPressureEntry? {
        val properties = page.optJSONObject("properties") ?: return null
        val systolic = properties.numberProperty(config.systolicProperty) ?: return null
        val diastolic = properties.numberProperty(config.diastolicProperty) ?: return null
        val time = properties.dateProperty(config.dateProperty)
            ?: page.optString("created_time").takeIf { it.isNotBlank() }?.toInstantOrNull()
            ?: return null
        return BloodPressureEntry(
            pageId = page.optString("id"),
            lastEditedTime = page.optString("last_edited_time").toInstantOrNull() ?: Instant.now(),
            time = time,
            systolic = systolic,
            diastolic = diastolic,
            heartRate = properties.numberProperty(config.heartRateProperty)?.toLong()
        )
    }

    private fun request(method: String, endpoint: String, body: JSONObject): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Notion-Version", "2026-03-11")
            setRequestProperty("Content-Type", "application/json")
        }
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        if (status !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
            error("Notion API ${status}: ${message ?: text}")
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}

private fun Instant.toNotionDateTime(): String =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(atZone(ZoneId.systemDefault()))
