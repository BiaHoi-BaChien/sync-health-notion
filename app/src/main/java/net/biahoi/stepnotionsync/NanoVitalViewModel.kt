package net.biahoi.stepnotionsync

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

internal enum class NanoImageDestination { WAIT, IMAGE, SETTINGS }

internal data class NanoVitalState(
    val status: Int? = null,
    val busy: Boolean = false,
    val message: String = "端末・モデルの状態を確認します。",
    val modelName: String = "未確認",
    val bitmap: Bitmap? = null,
    val reading: NanoVitalReading? = null,
    val processingMillis: Long? = null,
    val startedAt: Long = 0,
    val attempts: Int = 0,
    val responseDetails: String = "",
) {
    fun imageDestination(): NanoImageDestination = when {
        busy -> NanoImageDestination.WAIT
        status == FeatureStatus.AVAILABLE -> NanoImageDestination.IMAGE
        else -> NanoImageDestination.SETTINGS
    }

    fun statusLabel(): String = when (status) {
        FeatureStatus.AVAILABLE -> "AVAILABLE（利用可能）"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE（ダウンロード可能）"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING（準備中）"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE（現在利用不可）"
        else -> "未確認"
    }

    fun candidateValues(): ArrayList<String>? {
        if (busy || processingMillis == null) return null
        return reading?.inputValues()?.takeIf { values -> values.any { it.isNotEmpty() } }
    }
}

internal class NanoVitalViewModel(application: Application) : AndroidViewModel(application) {
    private val modelDelegate = lazy { Generation.getClient() }
    private val model by modelDelegate
    private val mutableState = MutableStateFlow(NanoVitalState())
    val state = mutableState.asStateFlow()
    val imageInput = VitalImageInputSession()
    private var captureFile: File? = null
    private var captureUri: Uri? = null

    init {
        checkStatus()
    }

    fun checkStatus() = runOperation {
        mutableState.value = state.value.copy(status = null, modelName = "未確認", message = "端末・モデルの状態を確認中…")
        updateStatus()
    }

    private suspend fun updateStatus() {
        val status = withTimeout(30_000) { model.checkStatus() }
        mutableState.value = state.value.copy(status = status, message = when (status) {
            FeatureStatus.AVAILABLE -> "画像入力を利用できます。"
            FeatureStatus.DOWNLOADABLE -> "モデルをダウンロードできます。準備後に画像入力を利用できます。"
            FeatureStatus.DOWNLOADING -> "モデルの準備中です。しばらく待って状態を再確認してください。"
            else -> "この端末では現在利用できません。AICoreの更新・初期化状況を確認してください。"
        })
        if (status == FeatureStatus.AVAILABLE) {
            val name = try {
                withTimeout(10_000) { model.getBaseModelName() }
            } catch (_: TimeoutCancellationException) {
                "取得できませんでした"
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                "取得できませんでした"
            }
            mutableState.value = state.value.copy(modelName = name)
        }
    }

    fun download() = runOperation {
        // Downloading model assets may use the network; no image is supplied here.
        mutableState.value = state.value.copy(message = "モデルのダウンロードを要求しています。準備には時間がかかる場合があります。")
        withTimeout(15 * 60_000L) {
            model.download().collect { progress ->
                val message = when (progress) {
                    is DownloadStatus.DownloadStarted -> "モデルのダウンロードを開始しました。"
                    is DownloadStatus.DownloadProgress ->
                        "モデルをダウンロード中: ${progress.totalBytesDownloaded / (1024 * 1024)} MB"
                    DownloadStatus.DownloadCompleted -> "モデルの準備が完了しました。"
                    is DownloadStatus.DownloadFailed -> throw progress.e
                }
                mutableState.value = state.value.copy(status = FeatureStatus.DOWNLOADING, message = message)
            }
        }
        updateStatus()
    }

    fun beginSelection() {
        if (state.value.startedAt == 0L) {
            mutableState.value = state.value.copy(startedAt = SystemClock.elapsedRealtime())
        }
    }

    fun continueTrial(startedAt: Long, attempts: Int) {
        if (state.value.startedAt == 0L && startedAt > 0) {
            mutableState.value = state.value.copy(startedAt = startedAt, attempts = attempts)
        }
    }

    fun reportInputError(source: VitalImageSource) {
        imageInput.complete(source)
        if (source == VitalImageSource.CAMERA) discardCapture()
        val action = if (source == VitalImageSource.CAMERA) "カメラ" else "画像の選択"
        mutableState.value = state.value.copy(message = "${action}を開始できませんでした。別の方法で画像を入力するか、手入力を利用してください。")
    }

    fun reportInterruptedInput() {
        mutableState.value = state.value.copy(message = "画像入力が中断されました。画像を再選択するか、もう一度撮影してください。")
    }

    fun createCameraCapture(): Uri {
        discardCapture()
        val application = getApplication<Application>()
        val file = VitalCaptureFiles(application.cacheDir).create()
        captureFile = file
        return FileProvider.getUriForFile(application, "${application.packageName}.vital-images", file)
            .also { captureUri = it }
    }

    fun finishCameraCapture(success: Boolean) {
        val uri = captureUri
        if (!success || uri == null) {
            discardCapture()
            return
        }
        revokeCaptureAccess()
        readImage(uri, temporaryCapture = true)
    }

    private fun revokeCaptureAccess() {
        captureUri?.let {
            getApplication<Application>().revokeUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun discardCapture() {
        revokeCaptureAccess()
        captureFile?.delete()
        captureFile = null
        captureUri = null
    }

    fun readImage(uri: Uri, temporaryCapture: Boolean = false) = runOperation {
        // A picker result delivered after process death belongs to an expired trial.
        check(state.value.startedAt > 0)
        mutableState.value = state.value.copy(
            bitmap = null, reading = null, processingMillis = null, responseDetails = "",
            message = "端末内で画像を読み取り中…", attempts = state.value.attempts + 1,
        )
        val started = SystemClock.elapsedRealtime()
        try {
            withTimeout(120_000) {
                mutableState.value = state.value.copy(status = null)
                val currentStatus = model.checkStatus()
                mutableState.value = state.value.copy(status = currentStatus)
                check(currentStatus == FeatureStatus.AVAILABLE)
                val bitmap = withContext(Dispatchers.IO) {
                    require(uri.scheme == "content")
                    val resolver = getApplication<Application>().contentResolver
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                        val width = info.size.width
                        val height = info.size.height
                        require(width > 0 && height > 0 && width.toLong() * height <= 100_000_000L)
                        val ratio = minOf(1.0, 1600.0 / maxOf(width, height))
                        decoder.setTargetSize(maxOf(1, (width * ratio).toInt()), maxOf(1, (height * ratio).toInt()))
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.setOnPartialImageListener { false }
                    }
                }
                if (temporaryCapture) discardCapture()
                mutableState.value = state.value.copy(bitmap = bitmap)
                val response = model.generateContent(generateContentRequest(ImagePart(bitmap), TextPart(NANO_VITAL_PROMPT)) {
                    temperature = 0.0f
                    candidateCount = 1
                    maxOutputTokens = 128
                })
                val candidate = response.candidates.singleOrNull()
                val reading = candidate
                    ?.takeIf { it.finishReason == Candidate.FinishReason.STOP }
                    ?.text?.let(::parseNanoVitalReading)
                mutableState.value = state.value.copy(
                    reading = reading,
                    responseDetails = buildString {
                        append("検証用のモデル応答（端末のメモリ内のみ・未登録）\n応答数: ${response.candidates.size}")
                        response.candidates.take(3).forEachIndexed { index, item ->
                            val reason = when (item.finishReason) {
                                Candidate.FinishReason.STOP -> "STOP"
                                Candidate.FinishReason.MAX_TOKENS -> "MAX_TOKENS"
                                Candidate.FinishReason.OTHER -> "OTHER"
                                else -> "不明 (${item.finishReason})"
                            }
                            append("\n応答${index + 1}: $reason\n${item.text.take(1024)}")
                        }
                    },
                    message = when {
                        response.candidates.isEmpty() -> "モデルから応答がありませんでした。手入力するか、別の画像で試してください。"
                        candidate == null -> "モデルが複数の応答を返したため、候補を採用していません。"
                        candidate.finishReason == Candidate.FinishReason.MAX_TOKENS -> "モデルの応答が長さの上限で途切れました。候補は採用していません。"
                        candidate.finishReason != Candidate.FinishReason.STOP -> "モデルが正常に応答を完了しなかったため、候補を採用していません。"
                        reading == null -> "モデルは応答しましたが、指定の形式に合わないため候補を採用していません。"
                        reading.inputValues().all { it.isEmpty() } -> "モデルは3項目とも読み取り不明としました。手入力するか、別の画像で試してください。"
                        else -> "未確認の候補です。誤読の可能性があります。不明な項目は空欄のまま確認・修正してください。"
                    },
                )
            }
        } finally {
            if (temporaryCapture) discardCapture()
            mutableState.value = state.value.copy(processingMillis = SystemClock.elapsedRealtime() - started)
        }
    }

    private fun runOperation(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.value = state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                block()
            } catch (_: TimeoutCancellationException) {
                mutableState.value = state.value.copy(message = "処理が時間内に完了しませんでした。状態を再確認するか手入力を利用してください。")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never log image data, generated text, file names, or health values.
                val code = (e as? GenAiException)?.errorCode?.let { " (code=$it)" }.orEmpty()
                mutableState.value = state.value.copy(
                    message = "処理できませんでした$code。端末・モデル状態と画像を確認し、再試行するか手入力してください。",
                )
            } finally {
                mutableState.value = state.value.copy(busy = false)
            }
        }
    }

    override fun onCleared() {
        discardCapture()
        if (modelDelegate.isInitialized()) model.close()
        super.onCleared()
    }
}
