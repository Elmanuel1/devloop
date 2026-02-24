package com.tosspaper.common.validator

import jakarta.validation.Constraint
import jakarta.validation.Payload
import spock.lang.Specification

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

class ValidPresignedUploadSpec extends Specification {

    def "should have @Constraint meta-annotation with empty validatedBy"() {
        given: "the ValidPresignedUpload annotation class"
            def annotationType = ValidPresignedUpload

        when: "reading the @Constraint meta-annotation"
            def constraint = annotationType.getAnnotation(Constraint)

        then: "it is present and has no validatedBy entries"
            constraint != null
            constraint.validatedBy().length == 0
    }

    def "should have @Target with TYPE and PARAMETER element types"() {
        given: "the ValidPresignedUpload annotation class"
            def annotationType = ValidPresignedUpload

        when: "reading the @Target meta-annotation"
            def target = annotationType.getAnnotation(Target)

        then: "it targets TYPE and PARAMETER"
            target != null
            target.value().contains(ElementType.TYPE)
            target.value().contains(ElementType.PARAMETER)
            target.value().length == 2
    }

    def "should have @Retention set to RUNTIME"() {
        given: "the ValidPresignedUpload annotation class"
            def annotationType = ValidPresignedUpload

        when: "reading the @Retention meta-annotation"
            def retention = annotationType.getAnnotation(Retention)

        then: "retention policy is RUNTIME"
            retention != null
            retention.value() == RetentionPolicy.RUNTIME
    }

    def "should declare a message attribute with default value"() {
        given: "the ValidPresignedUpload annotation class"

        when: "reading the message attribute method"
            def messageMethod = ValidPresignedUpload.getDeclaredMethod("message")

        then: "it exists and has the expected default value"
            messageMethod != null
            messageMethod.defaultValue == "Invalid upload request"
    }

    def "should declare a groups attribute defaulting to empty array"() {
        given: "the ValidPresignedUpload annotation class"

        when: "reading the groups attribute method"
            def groupsMethod = ValidPresignedUpload.getDeclaredMethod("groups")

        then: "it exists and defaults to an empty Class array"
            groupsMethod != null
            groupsMethod.returnType == Class[].class
            (groupsMethod.defaultValue as Class[]).length == 0
    }

    def "should declare a payload attribute defaulting to empty array"() {
        given: "the ValidPresignedUpload annotation class"

        when: "reading the payload attribute method"
            def payloadMethod = ValidPresignedUpload.getDeclaredMethod("payload")

        then: "it exists, accepts Payload subclasses, and defaults to empty array"
            payloadMethod != null
            (payloadMethod.defaultValue as Class[]).length == 0
    }

    def "annotation type itself should be an annotation"() {
        expect: "ValidPresignedUpload is a proper annotation type"
            ValidPresignedUpload.isAnnotation()
    }
}
