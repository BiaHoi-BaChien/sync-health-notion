package net.biahoi.stepnotionsync

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.ArrayAdapter
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Mass
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

private const val NOTION_TRADEMARK_NOTICE =
    "NotionはNotion Labs, Inc.の商標です。本アプリはNotion Labs, Inc.の公式アプリではなく、同社による提供・提携・承認を受けたものではありません。"

class MainActivity : ComponentActivity() {
    private val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getWritePermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
    )
    private var requestedHealthPermissions: Set<String> = requiredPermissions
    private lateinit var permissionLauncher: ActivityResultLauncher<Set<String>>
    private lateinit var voicePermissionLauncher: ActivityResultLauncher<String>
    private lateinit var voiceInputLauncher: ActivityResultLauncher<Intent>
    private lateinit var statusText: TextView
    private lateinit var stepsPhoneDateText: TextView
    private lateinit var stepsNotionDateText: TextView
    private lateinit var vitalsPhoneDateText: TextView
    private lateinit var vitalsNotionDateText: TextView
    private lateinit var weightPhoneDateText: TextView
    private lateinit var weightNotionDateText: TextView
    private lateinit var autoSyncResultText: TextView
    private lateinit var autoSyncDetailsToggleButton: Button
    private lateinit var autoSyncDetailsContainer: LinearLayout
    private lateinit var autoSyncLastSuccessText: TextView
    private lateinit var autoSyncLastFailureText: TextView
    private lateinit var autoSyncFailureReasonText: TextView
    private lateinit var autoSyncClearFailureButton: ImageButton
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
    private lateinit var weightDataSourceInput: EditText
    private lateinit var weightMeasuredAtPropertyInput: EditText
    private lateinit var weightPropertyInput: EditText
    private lateinit var stepsDirectionSpinner: Spinner
    private lateinit var vitalsDirectionSpinner: Spinner
    private lateinit var weightDirectionSpinner: Spinner
    private lateinit var autoSyncSpinner: Spinner
    private lateinit var uiModeSpinner: Spinner
    private var currentSyncJob: Job? = null
    private var latestDateRefreshJob: Job? = null
    private var syncDialog: Dialog? = null
    private var syncDialogMessageText: TextView? = null
    private var messageDialog: Dialog? = null
    private var latestDateRefreshDialog: Dialog? = null
    private var autoSyncDetailsExpanded = false
    private var manualVitalVoiceInputs: ManualVitalVoiceInputs? = null
    private var manualWeightVoiceInput: EditText? = null
    private var manualVoiceTarget: ManualVoiceTarget? = null
    private var operationCompletedTone: ToneGenerator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            val message = if (granted.containsAll(requestedHealthPermissions)) {
                "Health Connectの権限が許可されました。"
            } else {
                "Health Connectの必要な権限を許可してください。"
            }
            setStatusMessage(message, floating = true)
            refreshLatestDates()
        }
        voicePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                launchManualVoiceInput()
            } else {
                setStatusMessage("マイク権限を許可してください。", floating = true)
            }
        }
        voiceInputLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }
            val matches = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                .orEmpty()
            applyManualVoiceResult(matches)
        }
        migrateAutoSyncScheduleIfNeeded()
        applyUiMode()
        showTopPage()
    }

    override fun onDestroy() {
        currentSyncJob?.cancel()
        latestDateRefreshJob?.cancel()
        dismissSyncDialog()
        dismissLatestDateRefreshDialog()
        operationCompletedTone?.release()
        operationCompletedTone = null
        super.onDestroy()
    }

    private fun showTopPage() {
        val config = currentConfig()
        val palette = uiPalette()
        val density = resources.displayMetrics.density
        val padding = (18 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            background = topBackground()
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
            text = "健康データ Notion 同期"
            textSize = 30f
            setSingleLine(true)
            setAutoSizeTextTypeUniformWithConfiguration(
                18,
                30,
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
            setTextColor(palette.primaryText)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(createSettingsButton().apply {
            setOnClickListener { showSettingsPage() }
        })
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "最新データの同期状況を確認できます"
            textSize = 14f
            setTextColor(palette.mutedText)
            setPadding(0, (6 * density).toInt(), 0, (24 * density).toInt())
        })

        val updateNotice = root.addUpdateNoticeCard()

        root.addSyncStatusCard(
            title = "歩数",
            iconResId = R.drawable.ic_footsteps,
            direction = config.stepsDirection,
            actionLabel = "すぐに同期",
            actionDescription = "歩数をすぐに同期",
            action = { syncStepsToNotion() },
            notionAction = { openConfiguredNotionPage("歩数", config.stepsDataSourceId) },
        ).also { views ->
            stepsPhoneDateText = views.healthConnectDateText
            stepsNotionDateText = views.notionDateText
        }
        root.addSyncStatusCard(
            title = "バイタル",
            iconResId = R.drawable.ic_heart_pulse,
            direction = config.vitalsDirection,
            actionLabel = "すぐに同期",
            actionDescription = "バイタルをすぐに同期",
            action = { syncVitalsToNotion() },
            secondaryActionDescription = "バイタルをHealth Connectに登録",
            secondaryActionIconResId = R.drawable.ic_add,
            secondaryAction = { showManualVitalEntryDialog() },
            notionAction = { openConfiguredNotionPage("バイタル", config.vitalsDataSourceId) },
        ).also { views ->
            vitalsPhoneDateText = views.healthConnectDateText
            vitalsNotionDateText = views.notionDateText
        }
        root.addSyncStatusCard(
            title = "体重",
            iconResId = R.drawable.ic_weight,
            direction = config.weightDirection,
            actionLabel = "すぐに同期",
            actionDescription = "体重をすぐに同期",
            action = { syncWeightToNotion() },
            secondaryActionDescription = "体重をHealth Connectに登録",
            secondaryActionIconResId = R.drawable.ic_add,
            secondaryAction = { showManualWeightEntryDialog() },
            notionAction = { openConfiguredNotionPage("体重", config.weightDataSourceId) },
        ).also { views ->
            weightPhoneDateText = views.healthConnectDateText
            weightNotionDateText = views.notionDateText
        }
        root.addSummaryCard(
            title = "自動同期",
            actionLabel = "手動同期",
            actionDescription = "設定済みの歩数、バイタル、体重を手動同期",
            action = { syncAllToNotion() }
        ).also { section ->
            section.addStaticRow("スケジュール", autoSyncLabel(loadAutoSyncTime()))
            autoSyncResultText = section.addDateRow("最終結果", null)
            autoSyncDetailsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (autoSyncDetailsExpanded) View.VISIBLE else View.GONE
            }
            section.addView(autoSyncDetailsContainer)
            autoSyncLastSuccessText = autoSyncDetailsContainer.addDateRow("最終成功", null)
            autoSyncLastFailureText = autoSyncDetailsContainer.addDateRow("最終失敗", null)
            autoSyncFailureReasonText = autoSyncDetailsContainer.addDateRow("失敗理由", null)
            autoSyncClearFailureButton = autoSyncDetailsContainer.addAutoSyncFailureClearRow {
                clearAutoSyncFailureDetails(this)
            }
            autoSyncNextRunText = autoSyncDetailsContainer.addDateRow("次回予定", null)
            autoSyncDetailsToggleButton = section.addButton("") { toggleAutoSyncDetails() }.apply {
                isAllCaps = false
                gravity = Gravity.CENTER
                setTextColor(palette.secondaryText)
                background = GradientDrawable().apply {
                    cornerRadius = 10 * density
                    setColor(palette.secondaryButtonBackground)
                    setStroke((1 * density).toInt(), palette.border)
                }
            }
            updateAutoSyncDetailsToggleButton()
        }

        statusText = TextView(this).apply {
            text = "設定した方向に従ってデータを同期します。"
            textSize = 16f
            setTextColor(palette.secondaryText)
            setPadding(0, (14 * density).toInt(), 0, 0)
        }
        root.addView(statusText)

        setContentView(centeredScrollContent(root, padding) {
            refreshLatestReleaseNotice(updateNotice)
            refreshAutoSyncStatus()
            refreshLatestDates(showProgress = true)
        })
        refreshLatestReleaseNotice(updateNotice)
        refreshAutoSyncStatus()
        refreshLatestDates()
    }

    private fun showSettingsPage() {
        val palette = uiPalette()
        val density = resources.displayMetrics.density
        val padding = (18 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(palette.pageBackground)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "設定"
                textSize = 28f
                setTextColor(palette.primaryText)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            })
            addView(createCardTextButton(
                label = "設定ガイド",
                description = "設定ガイドを開く",
                onClick = ::openManualPage
            ))
        })
        root.addView(TextView(this).apply {
            text = "バージョン ${appVersionName()}"
            textSize = 14f
            setTextColor(palette.mutedText)
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
        })

        root.addSectionTitle(
            title = "共通設定",
            helpText = """
                Notionとの接続に使うAccess Tokenを設定します。
            """.trimIndent()
        )
        tokenInput = root.addInput("ConnectionsのAccess Token", password = true)
        root.addSectionTitle(
            title = "表示設定",
            helpText = """
                アプリの表示モードを選択します。

                「システム」を選ぶと、スマホ本体のライト／ダーク設定に従います。
            """.trimIndent()
        )
        uiModeSpinner = root.addUiModeSpinner()
        root.addSectionTitle(
            title = "自動同期",
            helpText = """
                毎日同期を実行する時刻を選択します。「自動同期しない」を選ぶと停止します。

                保存後、指定時刻を目安にバックグラウンドで同期します。端末の省電力設定や通信状況により、開始時刻が前後する場合があります。利用するデータのHealth Connect権限も必要です。
            """.trimIndent()
        )
        autoSyncSpinner = root.addAutoSyncSpinner()
        root.addSectionTitle(
            title = "Health Connect",
            helpText = """
                歩数、血圧・心拍、体重を読み書きするための権限を許可します。

                設定した同期方向に応じて必要な権限が変わります。自動同期を使う場合は、Health Connectのバックグラウンド読み取り権限も許可してください。
            """.trimIndent()
        )
        root.addButton("Health Connect権限を許可") { requestHealthPermission() }

        root.addSectionTitle(
            title = "Notionプロパティ設定",
            helpText = """
                歩数、バイタル、体重ごとに、同期方向、NotionのData Source ID、同期に使うプロパティ名を設定します。

                タブを切り替えて対象データを選び、Notion側のデータソースで使っているプロパティ名と同じ名前を入力してください。Data Source IDはNotionの連携対象データソースをインテグレーションに共有してから設定します。
            """.trimIndent()
        )
        val tabButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        root.addView(tabButtons)
        val tabContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(tabContent)

        val stepsTab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        stepsDirectionSpinner = stepsTab.addSyncDirectionSpinner()
        stepsDataSourceInput = stepsTab.addInput("歩数データベースのData sourceID")
        stepsDatePropertyInput = stepsTab.addInput("歩数データベースの日付プロパティ名")
        stepsPropertyInput = stepsTab.addInput("歩数データベースの歩数プロパティ名")
        tabContent.addView(stepsTab)

        val vitalsTab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        vitalsDirectionSpinner = vitalsTab.addSyncDirectionSpinner()
        vitalsDataSourceInput = vitalsTab.addInput("バイタルデータベースのData sourceID")
        vitalsMeasuredAtPropertyInput = vitalsTab.addInput("バイタルデータベースの日付プロパティ名")
        systolicPropertyInput = vitalsTab.addInput("バイタルデータベースの最高血圧プロパティ名")
        diastolicPropertyInput = vitalsTab.addInput("バイタルデータベースの最低血圧プロパティ名")
        heartRatePropertyInput = vitalsTab.addInput("バイタルデータベースの脈拍プロパティ名")
        tabContent.addView(vitalsTab)

        val weightTab = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        weightDirectionSpinner = weightTab.addSyncDirectionSpinner()
        weightDataSourceInput = weightTab.addInput("体重データベースのData sourceID")
        weightMeasuredAtPropertyInput = weightTab.addInput("体重データベースの日付プロパティ名")
        weightPropertyInput = weightTab.addInput("体重データベースの体重プロパティ名")
        tabContent.addView(weightTab)

        val tabs = listOf(
            "歩数" to stepsTab,
            "バイタル" to vitalsTab,
            "体重" to weightTab
        )
        tabs.forEachIndexed { index, tab ->
            tabButtons.addView(createSettingsTabButton(tab.first).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) leftMargin = (6 * density).toInt()
                }
                setOnClickListener {
                    tabs.forEach { item -> item.second.visibility = if (item === tab) View.VISIBLE else View.GONE }
                    updateSettingsTabButtons(tabButtons, index)
                }
            })
        }
        updateSettingsTabButtons(tabButtons, 0)

        root.addButton("設定を保存") {
            val uiModeChanged = saveSettings()
            if (uiModeChanged) {
                showSettingsPage()
            }
            setStatusMessage("設定を保存しました。", floating = true)
        }
        root.addButton("トップへ戻る") { showTopPage() }
        root.addTrademarkNotice()

        statusText = TextView(this).apply {
            text = "NotionのData Source IDとプロパティ名を入力してください。"
            textSize = 16f
            setTextColor(palette.secondaryText)
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
        return object : ScrollView(this) {
            private var pullStartY = 0f
            private var pullStartedAtTop = false
            private val threshold = 92 * resources.displayMetrics.density

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (onPullRefresh != null) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            pullStartedAtTop = scrollY == 0
                            pullStartY = event.y
                        }
                        MotionEvent.ACTION_UP -> {
                            if (pullStartedAtTop && scrollY == 0 && event.y - pullStartY > threshold) {
                                performClick()
                                onPullRefresh()
                            }
                            pullStartedAtTop = false
                        }
                        MotionEvent.ACTION_CANCEL -> pullStartedAtTop = false
                    }
                }
                return super.onTouchEvent(event)
            }

            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }.apply {
            isFillViewport = true
            background = topBackground()
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

    private fun createSettingsButton(): ImageButton {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        return ImageButton(this).apply {
            contentDescription = "設定"
            tooltipText = "設定"
            setImageResource(R.drawable.ic_settings)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.primaryText)
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(palette.controlBackground)
                setStroke((1 * density).toInt(), palette.border)
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams((54 * density).toInt(), (54 * density).toInt()).apply {
                leftMargin = (12 * density).toInt()
            }
        }
    }

    private fun topBackground(): GradientDrawable {
        val palette = uiPalette()
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            palette.backgroundGradient
        )
    }

    private fun cardBackground(): GradientDrawable {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            palette.cardGradient
        ).apply {
            cornerRadius = 16 * density
            setStroke((1 * density).toInt(), palette.border)
        }
    }

    private fun LinearLayout.addSyncStatusCard(
        title: String,
        iconResId: Int,
        direction: SyncDirection,
        actionLabel: String? = null,
        actionDescription: String? = null,
        actionIconResId: Int? = null,
        action: (() -> Unit)? = null,
        secondaryActionDescription: String? = null,
        secondaryActionIconResId: Int? = null,
        secondaryAction: (() -> Unit)? = null,
        notionAction: (() -> Unit)? = null
    ): SyncStatusCardViews {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        val presentation = syncCardPresentation(direction)
        val enabled = presentation.enabled
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (18 * density).toInt())
            background = if (enabled) cardBackground() else disabledCardBackground()
            alpha = if (enabled) 1f else 0.62f
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (14 * density).toInt()
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(context).apply {
            setImageResource(iconResId)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt()).apply {
                rightMargin = (10 * density).toInt()
            }
        })
        header.addView(TextView(context).apply {
            text = title
            textSize = 20f
            setTextColor(palette.primaryText)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (secondaryActionDescription != null && secondaryActionIconResId != null && secondaryAction != null) {
            header.addView(createCardIconButton(secondaryActionDescription, secondaryActionIconResId, secondaryAction))
        }
        if (actionLabel != null && actionDescription != null && action != null && enabled) {
            header.addView(createCardTextButton(actionLabel, actionDescription, action))
        } else if (actionDescription != null && actionIconResId != null && action != null) {
            header.addView(createCardIconButton(actionDescription, actionIconResId, action))
        }
        card.addView(header)

        if (!enabled) {
            card.addView(TextView(context).apply {
                text = "同期しない"
                textSize = 16f
                setTextColor(palette.mutedText)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(0, (22 * density).toInt(), 0, (10 * density).toInt())
            })
        }

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (22 * density).toInt(), 0, 0)
        }
        val healthConnectFirst = presentation.healthConnectFirst
        val leftLabel = presentation.leftLabel
        val leftIcon = if (healthConnectFirst) R.drawable.ic_phone else R.drawable.ic_notion
        val rightLabel = presentation.rightLabel
        val rightIcon = if (healthConnectFirst) R.drawable.ic_notion else R.drawable.ic_phone
        val notionActionDescription = "${title}のNotionデータベースを開く"
        val leftDate = body.addEndpoint(
            label = leftLabel,
            iconResId = leftIcon,
            actionDescription = notionActionDescription.takeIf { leftIcon == R.drawable.ic_notion },
            action = notionAction.takeIf { leftIcon == R.drawable.ic_notion }
        )
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.5f).apply {
                leftMargin = (4 * density).toInt()
                rightMargin = (4 * density).toInt()
            }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_arrow_right)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (enabled) palette.accent else palette.disabledText
                )
                layoutParams = LinearLayout.LayoutParams((74 * density).toInt(), (26 * density).toInt())
            })
        })
        val rightDate = body.addEndpoint(
            label = rightLabel,
            iconResId = rightIcon,
            actionDescription = notionActionDescription.takeIf { rightIcon == R.drawable.ic_notion },
            action = notionAction.takeIf { rightIcon == R.drawable.ic_notion }
        )
        card.addView(body)
        addView(card)
        return if (healthConnectFirst) {
            SyncStatusCardViews(leftDate, rightDate)
        } else {
            SyncStatusCardViews(rightDate, leftDate)
        }
    }

    private fun disabledCardBackground(): GradientDrawable {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        return GradientDrawable().apply {
            cornerRadius = 16 * density
            setColor(palette.disabledCard)
            setStroke((1 * density).toInt(), palette.border)
        }
    }

    private fun LinearLayout.addEndpoint(
        label: String,
        iconResId: Int,
        actionDescription: String? = null,
        action: (() -> Unit)? = null
    ): TextView {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        val isPhoneIcon = iconResId == R.drawable.ic_phone
        val isLightNotionIcon = iconResId == R.drawable.ic_notion && !isDarkUiMode()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val endpointIcon = ImageView(context).apply {
            setImageResource(iconResId)
            if (actionDescription != null && action != null) {
                contentDescription = actionDescription
                tooltipText = actionDescription
                isClickable = true
                isFocusable = true
                setOnClickListener { action() }
            }
            imageTintList = android.content.res.ColorStateList.valueOf(
                when {
                    isPhoneIcon -> palette.onAccent
                    isLightNotionIcon -> Color.BLACK
                    else -> palette.pageBackground
                }
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    when {
                        isPhoneIcon -> palette.accent
                        isLightNotionIcon -> Color.WHITE
                        else -> palette.primaryText
                    }
                )
                setStroke((3 * density).toInt(), palette.border)
            }
            setPadding((18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt())
            layoutParams = LinearLayout.LayoutParams((80 * density).toInt(), (80 * density).toInt())
        }
        column.addView(endpointIcon)
        column.addView(TextView(context).apply {
            text = label
            textSize = 17f
            setTextColor(palette.primaryText)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, (10 * density).toInt(), 0, 0)
        })
        val dateText = TextView(context).apply {
            text = "確認中..."
            textSize = 13.5f
            setTextColor(palette.accent)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        column.addView(dateText)
        addView(column)
        return dateText
    }

    private fun LinearLayout.addSummaryCard(
        title: String,
        actionLabel: String,
        actionDescription: String,
        action: () -> Unit
    ): LinearLayout {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            background = cardBackground()
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (14 * density).toInt()
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(palette.primaryText)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(createCardTextButton(actionLabel, actionDescription, action))
        card.addView(header)
        addView(card)
        return card
    }

    private fun createCardTextButton(label: String, description: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        return Button(this).apply {
            text = label
            contentDescription = description
            tooltipText = description
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.onAccent)
            minHeight = (40 * density).toInt()
            minWidth = 0
            minimumWidth = 0
            isAllCaps = false
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(palette.accent)
            }
            backgroundTintList = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (40 * density).toInt()
            ).apply {
                leftMargin = (12 * density).toInt()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createCardIconButton(description: String, iconResId: Int, onClick: () -> Unit): ImageButton {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        return ImageButton(this).apply {
            contentDescription = description
            tooltipText = description
            setImageResource(iconResId)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.onAccent)
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(palette.accent)
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()).apply {
                leftMargin = (12 * density).toInt()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun LinearLayout.addUpdateNoticeCard(): UpdateNoticeViews {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(palette.successBackground)
                setStroke((1 * density).toInt(), palette.successBorder)
            }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
        }
        card.addView(TextView(context).apply {
            text = "新しいバージョンがあります"
            textSize = 18f
            setTextColor(palette.primaryText)
            typeface = Typeface.DEFAULT_BOLD
        })
        val message = TextView(context).apply {
            textSize = 14f
            setTextColor(palette.secondaryText)
            setPadding(0, (6 * density).toInt(), 0, 0)
        }
        card.addView(message)
        val button = Button(context).apply {
            text = "最新版をダウンロード"
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.onAccent)
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(palette.successButtonBackground)
            }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * density).toInt()
            }
        }
        card.addView(button)
        addView(card)
        return UpdateNoticeViews(card, message, button)
    }

    private fun LinearLayout.addStaticRow(label: String, valueText: String) {
        addDateRow(label, null).text = valueText
    }

    private fun LinearLayout.addDateRow(label: String, role: String?): TextView {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (14 * density).toInt(), 0, 0)
        }
        row.addView(TextView(context).apply {
            text = role?.let { "$label（$it）" } ?: label
            textSize = 14f
            setTextColor(palette.mutedText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val value = TextView(context).apply {
            text = "確認中..."
            textSize = 20f
            setTextColor(palette.accent)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(value)
        addView(row)
        return value
    }

    private fun LinearLayout.addAutoSyncFailureClearRow(onClick: () -> Unit): ImageButton {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        row.addView(TextView(context).apply {
            text = "削除対象: 最終失敗・失敗理由"
            textSize = 13f
            setTextColor(palette.mutedText)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val button = ImageButton(context).apply {
            contentDescription = "最終失敗と失敗理由を削除"
            tooltipText = "最終失敗と失敗理由を削除"
            setImageResource(R.drawable.ic_trash)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.dangerText)
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(palette.dangerBackground)
                setStroke((1 * density).toInt(), palette.dangerBorder)
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt()).apply {
                leftMargin = (12 * density).toInt()
            }
            setOnClickListener { onClick() }
        }
        row.addView(button)
        addView(row)
        return button
    }

    private fun LinearLayout.addTrademarkNotice() {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        addView(TextView(context).apply {
            text = NOTION_TRADEMARK_NOTICE
            textSize = 12.5f
            setTextColor(palette.mutedText)
            setLineSpacing(0f, 1.15f)
            setPadding(0, (18 * density).toInt(), 0, 0)
        })
    }

    private fun LinearLayout.addSectionTitle(title: String, helpText: String) {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (24 * density).toInt(), 0, 0)
        }
        row.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTextColor(palette.primaryText)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        row.addView(ImageButton(context).apply {
            contentDescription = "${title}のヘルプ"
            tooltipText = "${title}の説明"
            setImageResource(R.drawable.ic_help_outline)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.infoText)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.controlBackground)
                setStroke((1 * density).toInt(), palette.border)
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(
                (6 * density).toInt(),
                (6 * density).toInt(),
                (6 * density).toInt(),
                (6 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                (32 * density).toInt(),
                (32 * density).toInt()
            ).apply {
                leftMargin = (8 * density).toInt()
            }
            setOnClickListener { showSettingsHelpDialog(title, helpText) }
        })
        addView(row)
    }

    private fun showSettingsHelpDialog(title: String, helpText: String) {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        lateinit var dialog: Dialog

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(
                (22 * density).toInt(),
                (20 * density).toInt(),
                (22 * density).toInt(),
                (18 * density).toInt()
            )
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(palette.controlBackground)
                setStroke((1 * density).toInt(), palette.border)
            }
        }
        panel.addView(TextView(this).apply {
            text = title
            textSize = 21f
            setTextColor(palette.primaryText)
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(this).apply {
            text = helpText
            textSize = 16f
            setTextColor(palette.secondaryText)
            setLineSpacing(0f, 1.18f)
            setPadding(0, (12 * density).toInt(), 0, 0)
        })
        panel.addView(Button(this).apply {
            text = "閉じる"
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.onAccent)
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(palette.accent)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (18 * density).toInt()
            }
            setOnClickListener { dialog.dismiss() }
        })

        val overlay = FrameLayout(this).apply {
            setPadding(
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt()
            )
            setOnClickListener { dialog.dismiss() }
            addView(
                panel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            setContentView(overlay)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0.58f)
            show()
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun LinearLayout.addInput(hintText: String, password: Boolean = false): EditText {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        val input = EditText(context).apply {
            hint = hintText
            setHintTextColor(palette.hintText)
            setTextColor(palette.primaryText)
            setSingleLine(true)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(palette.inputBackground)
                setStroke((1 * density).toInt(), palette.border)
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

    private fun LinearLayout.addNumberInput(hintText: String): EditText {
        val input = addInput(hintText)
        input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        return input
    }

    private fun LinearLayout.addButton(label: String, onClick: () -> Unit): Button {
        val button = createActionButton(label, onClick)
        addView(button)
        return button
    }

    private fun createSettingsTabButton(label: String): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            typeface = Typeface.DEFAULT_BOLD
        }

    private fun updateSettingsTabButtons(container: LinearLayout, selectedIndex: Int) {
        val palette = uiPalette()
        for (index in 0 until container.childCount) {
            val button = container.getChildAt(index) as Button
            val selected = index == selectedIndex
            button.setTextColor(if (selected) palette.onAccent else palette.secondaryText)
            button.background = GradientDrawable().apply {
                cornerRadius = 10 * resources.displayMetrics.density
                setColor(if (selected) palette.accent else palette.secondaryButtonBackground)
                setStroke(
                    resources.displayMetrics.density.toInt().coerceAtLeast(1),
                    palette.border
                )
            }
        }
    }

    private fun createActionButton(label: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        val cornerRadius = 10 * density
        return Button(this).apply {
            text = label
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.onAccent)
            minHeight = (52 * density).toInt()
            isAllCaps = false
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    cornerRadius,
                    cornerRadius,
                    cornerRadius,
                    cornerRadius,
                    cornerRadius,
                    cornerRadius,
                    cornerRadius,
                    cornerRadius,
                )
                setColor(palette.accent)
            }
            backgroundTintList = null
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * density).toInt()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun LinearLayout.addAutoSyncSpinner(): Spinner {
        val choices = autoSyncChoices()
        val spinner = createSettingsSpinner(choices.map { it.label }, 6, 8)
        addView(spinner)
        return spinner
    }

    private fun LinearLayout.addUiModeSpinner(): Spinner {
        val spinner = createSettingsSpinner(UiModePreference.entries.map { it.label }, 6, 8)
        addView(spinner)
        return spinner
    }

    private fun LinearLayout.addSyncDirectionSpinner(): Spinner {
        return createSettingsSpinner(SyncDirection.entries.map { it.label }, 10, 4).apply {
            this@addSyncDirectionSpinner.addView(this)
        }
    }

    private fun createSettingsSpinner(labels: List<String>, topMarginDp: Int, bottomMarginDp: Int): Spinner {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        return Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(
                context,
                android.R.layout.simple_spinner_item,
                labels
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).apply {
                        (this as? TextView)?.setTextColor(palette.primaryText)
                        setBackgroundColor(palette.spinnerBackground)
                    }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getDropDownView(position, convertView, parent).apply {
                        (this as? TextView)?.setTextColor(palette.primaryText)
                        setBackgroundColor(palette.controlBackground)
                    }
            }.apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(palette.spinnerBackground)
            }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (topMarginDp * density).toInt()
                bottomMargin = (bottomMarginDp * density).toInt()
            }
        }
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
        weightDataSourceInput.setText(prefs.getString("weightDataSource", ""))
        weightMeasuredAtPropertyInput.setText(prefs.getString("weightMeasuredAtProperty", "日付"))
        weightPropertyInput.setText(prefs.getString("weightProperty", "体重"))
        stepsDirectionSpinner.setSelection(SyncDirection.indexOf(prefs.getString(STEPS_DIRECTION_KEY, null)))
        vitalsDirectionSpinner.setSelection(SyncDirection.indexOf(prefs.getString(VITALS_DIRECTION_KEY, null)))
        weightDirectionSpinner.setSelection(SyncDirection.indexOf(prefs.getString(WEIGHT_DIRECTION_KEY, null)))
        val autoSyncTime = prefs.getString(AUTO_SYNC_TIME_KEY, AUTO_SYNC_OFF) ?: AUTO_SYNC_OFF
        autoSyncSpinner.setSelection(autoSyncChoices().indexOfFirst { it.value == autoSyncTime }.coerceAtLeast(0))
        uiModeSpinner.setSelection(UiModePreference.indexOf(prefs.getString(UI_MODE_KEY, null)))
    }

    private fun saveSettings(): Boolean {
        val prefs = getSharedPreferences("notion", Context.MODE_PRIVATE)
        val previousUiMode = loadUiMode()
        val selectedUiMode = UiModePreference.entries[uiModeSpinner.selectedItemPosition].value
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
            .putString("weightDataSource", weightDataSourceInput.text.toString().trim())
            .putString("weightMeasuredAtProperty", weightMeasuredAtPropertyInput.text.toString().trim())
            .putString("weightProperty", weightPropertyInput.text.toString().trim())
            .putString(STEPS_DIRECTION_KEY, SyncDirection.entries[stepsDirectionSpinner.selectedItemPosition].value)
            .putString(VITALS_DIRECTION_KEY, SyncDirection.entries[vitalsDirectionSpinner.selectedItemPosition].value)
            .putString(WEIGHT_DIRECTION_KEY, SyncDirection.entries[weightDirectionSpinner.selectedItemPosition].value)
            .putString(AUTO_SYNC_TIME_KEY, autoSyncChoices()[autoSyncSpinner.selectedItemPosition].value)
            .putString(UI_MODE_KEY, selectedUiMode)
            .apply()
        applyUiMode()
        scheduleAutoSync(this, loadAutoSyncTime())
        return previousUiMode != selectedUiMode
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
            heartRateProperty = prefs.getString("heartRateProperty", "脈拍") ?: "脈拍",
            weightDataSourceId = prefs.getString("weightDataSource", "") ?: "",
            weightMeasuredAtProperty = prefs.getString("weightMeasuredAtProperty", "日付") ?: "日付",
            weightProperty = prefs.getString("weightProperty", "体重") ?: "体重",
            stepsDirection = SyncDirection.from(prefs.getString(STEPS_DIRECTION_KEY, null)),
            vitalsDirection = SyncDirection.from(prefs.getString(VITALS_DIRECTION_KEY, null)),
            weightDirection = SyncDirection.from(prefs.getString(WEIGHT_DIRECTION_KEY, null))
        )
    }

    private fun loadAutoSyncTime(): String {
        return getSharedPreferences("notion", Context.MODE_PRIVATE)
            .getString(AUTO_SYNC_TIME_KEY, AUTO_SYNC_OFF) ?: AUTO_SYNC_OFF
    }

    private fun migrateAutoSyncScheduleIfNeeded() {
        val prefs = getSharedPreferences("notion", Context.MODE_PRIVATE)
        if (prefs.getInt(AUTO_SYNC_SCHEDULE_VERSION_KEY, 0) >= AUTO_SYNC_SCHEDULE_VERSION) {
            return
        }
        scheduleAutoSync(this, loadAutoSyncTime())
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
                weightPhoneDateText.text = "確認中..."
                weightNotionDateText.text = "確認中..."

                val client = healthConnectClientOrNull()
                if (client == null) {
                    stepsPhoneDateText.text = "利用不可"
                    vitalsPhoneDateText.text = "利用不可"
                    weightPhoneDateText.text = "利用不可"
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
                    if (granted.contains(HealthPermission.getReadPermission(WeightRecord::class))) {
                        weightPhoneDateText.text = displayDateTime(readLatestWeightTime(client))
                    } else {
                        weightPhoneDateText.text = "権限未許可"
                    }
                }

                withContext(Dispatchers.IO) {
                    val stepsDate = runCatching {
                        if (config.hasStepsSettings()) NotionClient(config).latestStepsDate(DEFAULT_LOOKBACK_DAYS) else null
                    }.getOrNull()
                    val vitalsDate = runCatching {
                        if (config.hasVitalsSettings()) NotionClient(config).latestVitalsDate(DEFAULT_LOOKBACK_DAYS) else null
                    }.getOrNull()
                    val weightDate = runCatching {
                        if (config.hasWeightSettings()) NotionClient(config).latestWeightDate(DEFAULT_LOOKBACK_DAYS) else null
                    }.getOrNull()
                    withContext(Dispatchers.Main) {
                        stepsNotionDateText.text = if (config.hasStepsSettings()) displayNotionDateTime(stepsDate) else "設定未完了"
                        vitalsNotionDateText.text = if (config.hasVitalsSettings()) displayNotionDateTime(vitalsDate) else "設定未完了"
                        weightNotionDateText.text = if (config.hasWeightSettings()) displayNotionDateTime(weightDate) else "設定未完了"
                        if (config.stepsDirection == SyncDirection.DISABLED) {
                            stepsPhoneDateText.text = "同期しない"
                            stepsNotionDateText.text = "同期しない"
                        }
                        if (config.vitalsDirection == SyncDirection.DISABLED) {
                            vitalsPhoneDateText.text = "同期しない"
                            vitalsNotionDateText.text = "同期しない"
                        }
                        if (config.weightDirection == SyncDirection.DISABLED) {
                            weightPhoneDateText.text = "同期しない"
                            weightNotionDateText.text = "同期しない"
                        }
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
        val palette = uiPalette()
        val autoSyncTime = loadAutoSyncTime()
        val status = loadAutoSyncStatus(this)
        autoSyncResultText.text = status.topResultLabel()
        autoSyncResultText.setCompoundDrawablesWithIntrinsicBounds(
            0,
            0,
            if (status.resultLabel == "成功") R.drawable.ic_check_circle else 0,
            0
        )
        autoSyncResultText.compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
        autoSyncResultText.compoundDrawableTintList =
            android.content.res.ColorStateList.valueOf(palette.accent)
        autoSyncLastSuccessText.text = displayTimestampMillis(status.lastSuccessAtMillis)
        autoSyncLastFailureText.text = displayTimestampMillis(status.lastFailureAtMillis)
        autoSyncFailureReasonText.text = status.failureReason.takeIf { it.isNotBlank() } ?: "なし"
        val hasFailureDetails = status.lastFailureAtMillis > 0L || status.failureReason.isNotBlank()
        autoSyncClearFailureButton.isEnabled = hasFailureDetails
        autoSyncClearFailureButton.alpha = if (hasFailureDetails) 1f else 0.45f
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
        val palette = uiPalette()
        val description = if (autoSyncDetailsExpanded) "詳細を閉じる" else "詳細を表示"
        autoSyncDetailsToggleButton.text = description
        autoSyncDetailsToggleButton.contentDescription = description
        autoSyncDetailsToggleButton.setCompoundDrawablesWithIntrinsicBounds(
            0,
            0,
            if (autoSyncDetailsExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
            0,
        )
        autoSyncDetailsToggleButton.compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
        autoSyncDetailsToggleButton.compoundDrawableTintList =
            android.content.res.ColorStateList.valueOf(palette.secondaryText)
    }

    private fun clearAutoSyncFailureDetails(context: Context) {
        clearAutoSyncFailureDetailsPreference(context)
        refreshAutoSyncStatus()
        setStatusMessage("最終失敗と失敗理由を削除しました。", floating = true)
    }

    private fun syncStepsToNotion() {
        val config = currentConfig()
        if (config.stepsDirection == SyncDirection.DISABLED) {
            setStatusMessage("歩数は「同期しない」に設定されています。", floating = true)
            return
        }
        if (!config.hasStepsSettings()) {
            setStatusMessage("歩数データのNotion設定を入力してください。", floating = true)
            return
        }

        startSync(
            syncMessage = "歩数データを同期中...",
            failurePrefix = "歩数データの同期に失敗しました",
            requiredHealthPermissions = config.stepsDirection.stepsPermissions(),
            permissionTarget = "歩数"
        ) { client ->
            val synced = HealthNotionSyncEngine.syncSteps(client, config, DEFAULT_LOOKBACK_DAYS, packageName)
            syncCountMessage("歩数データ", synced)
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
                    notion.readStepPagesByDate(DEFAULT_LOOKBACK_DAYS).toMutableMap()
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
        if (config.vitalsDirection == SyncDirection.DISABLED) {
            setStatusMessage("バイタルは「同期しない」に設定されています。", floating = true)
            return
        }
        if (!config.hasVitalsSettings()) {
            setStatusMessage("血圧・心拍データのNotion設定を入力してください。", floating = true)
            return
        }

        startSync(
            syncMessage = "血圧・心拍データを同期中...",
            failurePrefix = "血圧・心拍データの同期に失敗しました",
            requiredHealthPermissions = config.vitalsDirection.vitalsPermissions(),
            permissionTarget = "バイタル"
        ) { client ->
            val synced = HealthNotionSyncEngine.syncVitals(client, config, DEFAULT_LOOKBACK_DAYS, packageName)
            syncCountMessage("血圧・心拍データ", synced)
        }
    }

    private fun syncWeightToNotion() {
        val config = currentConfig()
        if (config.weightDirection == SyncDirection.DISABLED) {
            setStatusMessage("体重は「同期しない」に設定されています。", floating = true)
            return
        }
        if (!config.hasWeightSettings()) {
            setStatusMessage("体重データのNotion設定を入力してください。", floating = true)
            return
        }

        startSync(
            syncMessage = "体重データを同期中...",
            failurePrefix = "体重データの同期に失敗しました",
            requiredHealthPermissions = config.weightDirection.weightPermissions(),
            permissionTarget = "体重"
        ) { client ->
            val synced = HealthNotionSyncEngine.syncWeight(client, config, DEFAULT_LOOKBACK_DAYS, packageName)
            syncCountMessage("体重データ", synced)
        }
    }

    private fun showManualEntryDialog(
        title: String,
        description: String,
        voiceDescription: String,
        content: LinearLayout.(ImageButton) -> Unit,
        onRegister: (Dialog) -> Unit,
        onDismiss: () -> Unit
    ) {
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
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = (24 * density).toInt()
                rightMargin = (24 * density).toInt()
            }
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        titleRow.addView(TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val micButton = ImageButton(this).apply {
            contentDescription = voiceDescription
            setImageResource(R.drawable.ic_mic)
            setColorFilter(Color.parseColor("#081018"))
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.parseColor("#44D7B6"))
            }
            layoutParams = LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()).apply {
                leftMargin = (12 * density).toInt()
            }
        }
        titleRow.addView(micButton)
        panel.addView(titleRow)
        panel.addView(TextView(this).apply {
            text = description
            textSize = 13f
            setTextColor(Color.parseColor("#AAB7C4"))
            setPadding(0, (6 * density).toInt(), 0, (4 * density).toInt())
        })
        panel.content(micButton)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (16 * density).toInt()
            }
        }
        buttons.addView(Button(this).apply {
            text = "キャンセル"
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#DDE7EF"))
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.parseColor("#22313B"))
                setStroke((1 * density).toInt(), Color.parseColor("#44D7B6"))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = (8 * density).toInt()
            }
            setOnClickListener { dialog.dismiss() }
        })
        buttons.addView(Button(this).apply {
            text = "Health Connectに登録"
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#081018"))
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(Color.parseColor("#44D7B6"))
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = (8 * density).toInt()
            }
            setOnClickListener { onRegister(dialog) }
        })
        panel.addView(buttons)

        dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(true)
            setCanceledOnTouchOutside(true)
            setContentView(FrameLayout(this@MainActivity).apply {
                addView(panel)
            })
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0.64f)
            setOnDismissListener { onDismiss() }
            show()
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun showManualWeightEntryDialog() {
        if (currentSyncJob?.isActive == true) {
            setStatusMessage("同期中は体重を登録できません。", floating = true)
            return
        }

        lateinit var weightInput: EditText
        showManualEntryDialog(
            title = "体重をHealth Connectに登録",
            description = "測定日時は登録時点の時刻で保存します。体重はkg単位で小数第1位まで入力できます。",
            voiceDescription = "音声で体重を入力",
            content = { micButton ->
                weightInput = addNumberInput("体重 (kg)")
                micButton.setOnClickListener {
                    startManualWeightVoiceInput(weightInput)
                }
            },
            onRegister = { dialog ->
                val measurement = runCatching {
                    WeightMeasurement(
                        measuredAt = Instant.now(),
                        kilograms = parseManualWeight(weightInput.text.toString())
                    )
                }.getOrElse { error ->
                    setStatusMessage(error.message ?: "入力値を確認してください。", floating = true)
                    return@showManualEntryDialog
                }
                dialog.dismiss()
                confirmAndRegisterManualWeightToHealthConnect(measurement)
            },
            onDismiss = {
                if (manualWeightVoiceInput === weightInput) {
                    manualWeightVoiceInput = null
                    if (manualVoiceTarget == ManualVoiceTarget.WEIGHT) {
                        manualVoiceTarget = null
                    }
                }
            }
        )
    }

    private fun showManualVitalEntryDialog() {
        if (currentSyncJob?.isActive == true) {
            setStatusMessage("同期中はバイタルを登録できません。", floating = true)
            return
        }

        lateinit var systolicInput: EditText
        lateinit var diastolicInput: EditText
        lateinit var heartRateInput: EditText
        showManualEntryDialog(
            title = "バイタルをHealth Connectに登録",
            description = "測定日時は登録時点の時刻で保存します。",
            voiceDescription = "音声でバイタルを入力",
            content = { micButton ->
                systolicInput = addNumberInput("最高血圧")
                diastolicInput = addNumberInput("最低血圧")
                heartRateInput = addNumberInput("脈拍")
                micButton.setOnClickListener {
                    startManualVitalVoiceInput(
                        ManualVitalVoiceInputs(
                            systolic = systolicInput,
                            diastolic = diastolicInput,
                            heartRate = heartRateInput
                        )
                    )
                }
            },
            onRegister = { dialog ->
                val measurement = runCatching {
                    VitalMeasurement(
                        measuredAt = Instant.now(),
                        systolic = systolicInput.requiredPositiveDouble("最高血圧"),
                        diastolic = diastolicInput.requiredPositiveDouble("最低血圧"),
                        heartRate = heartRateInput.requiredPositiveLong("脈拍")
                    )
                }.getOrElse { error ->
                    setStatusMessage(error.message ?: "入力値を確認してください。", floating = true)
                    return@showManualEntryDialog
                }
                dialog.dismiss()
                confirmAndRegisterManualVitalToHealthConnect(measurement)
            },
            onDismiss = {
                if (manualVitalVoiceInputs?.systolic === systolicInput) {
                    manualVitalVoiceInputs = null
                    if (manualVoiceTarget == ManualVoiceTarget.VITALS) {
                        manualVoiceTarget = null
                    }
                }
            }
        )
    }

    private fun startManualVitalVoiceInput(inputs: ManualVitalVoiceInputs) {
        manualVitalVoiceInputs = inputs
        manualVoiceTarget = ManualVoiceTarget.VITALS
        startManualVoiceInput()
    }

    private fun startManualWeightVoiceInput(input: EditText) {
        manualWeightVoiceInput = input
        manualVoiceTarget = ManualVoiceTarget.WEIGHT
        startManualVoiceInput()
    }

    private fun startManualVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            launchManualVoiceInput()
        } else {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchManualVoiceInput() {
        val target = manualVoiceTarget ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPAN.toLanguageTag())
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                when (target) {
                    ManualVoiceTarget.VITALS -> "最高血圧、最低血圧、脈拍の順に数字を話してください。"
                    ManualVoiceTarget.WEIGHT -> "体重をキログラムで話してください。"
                }
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            voiceInputLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            setStatusMessage("この端末では音声入力を起動できません。", floating = true)
        }
    }

    private fun applyManualVoiceResult(matches: List<String>) {
        when (manualVoiceTarget) {
            ManualVoiceTarget.VITALS -> applyManualVitalVoiceResult(matches)
            ManualVoiceTarget.WEIGHT -> applyManualWeightVoiceResult(matches)
            null -> Unit
        }
    }

    private fun applyManualVitalVoiceResult(matches: List<String>) {
        val inputs = manualVitalVoiceInputs ?: return
        val values = parseManualVitalVoiceValues(matches)
        if (values.size < 3) {
            setStatusMessage("最高血圧、最低血圧、脈拍の順に3つの数字を話してください。", floating = true)
            return
        }

        inputs.systolic.setText(formatManualVitalNumber(values[0]))
        inputs.diastolic.setText(formatManualVitalNumber(values[1]))
        inputs.heartRate.setText(values[2].toLong().toString())
        setStatusMessage("音声入力を反映しました。", floating = true)
    }

    private fun applyManualWeightVoiceResult(matches: List<String>) {
        val input = manualWeightVoiceInput ?: return
        val value = matches
            .asSequence()
            .flatMap { extractSpokenNumbers(it).asSequence() }
            .firstOrNull()
        if (value == null) {
            setStatusMessage("体重を数字で話してください。", floating = true)
            return
        }

        input.setText(formatManualWeight(value))
        setStatusMessage("音声入力を反映しました。", floating = true)
    }

    private fun parseManualVitalVoiceValues(matches: List<String>): List<Double> {
        matches.forEach { match ->
            extractManualVitalVoiceCandidate(match)?.let { return it }
        }
        return matches
            .flatMap { extractSpokenNumbers(it) }
            .take(MANUAL_VITAL_FIELD_COUNT)
    }

    private fun extractManualVitalVoiceCandidate(text: String): List<Double>? {
        val tokens = extractSpokenNumberTokens(text)
        if (tokens.size >= MANUAL_VITAL_FIELD_COUNT) {
            return tokens
                .take(MANUAL_VITAL_FIELD_COUNT)
                .mapNotNull { it.toDoubleOrNull() }
                .takeIf { it.size == MANUAL_VITAL_FIELD_COUNT && it.all { value -> value > 0 } }
        }

        if (tokens.isEmpty()) {
            return null
        }

        return splitCompactManualVitalTokens(tokens)
    }

    private fun splitCompactManualVitalTokens(tokens: List<String>): List<Double>? {
        fun buildCombinations(index: Int, current: List<String>): Sequence<List<String>> = sequence {
            if (index == tokens.size) {
                if (current.size == MANUAL_VITAL_FIELD_COUNT) {
                    yield(current)
                }
                return@sequence
            }

            val token = tokens[index]
            val remainingTokens = tokens.size - index - 1
            val maxParts = MANUAL_VITAL_FIELD_COUNT - current.size - remainingTokens
            for (parts in 1..maxParts) {
                splitManualVitalToken(token, parts).forEach { split ->
                    yieldAll(buildCombinations(index + 1, current + split))
                }
            }
        }

        return buildCombinations(0, emptyList())
            .mapNotNull { parts ->
                val values = parts.mapNotNull { it.toDoubleOrNull() }
                if (values.size == MANUAL_VITAL_FIELD_COUNT) values else null
            }
            .filter { it.all { value -> value > 0 } }
            .minByOrNull { manualVitalPlausibilityPenalty(it) }
    }

    private fun splitManualVitalToken(token: String, parts: Int): List<List<String>> {
        if (parts == 1) {
            return listOf(listOf(token))
        }
        if (token.any { it == '.' } || token.length < parts * MIN_MANUAL_VITAL_DIGITS_PER_COMPACT_VALUE) {
            return emptyList()
        }

        fun splitFrom(start: Int, remainingParts: Int): Sequence<List<String>> = sequence {
            if (remainingParts == 1) {
                val last = token.substring(start)
                if (last.length in MIN_MANUAL_VITAL_DIGITS_PER_COMPACT_VALUE..MAX_MANUAL_VITAL_DIGITS_PER_COMPACT_VALUE) {
                    yield(listOf(last))
                }
                return@sequence
            }

            val remainingAfterThis = remainingParts - 1
            val minEnd = start + MIN_MANUAL_VITAL_DIGITS_PER_COMPACT_VALUE
            val maxEnd = minOf(
                start + MAX_MANUAL_VITAL_DIGITS_PER_COMPACT_VALUE,
                token.length - remainingAfterThis * MIN_MANUAL_VITAL_DIGITS_PER_COMPACT_VALUE
            )
            for (end in minEnd..maxEnd) {
                val head = token.substring(start, end)
                splitFrom(end, remainingAfterThis).forEach { tail ->
                    yield(listOf(head) + tail)
                }
            }
        }

        return splitFrom(0, parts).toList()
    }

    private fun manualVitalPlausibilityPenalty(values: List<Double>): Double {
        val systolic = values[0]
        val diastolic = values[1]
        val heartRate = values[2]
        var penalty = 0.0
        penalty += rangePenalty(systolic, 80.0, 250.0) * 4
        penalty += rangePenalty(diastolic, 40.0, 150.0) * 4
        penalty += rangePenalty(heartRate, 40.0, 220.0) * 4
        if (systolic <= diastolic) {
            penalty += 1_000.0 + (diastolic - systolic)
        }
        penalty += kotlin.math.abs(systolic - 120.0) / 120.0
        penalty += kotlin.math.abs(diastolic - 80.0) / 80.0
        penalty += kotlin.math.abs(heartRate - 75.0) / 75.0
        return penalty
    }

    private fun rangePenalty(value: Double, min: Double, max: Double): Double {
        return when {
            value < min -> min - value
            value > max -> value - max
            else -> 0.0
        }
    }

    private fun extractSpokenNumbers(text: String): List<Double> {
        return extractSpokenNumberTokens(text)
            .mapNotNull { it.toDoubleOrNull() }
            .filter { it > 0 }
            .toList()
    }

    private fun extractSpokenNumberTokens(text: String): List<String> {
        val normalized = text.map { char ->
            when (char) {
                in '０'..'９' -> '0' + (char - '０')
                '．' -> '.'
                else -> char
            }
        }.joinToString("")
        return Regex("""\d+(?:\.\d+)?""")
            .findAll(normalized)
            .map { it.value }
            .toList()
    }

    private fun formatManualVitalNumber(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    private fun confirmAndRegisterManualVitalToHealthConnect(measurement: VitalMeasurement) {
        if (currentSyncJob?.isActive == true) {
            return
        }

        val message = "入力値を確認中..."
        setStatusMessage(message)
        showSyncDialog(message)
        currentSyncJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val client = checkedHealthClient(MANUAL_VITAL_REQUIRED_PERMISSIONS) ?: return@launch
                val recentMeasurements = withContext(Dispatchers.IO) {
                    HealthNotionSyncEngine.readVitalMeasurements(client, DEFAULT_LOOKBACK_DAYS)
                }
                val warnings = vitalInputWarnings(measurement, recentMeasurements)
                dismissSyncDialog()
                if (warnings.isNotEmpty()) {
                    val shouldContinue = showInputConfirmationDialog(
                        title = "バイタルの入力値を確認",
                        valueSummary = measurement.toConfirmationSummary(),
                        warnings = warnings,
                        continueText = "このまま登録"
                    )
                    if (!shouldContinue) {
                        setStatusMessage("バイタルの登録を中止しました。", floating = true)
                        return@launch
                    }
                }
                insertManualVitalToHealthConnect(client, measurement)
            } catch (e: Exception) {
                setStatusMessage("バイタルの登録に失敗しました: ${safeErrorMessage(e)}", floating = true)
            } finally {
                currentSyncJob = null
                dismissSyncDialog()
            }
        }
    }

    private suspend fun insertManualVitalToHealthConnect(
        client: HealthConnectClient,
        measurement: VitalMeasurement
    ) {
        val message = "バイタルをHealth Connectに登録中..."
        setStatusMessage(message)
        showSyncDialog(message)
        withContext(Dispatchers.IO) {
            client.insertRecords(measurement.toHealthConnectRecords())
        }
        playOperationCompletedSound()
        setStatusMessage("バイタルをHealth Connectに登録しました。", floating = true)
        refreshLatestDates()
    }

    private fun confirmAndRegisterManualWeightToHealthConnect(measurement: WeightMeasurement) {
        if (currentSyncJob?.isActive == true) {
            return
        }

        val message = "入力値を確認中..."
        setStatusMessage(message)
        showSyncDialog(message)
        currentSyncJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val client = checkedHealthClient(MANUAL_WEIGHT_REQUIRED_PERMISSIONS) ?: return@launch
                val recentMeasurements = withContext(Dispatchers.IO) {
                    HealthNotionSyncEngine.readWeightMeasurements(client, DEFAULT_LOOKBACK_DAYS)
                }
                val warnings = weightInputWarnings(measurement, recentMeasurements)
                dismissSyncDialog()
                if (warnings.isNotEmpty()) {
                    val shouldContinue = showInputConfirmationDialog(
                        title = "体重の入力値を確認",
                        valueSummary = measurement.toConfirmationSummary(),
                        warnings = warnings,
                        continueText = "このまま登録"
                    )
                    if (!shouldContinue) {
                        setStatusMessage("体重の登録を中止しました。", floating = true)
                        return@launch
                    }
                }
                insertManualWeightToHealthConnect(client, measurement)
            } catch (e: Exception) {
                setStatusMessage("体重の登録に失敗しました: ${safeErrorMessage(e)}", floating = true)
            } finally {
                currentSyncJob = null
                dismissSyncDialog()
            }
        }
    }

    private suspend fun insertManualWeightToHealthConnect(
        client: HealthConnectClient,
        measurement: WeightMeasurement
    ) {
        val message = "体重をHealth Connectに登録中..."
        setStatusMessage(message)
        showSyncDialog(message)
        withContext(Dispatchers.IO) {
            client.insertRecords(
                listOf(measurement.toHealthConnectRecord(metadataIdPrefix = "manual-weight"))
            )
        }
        playOperationCompletedSound()
        setStatusMessage("体重をHealth Connectに登録しました。", floating = true)
        refreshLatestDates()
    }

    private fun syncAllToNotion() {
        val config = currentConfig()
        if (!config.hasAnySettings()) {
            setStatusMessage("歩数、バイタル、体重のいずれかのNotion設定を入力してください。", floating = true)
            return
        }

        startSync(
            syncMessage = "すべてのデータを同期中...",
            failurePrefix = "同期に失敗しました",
            requiredHealthPermissions = config.requiredSyncPermissions()
        ) { client ->
            val result = HealthNotionSyncEngine.syncConfigured(client, config, DEFAULT_LOOKBACK_DAYS, packageName)
            result.toDisplayMessage()
        }
    }

    private fun startSync(
        syncMessage: String,
        failurePrefix: String,
        requiredHealthPermissions: Set<String>,
        permissionTarget: String? = null,
        sync: suspend (HealthConnectClient) -> String
    ) {
        if (currentSyncJob?.isActive == true) {
            return
        }

        setStatusMessage(syncMessage)
        showSyncDialog(syncMessage)
        currentSyncJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val client = checkedHealthClient(requiredHealthPermissions, permissionTarget) ?: return@launch
                val resultMessage = withContext(Dispatchers.IO) { sync(client) }
                playOperationCompletedSound()
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

    private fun playOperationCompletedSound() {
        val tone = operationCompletedTone ?: runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        }.getOrNull()?.also { operationCompletedTone = it } ?: return

        tone.startTone(ToneGenerator.TONE_PROP_ACK, OPERATION_COMPLETED_TONE_DURATION_MS)
    }

    private fun showSyncDialog(message: String) {
        val density = resources.displayMetrics.density
        val palette = uiPalette()
        val dialogMessage = TextView(this).apply {
            text = message
            textSize = 20f
            setTextColor(palette.primaryText)
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
                setColor(palette.dialogBackground)
                setStroke((1 * density).toInt(), palette.accent)
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
            indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.accent)
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
            setTextColor(palette.dangerButtonText)
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(palette.dangerButtonBackground)
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
        val palette = uiPalette()
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
                setColor(palette.dialogBackground)
                setStroke((1 * density).toInt(), palette.accent)
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
            setTextColor(palette.primaryText)
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(palette.accent)
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
        val palette = uiPalette()
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
                setColor(palette.dialogBackground)
                setStroke((1 * density).toInt(), palette.accent)
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
            setTextColor(palette.primaryText)
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
            val palette = uiPalette()
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
                    setColor(palette.dialogBackground)
                    setStroke((1 * density).toInt(), palette.accent)
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
                setTextColor(palette.primaryText)
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
                        setTextColor(palette.secondaryText)
                        setLineSpacing(0f, 1.12f)
                    })
                    val detailsText = TextView(this@MainActivity).apply {
                        text = result.toDebugDetailsText()
                        textSize = 15f
                        setTextColor(palette.secondaryText)
                        setLineSpacing(0f, 1.12f)
                        visibility = View.GONE
                    }
                    addView(Button(this@MainActivity).apply {
                        text = ""
                        contentDescription = "詳細を表示"
                        setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_expand_window_down, 0, 0)
                        compoundDrawableTintList = android.content.res.ColorStateList.valueOf(palette.secondaryText)
                        background = GradientDrawable().apply {
                            cornerRadius = 10 * density
                            setColor(palette.secondaryButtonBackground)
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
                setTextColor(palette.secondaryText)
                background = GradientDrawable().apply {
                    cornerRadius = 10 * density
                    setColor(palette.secondaryButtonBackground)
                    setStroke((1 * density).toInt(), palette.border)
                }
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
                setTextColor(palette.onAccent)
                background = GradientDrawable().apply {
                    cornerRadius = 10 * density
                    setColor(palette.accent)
                }
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

    private suspend fun showInputConfirmationDialog(
        title: String,
        valueSummary: String,
        warnings: List<String>,
        continueText: String
    ): Boolean =
        suspendCancellableCoroutine { continuation ->
            messageDialog?.dismiss()

            val density = resources.displayMetrics.density
            val palette = uiPalette()
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
                    setColor(palette.dialogBackground)
                    setStroke((1 * density).toInt(), palette.warningBorder)
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ).apply {
                    leftMargin = (24 * density).toInt()
                    rightMargin = (24 * density).toInt()
                }
            }
            panel.addView(TextView(this).apply {
                text = title
                textSize = 20f
                setTextColor(palette.primaryText)
                typeface = Typeface.DEFAULT_BOLD
            })
            panel.addView(TextView(this).apply {
                text = buildString {
                    append("入力値が通常と異なる可能性があります。\n\n")
                    append(valueSummary)
                    append("\n\n")
                    warnings.forEach { warning ->
                        append("・")
                        append(warning)
                        append("\n")
                    }
                }.trimEnd()
                textSize = 15f
                setTextColor(palette.secondaryText)
                setLineSpacing(0f, 1.12f)
                setPadding(0, (12 * density).toInt(), 0, 0)
            })

            val buttons = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (16 * density).toInt()
                }
            }
            buttons.addView(Button(this).apply {
                text = "修正する"
                setTextColor(palette.secondaryText)
                background = GradientDrawable().apply {
                    cornerRadius = 10 * density
                    setColor(palette.secondaryButtonBackground)
                    setStroke((1 * density).toInt(), palette.border)
                }
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
                text = continueText
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.onAccent)
                background = GradientDrawable().apply {
                    cornerRadius = 10 * density
                    setColor(palette.accent)
                }
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
        lines.add("集計対象origin: すべて")
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

    private fun EditText.requiredPositiveDouble(label: String): Double {
        val value = text.toString().trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("${label}を数値で入力してください。")
        require(value > 0.0) { "${label}は1以上で入力してください。" }
        return value
    }

    private fun EditText.requiredPositiveLong(label: String): Long {
        val value = text.toString().trim().toLongOrNull()
            ?: throw IllegalArgumentException("${label}を整数で入力してください。")
        require(value > 0L) { "${label}は1以上で入力してください。" }
        return value
    }

    private suspend fun checkedHealthClient(
        permissions: Set<String> = requiredPermissions,
        permissionTarget: String? = null
    ): HealthConnectClient? {
        val client = healthConnectClientOrNull()
        if (client == null) {
            setStatusMessage("Health Connectが利用できません。", floating = true)
            return null
        }
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(permissions)) {
            requestedHealthPermissions = permissions
            permissionLauncher.launch(permissions)
            val target = permissionTarget
                ?: healthPermissionTargets(permissions - granted).joinToString("・").takeIf { it.isNotBlank() }
            val targetLabel = target?.let { "${it}の" }.orEmpty()
            setStatusMessage("Health Connectの${targetLabel}権限を許可してから再度実行してください。", floating = true)
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

    private fun syncOneDebugStepDay(
        notion: NotionClient,
        existingPages: MutableMap<LocalDate, NotionStepPage>,
        debugMeasurement: DailyStepDebugMeasurement
    ): StepDebugSyncResult {
        val steps = debugMeasurement.measurement
        val existingPage = existingPages[steps.date]
        val existingMeasurement = existingPage?.toMeasurement(steps.date)
        val operation = if (existingPage == null) {
            notion.createStepPage(steps)
            existingPages[steps.date] = NotionStepPage(
                id = "",
                recordedAt = steps.recordedAt,
                steps = steps.steps
            )
            StepDebugOperation.CREATED
        } else if (existingMeasurement == null || !steps.hasSameStepData(existingMeasurement)) {
            notion.updateStepPage(existingPage.id, steps)
            existingPages[steps.date] = existingPage.copy(
                recordedAt = steps.recordedAt,
                steps = steps.steps
            )
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

    private suspend fun readDailyStepDebugMeasurements(client: HealthConnectClient): List<DailyStepDebugMeasurement> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startDate = today.minusDays(DEFAULT_LOOKBACK_DAYS)
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
                timeRangeSlicer = Period.ofDays(1)
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

    private suspend fun readLatestWeightTime(client: HealthConnectClient): Instant? {
        return client.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = recentRecordTimeRange(),
                ascendingOrder = false,
                pageSize = 1
            )
        ).records.firstOrNull()?.time
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
            today.minusDays(DEFAULT_LOOKBACK_DAYS).atStartOfDay(zone).toInstant(),
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

    @Suppress("DEPRECATION")
    private fun applyUiMode() {
        val palette = uiPalette()
        window.statusBarColor = palette.pageBackground
        window.navigationBarColor = palette.pageBackground
        val lightSystemBars = !isDarkUiMode()
        window.decorView.systemUiVisibility = if (lightSystemBars) {
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            0
        }
    }

    private fun uiPalette(): UiPalette =
        if (isDarkUiMode()) UiPalette.dark() else UiPalette.light()

    private fun isDarkUiMode(): Boolean {
        return when (UiModePreference.from(loadUiMode())) {
            UiModePreference.SYSTEM -> {
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            UiModePreference.LIGHT -> false
            UiModePreference.DARK -> true
        }
    }

    private fun loadUiMode(): String {
        return getSharedPreferences("notion", Context.MODE_PRIVATE)
            .getString(UI_MODE_KEY, UiModePreference.SYSTEM.value) ?: UiModePreference.SYSTEM.value
    }

    private fun refreshLatestReleaseNotice(views: UpdateNoticeViews) {
        CoroutineScope(Dispatchers.Main).launch {
            val currentVersion = appVersionName().toSemanticVersion() ?: return@launch
            val release = withContext(Dispatchers.IO) {
                runCatching { GitHubReleaseClient.latestRelease() }
                    .onFailure { Log.w(UPDATE_NOTICE_TAG, "Failed to check latest GitHub release.", it) }
                    .getOrNull()
            } ?: return@launch
            if (release.version <= currentVersion) {
                return@launch
            }

            views.message.text = "現在のバージョンは ${currentVersion.label} です。${release.version.label} が公開されています。"
            views.downloadButton.setOnClickListener { openUrl(GITHUB_RELEASES_PAGE_URL, "ダウンロードページを開けませんでした。") }
            views.card.visibility = View.VISIBLE
        }
    }

    private fun openConfiguredNotionPage(label: String, dataSourceId: String) {
        val prefs = getSharedPreferences("notion", Context.MODE_PRIVATE)
        val normalizedDataSourceId = dataSourceId.trim()
        if (normalizedDataSourceId.isBlank()) {
            setStatusMessage("${label}のData Source IDが未設定です。", floating = true)
            return
        }
        if (SecureSettingsStore.loadToken(prefs).isBlank()) {
            setStatusMessage("Notion API Tokenを設定してください。", floating = true)
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    notionDatabaseUrl(NotionClient(currentConfig()).retrieveDataSourceDatabaseId(normalizedDataSourceId))
                }
            }
            result
                .onSuccess { openUrl(it, "${label}のNotionページを開けませんでした。") }
                .onFailure { error ->
                    val exception = error as? Exception ?: RuntimeException(error)
                    setStatusMessage("${label}のNotionページを開けませんでした。${safeErrorMessage(exception)}", floating = true)
                }
        }
    }

    private fun openUrl(url: String, failureMessage: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            setStatusMessage(failureMessage, floating = true)
        }
    }

    private fun openManualPage() {
        openUrl(MANUAL_PAGE_URL, "設定マニュアルを開けませんでした。")
    }

}

private data class UpdateNoticeViews(
    val card: LinearLayout,
    val message: TextView,
    val downloadButton: Button
)

private data class SyncStatusCardViews(
    val healthConnectDateText: TextView,
    val notionDateText: TextView
)

private data class UiPalette(
    val pageBackground: Int,
    val backgroundGradient: IntArray,
    val cardGradient: IntArray,
    val disabledCard: Int,
    val controlBackground: Int,
    val inputBackground: Int,
    val secondaryButtonBackground: Int,
    val spinnerBackground: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val mutedText: Int,
    val hintText: Int,
    val disabledText: Int,
    val infoText: Int,
    val border: Int,
    val accent: Int,
    val onAccent: Int,
    val dialogBackground: Int,
    val successBackground: Int,
    val successBorder: Int,
    val successButtonBackground: Int,
    val dangerText: Int,
    val dangerBackground: Int,
    val dangerBorder: Int,
    val dangerButtonBackground: Int,
    val dangerButtonText: Int,
    val warningBorder: Int
) {
    companion object {
        fun dark(): UiPalette = UiPalette(
            pageBackground = Color.parseColor("#101820"),
            backgroundGradient = intArrayOf(
                Color.parseColor("#071118"),
                Color.parseColor("#101C25"),
                Color.parseColor("#081018")
            ),
            cardGradient = intArrayOf(Color.parseColor("#14232E"), Color.parseColor("#101A23")),
            disabledCard = Color.parseColor("#171D22"),
            controlBackground = Color.parseColor("#172633"),
            inputBackground = Color.parseColor("#182630"),
            secondaryButtonBackground = Color.parseColor("#22313C"),
            spinnerBackground = Color.parseColor("#182630"),
            primaryText = Color.WHITE,
            secondaryText = Color.parseColor("#D9E3EA"),
            mutedText = Color.parseColor("#AAB7C4"),
            hintText = Color.parseColor("#7D8A96"),
            disabledText = Color.parseColor("#6E7C86"),
            infoText = Color.parseColor("#A9DDF5"),
            border = Color.parseColor("#38505E"),
            accent = Color.parseColor("#44D7B6"),
            onAccent = Color.parseColor("#081018"),
            dialogBackground = Color.parseColor("#17232D"),
            successBackground = Color.parseColor("#25301F"),
            successBorder = Color.parseColor("#6BAA3A"),
            successButtonBackground = Color.parseColor("#A6E05A"),
            dangerText = Color.parseColor("#FFB4A8"),
            dangerBackground = Color.parseColor("#2A1C1D"),
            dangerBorder = Color.parseColor("#6D3B3B"),
            dangerButtonBackground = Color.parseColor("#B93845"),
            dangerButtonText = Color.WHITE,
            warningBorder = Color.parseColor("#F5C542")
        )

        fun light(): UiPalette = UiPalette(
            pageBackground = Color.parseColor("#F6FAFC"),
            backgroundGradient = intArrayOf(
                Color.parseColor("#F7FBFC"),
                Color.parseColor("#EEF6F7"),
                Color.parseColor("#FFFFFF")
            ),
            cardGradient = intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#F1F7F8")),
            disabledCard = Color.parseColor("#EEF3F5"),
            controlBackground = Color.parseColor("#FFFFFF"),
            inputBackground = Color.WHITE,
            secondaryButtonBackground = Color.parseColor("#E8F0F2"),
            spinnerBackground = Color.WHITE,
            primaryText = Color.parseColor("#102028"),
            secondaryText = Color.parseColor("#324650"),
            mutedText = Color.parseColor("#687782"),
            hintText = Color.parseColor("#7B8B94"),
            disabledText = Color.parseColor("#98A4AA"),
            infoText = Color.parseColor("#0B6E8F"),
            border = Color.parseColor("#C8D6DC"),
            accent = Color.parseColor("#0D8F78"),
            onAccent = Color.WHITE,
            dialogBackground = Color.WHITE,
            successBackground = Color.parseColor("#EAF7ED"),
            successBorder = Color.parseColor("#6AAE63"),
            successButtonBackground = Color.parseColor("#0D8F78"),
            dangerText = Color.parseColor("#B93845"),
            dangerBackground = Color.parseColor("#FFF0F1"),
            dangerBorder = Color.parseColor("#E0A4AA"),
            dangerButtonBackground = Color.parseColor("#B93845"),
            dangerButtonText = Color.WHITE,
            warningBorder = Color.parseColor("#B98900")
        )
    }
}

internal enum class UiModePreference(val value: String, val label: String) {
    SYSTEM("system", "システム"),
    LIGHT("light", "ライト"),
    DARK("dark", "ダーク");

    companion object {
        fun from(value: String?): UiModePreference =
            entries.firstOrNull { it.value == value } ?: SYSTEM

        fun indexOf(value: String?): Int = entries.indexOf(from(value))
    }
}

internal data class SyncConfig(
    val token: String,
    val stepsDataSourceId: String,
    val stepsDateProperty: String,
    val stepsProperty: String,
    val vitalsDataSourceId: String,
    val vitalsMeasuredAtProperty: String,
    val systolicProperty: String,
    val diastolicProperty: String,
    val heartRateProperty: String,
    val weightDataSourceId: String,
    val weightMeasuredAtProperty: String,
    val weightProperty: String,
    val stepsDirection: SyncDirection,
    val vitalsDirection: SyncDirection,
    val weightDirection: SyncDirection
) {
    fun hasStepsSettings(): Boolean =
        stepsDirection != SyncDirection.DISABLED &&
        token.isNotBlank() &&
            stepsDataSourceId.isNotBlank() &&
            stepsDateProperty.isNotBlank() &&
            stepsProperty.isNotBlank()

    fun hasVitalsSettings(): Boolean =
        vitalsDirection != SyncDirection.DISABLED &&
        token.isNotBlank() &&
            vitalsDataSourceId.isNotBlank() &&
            vitalsMeasuredAtProperty.isNotBlank() &&
            systolicProperty.isNotBlank() &&
            diastolicProperty.isNotBlank() &&
            heartRateProperty.isNotBlank()

    fun hasWeightSettings(): Boolean =
        weightDirection != SyncDirection.DISABLED &&
        token.isNotBlank() &&
            weightDataSourceId.isNotBlank() &&
            weightMeasuredAtProperty.isNotBlank() &&
            weightProperty.isNotBlank()

    fun hasAnySettings(): Boolean = hasStepsSettings() || hasVitalsSettings() || hasWeightSettings()

    fun requiredSyncPermissions(): Set<String> = buildSet {
        if (hasStepsSettings()) addAll(stepsDirection.stepsPermissions())
        if (hasVitalsSettings()) addAll(vitalsDirection.vitalsPermissions())
        if (hasWeightSettings()) addAll(weightDirection.weightPermissions())
    }
}

internal enum class SyncDirection(val value: String, val label: String) {
    DISABLED("disabled", "同期しない"),
    HEALTH_CONNECT_TO_NOTION("health_connect_to_notion", "HealthConnect→Notion"),
    NOTION_TO_HEALTH_CONNECT("notion_to_health_connect", "Notion→HealthConnect");

    companion object {
        fun from(value: String?): SyncDirection =
            entries.firstOrNull { it.value == value } ?: HEALTH_CONNECT_TO_NOTION

        fun indexOf(value: String?): Int = entries.indexOf(from(value))
    }
}

internal data class SyncCardPresentation(
    val enabled: Boolean,
    val healthConnectFirst: Boolean,
    val leftLabel: String,
    val rightLabel: String
)

internal fun syncCardPresentation(direction: SyncDirection): SyncCardPresentation =
    when (direction) {
        SyncDirection.DISABLED,
        SyncDirection.HEALTH_CONNECT_TO_NOTION -> SyncCardPresentation(
            enabled = direction != SyncDirection.DISABLED,
            healthConnectFirst = true,
            leftLabel = "Health Connect",
            rightLabel = "Notion"
        )
        SyncDirection.NOTION_TO_HEALTH_CONNECT -> SyncCardPresentation(
            enabled = true,
            healthConnectFirst = false,
            leftLabel = "Notion",
            rightLabel = "Health Connect"
        )
    }

internal fun SyncDirection.stepsPermissions(): Set<String> = when (this) {
    SyncDirection.DISABLED -> emptySet()
    SyncDirection.HEALTH_CONNECT_TO_NOTION -> STEPS_SYNC_REQUIRED_PERMISSIONS
    SyncDirection.NOTION_TO_HEALTH_CONNECT -> STEPS_WRITE_REQUIRED_PERMISSIONS
}

internal fun SyncDirection.vitalsPermissions(): Set<String> = when (this) {
    SyncDirection.DISABLED -> emptySet()
    SyncDirection.HEALTH_CONNECT_TO_NOTION -> VITALS_SYNC_REQUIRED_PERMISSIONS
    SyncDirection.NOTION_TO_HEALTH_CONNECT -> VITALS_WRITE_REQUIRED_PERMISSIONS
}

internal fun SyncDirection.weightPermissions(): Set<String> = when (this) {
    SyncDirection.DISABLED -> emptySet()
    SyncDirection.HEALTH_CONNECT_TO_NOTION -> WEIGHT_SYNC_REQUIRED_PERMISSIONS
    SyncDirection.NOTION_TO_HEALTH_CONNECT -> WEIGHT_WRITE_REQUIRED_PERMISSIONS
}

private data class AutoSyncChoice(
    val label: String,
    val value: String
)

private data class AutoSyncRunStatus(
    val resultLabel: String,
    val lastSuccessAtMillis: Long,
    val lastFailureAtMillis: Long,
    val failureReason: String,
    val resultDetails: String
) {
    fun topResultLabel(): String =
        when (resultLabel) {
            "成功" -> "成功"
            "未実行" -> resultLabel
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
        failureReason = prefs.getString(AUTO_SYNC_FAILURE_REASON_KEY, "") ?: "",
        resultDetails = prefs.getString(AUTO_SYNC_RESULT_DETAILS_KEY, "") ?: ""
    )
}

private fun recordAutoSyncSuccess(context: Context, result: SyncResultCounts) {
    context.getSharedPreferences("notion", Context.MODE_PRIVATE)
        .edit()
        .putString(AUTO_SYNC_RESULT_KEY, "成功")
        .putString(AUTO_SYNC_RESULT_DETAILS_KEY, result.toDisplayMessage())
        .putLong(AUTO_SYNC_LAST_SUCCESS_AT_KEY, System.currentTimeMillis())
        .remove(AUTO_SYNC_LAST_FAILURE_AT_KEY)
        .remove(AUTO_SYNC_FAILURE_REASON_KEY)
        .apply()
}

private fun recordAutoSyncFailure(context: Context, resultLabel: String, reason: String) {
    context.getSharedPreferences("notion", Context.MODE_PRIVATE)
        .edit()
        .putString(AUTO_SYNC_RESULT_KEY, resultLabel)
        .remove(AUTO_SYNC_RESULT_DETAILS_KEY)
        .putLong(AUTO_SYNC_LAST_FAILURE_AT_KEY, System.currentTimeMillis())
        .putString(AUTO_SYNC_FAILURE_REASON_KEY, reason.take(200))
        .apply()
}

private fun clearAutoSyncFailureDetailsPreference(context: Context) {
    context.getSharedPreferences("notion", Context.MODE_PRIVATE)
        .edit()
        .remove(AUTO_SYNC_LAST_FAILURE_AT_KEY)
        .remove(AUTO_SYNC_FAILURE_REASON_KEY)
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

internal fun isRetryableAutoSyncError(error: Exception): Boolean {
    if (error is NotionSyncDataException) {
        return false
    }
    return (error as? NotionRequestException)?.isRetryable != false
}

private fun loadSyncConfig(context: Context): SyncConfig {
    val prefs = context.getSharedPreferences("notion", Context.MODE_PRIVATE)
    return SyncConfig(
        token = SecureSettingsStore.loadToken(prefs),
        stepsDataSourceId = prefs.getString("stepsDataSource", prefs.getString("dataSource", "")) ?: "",
        stepsDateProperty = prefs.getString("stepsDateProperty", prefs.getString("dateProperty", "日付")) ?: "日付",
        stepsProperty = prefs.getString("stepsProperty", "歩数") ?: "歩数",
        vitalsDataSourceId = prefs.getString("vitalsDataSource", "") ?: "",
        vitalsMeasuredAtProperty = prefs.getString("vitalsMeasuredAtProperty", "日付") ?: "日付",
        systolicProperty = prefs.getString("systolicProperty", "収縮期") ?: "収縮期",
        diastolicProperty = prefs.getString("diastolicProperty", "拡張期") ?: "拡張期",
        heartRateProperty = prefs.getString("heartRateProperty", "脈拍") ?: "脈拍",
        weightDataSourceId = prefs.getString("weightDataSource", "") ?: "",
        weightMeasuredAtProperty = prefs.getString("weightMeasuredAtProperty", "日付") ?: "日付",
        weightProperty = prefs.getString("weightProperty", "体重") ?: "体重",
        stepsDirection = SyncDirection.from(prefs.getString(STEPS_DIRECTION_KEY, null)),
        vitalsDirection = SyncDirection.from(prefs.getString(VITALS_DIRECTION_KEY, null)),
        weightDirection = SyncDirection.from(prefs.getString(WEIGHT_DIRECTION_KEY, null))
    )
}

private fun scheduleAutoSync(context: Context, autoSyncTime: String) {
    enqueueAutoSync(context, autoSyncTime, ExistingWorkPolicy.REPLACE)
    context.getSharedPreferences("notion", Context.MODE_PRIVATE)
        .edit()
        .putInt(AUTO_SYNC_SCHEDULE_VERSION_KEY, AUTO_SYNC_SCHEDULE_VERSION)
        .apply()
}

private fun scheduleNextAutoSync(context: Context) {
    val autoSyncTime = context.getSharedPreferences("notion", Context.MODE_PRIVATE)
        .getString(AUTO_SYNC_TIME_KEY, AUTO_SYNC_OFF) ?: AUTO_SYNC_OFF
    enqueueAutoSync(context, autoSyncTime, ExistingWorkPolicy.APPEND_OR_REPLACE)
}

private fun enqueueAutoSync(
    context: Context,
    autoSyncTime: String,
    policy: ExistingWorkPolicy
) {
    val workManager = WorkManager.getInstance(context.applicationContext)
    if (autoSyncTime == AUTO_SYNC_OFF) {
        workManager.cancelUniqueWork(AUTO_SYNC_WORK_NAME)
        return
    }

    val request = OneTimeWorkRequestBuilder<AutoSyncWorker>()
        .setInitialDelay(initialAutoSyncDelayMillis(autoSyncTime), TimeUnit.MILLISECONDS)
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .build()
    workManager.enqueueUniqueWork(
        AUTO_SYNC_WORK_NAME,
        policy,
        request
    )
}

internal fun initialAutoSyncDelayMillis(
    autoSyncTime: String,
    now: LocalDateTime = LocalDateTime.now()
): Long {
    val targetTime = runCatching { LocalTime.parse(autoSyncTime) }.getOrDefault(LocalTime.MIDNIGHT)
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
                return Result.retry()
            }
        }

        val config = loadSyncConfig(applicationContext)
        if (!config.hasAnySettings()) {
            recordAutoSyncFailure(applicationContext, "失敗", "Notion同期設定が未完了です")
            scheduleNextAutoSync(applicationContext)
            return Result.success()
        }

        val requiredPermissions = buildSet {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
            addAll(config.requiredSyncPermissions())
        }
        val granted = client.permissionController.getGrantedPermissions()
        if (!granted.containsAll(requiredPermissions)) {
            val targets = healthPermissionTargets(requiredPermissions - granted).joinToString("・")
            val detail = targets.takeIf { it.isNotBlank() }?.let { "${it}の" }.orEmpty()
            recordAutoSyncFailure(applicationContext, "失敗", "Health Connectの${detail}自動同期権限が不足しています")
            scheduleNextAutoSync(applicationContext)
            return Result.success()
        }

        return try {
            val result = HealthNotionSyncEngine.syncConfigured(
                client,
                config,
                DEFAULT_LOOKBACK_DAYS,
                applicationContext.packageName
            )
            recordAutoSyncSuccess(applicationContext, result)
            scheduleNextAutoSync(applicationContext)
            Result.success()
        } catch (_: CancellationException) {
            Result.retry()
        } catch (e: Exception) {
            if (isRetryableAutoSyncError(e)) {
                Result.retry()
            } else {
                recordAutoSyncFailure(applicationContext, "失敗", workerErrorMessage(e))
                Result.failure()
            }
        }
    }
}

private object HealthNotionSyncEngine {
    suspend fun syncConfigured(
        client: HealthConnectClient,
        config: SyncConfig,
        lookbackDays: Long,
        applicationPackageName: String
    ): SyncResultCounts {
        val steps = if (config.hasStepsSettings()) {
            syncSteps(client, config, lookbackDays, applicationPackageName)
        } else 0
        val vitals = if (config.hasVitalsSettings()) {
            syncVitals(client, config, lookbackDays, applicationPackageName)
        } else 0
        val weight = if (config.hasWeightSettings()) {
            syncWeight(client, config, lookbackDays, applicationPackageName)
        } else 0
        return SyncResultCounts(
            steps = steps.takeIf { config.hasStepsSettings() },
            vitals = vitals.takeIf { config.hasVitalsSettings() },
            weight = weight.takeIf { config.hasWeightSettings() }
        )
    }

    suspend fun syncSteps(
        client: HealthConnectClient,
        config: SyncConfig,
        lookbackDays: Long,
        applicationPackageName: String
    ): Int {
        if (config.stepsDirection == SyncDirection.NOTION_TO_HEALTH_CONNECT) {
            return syncStepsToHealthConnect(client, config, lookbackDays, applicationPackageName)
        }
        val notion = NotionClient(config)
        val existingPages = notion.readStepPagesByDate(lookbackDays).toMutableMap()
        var synced = 0
        for (steps in readDailyStepMeasurements(client, lookbackDays)) {
            coroutineContext.ensureActive()
            val existingPage = existingPages[steps.date]
            if (existingPage == null) {
                notion.createStepPage(steps)
                existingPages[steps.date] = NotionStepPage(
                    id = "",
                    recordedAt = steps.recordedAt,
                    steps = steps.steps
                )
                synced++
            } else {
                val existingMeasurement = existingPage.toMeasurement(steps.date)
                if (existingMeasurement == null || !steps.hasSameStepData(existingMeasurement)) {
                    notion.updateStepPage(existingPage.id, steps)
                    existingPages[steps.date] = existingPage.copy(
                        recordedAt = steps.recordedAt,
                        steps = steps.steps
                    )
                    synced++
                }
            }
        }
        return synced
    }

    suspend fun syncVitals(
        client: HealthConnectClient,
        config: SyncConfig,
        lookbackDays: Long,
        applicationPackageName: String
    ): Int {
        if (config.vitalsDirection == SyncDirection.NOTION_TO_HEALTH_CONNECT) {
            return syncVitalsToHealthConnect(client, config, lookbackDays, applicationPackageName)
        }
        val notion = NotionClient(config)
        val existingPages = notion.readVitalPagesByMinute(lookbackDays).toMutableMap()
        val measurements = latestVitalMeasurementsByMinute(readVitalMeasurements(client, lookbackDays))
        var synced = 0
        for (measurement in measurements) {
            coroutineContext.ensureActive()
            val minute = measurement.measuredAt.toMinuteKey()
            val existingPage = existingPages[minute]
            if (existingPage == null) {
                notion.createVitalPage(measurement)
                synced++
            } else if (!measurement.hasSameVitalValues(existingPage.measurement)) {
                notion.updateVitalPage(existingPage.id, measurement)
                synced++
            }
            existingPages[minute] = NotionVitalPage(existingPage?.id.orEmpty(), measurement)
        }
        return synced
    }

    suspend fun syncWeight(
        client: HealthConnectClient,
        config: SyncConfig,
        lookbackDays: Long,
        applicationPackageName: String
    ): Int {
        if (config.weightDirection == SyncDirection.NOTION_TO_HEALTH_CONNECT) {
            return syncWeightToHealthConnect(client, config, lookbackDays, applicationPackageName)
        }
        val notion = NotionClient(config)
        val existingPages = notion.readWeightPagesByMinute(lookbackDays).toMutableMap()
        val measurements = latestWeightMeasurementsByMinute(readWeightMeasurements(client, lookbackDays))
        var synced = 0
        for (measurement in measurements) {
            coroutineContext.ensureActive()
            val minute = measurement.measuredAt.toMinuteKey()
            val existingPage = existingPages[minute]
            if (existingPage == null) {
                notion.createWeightPage(measurement)
                synced++
            } else if (!measurement.hasSameWeightData(existingPage.measurement)) {
                notion.updateWeightPage(existingPage.id, measurement)
                synced++
            }
            existingPages[minute] = NotionWeightPage(existingPage?.id.orEmpty(), measurement)
        }
        return synced
    }

    private suspend fun syncStepsToHealthConnect(
        client: HealthConnectClient,
        config: SyncConfig,
        lookbackDays: Long,
        applicationPackageName: String
    ): Int {
        val existingRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = recentTimeRange(lookbackDays),
                ascendingOrder = true,
                pageSize = 5000
            )
        ).records
            .ownedByApplication(applicationPackageName) { it.metadata.dataOrigin.packageName }
            .associateLatestByMinute { it.endTime }
        var synced = 0
        for (measurement in NotionClient(config).readStepMeasurements(lookbackDays)) {
            val existingRecord = existingRecords[measurement.recordedAt.toMinuteKey()]
            val existingMeasurement = existingRecord?.let {
                DailyStepMeasurement(measurement.date, it.endTime, it.count)
            }
            if (existingMeasurement != null && measurement.hasSameStepData(existingMeasurement)) {
                continue
            }
            val record = measurement.toHealthConnectRecord(existingRecord?.metadata?.id)
            if (existingRecord == null) client.insertRecords(listOf(record)) else client.updateRecords(listOf(record))
            synced++
        }
        return synced
    }

    private suspend fun syncVitalsToHealthConnect(
        client: HealthConnectClient,
        config: SyncConfig,
        lookbackDays: Long,
        applicationPackageName: String
    ): Int {
        val existingBloodPressures = client.readRecords(
            ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = recentTimeRange(lookbackDays),
                ascendingOrder = true,
                pageSize = 5000
            )
        ).records
            .ownedByApplication(applicationPackageName) { it.metadata.dataOrigin.packageName }
            .associateLatestByMinute { it.time }
        val existingHeartRates = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = recentTimeRange(lookbackDays),
                ascendingOrder = true,
                pageSize = 5000
            )
        ).records
            .ownedByApplication(applicationPackageName) { it.metadata.dataOrigin.packageName }
            .associateLatestByMinute { it.startTime }
        val measurements = latestVitalMeasurementsByMinute(
            NotionClient(config).readVitalMeasurements(lookbackDays)
        )
        var synced = 0
        for (measurement in measurements) {
            val minute = measurement.measuredAt.toMinuteKey()
            val bloodPressure = existingBloodPressures[minute]
            val heartRate = existingHeartRates[minute]
            val existingMeasurement = bloodPressure?.let {
                VitalMeasurement(
                    measuredAt = it.time,
                    systolic = it.systolic.inMillimetersOfMercury,
                    diastolic = it.diastolic.inMillimetersOfMercury,
                    heartRate = heartRate?.samples
                        ?.filter { sample -> sample.time.toMinuteKey() == minute }
                        ?.maxByOrNull { sample -> sample.time }
                        ?.beatsPerMinute
                )
            }
            if (existingMeasurement != null && measurement.hasSameVitalValues(existingMeasurement)) {
                continue
            }
            val records = measurement.toHealthConnectRecords(
                includeHeartRateWhenMissing = false,
                bloodPressureMetadataId = bloodPressure?.metadata?.id,
                heartRateMetadataId = heartRate?.metadata?.id
            )
            if (bloodPressure == null) {
                client.insertRecords(records)
            } else {
                client.updateRecords(records.filter { it is BloodPressureRecord || heartRate != null })
                records.filterIsInstance<HeartRateRecord>()
                    .takeIf { heartRate == null && it.isNotEmpty() }
                    ?.let { client.insertRecords(it) }
            }
            synced++
        }
        return synced
    }

    private suspend fun syncWeightToHealthConnect(
        client: HealthConnectClient,
        config: SyncConfig,
        lookbackDays: Long,
        applicationPackageName: String
    ): Int {
        val existingRecords = client.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = recentTimeRange(lookbackDays),
                ascendingOrder = true,
                pageSize = 5000
            )
        ).records
            .ownedByApplication(applicationPackageName) { it.metadata.dataOrigin.packageName }
            .associateLatestByMinute { it.time }
        var synced = 0
        for (measurement in latestWeightMeasurementsByMinute(NotionClient(config).readWeightMeasurements(lookbackDays))) {
            val existingRecord = existingRecords[measurement.measuredAt.toMinuteKey()]
            val existingMeasurement = existingRecord?.let {
                WeightMeasurement(it.time, it.weight.inKilograms)
            }
            if (existingMeasurement != null && measurement.hasSameWeightData(existingMeasurement)) {
                continue
            }
            val record = measurement.toHealthConnectRecord(existingRecord?.metadata?.id)
            if (existingRecord == null) client.insertRecords(listOf(record)) else client.updateRecords(listOf(record))
            synced++
        }
        return synced
    }

    private fun recentTimeRange(lookbackDays: Long): TimeRangeFilter {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return TimeRangeFilter.between(
            today.minusDays(lookbackDays).atStartOfDay(zone).toInstant(),
            today.plusDays(1).atStartOfDay(zone).toInstant()
        )
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
                timeRangeSlicer = Period.ofDays(1)
            )
        )

        return aggregatedByDay.mapNotNull { bucket ->
            val steps = bucket.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
            if (steps <= 0L) {
                return@mapNotNull null
            }
            val date = bucket.startTime.atZone(zone).toLocalDate()
            val recordedAt = stepRecordedAtForDate(date, latestRecordTimeByDate[date], zone)
            DailyStepMeasurement(
                date = date,
                recordedAt = recordedAt,
                steps = steps
            )
        }.sortedBy { it.date }
    }

    suspend fun readVitalMeasurements(
        client: HealthConnectClient,
        lookbackDays: Long
    ): List<VitalMeasurement> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.minusDays(lookbackDays).atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        val bloodPressures = client.readRecords(
            ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                ascendingOrder = true,
                pageSize = 5000
            )
        ).records
        val heartRateSamples = client.readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                ascendingOrder = true,
                pageSize = 5000
            )
        ).records.flatMap { it.samples }
        return pairVitalMeasurements(
            bloodPressures = bloodPressures.map {
                BloodPressureMeasurement(
                    measuredAt = it.time,
                    systolic = it.systolic.inMillimetersOfMercury,
                    diastolic = it.diastolic.inMillimetersOfMercury
                )
            },
            heartRatesByTime = heartRateSamples.associate { it.time to it.beatsPerMinute }
        )
    }

    suspend fun readWeightMeasurements(
        client: HealthConnectClient,
        lookbackDays: Long
    ): List<WeightMeasurement> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.minusDays(lookbackDays).atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        return client.readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                ascendingOrder = true,
                pageSize = 5000
            )
        ).records.map {
            WeightMeasurement(
                measuredAt = it.time,
                kilograms = it.weight.inKilograms
            )
        }
    }
}

internal fun syncCountMessage(subject: String, count: Int): String =
    if (count == 0) "すでに最新です。" else "${subject}を${count}件同期しました。"

internal data class SyncResultCounts(
    val steps: Int?,
    val vitals: Int?,
    val weight: Int?
) {
    fun toDisplayMessage(): String {
        val syncedItems = buildList {
            steps?.takeIf { it > 0 }?.let { add("歩数${it}件") }
            vitals?.takeIf { it > 0 }?.let { add("バイタル${it}件") }
            weight?.takeIf { it > 0 }?.let { add("体重${it}件") }
        }
        return if (syncedItems.isEmpty()) {
            "すでに最新です。"
        } else {
            syncedItems.joinToString("、", postfix = "を同期しました。")
        }
    }
}

internal data class DailyStepMeasurement(
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
    val recordedAt: Instant?,
    val steps: Long? = null
)

private data class NotionVitalPage(
    val id: String,
    val measurement: VitalMeasurement
)

private data class NotionWeightPage(
    val id: String,
    val measurement: WeightMeasurement
)

private data class NotionMeasurementPage(
    val id: String,
    val properties: JSONObject
)

internal data class NotionDateValue(
    val date: LocalDate,
    val timestamp: Instant?
)

private data class ManualVitalVoiceInputs(
    val systolic: EditText,
    val diastolic: EditText,
    val heartRate: EditText
)

private enum class ManualVoiceTarget {
    VITALS,
    WEIGHT
}

internal data class VitalMeasurement(
    val measuredAt: Instant,
    val systolic: Double,
    val diastolic: Double,
    val heartRate: Long?
)

internal data class BloodPressureMeasurement(
    val measuredAt: Instant,
    val systolic: Double,
    val diastolic: Double
)

internal data class WeightMeasurement(
    val measuredAt: Instant,
    val kilograms: Double
)

internal fun isOwnedHealthConnectRecord(
    recordPackageName: String,
    applicationPackageName: String
): Boolean = recordPackageName == applicationPackageName

internal fun <T> Iterable<T>.ownedByApplication(
    applicationPackageName: String,
    recordPackageName: (T) -> String
): List<T> = filter { record ->
    isOwnedHealthConnectRecord(recordPackageName(record), applicationPackageName)
}

internal fun notionVitalMeasurementOrNull(
    measuredAt: Instant,
    systolic: Double,
    diastolic: Double,
    heartRate: Double?
): VitalMeasurement? {
    if (!systolic.isFinite() || systolic !in 20.0..200.0) {
        return null
    }
    if (!diastolic.isFinite() || diastolic !in 10.0..180.0 || systolic <= diastolic) {
        return null
    }
    val validatedHeartRate = when {
        heartRate == null -> null
        !heartRate.isFinite() || heartRate !in 1.0..300.0 || heartRate % 1.0 != 0.0 -> return null
        else -> heartRate.toLong()
    }
    return VitalMeasurement(measuredAt, systolic, diastolic, validatedHeartRate)
}

internal fun notionWeightMeasurementOrNull(
    measuredAt: Instant,
    kilograms: Double
): WeightMeasurement? {
    if (!kilograms.isFinite() || kilograms <= 0.0 || kilograms > 1000.0) {
        return null
    }
    return WeightMeasurement(measuredAt, kilograms)
}

internal fun validateNotionMeasurementPage(
    pageNumber: Int,
    currentRowCount: Int,
    pageRowCount: Int,
    hasMore: Boolean,
    nextCursor: String?,
    seenCursors: Set<String>,
    maxPages: Int = NOTION_MAX_MEASUREMENT_QUERY_PAGES,
    maxRows: Int = NOTION_MAX_MEASUREMENT_ROWS
): String? {
    val totalRowCount = currentRowCount + pageRowCount
    if (totalRowCount > maxRows || (hasMore && totalRowCount >= maxRows)) {
        throw NotionSyncDataException(
            "Notionの取得件数が上限${maxRows}件に達しました。同期期間またはData Sourceを確認してください。"
        )
    }
    if (!hasMore) {
        return null
    }
    if (pageNumber >= maxPages) {
        throw NotionSyncDataException(
            "Notionのページ取得が上限${maxPages}回に達しました。同期期間またはData Sourceを確認してください。"
        )
    }
    val validatedCursor = nextCursor?.takeIf { it.isNotBlank() }
        ?: throw NotionSyncDataException("Notion APIのページ情報に次のカーソルがありません。")
    if (validatedCursor in seenCursors) {
        throw NotionSyncDataException("Notion APIのページ情報で同じカーソルが繰り返されました。")
    }
    return validatedCursor
}

internal fun pairVitalMeasurements(
    bloodPressures: List<BloodPressureMeasurement>,
    heartRatesByTime: Map<Instant, Long>
): List<VitalMeasurement> =
    bloodPressures.map { bloodPressure ->
        VitalMeasurement(
            measuredAt = bloodPressure.measuredAt,
            systolic = bloodPressure.systolic,
            diastolic = bloodPressure.diastolic,
            heartRate = heartRatesByTime[bloodPressure.measuredAt]
        )
    }

private fun NotionStepPage.toMeasurement(date: LocalDate): DailyStepMeasurement? {
    val recordedAt = recordedAt ?: return null
    val steps = steps ?: return null
    return DailyStepMeasurement(date, recordedAt, steps)
}

internal fun DailyStepMeasurement.hasSameStepData(other: DailyStepMeasurement): Boolean =
    recordedAt.toMinuteKey() == other.recordedAt.toMinuteKey() && steps == other.steps

internal fun stepRecordedAtForDate(date: LocalDate, latestRecordTime: Instant?, zone: ZoneId): Instant =
    latestRecordTime ?: date.atStartOfDay(zone).toInstant()

internal fun WeightMeasurement.hasSameWeightData(other: WeightMeasurement): Boolean =
    measuredAt.toMinuteKey() == other.measuredAt.toMinuteKey() && kilograms == other.kilograms

internal fun VitalMeasurement.hasSameVitalValues(other: VitalMeasurement): Boolean =
    systolic == other.systolic &&
        diastolic == other.diastolic &&
        heartRate == other.heartRate

internal fun vitalInputWarnings(
    measurement: VitalMeasurement,
    recentMeasurements: List<VitalMeasurement>
): List<String> = buildList {
    if (measurement.systolic <= measurement.diastolic) {
        add("最高血圧が最低血圧以下です。")
    }
    if (measurement.systolic !in 70.0..250.0) {
        add("最高血圧が一般的な範囲から外れています。")
    }
    if (measurement.diastolic !in 40.0..160.0) {
        add("最低血圧が一般的な範囲から外れています。")
    }
    measurement.heartRate?.let { heartRate ->
        if (heartRate !in 35L..220L) {
            add("脈拍が一般的な範囲から外れています。")
        }
    }

    recentMeasurements.medianOf { it.systolic }?.let { median ->
        if (kotlin.math.abs(measurement.systolic - median) >= 40.0) {
            add("最高血圧が最近の中央値${formatComparisonNumber(median)}と大きく異なります。")
        }
    }
    recentMeasurements.medianOf { it.diastolic }?.let { median ->
        if (kotlin.math.abs(measurement.diastolic - median) >= 25.0) {
            add("最低血圧が最近の中央値${formatComparisonNumber(median)}と大きく異なります。")
        }
    }
    measurement.heartRate?.let { heartRate ->
        recentMeasurements.mapNotNull { it.heartRate?.toDouble() }.medianOrNull()?.let { median ->
            if (kotlin.math.abs(heartRate - median) >= 35.0) {
                add("脈拍が最近の中央値${formatComparisonNumber(median)}と大きく異なります。")
            }
        }
    }
}

internal fun weightInputWarnings(
    measurement: WeightMeasurement,
    recentMeasurements: List<WeightMeasurement>
): List<String> = buildList {
    if (measurement.kilograms !in 20.0..300.0) {
        add("体重が一般的な範囲から外れています。")
    }
    recentMeasurements.medianOf { it.kilograms }?.let { median ->
        if (kotlin.math.abs(measurement.kilograms - median) >= 3.0) {
            add("体重が最近の中央値${formatComparisonNumber(median)}kgと大きく異なります。")
        }
    }
}

internal fun latestVitalMeasurementsByMinute(
    measurements: List<VitalMeasurement>
): List<VitalMeasurement> =
    measurements
        .associateLatestByMinute { it.measuredAt }
        .values
        .sortedBy { it.measuredAt }

internal fun Instant.toMinuteKey(): Instant = truncatedTo(ChronoUnit.MINUTES)

private fun <T> Iterable<T>.associateLatestByMinute(timestamp: (T) -> Instant): Map<Instant, T> =
    groupBy { timestamp(it).toMinuteKey() }
        .mapValues { (_, values) -> values.maxBy(timestamp) }

internal fun latestWeightMeasurementsByMinute(
    measurements: List<WeightMeasurement>
): List<WeightMeasurement> =
    measurements
        .associateLatestByMinute { it.measuredAt }
        .values
        .sortedBy { it.measuredAt }

internal fun parseManualWeight(text: String): Double {
    val normalized = text.trim()
    require(MANUAL_WEIGHT_PATTERN.matches(normalized)) {
        "体重は小数第1位までの数値で入力してください。"
    }
    val value = normalized.toDouble()
    require(value > 0.0) { "体重は0より大きい値で入力してください。" }
    return value
}

internal fun formatManualWeight(value: Double): String =
    String.format(Locale.JAPAN, "%.1f", value)

private fun VitalMeasurement.toConfirmationSummary(): String =
    buildString {
        append("最高血圧: ${formatComparisonNumber(systolic)}\n")
        append("最低血圧: ${formatComparisonNumber(diastolic)}")
        heartRate?.let {
            append("\n脈拍: $it")
        }
    }

private fun WeightMeasurement.toConfirmationSummary(): String =
    "体重: ${formatComparisonNumber(kilograms)}kg"

private fun <T> List<T>.medianOf(selector: (T) -> Double): Double? =
    map(selector).medianOrNull()

private fun List<Double>.medianOrNull(): Double? {
    if (isEmpty()) {
        return null
    }
    val values = sorted()
    val middle = values.size / 2
    return if (values.size % 2 == 0) {
        (values[middle - 1] + values[middle]) / 2.0
    } else {
        values[middle]
    }
}

private fun formatComparisonNumber(value: Double): String =
    if (value % 1.0 == 0.0) {
        value.toLong().toString()
    } else {
        String.format(Locale.JAPAN, "%.1f", value)
    }

private fun VitalMeasurement.toHealthConnectRecords(
    includeHeartRateWhenMissing: Boolean = true,
    bloodPressureMetadataId: String? = null,
    heartRateMetadataId: String? = null
): List<androidx.health.connect.client.records.Record> {
    val zoneOffset = measuredAt.atZone(ZoneId.systemDefault()).offset
    val records = mutableListOf<androidx.health.connect.client.records.Record>(
        BloodPressureRecord(
            time = measuredAt,
            zoneOffset = zoneOffset,
            metadata = bloodPressureMetadataId
                ?.let(Metadata::manualEntryWithId)
                ?: Metadata.manualEntry("manual-bp-${measuredAt.toEpochMilli()}"),
            systolic = Pressure.millimetersOfMercury(systolic),
            diastolic = Pressure.millimetersOfMercury(diastolic)
        )
    )
    if (heartRate != null || includeHeartRateWhenMissing) {
        records.add(HeartRateRecord(
            startTime = measuredAt,
            startZoneOffset = zoneOffset,
            endTime = measuredAt.plusSeconds(1),
            endZoneOffset = zoneOffset,
            samples = listOf(HeartRateRecord.Sample(measuredAt, checkNotNull(heartRate))),
            metadata = heartRateMetadataId
                ?.let(Metadata::manualEntryWithId)
                ?: Metadata.manualEntry("manual-hr-${measuredAt.toEpochMilli()}")
        ))
    }
    return records
}

private fun DailyStepMeasurement.toHealthConnectRecord(metadataId: String? = null): StepsRecord {
    val zone = ZoneId.systemDefault()
    val dayStart = date.atStartOfDay(zone).toInstant()
    val end = if (recordedAt.isAfter(dayStart)) recordedAt else dayStart.plusSeconds(1)
    return StepsRecord(
        startTime = dayStart,
        startZoneOffset = dayStart.atZone(zone).offset,
        endTime = end,
        endZoneOffset = end.atZone(zone).offset,
        count = steps,
        metadata = metadataId
            ?.let(Metadata::manualEntryWithId)
            ?: Metadata.manualEntry("notion-steps-${recordedAt.toEpochMilli()}")
    )
}

private fun WeightMeasurement.toHealthConnectRecord(
    metadataId: String? = null,
    metadataIdPrefix: String = "notion-weight"
): WeightRecord =
    WeightRecord(
        time = measuredAt,
        zoneOffset = measuredAt.atZone(ZoneId.systemDefault()).offset,
        weight = Mass.kilograms(kilograms),
        metadata = metadataId
            ?.let(Metadata::manualEntryWithId)
            ?: Metadata.manualEntry("$metadataIdPrefix-${measuredAt.toEpochMilli()}")
    )

private class NotionClient(private val config: SyncConfig) {
    fun retrieveDataSourceDatabaseId(dataSourceId: String): String {
        val response = request("GET", "https://api.notion.com/v1/data_sources/${validDataSourceId(dataSourceId)}")
        val parent = response.optJSONObject("parent")
        val databaseId = when (parent?.optString("type")) {
            "database_id" -> parent.optString("database_id")
            "data_source_id" -> parent.optString("database_id")
            else -> null
        }?.takeIf { it.isNotBlank() }
        require(databaseId != null) {
            "NotionデータベースURLを特定できませんでした。"
        }
        return databaseId
    }

    fun latestStepsDate(lookbackDays: Long): NotionDateValue? =
        latestDateInSyncWindow(validDataSourceId(config.stepsDataSourceId), config.stepsDateProperty, lookbackDays)

    fun latestVitalsDate(lookbackDays: Long): NotionDateValue? =
        latestDateInSyncWindow(validDataSourceId(config.vitalsDataSourceId), config.vitalsMeasuredAtProperty, lookbackDays)

    fun latestWeightDate(lookbackDays: Long): NotionDateValue? =
        latestDateInSyncWindow(validDataSourceId(config.weightDataSourceId), config.weightMeasuredAtProperty, lookbackDays)

    fun readStepPagesByDate(lookbackDays: Long): Map<LocalDate, NotionStepPage> {
        return readDatePagesByDate(
            dataSourceId = validDataSourceId(config.stepsDataSourceId),
            dateProperty = config.stepsDateProperty,
            lookbackDays = lookbackDays
        )
    }

    fun readStepMeasurements(lookbackDays: Long): List<DailyStepMeasurement> =
        readStepPagesByDate(lookbackDays).mapNotNull { (date, page) ->
            val recordedAt = page.recordedAt ?: return@mapNotNull null
            val steps = page.steps ?: return@mapNotNull null
            if (steps <= 0L) return@mapNotNull null
            DailyStepMeasurement(date, recordedAt, steps)
        }.sortedBy { it.recordedAt }

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

    fun createVitalPage(measurement: VitalMeasurement) {
        val body = JSONObject()
            .put("parent", dataSourceParent(validDataSourceId(config.vitalsDataSourceId)))
            .put("properties", vitalProperties(measurement))
        request("POST", "https://api.notion.com/v1/pages", body)
    }

    fun updateVitalPage(pageId: String, measurement: VitalMeasurement) {
        val body = JSONObject().put("properties", vitalProperties(measurement))
        request("PATCH", "https://api.notion.com/v1/pages/$pageId", body)
    }

    private fun vitalProperties(measurement: VitalMeasurement): JSONObject {
        return JSONObject()
            .put(
                config.vitalsMeasuredAtProperty,
                JSONObject().put("date", JSONObject().put("start", measurement.measuredAt.toNotionDateTime()))
            )
            .put(config.systolicProperty, JSONObject().put("number", measurement.systolic))
            .put(config.diastolicProperty, JSONObject().put("number", measurement.diastolic))
            .put(
                config.heartRateProperty,
                JSONObject().put("number", measurement.heartRate ?: JSONObject.NULL)
            )
    }

    fun readVitalPagesByMinute(lookbackDays: Long): Map<Instant, NotionVitalPage> {
        return readMeasurementPages(
            dataSourceId = config.vitalsDataSourceId,
            dateProperty = config.vitalsMeasuredAtProperty,
            lookbackDays = lookbackDays
        ).mapNotNull { page ->
            page.toVitalMeasurement()?.let { NotionVitalPage(page.id, it) }
        }.associateLatestByMinute { it.measurement.measuredAt }
    }

    fun createWeightPage(measurement: WeightMeasurement) {
        val body = JSONObject()
            .put("parent", dataSourceParent(validDataSourceId(config.weightDataSourceId)))
            .put("properties", weightProperties(measurement))
        request("POST", "https://api.notion.com/v1/pages", body)
    }

    fun updateWeightPage(pageId: String, measurement: WeightMeasurement) {
        val body = JSONObject().put("properties", weightProperties(measurement))
        request("PATCH", "https://api.notion.com/v1/pages/$pageId", body)
    }

    private fun weightProperties(measurement: WeightMeasurement): JSONObject =
        JSONObject()
            .put(
                config.weightMeasuredAtProperty,
                JSONObject().put("date", JSONObject().put("start", measurement.measuredAt.toNotionDateTime()))
            )
            .put(config.weightProperty, JSONObject().put("number", measurement.kilograms))

    fun readWeightPagesByMinute(lookbackDays: Long): Map<Instant, NotionWeightPage> {
        return readMeasurementPages(
            dataSourceId = config.weightDataSourceId,
            dateProperty = config.weightMeasuredAtProperty,
            lookbackDays = lookbackDays
        ).mapNotNull { page ->
            page.toWeightMeasurement()?.let { NotionWeightPage(page.id, it) }
        }.associateLatestByMinute { it.measurement.measuredAt }
    }

    fun readVitalMeasurements(lookbackDays: Long): List<VitalMeasurement> {
        return readMeasurementPages(
            dataSourceId = config.vitalsDataSourceId,
            dateProperty = config.vitalsMeasuredAtProperty,
            lookbackDays = lookbackDays
        ).mapNotNull { it.toHealthConnectVitalMeasurement() }
    }

    fun readWeightMeasurements(lookbackDays: Long): List<WeightMeasurement> {
        return readMeasurementPages(
            dataSourceId = config.weightDataSourceId,
            dateProperty = config.weightMeasuredAtProperty,
            lookbackDays = lookbackDays
        ).mapNotNull { it.toHealthConnectWeightMeasurement() }
    }

    private fun readMeasurementPages(
        dataSourceId: String,
        dateProperty: String,
        lookbackDays: Long
    ): List<NotionMeasurementPage> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.minusDays(lookbackDays).atStartOfDay(zone).toInstant().toNotionDateTime()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toNotionDateTime()
        val pages = mutableListOf<NotionMeasurementPage>()
        var cursor: String? = null
        var pageNumber = 0
        var totalRowCount = 0
        val seenCursors = mutableSetOf<String>()
        do {
            pageNumber++
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
            val response = request(
                "POST",
                "https://api.notion.com/v1/data_sources/${validDataSourceId(dataSourceId)}/query",
                body
            )
            ensureCompleteQuery(response)
            val results = response.optJSONArray("results") ?: JSONArray()
            val nextCursor = validateNotionMeasurementPage(
                pageNumber = pageNumber,
                currentRowCount = totalRowCount,
                pageRowCount = results.length(),
                hasMore = response.optBoolean("has_more"),
                nextCursor = response.optString("next_cursor"),
                seenCursors = seenCursors
            )
            totalRowCount += results.length()
            for (index in 0 until results.length()) {
                val page = results.optJSONObject(index) ?: continue
                val properties = page.optJSONObject("properties") ?: continue
                pages.add(NotionMeasurementPage(page.optString("id"), properties))
            }
            cursor = nextCursor
            cursor?.let { seenCursors.add(it) }
        } while (cursor != null)
        return pages
    }

    private fun NotionMeasurementPage.toWeightMeasurement(): WeightMeasurement? {
        val measuredAt = properties.notionInstant(config.weightMeasuredAtProperty) ?: return null
        val kilograms = properties.notionNumber(config.weightProperty) ?: return null
        return WeightMeasurement(measuredAt, kilograms)
    }

    private fun NotionMeasurementPage.toHealthConnectWeightMeasurement(): WeightMeasurement? {
        val measuredAt = properties.notionInstant(config.weightMeasuredAtProperty) ?: return null
        val kilograms = properties.notionNumber(config.weightProperty) ?: return null
        return notionWeightMeasurementOrNull(measuredAt, kilograms).also { measurement ->
            if (measurement == null) {
                Log.w(NOTION_SYNC_LOG_TAG, "Health Connectの許容範囲外のNotion体重レコードをスキップしました")
            }
        }
    }

    private fun NotionMeasurementPage.toVitalMeasurement(): VitalMeasurement? {
        val measuredAt = properties.notionInstant(config.vitalsMeasuredAtProperty) ?: return null
        val systolic = properties.notionNumber(config.systolicProperty) ?: return null
        val diastolic = properties.notionNumber(config.diastolicProperty) ?: return null
        return VitalMeasurement(
            measuredAt = measuredAt,
            systolic = systolic,
            diastolic = diastolic,
            heartRate = properties.notionNumber(config.heartRateProperty)?.toLong()
        )
    }

    private fun NotionMeasurementPage.toHealthConnectVitalMeasurement(): VitalMeasurement? {
        val measuredAt = properties.notionInstant(config.vitalsMeasuredAtProperty) ?: return null
        val systolic = properties.notionNumber(config.systolicProperty) ?: return null
        val diastolic = properties.notionNumber(config.diastolicProperty) ?: return null
        val heartRate = properties.notionNumber(config.heartRateProperty)
        return notionVitalMeasurementOrNull(measuredAt, systolic, diastolic, heartRate).also { measurement ->
            if (measurement == null) {
                Log.w(NOTION_SYNC_LOG_TAG, "Health Connectへ安全に書き込めないNotionバイタルレコードをスキップしました")
            }
        }
    }

    private fun readMeasurementTimes(
        dataSourceId: String,
        dateProperty: String,
        lookbackDays: Long
    ): Set<Instant> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val windowStart = today.minusDays(lookbackDays).atStartOfDay(zone).toInstant().toNotionDateTime()
        val windowEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toNotionDateTime()
        val measurementTimes = mutableSetOf<Instant>()
        var cursor: String? = null

        do {
            val body = JSONObject()
                .put(
                    "filter",
                    JSONObject().put(
                        "and",
                        JSONArray()
                            .put(JSONObject().put("property", dateProperty).put("date", JSONObject().put("on_or_after", windowStart)))
                            .put(JSONObject().put("property", dateProperty).put("date", JSONObject().put("before", windowEnd)))
                    )
                )
                .put("page_size", 100)
            cursor?.let { body.put("start_cursor", it) }

            val response = request(
                "POST",
                "https://api.notion.com/v1/data_sources/${validDataSourceId(dataSourceId)}/query",
                body
            )
            ensureCompleteQuery(response)
            val results = response.optJSONArray("results") ?: JSONArray()
            for (i in 0 until results.length()) {
                results.optJSONObject(i)
                    ?.optJSONObject("properties")
                    ?.optJSONObject(dateProperty)
                    ?.optJSONObject("date")
                    ?.optString("start")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?.let { measurementTimes.add(it) }
            }
            cursor = response.optString("next_cursor").takeIf {
                response.optBoolean("has_more") && it.isNotBlank()
            }
        } while (cursor != null)

        return measurementTimes
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
        ensureCompleteQuery(response)
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
            ensureCompleteQuery(response)
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
                val steps = page
                    .optJSONObject("properties")
                    ?.notionNumber(config.stepsProperty)
                    ?.toLong()
                val existing = pages[value.date]
                if (existing == null || notionTimestampSortValue(existing.recordedAt) < notionTimestampSortValue(value.timestamp)) {
                    pages[value.date] = NotionStepPage(id = pageId, recordedAt = value.timestamp, steps = steps)
                }
            }
            cursor = response.optString("next_cursor").takeIf {
                response.optBoolean("has_more") && it.isNotBlank()
            }
        } while (cursor != null)

        return pages
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

    private fun ensureCompleteQuery(response: JSONObject) {
        val requestStatus = response.optJSONObject("request_status") ?: return
        val type = requestStatus.optString("type")
        val reason = requestStatus.optString("incomplete_reason")
        incompleteQueryError(type, reason)?.let { throw IllegalStateException(it) }
    }

    private fun request(method: String, endpoint: String, body: JSONObject? = null): JSONObject {
        repeat(NOTION_MAX_REQUEST_ATTEMPTS) { attempt ->
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 30_000
                doInput = true
                doOutput = body != null
                setRequestProperty("Authorization", "Bearer ${config.token}")
                setRequestProperty("Notion-Version", NOTION_API_VERSION)
                if (body != null) {
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) {
                    OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
                }.orEmpty()
                if (status in 200..299) {
                    return if (text.isBlank()) JSONObject() else JSONObject(text)
                }

                val errorBody = runCatching { JSONObject(text) }.getOrNull()
                val delayMillis = notionRetryDelayMillis(
                    status = status,
                    retryAfterSeconds = connection.getHeaderField("Retry-After"),
                    completedAttempts = attempt
                )
                if (delayMillis != null && attempt < NOTION_MAX_REQUEST_ATTEMPTS - 1) {
                    Thread.sleep(delayMillis)
                    return@repeat
                }

                throw NotionRequestException(
                    status = status,
                    notionCode = errorBody?.optString("code"),
                    notionMessage = errorBody?.optString("message"),
                    requestId = errorBody?.optString("request_id")
                )
            } finally {
                connection.disconnect()
            }
        }
        error("Notion API request exhausted retries")
    }
}

internal fun notionDatabaseUrl(databaseId: String): String {
    val normalized = databaseId.trim().replace("-", "")
    require(NOTION_DATABASE_ID_PATTERN.matches(normalized)) {
        "NotionデータベースIDの形式が正しくありません。"
    }
    return "https://www.notion.so/$normalized"
}

internal fun incompleteQueryError(type: String?, reason: String?): String? {
    if (type != "incomplete") {
        return null
    }
    val detail = reason?.takeIf { it.isNotBlank() } ?: "unknown"
    return "Notion APIの検索結果が不完全です($detail)。同期範囲を短くしてください。"
}

internal fun notionRetryDelayMillis(
    status: Int,
    retryAfterSeconds: String?,
    completedAttempts: Int
): Long? {
    if (status !in NOTION_RETRYABLE_STATUS_CODES) {
        return null
    }
    val retryAfterMillis = retryAfterSeconds
        ?.trim()
        ?.toLongOrNull()
        ?.coerceAtLeast(0L)
        ?.times(1_000L)
    return retryAfterMillis?.coerceAtMost(NOTION_MAX_RETRY_DELAY_MILLIS)
        ?: (NOTION_INITIAL_RETRY_DELAY_MILLIS shl completedAttempts)
            .coerceAtMost(NOTION_MAX_RETRY_DELAY_MILLIS)
}

private fun JSONObject.notionNumber(property: String): Double? =
    optJSONObject(property)
        ?.takeIf { !it.isNull("number") }
        ?.optDouble("number")

private fun JSONObject.notionInstant(property: String): Instant? =
    optJSONObject(property)
        ?.optJSONObject("date")
        ?.optString("start")
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Instant.parse(it) }.getOrNull() }

internal fun Instant.toNotionDateTime(): String =
    DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(atZone(ZoneId.systemDefault()))

internal fun String.toNotionDateValue(): NotionDateValue =
    NotionDateValue(
        date = LocalDate.parse(take(10)),
        timestamp = runCatching { Instant.parse(this) }.getOrNull()
    )

internal fun notionTimestampSortValue(timestamp: Instant?): Instant =
    timestamp ?: Instant.EPOCH

private const val AUTO_SYNC_WORK_NAME = "health_notion_auto_sync"
private const val AUTO_SYNC_TIME_KEY = "autoSyncTime"
private const val AUTO_SYNC_SCHEDULE_VERSION_KEY = "autoSyncScheduleVersion"
private const val AUTO_SYNC_SCHEDULE_VERSION = 2
private const val AUTO_SYNC_OFF = "off"
private const val AUTO_SYNC_RESULT_KEY = "autoSyncResult"
private const val AUTO_SYNC_LAST_SUCCESS_AT_KEY = "autoSyncLastSuccessAt"
private const val AUTO_SYNC_LAST_FAILURE_AT_KEY = "autoSyncLastFailureAt"
private const val AUTO_SYNC_FAILURE_REASON_KEY = "autoSyncFailureReason"
private const val AUTO_SYNC_RESULT_DETAILS_KEY = "autoSyncResultDetails"
private const val STEPS_DIRECTION_KEY = "stepsSyncDirection"
private const val VITALS_DIRECTION_KEY = "vitalsSyncDirection"
private const val WEIGHT_DIRECTION_KEY = "weightSyncDirection"
private const val UI_MODE_KEY = "uiMode"
private const val DEFAULT_LOOKBACK_DAYS = 30L
private const val NOTION_API_VERSION = "2026-03-11"
private const val NOTION_MAX_REQUEST_ATTEMPTS = 3
private const val NOTION_INITIAL_RETRY_DELAY_MILLIS = 500L
private const val NOTION_MAX_RETRY_DELAY_MILLIS = 60_000L
private const val NOTION_MAX_MEASUREMENT_QUERY_PAGES = 100
private const val NOTION_MAX_MEASUREMENT_ROWS = 10_000
private const val NOTION_SYNC_LOG_TAG = "NotionSync"
private const val OPERATION_COMPLETED_TONE_DURATION_MS = 180
private val NOTION_RETRYABLE_STATUS_CODES = setOf(409, 429, 500, 502, 503, 504, 529)
private val NOTION_DATABASE_ID_PATTERN = Regex("^[A-Za-z0-9]{32}$")
private const val MANUAL_VITAL_FIELD_COUNT = 3
private const val MIN_MANUAL_VITAL_DIGITS_PER_COMPACT_VALUE = 2
private const val MAX_MANUAL_VITAL_DIGITS_PER_COMPACT_VALUE = 3
private val MANUAL_WEIGHT_PATTERN = Regex("""\d+(?:\.\d)?""")
private val STEPS_SYNC_REQUIRED_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(StepsRecord::class)
)
private val STEPS_WRITE_REQUIRED_PERMISSIONS = setOf(
    HealthPermission.getWritePermission(StepsRecord::class),
    HealthPermission.getReadPermission(StepsRecord::class)
)
private val VITALS_SYNC_REQUIRED_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(BloodPressureRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class)
)
private val WEIGHT_SYNC_REQUIRED_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(WeightRecord::class)
)
private val VITALS_WRITE_REQUIRED_PERMISSIONS = setOf(
    HealthPermission.getWritePermission(BloodPressureRecord::class),
    HealthPermission.getReadPermission(BloodPressureRecord::class),
    HealthPermission.getWritePermission(HeartRateRecord::class)
)
private val WEIGHT_WRITE_REQUIRED_PERMISSIONS = setOf(
    HealthPermission.getWritePermission(WeightRecord::class),
    HealthPermission.getReadPermission(WeightRecord::class)
)
private val MANUAL_VITAL_REQUIRED_PERMISSIONS = setOf(
    HealthPermission.getWritePermission(BloodPressureRecord::class),
    HealthPermission.getReadPermission(BloodPressureRecord::class),
    HealthPermission.getWritePermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class)
)
private val MANUAL_WEIGHT_REQUIRED_PERMISSIONS = setOf(
    HealthPermission.getWritePermission(WeightRecord::class),
    HealthPermission.getReadPermission(WeightRecord::class)
)

private fun healthPermissionTargets(permissions: Set<String>): List<String> = buildList {
    if (permissions.any { it in STEPS_SYNC_REQUIRED_PERMISSIONS || it in STEPS_WRITE_REQUIRED_PERMISSIONS }) add("歩数")
    if (permissions.any {
            it in VITALS_SYNC_REQUIRED_PERMISSIONS ||
                it in VITALS_WRITE_REQUIRED_PERMISSIONS ||
                it in MANUAL_VITAL_REQUIRED_PERMISSIONS
        }
    ) add("バイタル")
    if (permissions.any {
            it in WEIGHT_SYNC_REQUIRED_PERMISSIONS ||
                it in WEIGHT_WRITE_REQUIRED_PERMISSIONS ||
                it in MANUAL_WEIGHT_REQUIRED_PERMISSIONS
        }
    ) add("体重")
    if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in permissions) add("バックグラウンド")
}.distinct()

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
private const val UPDATE_NOTICE_TAG = "UpdateNotice"
private const val GITHUB_RELEASES_PAGE_URL =
    "https://github.com/BiaHoi-BaChien/sync-health-notion/releases"
private const val GITHUB_RELEASES_ENDPOINT =
    "https://api.github.com/repos/BiaHoi-BaChien/sync-health-notion/releases?per_page=20"
private const val MANUAL_PAGE_URL =
    "https://clb-biahoi.net/manual-health-notion-sync.html"

private object GitHubReleaseClient {
    fun latestRelease(): AppRelease? {
        val releases = JSONArray(get(GITHUB_RELEASES_ENDPOINT))
        return (0 until releases.length())
            .asSequence()
            .mapNotNull { index -> releases.optJSONObject(index) }
            .filterNot { it.optBoolean("draft") }
            .mapNotNull { release ->
                val version = release.optString("tag_name")
                    .toSemanticVersion()
                    ?: return@mapNotNull null
                AppRelease(version = version)
            }
            .maxByOrNull { it.version }
    }

    private fun get(endpoint: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            doInput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Health-Notion-Sync")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() } }.orEmpty()
        if (status !in 200..299) {
            throw IllegalStateException("GitHub Releases request failed with HTTP $status")
        }
        return text
    }
}

private data class AppRelease(
    val version: SemanticVersion
)

internal class NotionSyncDataException(message: String) : RuntimeException(message)

private class NotionRequestException(
    private val status: Int,
    private val notionCode: String?,
    private val notionMessage: String?,
    private val requestId: String?
) : RuntimeException("Notion API request failed with HTTP $status") {
    val isRetryable: Boolean = status in NOTION_RETRYABLE_STATUS_CODES

    val userMessage: String = buildString {
        append(
            when (status) {
                401 -> "Notion API Tokenが無効です"
                403 -> "Notion API Tokenに必要な権限がありません"
                404 -> "Data Sourceが見つからないか、Tokenに共有されていません"
                429, 529 -> "Notion APIが混雑しています。時間を置いて再実行してください"
                else -> "Notion APIのリクエストに失敗しました(HTTP $status)"
            }
        )
        val publicMessage = notionMessage
            ?.takeIf { it.isNotBlank() }
            ?.take(120)
        if (publicMessage != null) {
            append(": ")
            append(publicMessage)
        }
        notionCode?.takeIf { it.isNotBlank() }?.let {
            append(" [")
            append(it)
            append("]")
        }
        requestId?.takeIf { it.isNotBlank() }?.let {
            append(" request_id=")
            append(it)
        }
    }
}
