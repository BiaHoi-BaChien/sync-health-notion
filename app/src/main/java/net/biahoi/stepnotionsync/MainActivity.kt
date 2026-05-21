package net.biahoi.stepnotionsync

import android.content.Context
import android.os.Bundle
import android.text.InputType
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
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
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
import java.time.LocalDate
import java.time.Period

class MainActivity : ComponentActivity() {
    private val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    )
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var statusText: TextView
    private lateinit var tokenInput: EditText
    private lateinit var dataSourceInput: EditText
    private lateinit var datePropertyInput: EditText
    private lateinit var stepsPropertyInput: EditText
    private lateinit var syncButton: Button
    private lateinit var cancelButton: Button
    private lateinit var progressBar: ProgressBar
    private var syncJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            statusText.text = if (granted.containsAll(requiredPermissions)) {
                "Health Connectの歩数読み取り権限が許可されました。"
            } else {
                "Health Connectの歩数読み取り権限が必要です。"
            }
        }
        buildUi()
        loadSettings()
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = "Step Notion Sync"
            textSize = 24f
        })

        tokenInput = root.addInput("Notion Integration Token", password = true)
        dataSourceInput = root.addInput("Notion Data Source ID")
        datePropertyInput = root.addInput("Date property name")
        stepsPropertyInput = root.addInput("Steps property name")

        root.addButton("設定を保存") {
            saveSettings()
            statusText.text = "設定を保存しました。"
        }
        root.addButton("Health Connect権限を許可") { requestHealthPermission() }
        syncButton = root.addButton("同期") { syncStepsToNotion() }
        cancelButton = root.addButton("中断") { cancelSync() }.apply {
            isEnabled = false
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
        }
        root.addView(progressBar)

        statusText = TextView(this).apply {
            text = "Notion設定を入力し、権限を許可してから同期してください。"
            textSize = 16f
            setPadding(0, padding, 0, 0)
        }
        root.addView(statusText)

        setContentView(ScrollView(this).apply { addView(root) })
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

    private fun loadSettings() {
        val prefs = getSharedPreferences("notion", Context.MODE_PRIVATE)
        tokenInput.setText(prefs.getString("token", ""))
        dataSourceInput.setText(prefs.getString("dataSource", prefs.getString("database", "")))
        datePropertyInput.setText(prefs.getString("dateProperty", "Date"))
        stepsPropertyInput.setText(prefs.getString("stepsProperty", "Steps"))
    }

    private fun saveSettings() {
        getSharedPreferences("notion", Context.MODE_PRIVATE).edit()
            .putString("token", tokenInput.text.toString().trim())
            .putString("dataSource", dataSourceInput.text.toString().trim())
            .putString("dateProperty", datePropertyInput.text.toString().trim())
            .putString("stepsProperty", stepsPropertyInput.text.toString().trim())
            .apply()
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
                statusText.text = "Health Connectの歩数読み取り権限は許可済みです。"
            } else {
                permissionLauncher.launch(requiredPermissions)
            }
        }
    }

    private fun syncStepsToNotion() {
        if (syncJob?.isActive == true) {
            statusText.text = "同期中です。中断する場合は「中断」を押してください。"
            return
        }

        saveSettings()
        val config = NotionConfig(
            token = tokenInput.text.toString().trim(),
            dataSourceId = dataSourceInput.text.toString().trim(),
            dateProperty = datePropertyInput.text.toString().trim(),
            stepsProperty = stepsPropertyInput.text.toString().trim()
        )
        if (!config.isComplete()) {
            statusText.text = "Notion Token、Data Source ID、プロパティ名を入力してください。"
            return
        }

        syncJob = CoroutineScope(Dispatchers.Main).launch {
            setSyncUi(isSyncing = true)
            progressBar.isIndeterminate = true
            statusText.text = "歩数データを取得中..."
            try {
                val client = healthConnectClientOrNull()
                    ?: error("Health Connectが利用できません。")
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.containsAll(requiredPermissions)) {
                    permissionLauncher.launch(requiredPermissions)
                    statusText.text = "Health Connectの権限を許可してから再度同期してください。"
                    return@launch
                }

                val dailySteps = readAllDailySteps(client)
                if (dailySteps.isEmpty()) {
                    statusText.text = "同期対象の歩数データがありません。"
                    return@launch
                }

                progressBar.isIndeterminate = false
                progressBar.max = dailySteps.size
                progressBar.progress = 0

                val notionClient = NotionClient(config)
                val today = LocalDate.now()
                var createdCount = 0
                var updatedCount = 0
                var skippedCount = 0
                dailySteps.forEachIndexed { index, daily ->
                    ensureActive()
                    statusText.text =
                        "同期中 ${index + 1}/${dailySteps.size}: ${daily.date} の歩数 ${daily.steps}"
                    val result = withContext(Dispatchers.IO) {
                        ensureActive()
                        notionClient.syncSteps(
                            date = daily.date,
                            steps = daily.steps,
                            overwriteExisting = daily.date == today
                        )
                    }
                    when (result) {
                        SyncResult.CREATED -> createdCount++
                        SyncResult.UPDATED -> updatedCount++
                        SyncResult.SKIPPED -> skippedCount++
                    }
                    progressBar.progress = index + 1
                }

                statusText.text =
                    "${dailySteps.size}日分を確認しました。作成: ${createdCount}、更新: ${updatedCount}、スキップ: ${skippedCount}"
            } catch (e: CancellationException) {
                statusText.text = "同期を中断しました。"
            } catch (e: Exception) {
                statusText.text = "同期に失敗しました: ${e.message}"
            } finally {
                setSyncUi(isSyncing = false)
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
}

private data class DailySteps(
    val date: LocalDate,
    val steps: Long
)

private enum class SyncResult {
    CREATED,
    UPDATED,
    SKIPPED
}

private data class NotionConfig(
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

private class NotionClient(private val config: NotionConfig) {
    fun syncSteps(date: LocalDate, steps: Long, overwriteExisting: Boolean): SyncResult {
        val pageId = findPageForDate(date)
        return if (pageId == null) {
            createPage(date, steps)
            SyncResult.CREATED
        } else if (overwriteExisting) {
            updatePage(pageId, date, steps)
            SyncResult.UPDATED
        } else {
            SyncResult.SKIPPED
        }
    }

    private fun findPageForDate(date: LocalDate): String? {
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

    private fun createPage(date: LocalDate, steps: Long) {
        val body = JSONObject()
            .put(
                "parent",
                JSONObject()
                    .put("type", "data_source_id")
                    .put("data_source_id", config.dataSourceId)
            )
            .put("properties", properties(date, steps))
        request("POST", "https://api.notion.com/v1/pages", body)
    }

    private fun updatePage(pageId: String, date: LocalDate, steps: Long) {
        val body = JSONObject().put("properties", properties(date, steps))
        request("PATCH", "https://api.notion.com/v1/pages/$pageId", body)
    }

    private fun properties(date: LocalDate, steps: Long): JSONObject {
        return JSONObject()
            .put(config.dateProperty, JSONObject().put("date", JSONObject().put("start", date.toString())))
            .put(config.stepsProperty, JSONObject().put("number", steps))
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
