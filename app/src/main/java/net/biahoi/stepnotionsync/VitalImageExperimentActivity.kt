package net.biahoi.stepnotionsync

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
import kotlinx.coroutines.launch

class VitalImageExperimentActivity : ComponentActivity() {
    private val model: NanoVitalViewModel by viewModels()
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var preview: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var pickButton: Button
    private lateinit var registerButton: Button
    private lateinit var applyButton: Button
    private lateinit var detailsButton: Button
    private lateinit var detailsText: TextView
    private var detailsExpanded = false
    private var sourceDialog: AlertDialog? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!model.imageInput.complete(VitalImageSource.PICKER)) {
            model.reportInterruptedInput()
            return@registerForActivityResult
        }
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { model.readImage(it) }
        }
        render(model.state.value)
    }
    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (model.imageInput.complete(VitalImageSource.CAMERA)) {
            model.finishCameraCapture(success)
        } else {
            model.discardCapture()
            model.reportInterruptedInput()
        }
        render(model.state.value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VitalCaptureFiles.cleanOnProcessStart(cacheDir)
        // Keep the private image and candidates out of screenshots and recent-app previews.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        model.imageInput.open(restoredActivity = savedInstanceState != null)
        detailsExpanded = savedInstanceState?.getBoolean("details_expanded") ?: false
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
        text("画像から入力", 22f).typeface = Typeface.DEFAULT_BOLD
        text("選択・撮影した血圧計の画像を端末内のGemini Nanoで読み取ります。画像はクラウドに送信しません。")
        statusText = text("")
        progress = ProgressBar(this).also { content.addView(it) }
        pickButton = button("画像を選択・撮影する") {
            model.imageInput.chooseSource = true
            showSourceDialog()
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
        text("画像と読み取り値を確認してください。3項目が正しければそのままHealth Connectに登録できます。不明な項目や修正がある場合は入力画面へ進んでください。測定日時は登録時刻です。")
        registerButton = button("この値で登録") {
            finishExperiment(applyCandidates = true, registerImmediately = true)
        }
        applyButton = button("入力画面で修正する") {
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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("details_expanded", detailsExpanded)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        sourceDialog?.dismiss()
        sourceDialog = null
        super.onDestroy()
    }

    private fun showSourceDialog() {
        if (sourceDialog != null || isFinishing) return
        sourceDialog = AlertDialog.Builder(this)
            .setTitle("画像から入力")
            .setItems(arrayOf("画像を選択", "カメラで撮影")) { _, index ->
                launchImageSource(if (index == 0) VitalImageSource.PICKER else VitalImageSource.CAMERA)
            }
            .setNegativeButton("キャンセル") { _, _ -> model.imageInput.chooseSource = false }
            .setOnCancelListener { model.imageInput.chooseSource = false }
            .create().apply {
                setOnDismissListener { sourceDialog = null }
                show()
            }
    }

    private fun launchImageSource(source: VitalImageSource) {
        model.imageInput.begin(source)
        try {
            when (source) {
                VitalImageSource.PICKER -> picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                })
                VitalImageSource.CAMERA -> camera.launch(model.createCameraCapture())
            }
        } catch (_: ActivityNotFoundException) {
            model.reportInputError(source)
        } catch (_: SecurityException) {
            model.reportInputError(source)
        } catch (_: java.io.IOException) {
            model.reportInputError(source)
        } catch (_: IllegalArgumentException) {
            model.reportInputError(source)
        } catch (_: IllegalStateException) {
            model.reportInputError(source)
        }
    }

    private fun finishExperiment(
        applyCandidates: Boolean,
        openSettings: Boolean = false,
        registerImmediately: Boolean = false,
    ) {
        if (isFinishing) return
        val state = model.state.value
        val values = when {
            registerImmediately -> state.registrationValues()
            applyCandidates -> state.candidateValues()
            else -> null
        }
        if (applyCandidates && (values == null || model.imageInput.pendingSource != null)) return
        val data = Intent().apply {
            putExtra(EXTRA_REGISTER_IMMEDIATELY, registerImmediately)
            putExtra(EXTRA_OPEN_SETTINGS, openSettings)
            if (values != null) {
                putStringArrayListExtra(EXTRA_VALUES, values)
            }
        }
        setResult(if (values != null) Activity.RESULT_OK else Activity.RESULT_CANCELED, data)
        finish()
    }

    private fun render(state: NanoVitalState) {
        if (isFinishing) return
        if (state.imageDestination() == NanoImageDestination.SETTINGS) {
            finishExperiment(applyCandidates = false, openSettings = true)
            return
        }
        if (state.busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        statusText.text = if (model.imageInput.interrupted) {
            "画像入力が中断されました。画像を再選択するか、もう一度撮影してください。\n${state.message}"
        } else state.message
        progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        pickButton.isEnabled = state.imageDestination() == NanoImageDestination.IMAGE && model.imageInput.pendingSource == null
        if (pickButton.isEnabled && model.imageInput.chooseSource) showSourceDialog()
        preview.setImageBitmap(state.bitmap)
        preview.visibility = if (state.bitmap == null) View.GONE else View.VISIBLE
        resultText.text = if (state.readCompleted) {
            "最高血圧: ${state.reading?.systolic ?: "不明"} mmHg\n" +
                "最低血圧: ${state.reading?.diastolic ?: "不明"} mmHg\n" +
                "脈拍: ${state.reading?.pulse ?: "不明"} bpm"
        } else ""
        registerButton.isEnabled = state.registrationValues() != null && model.imageInput.pendingSource == null
        applyButton.isEnabled = state.candidateValues() != null && model.imageInput.pendingSource == null
        detailsButton.visibility = if (state.responseDetails.isEmpty()) View.GONE else View.VISIBLE
        detailsButton.text = if (detailsExpanded) "読取の検証詳細を閉じる" else "読取の検証詳細を表示"
        detailsText.text = state.responseDetails
        detailsText.visibility = if (detailsExpanded && state.responseDetails.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        internal const val EXTRA_VALUES = "nano_vital_values"
        internal const val EXTRA_REGISTER_IMMEDIATELY = "nano_vital_register_immediately"
        internal const val EXTRA_OPEN_SETTINGS = "nano_vital_open_settings"
    }
}
