package net.biahoi.stepnotionsync

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional local regression: personal photos are not versioned. */
class SevenSegmentVitalReaderPhotoTest {
    @Test
    fun september25GuidesRequireReframingRatherThanOverridingBoundaryEvidence() {
        val directory = System.getenv("VITAL_CAMERA_TEST_IMAGE_DIR")
        assumeNotNull(directory)
        val file = File(directory, "1000001671.png")
        assumeTrue(file.isFile)
        val photo = ImageIO.read(file)
        // Exclude the green outline. The divider cuts the 72 and the LCD border
        // enters the outer rows; this screenshot must not justify relaxing clipping checks.
        val boxes = listOf(
            intArrayOf(164, 530, 535, 289), intArrayOf(164, 825, 535, 232), intArrayOf(164, 1064, 535, 226)
        )
        boxes.forEachIndexed { index, box ->
            val row = photo.getSubimage(box[0], box[1], box[2], box[3])
            val pixels = row.getRGB(0, 0, row.width, row.height, null, 0, row.width)
            val result = readSevenSegmentNumber(pixels, row.width, row.height)
            assertEquals("row=$index", SevenSegmentNumberResult.Uncertain, result)
            assertNull(selectVitalCameraNumber(listOf(109, 72, 78)[index], result))
        }

        // The systolic digits are intact above the overlay and can be reframed.
        // The screenshot's overlaid divider hides pixels in 72, so it cannot
        // verify successful reframing of all three rows from the original camera bitmap.
        val row = photo.getSubimage(211, 571, 460, 235)
        val pixels = row.getRGB(0, 0, row.width, row.height, null, 0, row.width)
        assertEquals(SevenSegmentNumberResult.Recognized(109), readSevenSegmentNumber(pixels, row.width, row.height))
    }

    @Test
    fun readsIndividualMicrolifeRowsWithBordersOutsideTheGuides() {
        val directory = System.getenv("VITAL_CAMERA_TEST_IMAGE_DIR")
        assumeNotNull(directory)
        // Explicitly positioned guides with margin around all digits; no fragment is erased.
        val samples = listOf(
            Triple("Screenshot_20260921-193044.png", listOf(
                intArrayOf(245, 509, 410, 207), intArrayOf(245, 716, 410, 213), intArrayOf(438, 929, 207, 142)
            ), listOf(110, 74, 77))
        )
        for ((name, boxes, values) in samples) {
            val photo = ImageIO.read(File(directory, name))
            boxes.forEachIndexed { index, box ->
                val row = photo.getSubimage(box[0], box[1], box[2], box[3])
                val pixels = row.getRGB(0, 0, row.width, row.height, null, 0, row.width)
                assertEquals("$name row=$index", SevenSegmentNumberResult.Recognized(values[index]),
                    readSevenSegmentNumber(pixels, row.width, row.height))
            }
        }
    }

    @Test
    fun aPhotographedRowWithAmbiguousStrokesStillRequiresRetry() {
        val directory = System.getenv("VITAL_CAMERA_TEST_IMAGE_DIR")
        assumeNotNull(directory)
        val photo = ImageIO.read(File(directory, "PXL_20260918_154909527.jpg"))
        val row = photo.getSubimage(416, 449, 508, 284)
        val pixels = row.getRGB(0, 0, row.width, row.height, null, 0, row.width)
        val result = readSevenSegmentNumber(pixels, row.width, row.height)
        assertEquals(SevenSegmentNumberResult.Uncertain, result)
        assertNull(selectVitalCameraNumber(104, result))
    }

    @Test
    fun readsFramedMicrolifeScreenshot() {
        val directory = System.getenv("VITAL_CAMERA_TEST_IMAGE_DIR")
        assumeNotNull(directory)
        val screenshot = ImageIO.read(File(directory, "Screenshot_20260921-193044.png"))
        // Exclude only the overlaid green frame, which is absent from the camera bitmap.
        val framed = screenshot.getSubimage(196, 472, 471, 862)
        for (size in listOf(480, 640)) {
            val scale = size.toDouble() / maxOf(framed.width, framed.height)
            val image = BufferedImage((framed.width * scale).toInt(), (framed.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let {
                it.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                it.drawImage(framed, 0, 0, image.width, image.height, null)
                it.dispose()
            }
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            assertEquals("screenshot at $size px", SevenSegmentVitalResult.Recognized(VitalCameraReading(110, 74, 77)),
                readSevenSegmentVitals(pixels, image.width, image.height))
        }
    }

    @Test
    fun rejectsMicrolifePhotosWithStrongGlareOrAmbiguousEdgeStrokes() {
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
            // This formerly accepted crop leaves a thin LCD edge indistinguishable from a clipped digit.
            assertEquals("ambiguous LCD edge at $size px", SevenSegmentVitalResult.Uncertain, results[1])
            assertNull(selectVitalCameraReading(VitalCameraReading(104, 71, 89), results[1]))
        }
    }
}
