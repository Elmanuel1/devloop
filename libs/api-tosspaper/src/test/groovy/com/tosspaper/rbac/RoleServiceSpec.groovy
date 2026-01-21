package com.tosspaper.rbac

import com.tosspaper.generated.model.RoleInfo
import com.tosspaper.models.domain.Role
import spock.lang.Specification

class RoleServiceSpec extends Specification {

    RoleMapper roleMapper
    RoleServiceImpl service

    def setup() {
        roleMapper = Mock()
        service = new RoleServiceImpl(roleMapper)
    }

    // ==================== getRoles ====================

    def "getRoles returns all available roles"() {
        given: "role mapper configured"
            def ownerInfo = createRoleInfo("owner", "Owner")
            def adminInfo = createRoleInfo("admin", "Admin")
            def operationsInfo = createRoleInfo("operations", "Operations")
            def viewerInfo = createRoleInfo("viewer", "Viewer")

        when: "fetching roles"
            def result = service.getRoles()

        then: "each role is mapped"
            1 * roleMapper.toGenerated(Role.OWNER) >> ownerInfo
            1 * roleMapper.toGenerated(Role.ADMIN) >> adminInfo
            1 * roleMapper.toGenerated(Role.OPERATIONS) >> operationsInfo
            1 * roleMapper.toGenerated(Role.VIEWER) >> viewerInfo

        and: "result contains all roles"
            with(result) {
                size() == 4
            }
    }

    def "getRoles returns roles in consistent order"() {
        given: "role mapper configured"
            Role.values().each { role ->
                roleMapper.toGenerated(role) >> createRoleInfo(role.id, role.displayName)
            }

        when: "fetching roles multiple times"
            def result1 = service.getRoles()
            def result2 = service.getRoles()

        then: "order is consistent"
            result1.collect { it.id } == result2.collect { it.id }
    }

    def "getRoles maps all role properties correctly"() {
        given: "owner role mapper configured"
            def ownerInfo = new RoleInfo()
            ownerInfo.id = RoleInfo.IdEnum.OWNER
            ownerInfo.displayName = "Owner"

        when: "fetching roles"
            def result = service.getRoles()

        then: "owner role is mapped with all properties"
            1 * roleMapper.toGenerated(Role.OWNER) >> ownerInfo
            _ * roleMapper.toGenerated(_) >> createRoleInfo("viewer", "Viewer")

        and: "owner role has correct properties"
            def owner = result.find { it.id == RoleInfo.IdEnum.OWNER }
            with(owner) {
                id == RoleInfo.IdEnum.OWNER
                displayName == "Owner"
            }
    }

    // ==================== Helper Methods ====================

    private RoleInfo createRoleInfo(String id, String displayName) {
        def roleInfo = new RoleInfo()
        roleInfo.id = RoleInfo.IdEnum.fromValue(id)
        roleInfo.displayName = displayName
        return roleInfo
    }
}
