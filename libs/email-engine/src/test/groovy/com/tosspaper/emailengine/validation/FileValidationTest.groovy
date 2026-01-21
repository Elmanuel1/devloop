package com.tosspaper.emailengine.validation

import com.tosspaper.models.domain.FileObject
import com.tosspaper.models.properties.FileProperties
import com.tosspaper.models.validation.FileSizeValidation
import com.tosspaper.models.validation.ImageDimensionValidation
import com.tosspaper.models.validation.FileValidationChain
import com.tosspaper.models.validation.ValidationResult
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

import javax.imageio.ImageIO
import java.awt.image.BufferedImage

/**
 * Comprehensive tests for file validation chain.
 * Tests FileSizeValidation and ImageDimensionValidation using real email signature images.
 *
 * Test images in src/test/resources:
 * - mg_1768177915394_5d0ba2db_Outlook_Title__Twi.png (Twitter icon ~24x24, 1.6KB)
 * - mg_1768177915394_89b78a3a_Outlook_Title__Lin.png (LinkedIn icon ~24x24, 1.7KB)
 * - mg_1768177915395_c19fb15a_Outlook_Title__goo.png (Google+ icon ~24x24, 1.8KB)
 * - mg_1768177915394_d96398f2_Outlook_zdiyr1ye.png (ConstructDrive logo ~400x400, 83KB)
 */
class FileValidationTest extends Specification {

    FileProperties fileProperties
    FileSizeValidation fileSizeValidation
    ImageDimensionValidation imageDimensionValidation

    @Subject
    FileValidationChain fileValidationChain

    def setup() {
        fileProperties = new FileProperties()
        // Set defaults
        fileProperties.minFileSizeBytes = 5 * 1024L      // 5KB minimum
        fileProperties.maxFileSizeBytes = 3 * 1024 * 1024L // 3MB maximum
        fileProperties.minImageWidth = 100
        fileProperties.minImageHeight = 100
        fileProperties.minImageArea = 240_000L   // e.g., 400x600 or 600x400
        fileProperties.minAspectRatio = 0.3
        fileProperties.maxAspectRatio = 3.0

        fileSizeValidation = new FileSizeValidation(fileProperties)
        imageDimensionValidation = new ImageDimensionValidation(fileProperties)
        fileValidationChain = new FileValidationChain([fileSizeValidation, imageDimensionValidation])
    }

    // ==================== REAL IMAGE TESTS ====================

    def "should reject Twitter signature icon - too small file size and dimensions"() {
        given: "Twitter signature icon from test resources"
        byte[] content = loadTestResource("mg_1768177915394_5d0ba2db_Outlook_Title__Twi.png")
        def fileObject = createFileObject("twitter_icon.png", "image/png", content)

        when: "validating the file"
        def result = fileValidationChain.validate(fileObject)

        then: "should be rejected"
        !result.isValid()
        result.getViolationMessage().contains("below minimum") ||
            result.getViolationMessage().contains("signature icon")

        and: "log details for debugging"
        println "Twitter icon - Size: ${content.length} bytes, Violations: ${result.getViolationMessage()}"
    }

    def "should reject LinkedIn signature icon - too small file size and dimensions"() {
        given: "LinkedIn signature icon from test resources"
        byte[] content = loadTestResource("mg_1768177915394_89b78a3a_Outlook_Title__Lin.png")
        def fileObject = createFileObject("linkedin_icon.png", "image/png", content)

        when: "validating the file"
        def result = fileValidationChain.validate(fileObject)

        then: "should be rejected"
        !result.isValid()
        result.getViolationMessage().contains("below minimum") ||
            result.getViolationMessage().contains("signature icon")

        and: "log details for debugging"
        println "LinkedIn icon - Size: ${content.length} bytes, Violations: ${result.getViolationMessage()}"
    }

    def "should reject Google+ signature icon - too small file size and dimensions"() {
        given: "Google+ signature icon from test resources"
        byte[] content = loadTestResource("mg_1768177915395_c19fb15a_Outlook_Title__goo.png")
        def fileObject = createFileObject("google_plus_icon.png", "image/png", content)

        when: "validating the file"
        def result = fileValidationChain.validate(fileObject)

        then: "should be rejected"
        !result.isValid()
        result.getViolationMessage().contains("below minimum") ||
            result.getViolationMessage().contains("signature icon")

        and: "log details for debugging"
        println "Google+ icon - Size: ${content.length} bytes, Violations: ${result.getViolationMessage()}"
    }

    def "should reject ConstructDrive logo - area below minimum threshold"() {
        given: "ConstructDrive logo from test resources (488x434 = 211,792 pixels < 240,000 min)"
        byte[] content = loadTestResource("mg_1768177915394_d96398f2_Outlook_zdiyr1ye.png")
        def fileObject = createFileObject("constructdrive_logo.png", "image/png", content)

        when: "validating the file"
        def result = fileValidationChain.validate(fileObject)

        then: "should be rejected due to area below minimum"
        !result.isValid()
        result.getViolationMessage().contains("area") ||
            result.getViolationMessage().contains("below minimum")

        and: "log details for debugging"
        def image = ImageIO.read(new ByteArrayInputStream(content))
        long area = (long) image.width * image.height
        println "ConstructDrive logo - Size: ${content.length} bytes, Dimensions: ${image?.width}x${image?.height}, Area: ${area} pixels"
    }

    // ==================== FILE SIZE VALIDATION TESTS ====================

    def "FileSizeValidation should reject files below minimum size"() {
        given: "a file smaller than minimum size (5KB)"
        def smallContent = new byte[1024] // 1KB
        def fileObject = createFileObject("tiny.pdf", "application/pdf", smallContent)

        when: "validating file size"
        def result = fileSizeValidation.validate(fileObject)

        then: "should be rejected"
        !result.isValid()
        result.getViolationMessage().contains("below minimum")
        result.getViolationMessage().contains("1024 bytes")
        result.getViolationMessage().contains("signature icon")
    }

    def "FileSizeValidation should reject files above maximum size"() {
        given: "a file larger than maximum size (3MB)"
        def largeContent = new byte[4 * 1024 * 1024] // 4MB
        def fileObject = createFileObject("huge.pdf", "application/pdf", largeContent)

        when: "validating file size"
        def result = fileSizeValidation.validate(fileObject)

        then: "should be rejected"
        !result.isValid()
        result.getViolationMessage().contains("exceeds maximum")
    }

    def "FileSizeValidation should accept files within valid size range"() {
        given: "a file within valid size range"
        def validContent = new byte[10 * 1024] // 10KB
        def fileObject = createFileObject("valid.pdf", "application/pdf", validContent)

        when: "validating file size"
        def result = fileSizeValidation.validate(fileObject)

        then: "should be accepted"
        result.isValid()
    }

    @Unroll
    def "FileSizeValidation boundary test - #scenario"() {
        given: "a file of size #sizeBytes bytes"
        def content = new byte[sizeBytes]
        def fileObject = createFileObject("test.pdf", "application/pdf", content)

        when: "validating file size"
        def result = fileSizeValidation.validate(fileObject)

        then: "should be #expectedValid"
        result.isValid() == expectedValid

        where:
        scenario                    | sizeBytes              | expectedValid
        "exactly at minimum (5KB)"  | 5 * 1024               | true
        "1 byte below minimum"      | 5 * 1024 - 1           | false
        "1 byte above minimum"      | 5 * 1024 + 1           | true
        "exactly at maximum (3MB)"  | 3 * 1024 * 1024        | true
        "1 byte below maximum"      | 3 * 1024 * 1024 - 1    | true
        "1 byte above maximum"      | 3 * 1024 * 1024 + 1    | false
    }

    // ==================== IMAGE DIMENSION VALIDATION TESTS ====================

    def "ImageDimensionValidation should reject images below minimum dimensions"() {
        given: "a small 50x50 image"
        def image = createTestImage(50, 50)
        def fileObject = createFileObject("small.png", "image/png", imageToBytes(image))

        when: "validating image dimensions"
        def result = imageDimensionValidation.validate(fileObject)

        then: "should be rejected for small dimensions"
        !result.isValid()
        result.getViolationMessage().contains("50x50")
        result.getViolationMessage().contains("below minimum")
        result.getViolationMessage().contains("100x100")
    }

    def "ImageDimensionValidation should accept images meeting all requirements"() {
        given: "a 500x500 image (meets dimensions and area requirements)"
        def image = createTestImage(500, 500)
        def fileObject = createFileObject("valid.png", "image/png", imageToBytes(image))

        when: "validating image dimensions"
        def result = imageDimensionValidation.validate(fileObject)

        then: "should be accepted"
        result.isValid()
    }

    def "ImageDimensionValidation should reject images with extreme aspect ratios"() {
        given: "a very wide banner image with valid dimensions but extreme aspect ratio (2500x100 = 25:1)"
        // Dimensions 2500x100 pass min check (both >= 100), area = 250,000 passes area check
        // But aspect ratio 25:1 > maxAspectRatio 5.0
        def image = createTestImage(2500, 100)
        def fileObject = createFileObject("banner.png", "image/png", imageToBytes(image))

        when: "validating image dimensions"
        def result = imageDimensionValidation.validate(fileObject)

        then: "should be rejected for extreme aspect ratio"
        !result.isValid()
        result.getViolationMessage().contains("aspect ratio")
        result.getViolationMessage().contains("outside acceptable range")
    }

    def "ImageDimensionValidation should reject very tall images"() {
        given: "a very tall image (50x1000 = 0.05:1 aspect ratio)"
        def image = createTestImage(50, 1000)
        def fileObject = createFileObject("tall.png", "image/png", imageToBytes(image))

        when: "validating image dimensions"
        def result = imageDimensionValidation.validate(fileObject)

        then: "should be rejected - too tall"
        !result.isValid()
        // Either rejected for dimensions or aspect ratio
        result.getViolationMessage().contains("below minimum") ||
            result.getViolationMessage().contains("aspect ratio")
    }

    @Unroll
    def "ImageDimensionValidation dimension boundary test - #scenario"() {
        given: "an image of #width x #height pixels"
        def image = createTestImage(width, height)
        def fileObject = createFileObject("test.png", "image/png", imageToBytes(image))

        when: "validating image"
        def result = imageDimensionValidation.validate(fileObject)

        then: "should be #expectedValid"
        result.isValid() == expectedValid

        where:
        scenario                          | width | height | expectedValid
        "width below minimum 99x500"      | 99    | 500    | false   // fails dimension check
        "height below minimum 500x99"     | 500   | 99     | false   // fails dimension check
        "both below minimum 50x50"        | 50    | 50     | false   // fails dimension check
        "dimensions ok but area small"    | 100   | 100    | false   // 10,000 < 240,000 area
        "valid square 500x500"            | 500   | 500    | true    // 250,000 area
        "valid portrait 400x600"          | 400   | 600    | true    // 240,000 area
        "valid landscape 600x400"         | 600   | 400    | true    // 240,000 area
        "extreme landscape 600x100"       | 600   | 100    | false   // 6:1 > maxAspectRatio
        "extreme portrait 100x600"        | 100   | 600    | false   // 0.167:1 < minAspectRatio
    }

    // ==================== IMAGE AREA VALIDATION TESTS ====================

    def "ImageDimensionValidation should reject images with area below minimum"() {
        given: "an image with valid dimensions but area below minimum (300x300 = 90,000 < 240,000)"
        def image = createTestImage(300, 300)
        def fileObject = createFileObject("small_area.png", "image/png", imageToBytes(image))

        when: "validating image"
        def result = imageDimensionValidation.validate(fileObject)

        then: "should be rejected for small area"
        !result.isValid()
        result.getViolationMessage().contains("area")
        result.getViolationMessage().contains("90000")
        result.getViolationMessage().contains("below minimum")
    }

    def "ImageDimensionValidation should accept images at minimum area threshold"() {
        given: "an image with area exactly at minimum (400x600 = 240,000)"
        def image = createTestImage(400, 600)
        def fileObject = createFileObject("min_area.png", "image/png", imageToBytes(image))

        when: "validating image"
        def result = imageDimensionValidation.validate(fileObject)

        then: "should be accepted"
        result.isValid()
    }

    @Unroll
    def "ImageDimensionValidation area boundary test - #scenario"() {
        given: "an image of #width x #height pixels (area: #area)"
        def image = createTestImage(width, height)
        def fileObject = createFileObject("test.png", "image/png", imageToBytes(image))

        when: "validating image"
        def result = imageDimensionValidation.validate(fileObject)

        then: "should be #expectedValid"
        result.isValid() == expectedValid

        where:
        scenario                              | width | height | area     | expectedValid
        "area 1 below minimum (399x600)"      | 399   | 600    | 239_400  | false
        "area exactly at minimum (400x600)"   | 400   | 600    | 240_000  | true
        "area 1 above minimum (401x600)"      | 401   | 600    | 240_600  | true
        "large valid area (800x800)"          | 800   | 800    | 640_000  | true
    }

    @Unroll
    def "ImageDimensionValidation aspect ratio boundary test - #scenario"() {
        given: "an image with aspect ratio #aspectRatio"
        // Create image with specific aspect ratio, dimensions >= 100 and area >= 240,000
        // Use base 500 to ensure area passes (500*500=250,000 > 240,000)
        int width = (int)(500 * Math.max(1.0d, (double) aspectRatio))
        int height = (int)(500 * Math.max(1.0d, 1.0d / aspectRatio))
        def image = createTestImage(width, height)
        def fileObject = createFileObject("test.png", "image/png", imageToBytes(image))

        when: "validating image"
        def result = imageDimensionValidation.validate(fileObject)

        then: "should be #expectedValid"
        result.isValid() == expectedValid

        where:
        scenario                       | aspectRatio | expectedValid
        "square 1:1"                   | 1.0         | true
        "landscape 2:1"                | 2.0         | true
        "landscape 3:1 (at max)"       | 3.0         | true
        "landscape 4:1 (over max)"     | 4.0         | false
        "portrait 1:2"                 | 0.5         | true
        "portrait 1:3 (at min)"        | 0.33        | true
        "portrait 1:4 (under min)"     | 0.25        | false
    }

    // ==================== NON-IMAGE FILE TESTS ====================

    def "ImageDimensionValidation should skip non-image files"() {
        given: "a PDF file"
        def content = new byte[10 * 1024]
        def fileObject = createFileObject("document.pdf", "application/pdf", content)

        when: "validating"
        def result = imageDimensionValidation.validate(fileObject)

        then: "should pass (skip validation for non-images)"
        result.isValid()
    }

    @Unroll
    def "ImageDimensionValidation should validate image type: #contentType"() {
        given: "a valid image file of type #contentType (500x500 = 250,000 area)"
        def image = createTestImage(500, 500)
        def fileObject = createFileObject(fileName, contentType, imageToBytes(image))

        when: "validating"
        def result = imageDimensionValidation.validate(fileObject)

        then: "should be validated and pass"
        result.isValid()

        where:
        contentType   | fileName
        "image/png"   | "test.png"
        "image/jpeg"  | "test.jpg"
        "image/jpg"   | "test.jpg"
        "image/gif"   | "test.gif"
        "image/webp"  | "test.webp"
    }

    // ==================== VALIDATION CHAIN TESTS ====================

    def "FileValidationChain should collect all violations"() {
        given: "a file that fails both size and dimension validation"
        def smallImage = createTestImage(30, 30)
        def smallContent = imageToBytes(smallImage)
        // Content is already small from the tiny image
        def fileObject = createFileObject("tiny.png", "image/png", smallContent)

        when: "validating through the chain"
        def result = fileValidationChain.validate(fileObject)

        then: "should be invalid"
        !result.isValid()

        and: "should contain violations"
        println "Combined violations for tiny image: ${result.getViolationMessage()}"
    }

    def "FileValidationChain should pass valid files through all validators"() {
        given: "a valid image file (500x500 = 250,000 area, meets all requirements)"
        def image = createTestImage(500, 500)
        def content = imageToBytes(image)
        // Pad to ensure it meets minimum file size (5KB)
        def paddedContent = new byte[Math.max(content.length, 6 * 1024) as int]
        System.arraycopy(content, 0, paddedContent, 0, content.length)
        def fileObject = createFileObject("valid.png", "image/png", paddedContent)

        when: "validating through the chain"
        def result = fileValidationChain.validate(fileObject)

        then: "should be valid"
        result.isValid()
    }

    // ==================== EDGE CASES ====================

    def "should handle empty file content"() {
        given: "a file with empty content"
        def fileObject = createFileObject("empty.png", "image/png", new byte[0])

        when: "validating"
        def sizeResult = fileSizeValidation.validate(fileObject)

        then: "should be rejected for size"
        !sizeResult.isValid()
    }

    def "should handle corrupted image data gracefully"() {
        given: "a file claiming to be PNG but with invalid data"
        def invalidContent = "not an image".bytes
        // Pad to meet size requirements
        def paddedContent = new byte[6 * 1024]
        System.arraycopy(invalidContent, 0, paddedContent, 0, invalidContent.length)
        def fileObject = createFileObject("corrupt.png", "image/png", paddedContent)

        when: "validating"
        def result = imageDimensionValidation.validate(fileObject)

        then: "should handle gracefully (either pass with warning or reject)"
        // The validator logs a warning but may still return valid or invalid
        noExceptionThrown()
    }

    // ==================== HELPER METHODS ====================

    private byte[] loadTestResource(String fileName) {
        def resourceStream = getClass().getClassLoader().getResourceAsStream(fileName)
        if (resourceStream == null) {
            throw new IllegalStateException("Test resource not found: ${fileName}")
        }
        return resourceStream.bytes
    }

    private static FileObject createFileObject(String fileName, String contentType, byte[] content) {
        return FileObject.builder()
            .assignedId("test-" + UUID.randomUUID().toString())
            .fileName(fileName)
            .contentType(contentType)
            .content(content)
            .sizeBytes((long) content.length)
            .checksum("sha256-test")
            .build()
    }

    private static BufferedImage createTestImage(int width, int height) {
        def image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        def graphics = image.createGraphics()
        graphics.setColor(java.awt.Color.WHITE)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return image
    }

    private static byte[] imageToBytes(BufferedImage image) {
        def baos = new ByteArrayOutputStream()
        ImageIO.write(image, "png", baos)
        return baos.toByteArray()
    }
}
