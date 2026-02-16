package com.tosspaper.models.utils

import spock.lang.Specification

/**
 * Tests for FileExtensionUtils.
 * Verifies file extension extraction and manipulation.
 */
class FileExtensionUtilsSpec extends Specification {

    def "getFileExtension should extract extension from filename"() {
        expect:
        FileExtensionUtils.getFileExtension(fileName) == expected

        where:
        fileName              | expected
        "document.pdf"       | "pdf"
        "image.png"          | "png"
        "archive.tar.gz"     | "gz"
        "file.TXT"           | "txt"
        "FILE.PDF"           | "pdf"
    }

    def "getFileExtension should return lowercase extension"() {
        expect:
        FileExtensionUtils.getFileExtension("FILE.PDF") == "pdf"
        FileExtensionUtils.getFileExtension("Document.TxT") == "txt"
    }

    def "getFileExtension should return empty for no extension"() {
        expect:
        FileExtensionUtils.getFileExtension(fileName) == ""

        where:
        fileName << ["noextension", "file", ""]
    }

    def "getFileExtension should return empty for null"() {
        expect:
        FileExtensionUtils.getFileExtension(null) == ""
    }

    def "getFileExtension should handle filenames with multiple dots"() {
        expect:
        FileExtensionUtils.getFileExtension("my.file.name.pdf") == "pdf"
    }

    def "getFileExtension should handle hidden files"() {
        expect:
        FileExtensionUtils.getFileExtension(".gitignore") == "gitignore"
    }

    def "getFileExtension should handle filename ending with dot"() {
        expect:
        FileExtensionUtils.getFileExtension("filename.") == ""
    }

    def "hasExtension should return true for files with extension"() {
        expect:
        FileExtensionUtils.hasExtension(fileName)

        where:
        fileName << [
            "document.pdf",
            "image.png",
            "file.txt",
            "archive.tar.gz"
        ]
    }

    def "hasExtension should return false for files without extension"() {
        expect:
        !FileExtensionUtils.hasExtension(fileName)

        where:
        fileName << [
            "noextension",
            "file",
            "filename.",
            null,
            ""
        ]
    }

    def "getFileNameWithoutExtension should remove extension"() {
        expect:
        FileExtensionUtils.getFileNameWithoutExtension(fileName) == expected

        where:
        fileName              | expected
        "document.pdf"       | "document"
        "image.png"          | "image"
        "my.file.pdf"        | "my.file"
        "archive.tar.gz"     | "archive.tar"
    }

    def "getFileNameWithoutExtension should handle no extension"() {
        expect:
        FileExtensionUtils.getFileNameWithoutExtension("noextension") == "noextension"
    }

    def "getFileNameWithoutExtension should handle null"() {
        expect:
        FileExtensionUtils.getFileNameWithoutExtension(null) == null
    }

    def "getFileNameWithoutExtension should handle filename ending with dot"() {
        expect:
        FileExtensionUtils.getFileNameWithoutExtension("filename.") == "filename"
    }

    def "getFileNameWithoutExtension should handle hidden files"() {
        expect:
        FileExtensionUtils.getFileNameWithoutExtension(".gitignore") == ""
    }

    def "getFileExtension should handle various extensions"() {
        expect:
        FileExtensionUtils.getFileExtension(fileName) == expected

        where:
        fileName                  | expected
        "file.pdf"               | "pdf"
        "file.jpg"               | "jpg"
        "file.jpeg"              | "jpeg"
        "file.png"               | "png"
        "file.gif"               | "gif"
        "file.webp"              | "webp"
        "file.doc"               | "doc"
        "file.docx"              | "docx"
        "file.xls"               | "xls"
        "file.xlsx"              | "xlsx"
        "file.zip"               | "zip"
        "file.tar"               | "tar"
        "file.gz"                | "gz"
    }

    def "getFileExtension should handle single character extension"() {
        expect:
        FileExtensionUtils.getFileExtension("file.c") == "c"
        FileExtensionUtils.getFileExtension("file.h") == "h"
    }

    def "getFileExtension should handle long extensions"() {
        expect:
        FileExtensionUtils.getFileExtension("file.verylongextension") == "verylongextension"
    }

    def "getFileExtension should handle filenames with spaces"() {
        expect:
        FileExtensionUtils.getFileExtension("my file name.pdf") == "pdf"
    }

    def "getFileExtension should handle filenames with special characters"() {
        expect:
        FileExtensionUtils.getFileExtension("file-name_v2.1.pdf") == "pdf"
    }

    def "hasExtension should handle edge cases"() {
        expect:
        FileExtensionUtils.hasExtension(fileName) == expected

        where:
        fileName       | expected
        ".txt"        | true
        "a.b"         | true
        ".a.b"        | true
        "."           | false
        ".."          | false
    }

    def "getFileNameWithoutExtension should handle complex filenames"() {
        expect:
        FileExtensionUtils.getFileNameWithoutExtension(fileName) == expected

        where:
        fileName                    | expected
        "my.document.v2.pdf"       | "my.document.v2"
        "file-name_v2.1.txt"       | "file-name_v2.1"
        "report.2024-01-15.pdf"    | "report.2024-01-15"
    }

    def "extension methods should work together correctly"() {
        given:
        def fileName = "my.document.name.pdf"

        when:
        def extension = FileExtensionUtils.getFileExtension(fileName)
        def nameWithout = FileExtensionUtils.getFileNameWithoutExtension(fileName)
        def hasExt = FileExtensionUtils.hasExtension(fileName)

        then:
        extension == "pdf"
        nameWithout == "my.document.name"
        hasExt
        "${nameWithout}.${extension}" == fileName
    }
}
