package net.biahoi.stepnotionsync

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
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
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val stepPermissions = setOf(HealthPermission.getReadPermission(StepsRecord::class))
    private val bloodPressurePermissions = setOf(
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class)
    )
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>
    private var pendingPermissionRequest: PermissionRequest? = null
    private lateinit var statusText: TextView
    private lateinit var tokenInput: EditText
    private lateinit var stepTabButton: Button
    private lateinit var bloodPressureTabButton: Button
    private lateinit var stepsContent: LinearLayout
    private lateinit var bloodPressureContent: LinearLayout
    private lateinit var stepDataSourceInput: EditText
    private lateinit var stepDatePropertyInput: EditText
    private lateinit var stepsPropertyInput: EditText
    private lateinit var bloodPressureDataSourceInput: EditText
    private lateinit var bloodPressureDatePropertyInput: EditText
    private lateinit var systolicPropertyInput: EditText
    private lateinit var diastolicPropertyInput: EditText
    private lateinit var heartRatePropertyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            val request = pendingPermissionRequest
            statusText.text = when {
                request == null -> "Health Connect権限の結果を受け取りました。"
                granted.containsAll(request.permissions) -> request.grantedMessage
                else -> request.deniedMessage
            }
            pendingPermissionRequest = null
        }
        buildUi()
        loadSettings()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val smallPadding = (8 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = "Step Notion Sync"
            textSize = 24f
        })

        tokenInput = root.addInput("Notion Integration Token", password = true)

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
        bloodPressureContent.addButton("血圧をGoogle Fitへ同期") { syncBloodPressureToHealthConnect() }
        root.addView(bloodPressureContent)

        statusText = TextView(this).apply {
            text = "Notion設定を入力し、権限を許可してから同期してください。"
            textSize = 16f
            setPadding(0, padding, 0, 0)
        }
        root.addView(statusText)

        setContentView(ScrollView(this).apply { addView(root) })
        showTab(AppTab.STEPS)
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
        stepDataSourceInput.setText(prefs.getString("stepDataSource", prefs.getString("dataSource", prefs.getString("database", ""))))
        stepDatePropertyInput.setText(prefs.getString("stepDateProperty", prefs.getString("dateProperty", "Date")))
        stepsPropertyInput.setText(prefs.getString("stepsProperty", "Steps"))
        bloodPressureDataSourceInput.setText(prefs.getString("bloodPressureDataSource", ""))
        bloodPressureDatePropertyInput.setText(prefs.getString("bloodPressureDateProperty", "Date"))
        systolicPropertyInput.setText(prefs.getString("systolicProperty", "Systolic"))
        diastolicPropertyInput.setText(prefs.getString("diastolicProperty", "Diastolic"))
        heartRatePropertyInput.setText(prefs.getString("heartRateProperty", "Heart Rate"))
    }

    private fun saveSettings() {
        getSharedPreferences("notion", Context.MODE_PRIVATE).edit()
            .putString("token", tokenInput.text.toString().trim())
            .putString("stepDataSource", stepDataSourceInput.text.toString().trim())
            .putString("stepDateProperty", stepDatePropertyInput.text.toString().trim())
            .putString("stepsProperty", stepsPropertyInput.text.toString().trim())
            .putString("bloodPressureDataSource", bloodPressureDataSourceInput.text.toString().trim())
            .putString("bloodPressureDateProperty", bloodPressureDatePropertyInput.text.toString().trim())
            .putString("systolicProperty", systolicPropertyInput.text.toString().trim())
            .putString("diastolicProperty", diastolicPropertyInput.text.toString().trim())
            .putString("heartRateProperty", heartRatePropertyInput.text.toString().trim())
            .apply()
    }

    private fun requestHealthPermissions(request: PermissionRequest) {
        CoroutineScope(Dispatchers.Main).launch {
            val client = healthConnectClientOrNull()
            if (client == null) {
                statusText.text = "Health Connectが利用できません。Pixelの設定でHealth Connectを確認してください。"
                return@launch
            }
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(request.permissions)) {
                statusText.text = request.grantedMessage
            } else {
                pendingPermissionRequest = request
                permissionLauncher.launch(request.permissions)
            }
        }
    }

    private fun syncStepsToNotion() {
        if (syncJob?.isActive == true) {
            statusText.text = "同期中です。中断する場合は「中断」を押してください。"
            return
        }

        saveSettings()
        val config = StepNotionConfig(
            token = tokenInput.text.toString().trim(),
            dataSourceId = stepDataSourceInput.text.toString().trim(),
            dateProperty = stepDatePropertyInput.text.toString().trim(),
            stepsProperty = stepsPropertyInput.text.toString().trim()
        )
        if (!config.isComplete()) {
            statusText.text = "Notion Token、歩数 Data Source ID、プロパティ名を入力してください。"
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            statusText.text = "歩数を同期中..."
            try {
                val client = healthConnectClientOrNull()
                    ?: error("Health Connectが利用できません。")
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.containsAll(stepPermissions)) {
                    pendingPermissionRequest = PermissionRequest(
                        permissions = stepPermissions,
                        grantedMessage = "Health Connectの歩数読み取り権限が許可されました。",
                        deniedMessage = "Health Connectの歩数読み取り権限が必要です。"
                    )
                    permissionLauncher.launch(stepPermissions)
                    statusText.text = "Health Connectの歩数読み取り権限を許可してから再度同期してください。"
                    return@launch
                }

                val date = LocalDate.now()
                val steps = readStepsForDate(client, date)
                withContext(Dispatchers.IO) {
                    NotionClient(config.token).upsertSteps(config, date, steps)
                }

                statusText.text =
                    "${dailySteps.size}日分を確認しました。作成: ${createdCount}、更新: ${updatedCount}、スキップ: ${skippedCount}"
            } catch (e: CancellationException) {
                statusText.text = "同期を中断しました。"
            } catch (e: Exception) {
                statusText.text = "歩数同期に失敗しました: ${e.message}"
            }
        }
    }

    private fun syncBloodPressureToHealthConnect() {
        saveSettings()
        val config = BloodPressureNotionConfig(
            token = tokenInput.text.toString().trim(),
            dataSourceId = bloodPressureDataSourceInput.text.toString().trim(),
            dateProperty = bloodPressureDatePropertyInput.text.toString().trim(),
            systolicProperty = systolicPropertyInput.text.toString().trim(),
            diastolicProperty = diastolicPropertyInput.text.toString().trim(),
            heartRateProperty = heartRatePropertyInput.text.toString().trim()
        )
        if (!config.isComplete()) {
            statusText.text = "Notion Token、血圧 Data Source ID、プロパティ名を入力してください。"
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            statusText.text = "血圧データを取得中..."
            try {
                val client = healthConnectClientOrNull()
                    ?: error("Health Connectが利用できません。")
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.containsAll(bloodPressurePermissions)) {
                    pendingPermissionRequest = PermissionRequest(
                        permissions = bloodPressurePermissions,
                        grantedMessage = "Health Connectの血圧/心拍書き込み権限が許可されました。",
                        deniedMessage = "Health Connectの血圧/心拍書き込み権限が必要です。"
                    )
                    permissionLauncher.launch(bloodPressurePermissions)
                    statusText.text = "Health Connectの血圧/心拍書き込み権限を許可してから再度同期してください。"
                    return@launch
                }

                val entries = withContext(Dispatchers.IO) {
                    NotionClient(config.token).fetchBloodPressureEntries(config)
                }
                if (entries.isEmpty()) {
                    statusText.text = "同期できる血圧データが見つかりませんでした。"
                    return@launch
                }
                val inserted = writeBloodPressureEntries(client, entries)
                statusText.text = "${inserted.bloodPressureCount}件の血圧データと${inserted.heartRateCount}件の心拍数をHealth Connectへ同期しました。"
            } catch (e: Exception) {
                statusText.text = "血圧同期に失敗しました: ${e.message}"
            }
        }
    }

    private fun cancelSync() {
        syncJob?.cancel()
        statusText.text = "同期を中断しています..."
    }

    private fun setSyncUi(isSyncing: Boolean) {
        syncButton.isEnabled = !isSyncing
        cancelButton.isEnabled = isSyncing
        if (!isSyncing) {
            progressBar.isIndeterminate = false
            syncJob = null
        }
    }

    private fun healthConnectClientOrNull(): HealthConnectClient? {
        return when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(this)
            else -> null
        }
    }

    private suspend fun readAllDailySteps(client: HealthConnectClient): List<DailySteps> {
        val end = LocalDate.now().plusDays(1)
        val start = end.minusYears(1)
        val dailySteps = mutableListOf<DailySteps>()
        var chunkStart = start
        while (chunkStart.isBefore(end)) {
            currentCoroutineContext().ensureActive()
            val chunkEnd = chunkStart.plusDays(MAX_HEALTH_CONNECT_GROUPS_PER_REQUEST).coerceAtMost(end)
            val response = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(chunkStart.atStartOfDay(), chunkEnd.atStartOfDay()),
                    timeRangeSlicer = Period.ofDays(1)
                )
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
}

private enum class AppTab {
    STEPS,
    BLOOD_PRESSURE
}

private data class PermissionRequest(
    val permissions: Set<String>,
    val grantedMessage: String,
    val deniedMessage: String
)

private data class StepNotionConfig(
    val token: String,
    val dataSourceId: String,
    val dateProperty: String,
    val stepsProperty: String
) {
    fun isComplete(): Boolean =
        token.isNotBlank() &&
            dataSourceId.isNotBlank() &&
            dateProperty.isNotBlank() &&
            stepsProperty.isNotBlank()
}

private data class BloodPressureNotionConfig(
    val token: String,
    val dataSourceId: String,
    val dateProperty: String,
    val systolicProperty: String,
    val diastolicProperty: String,
    val heartRateProperty: String
) {
    fun isComplete(): Boolean =
        token.isNotBlank() &&
            dataSourceId.isNotBlank() &&
            dateProperty.isNotBlank() &&
            systolicProperty.isNotBlank() &&
            diastolicProperty.isNotBlank() &&
            heartRateProperty.isNotBlank()
}

private data class BloodPressureEntry(
    val pageId: String,
    val lastEditedTime: Instant,
    val time: Instant,
    val systolic: Double,
    val diastolic: Double,
    val heartRate: Long?
)

private data class BloodPressureSyncResult(
    val bloodPressureCount: Int,
    val heartRateCount: Int
)

private class NotionClient(private val token: String) {
    fun upsertSteps(config: StepNotionConfig, date: LocalDate, steps: Long) {
        val pageId = findPageForDate(config, date)
        if (pageId == null) {
            createStepPage(config, date, steps)
        } else {
            updateStepPage(config, pageId, date, steps)
        }
    }

    fun fetchBloodPressureEntries(config: BloodPressureNotionConfig): List<BloodPressureEntry> {
        val entries = mutableListOf<BloodPressureEntry>()
        var cursor: String? = null
        do {
            val body = JSONObject().put("page_size", 100)
            if (cursor != null) {
                body.put("start_cursor", cursor)
            }
            val response = request("POST", "https://api.notion.com/v1/data_sources/${config.dataSourceId}/query", body)
            val results = response.optJSONArray("results")
            if (results != null) {
                for (index in 0 until results.length()) {
                    val page = results.optJSONObject(index) ?: continue
                    parseBloodPressureEntry(page, config)?.let(entries::add)
                }
            }
            cursor = response.optString("next_cursor").takeIf { response.optBoolean("has_more") && it.isNotBlank() }
        } while (cursor != null)
        return entries
    }

    private fun findPageForDate(config: StepNotionConfig, date: LocalDate): String? {
        val body = JSONObject()
            .put(
                "filter",
                JSONObject()
                    .put("property", config.dateProperty)
                    .put("date", JSONObject().put("equals", date.toString()))
            )
            .put("page_size", 1)
        val response = request("POST", "https://api.notion.com/v1/data_sources/${config.dataSourceId}/query", body)
        return response.optJSONArray("results")?.optJSONObject(0)?.optString("id")
    }

    private fun createStepPage(config: StepNotionConfig, date: LocalDate, steps: Long) {
        val body = JSONObject()
            .put(
                "parent",
                JSONObject()
                    .put("type", "data_source_id")
                    .put("data_source_id", config.dataSourceId)
            )
            .put("properties", stepProperties(config, date, steps))
        request("POST", "https://api.notion.com/v1/pages", body)
    }

    private fun updateStepPage(config: StepNotionConfig, pageId: String, date: LocalDate, steps: Long) {
        val body = JSONObject().put("properties", stepProperties(config, date, steps))
        request("PATCH", "https://api.notion.com/v1/pages/$pageId", body)
    }

    private fun stepProperties(config: StepNotionConfig, date: LocalDate, steps: Long): JSONObject {
        return JSONObject()
            .put(config.dateProperty, JSONObject().put("date", JSONObject().put("start", date.toString())))
            .put(config.stepsProperty, JSONObject().put("number", steps))
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

private fun JSONObject.numberProperty(name: String): Double? {
    val property = optJSONObject(name) ?: return null
    if (!property.isNull("number")) {
        return property.optDouble("number")
    }
    return property.optString("number").takeIf { it.isNotBlank() }?.toDoubleOrNull()
}

private fun JSONObject.dateProperty(name: String): Instant? {
    val property = optJSONObject(name) ?: return null
    val start = property.optJSONObject("date")?.optString("start") ?: return null
    return start.toInstantOrNull()
}

private fun String.toInstantOrNull(): Instant? {
    return runCatching { Instant.parse(this) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()
        ?: runCatching { LocalDate.parse(this).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull()
}
