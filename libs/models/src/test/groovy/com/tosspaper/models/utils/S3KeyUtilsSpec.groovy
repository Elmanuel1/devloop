package com.tosspaper.models.utils

import com.tosspaper.models.domain.FileObject
import spock.lang.Specification

/**
 * Tests for S3KeyUtils.
 * Verifies S3 key generation and manipulation.
 */
class S3KeyUtilsSpec extends Specification {

    def "generateS3Key should create key with UUID and filename"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("invoice.pdf")
            .build()

        when:
        def key = S3KeyUtils.generateS3Key("documents", fileObject)

        then:
        key.startsWith("documents/")
        key.contains("_invoice.pdf")
        key.split("_")[0].contains("-") // UUID format
    }

    def "generateS3Key should sanitize filename"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("my file:with*special?chars.pdf")
            .build()

        when:
        def key = S3KeyUtils.generateS3Key("docs", fileObject)

        then:
        !key.contains(":")
        !key.contains("*")
        !key.contains("?")
        !key.contains(" ")
        key.contains("my_file")
    }

    def "generateS3Key should normalize consecutive underscores"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("file___with___many___spaces.pdf")
            .build()

        when:
        def key = S3KeyUtils.generateS3Key("docs", fileObject)

        then:
        !key.contains("___")
        key.matches(".*_[^_].*") // Single underscores only
    }

    def "generateS3Key should add trailing slash to prefix if missing"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .build()

        when:
        def keyWithoutSlash = S3KeyUtils.generateS3Key("documents", fileObject)
        def keyWithSlash = S3KeyUtils.generateS3Key("documents/", fileObject)

        then:
        keyWithoutSlash.startsWith("documents/")
        keyWithSlash.startsWith("documents/")
    }

    def "generateS3Key should handle null prefix"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .build()

        when:
        def key = S3KeyUtils.generateS3Key(null, fileObject)

        then:
        key.contains("_test.pdf")
        !key.startsWith("/")
    }

    def "generateS3Key should handle empty prefix"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .build()

        when:
        def key = S3KeyUtils.generateS3Key("", fileObject)

        then:
        key.contains("_test.pdf")
    }

    def "generateS3Key should preserve allowed characters"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("my-file_v2.1.pdf")
            .build()

        when:
        def key = S3KeyUtils.generateS3Key("docs", fileObject)

        then:
        key.contains("my-file_v2.1.pdf")
    }

    def "extractFileNameFromKey should extract filename from key"() {
        expect:
        S3KeyUtils.extractFileNameFromKey(key) == expected

        where:
        key                                           | expected
        "path/to/file.pdf"                           | "file.pdf"
        "file.pdf"                                   | "file.pdf"
        "deep/nested/path/document.pdf"              | "document.pdf"
        "recipient@example.com/sender@example.com/invoice.pdf" | "invoice.pdf"
    }

    def "extractFileNameFromKey should return key if no slash"() {
        expect:
        S3KeyUtils.extractFileNameFromKey("filename.pdf") == "filename.pdf"
    }

    def "extractFileNameFromKey should return unknown for null"() {
        expect:
        S3KeyUtils.extractFileNameFromKey(null) == "unknown"
    }

    def "extractFileNameFromKey should return unknown for empty string"() {
        expect:
        S3KeyUtils.extractFileNameFromKey("") == "unknown"
    }

    def "extractFileNameFromKey should handle key ending with slash"() {
        expect:
        // When lastSlashIndex == key.length() - 1, falls through to return key
        S3KeyUtils.extractFileNameFromKey("path/to/directory/") == "path/to/directory/"
    }

    def "extractDirectoryFromKey should extract directory path"() {
        expect:
        S3KeyUtils.extractDirectoryFromKey(key) == expected

        where:
        key                              | expected
        "path/to/file.pdf"              | "path/to"
        "single/file.pdf"               | "single"
        "deep/nested/path/doc.pdf"      | "deep/nested/path"
        "file.pdf"                      | ""
    }

    def "extractDirectoryFromKey should return empty for no directory"() {
        expect:
        S3KeyUtils.extractDirectoryFromKey("file.pdf") == ""
    }

    def "extractDirectoryFromKey should return empty for null"() {
        expect:
        S3KeyUtils.extractDirectoryFromKey(null) == ""
    }

    def "extractDirectoryFromKey should return empty for empty string"() {
        expect:
        S3KeyUtils.extractDirectoryFromKey("") == ""
    }

    def "isFileKey should return true for file keys"() {
        expect:
        S3KeyUtils.isFileKey(key)

        where:
        key << [
            "path/to/file.pdf",
            "file.pdf",
            "deep/nested/path/document.pdf",
            "a/b/c/d/e.txt"
        ]
    }

    def "isFileKey should return false for directory keys"() {
        expect:
        !S3KeyUtils.isFileKey(key)

        where:
        key << [
            "path/to/directory/",
            "/",
            "folder/"
        ]
    }

    def "isFileKey should return false for null or empty"() {
        expect:
        !S3KeyUtils.isFileKey(key)

        where:
        key << [null, ""]
    }

    def "generateS3Key should create unique keys for same filename"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("same.pdf")
            .build()

        when:
        def key1 = S3KeyUtils.generateS3Key("docs", fileObject)
        def key2 = S3KeyUtils.generateS3Key("docs", fileObject)

        then:
        key1 != key2 // Different UUIDs
    }

    def "generateS3Key should handle nested prefixes"() {
        given:
        def fileObject = FileObject.builder()
            .fileName("test.pdf")
            .build()

        when:
        def key = S3KeyUtils.generateS3Key("level1/level2/level3", fileObject)

        then:
        key.startsWith("level1/level2/level3/")
    }

    def "generateS3Key should handle various special characters in filename"() {
        given:
        def fileObject = FileObject.builder()
            .fileName(filename)
            .build()

        when:
        def key = S3KeyUtils.generateS3Key("docs", fileObject)

        then:
        key.contains("docs/")
        !key.contains(badChar)

        where:
        filename                  | badChar
        "file:name.pdf"          | ":"
        "file*name.pdf"          | "*"
        "file?name.pdf"          | "?"
        "file<name.pdf"          | "<"
        "file>name.pdf"          | ">"
        "file|name.pdf"          | "|"
        "file name.pdf"          | " "
        "file\tname.pdf"         | "\t"
        "file\nname.pdf"         | "\n"
    }
}
