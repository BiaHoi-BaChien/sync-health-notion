package net.biahoi.stepnotionsync

import android.content.Context
import android.os.Bundle
import android.text.InputType
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
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val requiredPermissions = setOf(HealthPermission.getReadPermission(StepsRecord::class))
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var statusText: TextView
    private lateinit var tokenInput: EditText
    private lateinit var dataSourceInput: EditText
    private lateinit var datePropertyInput: EditText
    private lateinit var stepsPropertyInput: EditText

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
        root.addButton("同期") { syncStepsToNotion() }

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

    private fun LinearLayout.addButton(label: String, onClick: () -> Unit) {
        addView(Button(context).apply {
            text = label
            setOnClickListener { onClick() }
        })
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

        CoroutineScope(Dispatchers.Main).launch {
            statusText.text = "同期中..."
            try {
                val client = healthConnectClientOrNull()
                    ?: error("Health Connectが利用できません。")
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.containsAll(requiredPermissions)) {
                    permissionLauncher.launch(requiredPermissions)
                    statusText.text = "Health Connectの権限を許可してから再度同期してください。"
                    return@launch
                }

                val date = LocalDate.now()
                val steps = readStepsForDate(client, date)
                withContext(Dispatchers.IO) {
                    NotionClient(config).upsertSteps(date, steps)
                }
                statusText.text = "${date} の歩数 ${steps} をNotionへ同期しました。"
            } catch (e: Exception) {
                statusText.text = "同期に失敗しました: ${e.message}"
            }
        }
    }

    private fun healthConnectClientOrNull(): HealthConnectClient? {
        return when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(this)
            else -> null
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
    fun upsertSteps(date: LocalDate, steps: Long) {
        val pageId = findPageForDate(date)
        if (pageId == null) {
            createPage(date, steps)
        } else {
            updatePage(pageId, date, steps)
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
