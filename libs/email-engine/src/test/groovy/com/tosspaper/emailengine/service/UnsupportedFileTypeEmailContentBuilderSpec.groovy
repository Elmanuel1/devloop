package com.tosspaper.emailengine.service

import com.tosspaper.models.domain.FileObject
import com.tosspaper.models.properties.FileProperties
import spock.lang.Specification
import spock.lang.Subject

import java.time.OffsetDateTime

class UnsupportedFileTypeEmailContentBuilderSpec extends Specification {

    FileProperties fileProperties = Stub() {
        getAllowedContentTypes() >> (["application/pdf", "image/png", "image/jpeg"] as Set)
    }

    @Subject
    UnsupportedFileTypeEmailContentBuilder builder

    def setup() {
        builder = new UnsupportedFileTypeEmailContentBuilder(fileProperties)
    }

    def "should build correct subject"() {
        when:
        def subject = builder.buildSubject()

        then:
        subject == "Unsupported File Type"
    }

    def "should build body for single file"() {
        given:
        def file = FileObject.builder()
            .fileName("document.exe")
            .contentType("application/x-executable")
            .build()

        def receivedAt = OffsetDateTime.parse("2024-01-15T10:30:00Z")

        when:
        def body = builder.buildBody("sender@example.com", "inbox@company.com", [file], receivedAt)

        then:
        body.contains("1 attached file has an unsupported")
        body.contains("document.exe")
        body.contains("application/x-executable")
        body.contains("January 15, 2024")
        body.contains("application/pdf")
    }

    def "should build body for multiple files"() {
        given:
        def file1 = FileObject.builder().fileName("doc.exe").contentType("application/x-executable").build()
        def file2 = FileObject.builder().fileName("archive.zip").contentType("application/zip").build()

        def receivedAt = OffsetDateTime.now()

        when:
        def body = builder.buildBody("sender@example.com", "inbox@company.com", [file1, file2], receivedAt)

        then:
        body.contains("2 attached files have")
        body.contains("doc.exe")
        body.contains("archive.zip")
    }

    def "should handle null fileName"() {
        given:
        def file = FileObject.builder().fileName(null).contentType("application/zip").build()

        when:
        def body = builder.buildBody("sender@example.com", "inbox@company.com", [file], OffsetDateTime.now())

        then:
        body.contains("unknown")
    }

    def "should handle null contentType"() {
        given:
        def file = FileObject.builder().fileName("file.exe").contentType(null).build()

        when:
        def body = builder.buildBody("sender@example.com", "inbox@company.com", [file], OffsetDateTime.now())

        then:
        body.contains("file.exe")
        body.contains("unknown")
    }
}
