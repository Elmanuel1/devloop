package com.tosspaper.rbac

import com.tosspaper.generated.model.RoleInfo
import com.tosspaper.models.domain.Role
import spock.lang.Specification
import spock.lang.Unroll

class RoleMapperSpec extends Specification {

    RoleMapper mapper

    def setup() {
        mapper = new RoleMapper()
    }

    // ==================== toGenerated ====================

    def "toGenerated returns null when role is null"() {
        when: "mapping null role"
            def result = mapper.toGenerated(null)

        then: "result is null"
            result == null
    }

    @Unroll
    def "toGenerated maps #role correctly to RoleInfo"() {
        given: "a domain role"
            def domainRole = role

        when: "mapping to generated model"
            def result = mapper.toGenerated(domainRole)

        then: "all fields are mapped correctly"
            result != null
            result.id == expectedId
            result.displayName == expectedDisplayName

        where:
            role           || expectedId                    || expectedDisplayName
            Role.OWNER     || RoleInfo.IdEnum.OWNER         || "Owner"
            Role.ADMIN     || RoleInfo.IdEnum.ADMIN         || "Admin"
            Role.OPERATIONS|| RoleInfo.IdEnum.OPERATIONS    || "Operations"
            Role.VIEWER    || RoleInfo.IdEnum.VIEWER        || "Viewer"
    }

    def "toGenerated creates new RoleInfo instance"() {
        given: "a domain role"
            def role = Role.ADMIN

        when: "mapping twice"
            def result1 = mapper.toGenerated(role)
            def result2 = mapper.toGenerated(role)

        then: "creates separate instances"
            result1 != null
            result2 != null
            !result1.is(result2)
    }

    def "toGenerated preserves all role information"() {
        given: "owner role"
            def role = Role.OWNER

        when: "mapping to generated"
            def result = mapper.toGenerated(role)

        then: "all information is preserved"
            result.id.value == role.id
            result.displayName == role.displayName
    }
}
