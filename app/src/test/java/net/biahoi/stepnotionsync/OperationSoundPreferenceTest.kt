package net.biahoi.stepnotionsync

import android.media.ToneGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OperationSoundPreferenceTest {
    @Test
    fun exposesExpectedChoicesInDisplayOrder() {
        assertEquals(
            listOf("標準（現在の音）", "ビープ音", "案内音", "再生しない"),
            OperationSoundPreference.entries.map { it.label }
        )
        assertEquals(
            listOf("standard", "beep", "prompt", "off"),
            OperationSoundPreference.entries.map { it.value }
        )
    }

    @Test
    fun convertsSavedValuesToPreferences() {
        assertEquals(OperationSoundPreference.STANDARD, OperationSoundPreference.from("standard"))
        assertEquals(OperationSoundPreference.BEEP, OperationSoundPreference.from("beep"))
        assertEquals(OperationSoundPreference.PROMPT, OperationSoundPreference.from("prompt"))
        assertEquals(OperationSoundPreference.OFF, OperationSoundPreference.from("off"))
    }

    @Test
    fun missingOrUnknownValueFallsBackToStandard() {
        assertEquals(OperationSoundPreference.STANDARD, OperationSoundPreference.from(null))
        assertEquals(OperationSoundPreference.STANDARD, OperationSoundPreference.from("unknown"))
        assertEquals(0, OperationSoundPreference.indexOf(null))
        assertEquals(0, OperationSoundPreference.indexOf("unknown"))
    }

    @Test
    fun mapsChoicesToBuiltInTonesAndOffToNoTone() {
        assertEquals(ToneGenerator.TONE_PROP_ACK, OperationSoundPreference.STANDARD.toneType)
        assertEquals(ToneGenerator.TONE_PROP_BEEP, OperationSoundPreference.BEEP.toneType)
        assertEquals(ToneGenerator.TONE_PROP_PROMPT, OperationSoundPreference.PROMPT.toneType)
        assertNull(OperationSoundPreference.OFF.toneType)
    }
}
