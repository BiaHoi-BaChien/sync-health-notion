package net.biahoi.stepnotionsync

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.launch

class VitalImageExperimentActivity : ComponentActivity() {
    private val model: NanoVitalViewModel by viewModels()
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var preview: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var checkButton: Button
    private lateinit var downloadButton: Button
    private lateinit var pickButton: Button
    private lateinit var applyButton: Button
    private lateinit var detailsButton: Button
    private lateinit var detailsText: TextView
    private var detailsExpanded = false
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let(model::readImage)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the private image and candidates out of screenshots and recent-app previews.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model.continueTrial(intent.getLongExtra(EXTRA_STARTED_AT, 0), intent.getIntExtra(EXTRA_ATTEMPTS, 0))
        onBackPressedDispatcher.addCallback(this) { finishExperiment(applyCandidates = false) }
        val padding = dp(18)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.parseColor("#17232D"))
        }
        fun text(value: String, size: Float = 15f): TextView = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(6))
            content.addView(this)
        }
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnClickListener { action() }
        }
        text("画像から入力（検証）", 22f).typeface = Typeface.DEFAULT_BOLD
        text("${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        text("保存画像1枚を端末内のGemini Nanoで読み取ります。画像はクラウドに送信しません。モデルの準備には通信が必要です。")
        statusText = text("")
        progress = ProgressBar(this).also { content.addView(it) }
        checkButton = button("端末・モデル状態を再確認") { model.checkStatus() }
        downloadButton = button("モデルをダウンロード（通信あり）") { model.download() }
        pickButton = button("端末内の画像を1枚選んで読み取る") {
            try {
                model.beginSelection()
                picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                })
            } catch (_: ActivityNotFoundException) {
                model.reportPickerError()
            }
        }
        preview = ImageView(this).apply {
            contentDescription = "選択した血圧計画像"
            adjustViewBounds = true
            maxHeight = dp(320)
            scaleType = ImageView.ScaleType.FIT_CENTER
            content.addView(this, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        resultText = text("")
        detailsButton = button("読取の検証詳細を表示") {
            detailsExpanded = !detailsExpanded
            render(model.state.value)
        }
        detailsText = text("")
        text("候補は本人が確認・修正してください。この画面では登録しません。測定日時は既存の入力と同じく登録時刻です。")
        applyButton = button("候補を確認・修正する") {
            finishExperiment(applyCandidates = true)
        }
        button("戻る") { finishExperiment(applyCandidates = false) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect(::render)
            }
        }
    }

    private fun finishExperiment(applyCandidates: Boolean) {
        val state = model.state.value
        val values = if (applyCandidates) state.candidateValues() else null
        val data = Intent().apply {
            putExtra(EXTRA_STARTED_AT, state.startedAt)
            putExtra(EXTRA_PROCESSING_MILLIS, state.processingMillis ?: -1L)
            putExtra(EXTRA_ATTEMPTS, state.attempts)
            if (values != null) {
                putStringArrayListExtra(EXTRA_VALUES, values)
            }
        }
        setResult(if (values != null) Activity.RESULT_OK else Activity.RESULT_CANCELED, data)
        finish()
    }

    private fun render(state: NanoVitalState) {
        if (state.busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val status = when (state.status) {
            FeatureStatus.AVAILABLE -> "AVAILABLE（利用可能）"
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE（ダウンロード可能）"
            FeatureStatus.DOWNLOADING -> "DOWNLOADING（準備中）"
            FeatureStatus.UNAVAILABLE -> "UNAVAILABLE（現在利用不可）"
            else -> "未確認"
        }
        statusText.text = "API状態（最終確認）: $status\nモデル: ${state.modelName}\n${state.message}"
        progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        checkButton.isEnabled = !state.busy
        downloadButton.visibility = if (state.status == FeatureStatus.DOWNLOADABLE) View.VISIBLE else View.GONE
        downloadButton.isEnabled = !state.busy
        pickButton.isEnabled = !state.busy && state.status == FeatureStatus.AVAILABLE
        preview.setImageBitmap(state.bitmap)
        preview.visibility = if (state.bitmap == null) View.GONE else View.VISIBLE
        resultText.text = state.processingMillis?.let { elapsed ->
            "最高血圧: ${state.reading?.systolic ?: "不明"} mmHg\n" +
                "最低血圧: ${state.reading?.diastolic ?: "不明"} mmHg\n" +
                "脈拍: ${state.reading?.pulse ?: "不明"} bpm\n" +
                "処理時間: ${elapsedSeconds(elapsed)}（状態確認・画像展開・推論、試行${state.attempts}回目）"
        }.orEmpty()
        applyButton.isEnabled = state.candidateValues() != null
        detailsButton.visibility = if (state.responseDetails.isEmpty()) View.GONE else View.VISIBLE
        detailsButton.text = if (detailsExpanded) "読取の検証詳細を閉じる" else "読取の検証詳細を表示"
        detailsText.text = state.responseDetails
        detailsText.visibility = if (detailsExpanded && state.responseDetails.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        internal const val EXTRA_VALUES = "nano_vital_values"
        internal const val EXTRA_STARTED_AT = "nano_vital_started_at"
        internal const val EXTRA_PROCESSING_MILLIS = "nano_vital_processing_millis"
        internal const val EXTRA_ATTEMPTS = "nano_vital_attempts"
    }
}
