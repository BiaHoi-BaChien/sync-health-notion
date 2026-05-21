package net.biahoi.stepnotionsync

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlin.math.abs
import java.time.Duration

class MainActivity : ComponentActivity() {
    private val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class)
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

    private fun showTopPage() {
        val density = resources.displayMetrics.density
        val padding = (18 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.parseColor("#101820"))
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
            text = "スマホとNotionの最新データ日付"
            textSize = 14f
            setTextColor(Color.parseColor("#AAB7C4"))
            setPadding(0, (4 * density).toInt(), 0, (16 * density).toInt())
        })

        root.addSummaryCard("歩数").also { section ->
            stepsPhoneDateText = section.addDateRow("スマホ側")
            stepsNotionDateText = section.addDateRow("Notion側")
        }
        root.addSummaryCard("バイタル").also { section ->
            vitalsPhoneDateText = section.addDateRow("スマホ側")
            vitalsNotionDateText = section.addDateRow("Notion側")
        }

        root.addButton("Health Connect権限を許可") { requestHealthPermission() }
        root.addButton("歩数データを同期") { syncStepsToNotion() }
        root.addButton("血圧・心拍データを同期") { syncVitalsToNotion() }
        root.addButton("すべて同期") { syncAllToNotion() }
        root.addButton("最新日付を更新") { refreshLatestDates() }

        statusText = TextView(this).apply {
            text = "設定後に同期してください。本日の歩数データは同期対象外です。"
            textSize = 16f
            setTextColor(Color.parseColor("#D9E3EA"))
            setPadding(0, (14 * density).toInt(), 0, 0)
        }
        root.addView(statusText)

        setContentView(ScrollView(this).apply { addView(root) })
        refreshLatestDates()
    }

    private fun showSettingsPage() {
        val density = resources.displayMetrics.density
        val padding = (18 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.parseColor("#101820"))
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

        setContentView(ScrollView(this).apply { addView(root) })
        loadSettings()
    }

    private fun LinearLayout.addSummaryCard(title: String): LinearLayout {
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
        addView(card)
        return card
    }

    private fun LinearLayout.addDateRow(label: String): TextView {
        val density = resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        row.addView(TextView(context).apply {
            text = label
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
                    stepsPhoneDateText.text = displayDate(readLatestStepsDate(client))
                } else {
                    stepsPhoneDateText.text = "権限未許可"
                }
                if (
                    granted.contains(HealthPermission.getReadPermission(BloodPressureRecord::class)) ||
                        granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class))
                ) {
                    vitalsPhoneDateText.text = displayDate(readLatestVitalsDate(client, granted))
                } else {
                    vitalsPhoneDateText.text = "権限未許可"
                }
            }

            withContext(Dispatchers.IO) {
                val stepsDate = runCatching {
                    if (config.hasStepsSettings()) NotionClient(config).latestStepsDate() else null
                }.getOrNull()
                val vitalsDate = runCatching {
                    if (config.hasVitalsSettings()) NotionClient(config).latestVitalsDate() else null
                }.getOrNull()
                withContext(Dispatchers.Main) {
                    stepsNotionDateText.text = if (config.hasStepsSettings()) displayDate(stepsDate) else "設定未完了"
                    vitalsNotionDateText.text = if (config.hasVitalsSettings()) displayDate(vitalsDate) else "設定未完了"
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

        CoroutineScope(Dispatchers.Main).launch {
            statusText.text = "歩数データを同期中..."
            try {
                val client = checkedHealthClient() ?: return@launch
                val synced = withContext(Dispatchers.IO) { syncUnsyncedSteps(client, config) }
                statusText.text = "歩数データを${synced}件同期しました。本日の歩数データは同期していません。"
                refreshLatestDates()
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
                refreshLatestDates()
            } catch (e: Exception) {
                statusText.text = "血圧・心拍データの同期に失敗しました: ${e.message}"
            }
        }
    }

    private fun syncAllToNotion() {
        syncStepsToNotion()
        syncVitalsToNotion()
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
        var synced = 0
        for (dailySteps in readStepDays(client)) {
            if (!notion.hasStepPage(dailySteps.date)) {
                notion.createStepPage(dailySteps.date, dailySteps.steps)
                synced++
            }
        }
        return synced
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
        return (lookbackDays downTo 1).mapNotNull { daysAgo ->
            val date = today.minusDays(daysAgo)
            val steps = readStepsForDate(client, date)
            if (steps > 0L) DailySteps(date, steps) else null
        }
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
        )
        return response[StepsRecord.COUNT_TOTAL] ?: 0L
    }

    private suspend fun readLatestStepsDate(client: HealthConnectClient): LocalDate? {
        return client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = latestRecordTimeRange(),
                ascendingOrder = false,
                pageSize = 1
            )
        ).records.firstOrNull()?.endTime?.toLocalDate()
    }

    private suspend fun readLatestVitalsDate(client: HealthConnectClient, granted: Set<String>): LocalDate? {
        val dates = mutableListOf<LocalDate>()
        if (granted.contains(HealthPermission.getReadPermission(BloodPressureRecord::class))) {
            readLatestBloodPressureDate(client)?.let { dates.add(it) }
        }
        if (granted.contains(HealthPermission.getReadPermission(HeartRateRecord::class))) {
            readLatestHeartRateDate(client)?.let { dates.add(it) }
        }
        return dates.maxOrNull()
    }

    private suspend fun readLatestBloodPressureDate(client: HealthConnectClient): LocalDate? {
        return client.readRecords(
            ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = latestRecordTimeRange(),
                ascendingOrder = false,
                pageSize = 1
            )
        ).records.firstOrNull()?.time?.toLocalDate()
    }

    private suspend fun readLatestHeartRateDate(client: HealthConnectClient): LocalDate? {
        return client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = latestRecordTimeRange(),
                ascendingOrder = false,
                pageSize = 1
            )
        ).records.firstOrNull()?.endTime?.toLocalDate()
    }

    private fun latestRecordTimeRange(): TimeRangeFilter {
        val now = Instant.now()
        return TimeRangeFilter.between(now.minusSeconds(3650L * 24 * 60 * 60), now.plusSeconds(24 * 60 * 60))
    }

    private suspend fun readVitalMeasurements(client: HealthConnectClient): List<VitalMeasurement> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val start = today.minusDays(lookbackDays).atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        val bloodPressureRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        ).records
        val heartRateSamples = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        ).records.flatMap { it.samples }

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

    private fun Instant.toLocalDate(): LocalDate = atZone(ZoneId.systemDefault()).toLocalDate()

    private fun displayDate(date: LocalDate?): String = date?.toString() ?: "データなし"
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

private data class DailySteps(val date: LocalDate, val steps: Long)

private data class VitalMeasurement(
    val measuredAt: Instant,
    val systolic: Double,
    val diastolic: Double,
    val heartRate: Long?
)

private class NotionClient(private val config: SyncConfig) {
    fun latestStepsDate(): LocalDate? = latestDate(config.stepsDataSourceId, config.stepsDateProperty)

    fun latestVitalsDate(): LocalDate? = latestDate(config.vitalsDataSourceId, config.vitalsMeasuredAtProperty)

    fun hasStepPage(date: LocalDate): Boolean {
        return findPage(
            dataSourceId = config.stepsDataSourceId,
            property = config.stepsDateProperty,
            dateValue = date.toString()
        ) != null
    }

    fun createStepPage(date: LocalDate, steps: Long) {
        val body = JSONObject()
            .put("parent", dataSourceParent(config.stepsDataSourceId))
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
            .put("parent", dataSourceParent(config.vitalsDataSourceId))
            .put("properties", properties)
        request("POST", "https://api.notion.com/v1/pages", body)
    }

    private fun latestDate(dataSourceId: String, dateProperty: String): LocalDate? {
        val body = JSONObject()
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
        val start = response.optJSONArray("results")
            ?.optJSONObject(0)
            ?.optJSONObject("properties")
            ?.optJSONObject(dateProperty)
            ?.optJSONObject("date")
            ?.optString("start")
            ?.takeIf { it.isNotBlank() }
        return start?.let { LocalDate.parse(it.take(10)) }
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

    private fun dataSourceParent(dataSourceId: String): JSONObject {
        return JSONObject()
            .put("type", "data_source_id")
            .put("data_source_id", dataSourceId)
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
            error("Notion API ${status}: ${message ?: text}")
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}

private fun Instant.toNotionDateTime(): String =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(atZone(ZoneId.systemDefault()))
