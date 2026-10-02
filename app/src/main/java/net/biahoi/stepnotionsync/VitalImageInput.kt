package net.biahoi.stepnotionsync

import java.io.File

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

internal data class VitalEntryDraft(val values: List<String>, val timing: VitalEntryTiming) {
    fun withImageResult(
        candidates: List<String>?,
        imageStartedAt: Long,
        processingMillis: Long?,
        attempts: Int,
    ): VitalEntryDraft = copy(
        values = candidates ?: values,
        timing = timing.recordImageResult(imageStartedAt, processingMillis, attempts, candidates != null),
    )
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
