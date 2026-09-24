package net.biahoi.stepnotionsync

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Size
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
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.tasks.TaskCompletionSource
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VitalCameraActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var frozenView: ImageView
    private lateinit var scanFrame: VitalScanFrameView
    private lateinit var statusText: TextView
    private lateinit var readButton: Button
    private lateinit var useButton: Button
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var startingCamera = false
    private var recognizing = false
    private var recognizer: TextRecognizer? = null
    private val recognitionExecutor = Executors.newSingleThreadExecutor()
    private var capturedFrame: Bitmap? = null
    private var reading: VitalCameraReading? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (previewView.display?.displayId == displayId) updateCaptureRotation()
        }
    }

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
        root.addView(label("上から最高血圧・最低血圧・脈拍を合わせてください。四隅で傾き、区切り線で高さ、左右の点で各行の幅を調整できます。数字に余白を残し、液晶の縁・日付・時刻は外してください。", 14f))
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        frozenView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        scanFrame = VitalScanFrameView(this)
        root.addView(FrameLayout(this).apply {
            addView(previewView, FrameLayout.LayoutParams(-1, -1))
            addView(frozenView, FrameLayout.LayoutParams(-1, -1))
            addView(scanFrame, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, dp(240), 1f).apply { topMargin = dp(12) })
        statusText = label("カメラを準備しています…", 16f).apply {
            // Keep the preview/viewport stable between focusing, capture and results.
            minLines = 5
            maxLines = 5
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
                    else -> captureFrame()
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
                if (state == PreviewView.StreamState.STREAMING) statusText.text = "3つの枠を合わせて「読み取る」を押してください。ピントを合わせて撮影します。"
            }
        }
        previewView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                if (!recognizing && capturedFrame == null) {
                    unbindCamera()
                    startCamera()
                }
            }
        }
        if (hasCameraPermission()) startCamera()
        else if (savedInstanceState == null) permissionLauncher.launch(Manifest.permission.CAMERA)
        else showPermissionError()
    }

    override fun onStart() {
        super.onStart()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, null)
    }

    override fun onResume() {
        super.onResume()
        updateCaptureRotation()
        if (hasCameraPermission()) startCamera()
    }

    override fun onStop() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        super.onStop()
    }

    private fun updateCaptureRotation() {
        // A 180-degree display rotation does not recreate the Activity or resize the view.
        // PreviewView follows it automatically; ImageCapture must be updated explicitly.
        previewView.display?.let { imageCapture?.targetRotation = it.rotation }
    }

    private fun startCamera() {
        if (preview != null || startingCamera || !hasCameraPermission() || isDestroyed || isFinishing) return
        if (previewView.viewPort == null) return
        startingCamera = true
        readButton.isEnabled = false
        readButton.text = "読み取る"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            startingCamera = false
            if (isDestroyed || isFinishing || !hasCameraPermission()) return@addListener
            try {
                val cameraProvider = future.get()
                val viewport = previewView.viewPort ?: return@addListener
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    statusText.text = "背面カメラが見つかりません。戻って手入力または音声入力をご利用ください。"
                    return@addListener
                }
                val cameraPreview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    .setTargetRotation(previewView.display.rotation)
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build())
                    .build()
                val group = UseCaseGroup.Builder().setViewPort(viewport)
                    .addUseCase(cameraPreview).addUseCase(capture).build()
                val boundCamera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group)
                provider = cameraProvider
                preview = cameraPreview
                camera = boundCamera
                imageCapture = capture
                boundCamera.cameraInfo.cameraState.observe(this) { state ->
                    if (preview === cameraPreview && state.error != null) {
                        unbindCamera()
                        if (!recognizing && capturedFrame == null) showCameraError()
                    }
                }
            } catch (_: Exception) {
                showCameraError()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureFrame() {
        if (recognizing) return
        val boundCamera = camera ?: return
        val capture = imageCapture ?: return
        val guide = scanFrame.layout?.takeIf { it.isValid() } ?: return
        recognizing = true
        readButton.isEnabled = false
        useButton.isEnabled = false
        scanFrame.isEnabled = false
        scanFrame.invalidate()
        reading = null
        statusText.text = "ピント調整中です。スマホを動かさないでください。"
        val center = guide.corners.let { points ->
            previewView.meteringPointFactory.createPoint(points.map { it.x }.average().toFloat() * previewView.width,
                points.map { it.y }.average().toFloat() * previewView.height, 0.25f)
        }
        val action = FocusMeteringAction.Builder(center,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
            .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        try {
            if (!boundCamera.cameraInfo.isFocusMeteringSupported(action)) {
                takePicture(capture, guide)
                return
            }
            val future = boundCamera.cameraControl.startFocusAndMetering(action)
            var settled = false
            val timeout = Runnable {
                if (!settled) {
                    settled = true
                    boundCamera.cameraControl.cancelFocusAndMetering()
                    captureFailed("ピントを合わせられませんでした。少し離して読み取り直してください。")
                }
            }
            previewView.postDelayed(timeout, 3500)
            future.addListener({
                if (!settled) {
                    settled = true
                    previewView.removeCallbacks(timeout)
                    val focused = runCatching { future.get().isFocusSuccessful }.getOrDefault(false)
                    when {
                        isDestroyed || isFinishing -> finishRecognition()
                        focused -> takePicture(capture, guide)
                        else -> captureFailed("ピントを合わせられませんでした。少し離して読み取り直してください。")
                    }
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {
            captureFailed("撮影の準備に失敗しました。読み取り直してください。")
        }
    }

    private fun takePicture(capture: ImageCapture, guide: VitalScanLayout) {
        if (isDestroyed || isFinishing) { finishRecognition(); return }
        statusText.text = "撮影しています。スマホを動かさずにお待ちください。"
        try {
            // Also cover a rotation notification still queued while focus was settling.
            updateCaptureRotation()
            capture.takePicture(recognitionExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = try { capturedVitalBitmap(image) } catch (_: Exception) { null } finally { image.close() }
                    runOnUiThread {
                        when {
                            isDestroyed || isFinishing -> { bitmap?.recycle(); finishRecognition() }
                            bitmap == null -> captureFailed("撮影画像を取得できませんでした。読み取り直してください。")
                            else -> recognizeFrame(bitmap, guide)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread { captureFailed("撮影に失敗しました。読み取り直してください。") }
                }
            })
        } catch (_: Exception) {
            captureFailed("撮影に失敗しました。読み取り直してください。")
        }
    }

    private fun recognizeFrame(bitmap: Bitmap, guide: VitalScanLayout) {
        capturedFrame = bitmap
        frozenView.setImageBitmap(bitmap)
        frozenView.visibility = View.VISIBLE
        statusText.text = "3つの枠を個別に読み取っています…"
        try {
            val textRecognizer = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
            val completion = TaskCompletionSource<VitalCameraReading?>()
            recognitionExecutor.execute {
                try {
                    val rows = cropVitalRows(bitmap, guide)
                    val values = try {
                        rows.map { row ->
                            // Await on the worker only. Keep pixels alive until ML Kit completes.
                            val result = runCatching { Tasks.await(textRecognizer.process(InputImage.fromBitmap(row, 0))) }.getOrNull()
                            val elements = result?.textBlocks?.flatMap { it.lines }?.flatMap { it.elements }.orEmpty()
                            if (elements.any { it.boundingBox == null }) return@map null
                            val positioned = elements.map { element ->
                                val box = checkNotNull(element.boundingBox)
                                VitalOcrElement(element.text, box.left, box.top, box.right, box.bottom)
                            }
                            selectFramedVitalCameraNumber(positioned, row.width, row.height, readVitalRowPixels(row))
                        }
                    } finally {
                        rows.forEach { it.recycle() }
                    }
                    completion.setResult(if (values.any { it == null }) null else vitalReadingFromNumbers(values.filterNotNull()))
                } catch (error: Exception) {
                    completion.setException(error)
                }
            }
            completion.task
                .addOnSuccessListener { result ->
                    if (isDestroyed || isFinishing) return@addOnSuccessListener
                    reading = result
                    val value = reading
                    statusText.text = if (value == null) {
                        "読み取れませんでした。反射を避け、数字と枠・区切り線の間に余白を残してください。"
                    } else {
                        "最高血圧 ${value.systolic}\n最低血圧 ${value.diastolic}\n脈拍 ${value.heartRate}\n表示と一致するか確認してください。"
                    }
                    useButton.isEnabled = value != null
                }
                .addOnFailureListener {
                    if (!isDestroyed && !isFinishing) statusText.text = "読み取りに失敗しました。読み取り直すか、戻って手入力してください。"
                }
                .addOnCompleteListener {
                    finishRecognition()
                }
        } catch (_: Exception) {
            statusText.text = "文字認識を起動できません。戻って手入力または音声入力をご利用ください。"
            finishRecognition()
        }
    }

    private fun finishRecognition() {
        recognizing = false
        if (isDestroyed || isFinishing) {
            capturedFrame?.recycle()
            capturedFrame = null
            recognizer?.close()
            recognitionExecutor.shutdown()
            return
        }
        scanFrame.isEnabled = capturedFrame == null
        scanFrame.invalidate()
        readButton.text = if (capturedFrame == null) "読み取る" else "読み取り直す"
        readButton.isEnabled = true
    }

    private fun captureFailed(message: String) {
        if (!isDestroyed && !isFinishing) statusText.text = message
        finishRecognition()
    }

    private fun resetReading() {
        reading = null
        useButton.isEnabled = false
        frozenView.setImageDrawable(null)
        frozenView.visibility = View.GONE
        capturedFrame?.recycle()
        capturedFrame = null
        scanFrame.isEnabled = true
        scanFrame.invalidate()
        if (preview == null) {
            showCameraError()
            return
        }
        statusText.text = "3つの枠を合わせて「読み取る」を押してください。ピントを合わせて撮影します。"
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
        unbindCamera()
        frozenView.setImageDrawable(null)
        if (!recognizing) {
            capturedFrame?.recycle()
            capturedFrame = null
            recognizer?.close()
            recognitionExecutor.shutdown()
        }
        super.onDestroy()
    }

    private fun unbindCamera() {
        val useCases = listOfNotNull(preview, imageCapture).toTypedArray()
        camera?.cameraControl?.cancelFocusAndMetering()
        preview = null
        imageCapture = null
        camera = null
        if (useCases.isNotEmpty()) provider?.unbind(*useCases)
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
