package com.tosspaper.models.properties

import spock.lang.Specification

class FilePropertiesSpec extends Specification {

    // ==================== Default Values ====================

    def "should have default minFileSizeBytes of 5120"() {
        given: "a freshly constructed FileProperties"
            def props = new FileProperties()

        expect: "minFileSizeBytes defaults to 5 * 1024 = 5120"
            props.minFileSizeBytes == 5120L
    }

    def "should have default maxFileSizeBytes of 3145728"() {
        given: "a freshly constructed FileProperties"
            def props = new FileProperties()

        expect: "maxFileSizeBytes defaults to 3 * 1024 * 1024 = 3145728"
            props.maxFileSizeBytes == 3_145_728L
    }

    def "should have default maxFilenameLength of 255"() {
        given: "a freshly constructed FileProperties"
            def props = new FileProperties()

        expect: "maxFilenameLength defaults to 255"
            props.maxFilenameLength == 255
    }

    def "should have default allowedContentTypes containing five MIME types"() {
        given: "a freshly constructed FileProperties"
            def props = new FileProperties()

        when: "reading allowedContentTypes"
            def types = props.allowedContentTypes

        then: "it contains exactly the five expected MIME types"
            types.size() == 5
            types.contains("application/pdf")
            types.contains("image/jpeg")
            types.contains("image/png")
            types.contains("image/gif")
            types.contains("image/webp")
    }

    def "should have default allowedFileExtensions containing five extensions"() {
        given: "a freshly constructed FileProperties"
            def props = new FileProperties()

        when: "reading allowedFileExtensions"
            def exts = props.allowedFileExtensions

        then: "it contains exactly the five expected extensions without dots"
            exts.size() == 5
            exts.contains("pdf")
            exts.contains("jpg")
            exts.contains("jpeg")
            exts.contains("png")
            exts.contains("webp")
    }

    def "should have default replacementMap with ten forbidden-character entries"() {
        given: "a freshly constructed FileProperties"
            def props = new FileProperties()

        when: "reading replacementMap"
            def map = props.replacementMap

        then: "it contains all ten forbidden characters mapped to underscore"
            map.size() == 10
            map[".."] == "_"
            map["/"]  == "_"
            map["\\"] == "_"
            map[":"]  == "_"
            map["*"]  == "_"
            map["?"]  == "_"
            map["\""] == "_"
            map["<"]  == "_"
            map[">"]  == "_"
            map["|"]  == "_"
    }

    def "should have default filesystemPath of /tmp/email-attachments"() {
        given: "a freshly constructed FileProperties"
            def props = new FileProperties()

        expect: "filesystemPath defaults to /tmp/email-attachments"
            props.filesystemPath == "/tmp/email-attachments"
    }

    def "should have default minImageWidth of 100"() {
        given: "a freshly constructed FileProperties"
            def props = new FileProperties()

        expect: "minImageWidth defaults to 100"
            props.minImageWidth == 100
    }

    def "should have default minImageHeight of 100"() {
        given: "a freshly constructed FileProperties"
            def props = new FileProperties()

        expect: "minImageHeight defaults to 100"
            props.minImageHeight == 100
    }

    def "should have default minImageArea of 240000"() {
        given: "a freshly constructed FileProperties"
            def props = new FileProperties()

        expect: "minImageArea defaults to 240000"
            props.minImageArea == 240_000L
    }

    def "should have default minAspectRatio of 0.3"() {
        given: "a freshly constructed FileProperties"
            def props = new FileProperties()

        expect: "minAspectRatio defaults to 0.3"
            props.minAspectRatio == 0.3d
    }

    def "should have default maxAspectRatio of 3.0"() {
        given: "a freshly constructed FileProperties"
            def props = new FileProperties()

        expect: "maxAspectRatio defaults to 3.0"
            props.maxAspectRatio == 3.0d
    }

    // ==================== Lombok @Data Setters ====================

    def "should allow minFileSizeBytes to be overridden via setter"() {
        given: "a FileProperties with a custom minimum size"
            def props = new FileProperties()

        when: "setting minFileSizeBytes to 1024"
            props.minFileSizeBytes = 1024L

        then: "the value is stored correctly"
            props.minFileSizeBytes == 1024L
    }

    def "should allow maxFileSizeBytes to be overridden via setter"() {
        given: "a FileProperties with a custom maximum size"
            def props = new FileProperties()

        when: "setting maxFileSizeBytes to 10485760 (10 MB)"
            props.maxFileSizeBytes = 10_485_760L

        then: "the value is stored correctly"
            props.maxFileSizeBytes == 10_485_760L
    }

    def "should allow maxFilenameLength to be overridden via setter"() {
        given: "a FileProperties instance"
            def props = new FileProperties()

        when: "setting maxFilenameLength to 128"
            props.maxFilenameLength = 128

        then: "the value is stored correctly"
            props.maxFilenameLength == 128
    }

    def "should allow allowedContentTypes to be replaced via setter"() {
        given: "a FileProperties instance"
            def props = new FileProperties()
            def customTypes = Set.of("image/gif", "application/octet-stream")

        when: "replacing allowedContentTypes"
            props.allowedContentTypes = customTypes

        then: "the new set is returned"
            props.allowedContentTypes == customTypes
            props.allowedContentTypes.contains("image/gif")
            props.allowedContentTypes.contains("application/octet-stream")
    }

    def "should allow allowedFileExtensions to be replaced via setter"() {
        given: "a FileProperties instance"
            def props = new FileProperties()
            def customExtensions = Set.of("gif", "bmp")

        when: "replacing allowedFileExtensions"
            props.allowedFileExtensions = customExtensions

        then: "the new set is returned"
            props.allowedFileExtensions == customExtensions
    }

    def "should allow filesystemPath to be overridden via setter"() {
        given: "a FileProperties instance"
            def props = new FileProperties()

        when: "setting filesystemPath to a custom path"
            props.filesystemPath = "/var/data/uploads"

        then: "the value is stored correctly"
            props.filesystemPath == "/var/data/uploads"
    }

    def "should allow minImageWidth to be overridden via setter"() {
        given: "a FileProperties instance"
            def props = new FileProperties()

        when: "setting minImageWidth to 200"
            props.minImageWidth = 200

        then: "the value is stored correctly"
            props.minImageWidth == 200
    }

    def "should allow minImageHeight to be overridden via setter"() {
        given: "a FileProperties instance"
            def props = new FileProperties()

        when: "setting minImageHeight to 300"
            props.minImageHeight = 300

        then: "the value is stored correctly"
            props.minImageHeight == 300
    }

    def "should allow minImageArea to be overridden via setter"() {
        given: "a FileProperties instance"
            def props = new FileProperties()

        when: "setting minImageArea to 500000"
            props.minImageArea = 500_000L

        then: "the value is stored correctly"
            props.minImageArea == 500_000L
    }

    def "should allow minAspectRatio to be overridden via setter"() {
        given: "a FileProperties instance"
            def props = new FileProperties()

        when: "setting minAspectRatio to 0.5"
            props.minAspectRatio = 0.5d

        then: "the value is stored correctly"
            props.minAspectRatio == 0.5d
    }

    def "should allow maxAspectRatio to be overridden via setter"() {
        given: "a FileProperties instance"
            def props = new FileProperties()

        when: "setting maxAspectRatio to 4.0"
            props.maxAspectRatio = 4.0d

        then: "the value is stored correctly"
            props.maxAspectRatio == 4.0d
    }

    def "should allow replacementMap to be replaced via setter"() {
        given: "a FileProperties instance"
            def props = new FileProperties()
            def customMap = Map.of("&", "_", "%", "_")

        when: "replacing the replacementMap"
            props.replacementMap = customMap

        then: "the new map is returned"
            props.replacementMap == customMap
    }

    // ==================== Lombok @Data equals/hashCode/toString ====================

    def "two FileProperties instances with same defaults should be equal"() {
        given: "two default FileProperties instances"
            def props1 = new FileProperties()
            def props2 = new FileProperties()

        expect: "they are equal and have the same hashCode"
            props1 == props2
            props1.hashCode() == props2.hashCode()
    }

    def "two FileProperties instances with different values should not be equal"() {
        given: "two FileProperties instances with differing minFileSizeBytes"
            def props1 = new FileProperties()
            def props2 = new FileProperties()
            props2.minFileSizeBytes = 99L

        expect: "they are not equal"
            props1 != props2
    }

    def "toString should include field names and values"() {
        given: "a default FileProperties"
            def props = new FileProperties()

        when: "calling toString"
            def str = props.toString()

        then: "the output contains recognisable field names"
            str.contains("minFileSizeBytes")
            str.contains("maxFileSizeBytes")
            str.contains("filesystemPath")
    }
}
