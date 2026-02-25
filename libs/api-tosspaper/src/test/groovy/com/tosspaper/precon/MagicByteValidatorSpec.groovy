package com.tosspaper.precon

import com.tosspaper.models.validation.MagicByteValidator
import spock.lang.Specification

class MagicByteValidatorSpec extends Specification {

    def "should validate PDF magic bytes"() {
        given:
            // %PDF = 0x25 0x50 0x44 0x46
            byte[] header = [0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34] as byte[]

        when:
            def result = MagicByteValidator.validate(header, "application/pdf")

        then:
            result
    }

    def "should validate PNG magic bytes"() {
        given:
            // PNG header: 0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A
            byte[] header = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] as byte[]

        when:
            def result = MagicByteValidator.validate(header, "image/png")

        then:
            result
    }

    def "should validate JPEG magic bytes"() {
        given:
            // JPEG header: 0xFF 0xD8 0xFF
            byte[] header = [0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46] as byte[]

        when:
            def result = MagicByteValidator.validate(header, "image/jpeg")

        then:
            result
    }

    def "should reject wrong bytes for PDF"() {
        given:
            // PNG bytes passed as PDF
            byte[] header = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] as byte[]

        when:
            def result = MagicByteValidator.validate(header, "application/pdf")

        then:
            !result
    }

    def "should reject unknown content type"() {
        given:
            byte[] header = [0x25, 0x50, 0x44, 0x46] as byte[]

        when:
            def result = MagicByteValidator.validate(header, "text/plain")

        then:
            !result
    }

    def "should reject empty bytes"() {
        given:
            byte[] header = [] as byte[]

        when:
            def result = MagicByteValidator.validate(header, "application/pdf")

        then:
            !result
    }

    def "should reject null header"() {
        when:
            def result = MagicByteValidator.validate(null, "application/pdf")

        then:
            !result
    }

    def "should reject null content type"() {
        given:
            byte[] header = [0x25, 0x50, 0x44, 0x46] as byte[]

        when:
            def result = MagicByteValidator.validate(header, null)

        then:
            !result
    }

    def "should reject header that is too short for PDF"() {
        given:
            byte[] header = [0x25, 0x50, 0x44] as byte[]

        when:
            def result = MagicByteValidator.validate(header, "application/pdf")

        then:
            !result
    }

    def "should reject header that is too short for PNG"() {
        given:
            byte[] header = [0x89, 0x50, 0x4E, 0x47] as byte[]

        when:
            def result = MagicByteValidator.validate(header, "image/png")

        then:
            !result
    }
}
