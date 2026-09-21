package net.biahoi.stepnotionsync

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Optional local regression: personal photos are not versioned. */
class SevenSegmentVitalReaderPhotoTest {
    @Test
    fun readsClearMicrolifePhotoAndRejectsStrongGlare() {
        val directory = System.getenv("VITAL_CAMERA_TEST_IMAGE_DIR")
        assumeNotNull(directory)
        val samples = listOf(
            "PXL_20260918_154908396.jpg" to doubleArrayOf(0.27, 0.20, 0.73, 0.67),
            "PXL_20260918_154909527.jpg" to doubleArrayOf(0.30, 0.25, 0.68, 0.64)
        )
        for (size in listOf(480, 640)) {
            val results = samples.map { (name, crop) ->
                val photo = ImageIO.read(File(directory, name))
                val framed = photo.getSubimage((photo.width * crop[0]).toInt(), (photo.height * crop[1]).toInt(),
                    (photo.width * (crop[2] - crop[0])).toInt(), (photo.height * (crop[3] - crop[1])).toInt())
                val scale = size.toDouble() / maxOf(framed.width, framed.height)
                val image = BufferedImage((framed.width * scale).toInt(), (framed.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
                image.createGraphics().let {
                    it.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    it.drawImage(framed, 0, 0, image.width, image.height, null)
                    it.dispose()
                }
                val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                readSevenSegmentVitals(pixels, image.width, image.height)
            }
            // The first photo's pulse segments are obscured by glare: require a retry, not a guess.
            assertNull("glare at $size px", selectVitalCameraReading(null, results[0]))
            assertEquals("clear at $size px", SevenSegmentVitalResult.Recognized(VitalCameraReading(104, 71, 89)), results[1])
        }
    }
}
