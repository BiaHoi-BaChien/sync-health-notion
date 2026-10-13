package net.biahoi.stepnotionsync

import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

internal enum class VitalImageSource { PICKER, CAMERA }

// Kept in the ViewModel: rotation retains an outstanding result, process death does not.
internal class VitalImageInputSession {
    private var opened = false
    var chooseSource = false
    var interrupted = false
        private set
    var pendingSource: VitalImageSource? = null
        private set

    fun open(restoredActivity: Boolean): Boolean {
        if (opened) return false
        opened = true
        chooseSource = !restoredActivity
        interrupted = restoredActivity
        return restoredActivity
    }

    fun begin(source: VitalImageSource) {
        chooseSource = false
        interrupted = false
        pendingSource = source
    }

    fun complete(source: VitalImageSource): Boolean {
        if (pendingSource != source) return false
        pendingSource = null
        return true
    }
}

internal data class VitalEntryDraft(
    val values: List<String>,
    val measuredAt: Instant? = null,
    val clientRecordVersion: Long = 0,
    val writeAttempted: Boolean = false,
    val message: String? = null,
) {
    fun withImageResult(candidates: List<String>?): VitalEntryDraft =
        copy(values = candidates ?: values)

    // Keep the record IDs stable even when a completed write loses its response to cancellation.
    fun prepareRegistration(now: Instant): VitalEntryDraft = copy(
        measuredAt = measuredAt ?: now.truncatedTo(ChronoUnit.MILLIS),
        clientRecordVersion = if (measuredAt == null) 0 else Math.addExact(clientRecordVersion, 1),
        message = null,
    )

    fun withRegistrationFailure(reason: String): VitalEntryDraft = copy(
        message = if (writeAttempted) "$reason\n$UNCONFIRMED_REGISTRATION_MESSAGE" else reason,
    )

    fun feedback(): String? = message ?: if (writeAttempted) UNCONFIRMED_REGISTRATION_MESSAGE else null

    companion object {
        private const val UNCONFIRMED_REGISTRATION_MESSAGE =
            "保存済みの可能性があります。再試行して登録結果を確認してください。値を修正すると同じ測定を更新します。"
    }
}

internal class VitalCaptureFiles(cacheDir: File) {
    private val directory = File(cacheDir, "vital-captures")

    fun create(): File {
        check(directory.isDirectory || directory.mkdirs())
        return File.createTempFile("capture-", ".jpg", directory)
    }

    fun clear() {
        directory.listFiles()?.filter {
            it.isFile && it.name.startsWith("capture-") && it.extension == "jpg"
        }?.forEach { it.delete() }
    }

    companion object {
        private var cleanedOnStartup = false

        @Synchronized
        fun cleanOnProcessStart(cacheDir: File) {
            if (!cleanedOnStartup) {
                VitalCaptureFiles(cacheDir).clear()
                cleanedOnStartup = true
            }
        }
    }
}
