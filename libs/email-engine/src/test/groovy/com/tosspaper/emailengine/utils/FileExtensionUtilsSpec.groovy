package com.tosspaper.emailengine.utils

import spock.lang.Specification

class FileExtensionUtilsSpec extends Specification {

    def "should extract file extension correctly"() {
        expect:
        FileExtensionUtils.getFileExtension(fileName) == expectedExtension

        where:
        fileName                  | expectedExtension
        "document.pdf"            | "pdf"
        "invoice.PDF"             | "pdf"
        "archive.tar.gz"          | "gz"
        "file.TXT"                | "txt"
        "image.JPEG"              | "jpeg"
        "report.xlsx"             | "xlsx"
    }

    def "should return empty string for filename without extension"() {
        expect:
        FileExtensionUtils.getFileExtension(fileName) == ""

        where:
        fileName << ["noextension", "README", "Makefile"]
    }

    def "should return empty string for null filename"() {
        expect:
        FileExtensionUtils.getFileExtension(null) == ""
    }

    def "should return empty string for filename with only dot"() {
        expect:
        FileExtensionUtils.getFileExtension(".") == ""
    }

    def "should handle hidden files correctly"() {
        expect:
        FileExtensionUtils.getFileExtension(".gitignore") == "gitignore"
        FileExtensionUtils.getFileExtension(".env") == "env"
    }

    def "should return extension for file with multiple dots"() {
        expect:
        FileExtensionUtils.getFileExtension("my.file.name.txt") == "txt"
    }

    def "should check if file has extension"() {
        expect:
        FileExtensionUtils.hasExtension(fileName) == hasExt

        where:
        fileName          | hasExt
        "document.pdf"    | true
        "image.png"       | true
        "noextension"     | false
        "README"          | false
        null              | false
        ".gitignore"      | true
    }

    def "should get filename without extension"() {
        expect:
        FileExtensionUtils.getFileNameWithoutExtension(fileName) == expectedName

        where:
        fileName                  | expectedName
        "document.pdf"            | "document"
        "archive.tar.gz"          | "archive.tar"
        "noextension"             | "noextension"
        "my.file.name.txt"        | "my.file.name"
        null                      | null
        ".gitignore"              | ""
    }

    def "should handle edge cases for getFileNameWithoutExtension"() {
        expect:
        FileExtensionUtils.getFileNameWithoutExtension(fileName) == expectedName

        where:
        fileName     | expectedName
        ""           | ""
        "."          | ""
        "file."      | "file"
    }

    def "should convert extension to lowercase"() {
        expect:
        FileExtensionUtils.getFileExtension("FILE.PDF") == "pdf"
        FileExtensionUtils.getFileExtension("Document.TxT") == "txt"
        FileExtensionUtils.getFileExtension("IMAGE.JPEG") == "jpeg"
    }
}
