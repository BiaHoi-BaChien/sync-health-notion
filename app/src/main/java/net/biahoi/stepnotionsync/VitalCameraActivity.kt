package net.biahoi.stepnotionsync

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class VitalCameraActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var frozenView: ImageView
    private lateinit var statusText: TextView
    private lateinit var readButton: Button
    private lateinit var useButton: Button
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var startingCamera = false
    private var recognizing = false
    private var recognizer: TextRecognizer? = null
    private var capturedFrame: Bitmap? = null
    private var reading: VitalCameraReading? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else showPermissionError()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#17232D"))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(12) + bars.bottom)
            insets
        }
        root.addView(label("カメラでバイタルを入力", 20f))
        root.addView(label("最高血圧・最低血圧・脈拍の数字を、上から順に枠内へ合わせてください。日付や時刻は枠から外してください。", 14f))
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        frozenView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(frozenView, FrameLayout.LayoutParams(-1, -1))
            addView(ScanFrameView(this@VitalCameraActivity), FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, dp(240), 1f).apply { topMargin = dp(12) })
        statusText = label("カメラを準備しています…", 16f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(statusText)
        readButton = Button(this).apply {
            text = "読み取る"
            isEnabled = false
            setOnClickListener {
                when {
                    !hasCameraPermission() -> {
                        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        } else {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }
                    }
                    capturedFrame != null -> resetReading()
                    preview == null -> startCamera()
                    else -> recognizeFrame()
                }
            }
        }
        root.addView(readButton, LinearLayout.LayoutParams(-1, -2))
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(this@VitalCameraActivity).apply {
                text = "キャンセル"
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            useButton = Button(this@VitalCameraActivity).apply {
                text = "入力欄に反映"
                isEnabled = false
                setOnClickListener {
                    val result = reading ?: return@setOnClickListener
                    setResult(Activity.RESULT_OK, Intent().apply {
                        putExtra(EXTRA_SYSTOLIC, result.systolic)
                        putExtra(EXTRA_DIASTOLIC, result.diastolic)
                        putExtra(EXTRA_HEART_RATE, result.heartRate)
                    })
                    finish()
                }
            }
            addView(useButton, LinearLayout.LayoutParams(0, -2, 1f))
        })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#17232D"))
            addView(root)
        })
        previewView.previewStreamState.observe(this) { state ->
            if (preview != null && hasCameraPermission() && !recognizing && capturedFrame == null) {
                readButton.isEnabled = state == PreviewView.StreamState.STREAMING
                if (state == PreviewView.StreamState.STREAMING) statusText.text = "数字にピントが合ったら「読み取る」を押してください。"
            }
        }
        if (hasCameraPermission()) startCamera()
        else if (savedInstanceState == null) permissionLauncher.launch(Manifest.permission.CAMERA)
        else showPermissionError()
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) startCamera()
    }

    private fun startCamera() {
        if (preview != null || startingCamera || !hasCameraPermission()) return
        startingCamera = true
        readButton.isEnabled = false
        readButton.text = "読み取る"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            startingCamera = false
            if (isDestroyed || isFinishing || !hasCameraPermission()) return@addListener
            try {
                val cameraProvider = future.get()
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    statusText.text = "背面カメラが見つかりません。戻って手入力または音声入力をご利用ください。"
                    return@addListener
                }
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview)
                provider = cameraProvider
                preview = cameraPreview
                camera.cameraInfo.cameraState.observe(this) { state ->
                    if (preview === cameraPreview && state.error != null) {
                        preview = null
                        cameraProvider.unbind(cameraPreview)
                        if (!recognizing && capturedFrame == null) showCameraError()
                    }
                }
            } catch (_: Exception) {
                showCameraError()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun recognizeFrame() {
        if (recognizing) return
        val bitmap = runCatching { previewView.bitmap }.getOrNull()
        if (bitmap == null) {
            statusText.text = "映像を取得できません。少し待って再試行してください。"
            return
        }
        capturedFrame = bitmap
        frozenView.setImageBitmap(bitmap)
        frozenView.visibility = View.VISIBLE
        val bounds = scanBounds(bitmap.width, bitmap.height)
        val cropped = Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
        recognizing = true
        readButton.isEnabled = false
        useButton.isEnabled = false
        statusText.text = "数字を読み取っています…"
        try {
            val textRecognizer = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
            textRecognizer.process(InputImage.fromBitmap(cropped, 0))
                .addOnSuccessListener { result ->
                    if (isDestroyed || isFinishing) return@addOnSuccessListener
                    val elements = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                    // A numeric element without geometry must not be silently dropped.
                    reading = if (elements.any { it.boundingBox == null }) null else parseVitalCameraReading(
                        elements.map { element ->
                            val box = checkNotNull(element.boundingBox)
                            VitalOcrElement(element.text, box.left, box.top, box.right, box.bottom)
                        }
                    )
                    val value = reading
                    statusText.text = if (value == null) {
                        "3つの数字を確定できませんでした。反射や傾きを避け、数字だけを枠に合わせて読み取り直してください。"
                    } else {
                        "最高血圧 ${value.systolic} / 最低血圧 ${value.diastolic}\n脈拍 ${value.heartRate}\n血圧計の表示と一致することを確認してください。"
                    }
                    useButton.isEnabled = value != null
                }
                .addOnFailureListener {
                    if (!isDestroyed && !isFinishing) statusText.text = "読み取りに失敗しました。読み取り直すか、戻って手入力してください。"
                }
                .addOnCompleteListener {
                    cropped.recycle()
                    if (!isDestroyed && !isFinishing) finishRecognition()
                }
        } catch (_: Exception) {
            cropped.recycle()
            statusText.text = "文字認識を起動できません。戻って手入力または音声入力をご利用ください。"
            finishRecognition()
        }
    }

    private fun finishRecognition() {
        recognizing = false
        readButton.text = "読み取り直す"
        readButton.isEnabled = true
    }

    private fun resetReading() {
        reading = null
        useButton.isEnabled = false
        frozenView.setImageDrawable(null)
        frozenView.visibility = View.GONE
        capturedFrame?.recycle()
        capturedFrame = null
        if (preview == null) {
            showCameraError()
            return
        }
        statusText.text = "数字にピントが合ったら「読み取る」を押してください。"
        readButton.text = "読み取る"
        readButton.isEnabled = previewView.previewStreamState.value == PreviewView.StreamState.STREAMING
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun showCameraError() {
        statusText.text = "カメラを起動できません。他のカメラアプリを閉じて再試行してください。"
        readButton.text = "カメラを再試行"
        readButton.isEnabled = true
    }

    private fun showPermissionError() {
        statusText.text = "カメラ入力にはカメラ権限が必要です。許可するか、戻って手入力または音声入力をご利用ください。"
        readButton.text = if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) "カメラを許可" else "設定を開く"
        readButton.isEnabled = true
    }

    override fun onDestroy() {
        preview?.let { provider?.unbind(it) }
        recognizer?.close()
        frozenView.setImageDrawable(null)
        capturedFrame?.recycle()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
    }

    companion object {
        internal const val EXTRA_SYSTOLIC = "camera_systolic"
        internal const val EXTRA_DIASTOLIC = "camera_diastolic"
        internal const val EXTRA_HEART_RATE = "camera_heart_rate"
    }
}

private fun scanBounds(width: Int, height: Int): Rect =
    Rect((width * 0.20f).toInt(), (height * 0.12f).toInt(), (width * 0.80f).toInt(), (height * 0.88f).toInt())

private class ScanFrameView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bounds = scanBounds(width, height)
        paint.style = Paint.Style.FILL
        paint.color = 0x99000000.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), bounds.top.toFloat(), paint)
        canvas.drawRect(0f, bounds.bottom.toFloat(), width.toFloat(), height.toFloat(), paint)
        canvas.drawRect(0f, bounds.top.toFloat(), bounds.left.toFloat(), bounds.bottom.toFloat(), paint)
        canvas.drawRect(bounds.right.toFloat(), bounds.top.toFloat(), width.toFloat(), bounds.bottom.toFloat(), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * resources.displayMetrics.density
        paint.color = Color.parseColor("#44D7B6")
        canvas.drawRect(bounds, paint)
    }
}
