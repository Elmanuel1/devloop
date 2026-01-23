package com.tosspaper.integrations.common

import com.tosspaper.integrations.provider.IntegrationEntityType
import com.tosspaper.models.domain.PurchaseOrder
import com.tosspaper.models.domain.integration.IntegrationConnection
import spock.lang.Specification
import spock.lang.Subject

class DependencyCoordinatorServiceImplSpec extends Specification {

    def connection = Mock(IntegrationConnection)

    @Subject
    DependencyCoordinatorServiceImpl coordinator

    def "should return success when entities list is null"() {
        given:
        def poStrategy = Mock(DependencyStrategy)
        coordinator = new DependencyCoordinatorServiceImpl([poStrategy])

        when:
        def result = coordinator.ensureAllDependencies(connection, IntegrationEntityType.PURCHASE_ORDER, null)

        then:
        result.success
        0 * poStrategy._
    }

    def "should return success when entities list is empty"() {
        given:
        def vendorStrategy = Mock(DependencyStrategy)
        coordinator = new DependencyCoordinatorServiceImpl([vendorStrategy])

        when:
        def result = coordinator.ensureAllDependencies(connection, IntegrationEntityType.VENDOR, [])

        then:
        result.success
        0 * vendorStrategy._
    }

    def "should find and use supporting strategy"() {
        given:
        def itemStrategy = Mock(DependencyStrategy)
        def poStrategy = Mock(DependencyStrategy)
        def entities = [Mock(PurchaseOrder)]
        coordinator = new DependencyCoordinatorServiceImpl([itemStrategy, poStrategy])

        itemStrategy.supports(IntegrationEntityType.PURCHASE_ORDER) >> false
        poStrategy.supports(IntegrationEntityType.PURCHASE_ORDER) >> true
        poStrategy.ensureDependencies(connection, entities) >> DependencyPushResult.success()

        when:
        def result = coordinator.ensureAllDependencies(connection, IntegrationEntityType.PURCHASE_ORDER, entities)

        then:
        result.success
    }

    def "should return success when no strategy supports entity type"() {
        given:
        def customerStrategy = Mock(DependencyStrategy)
        def vendorStrategy = Mock(DependencyStrategy)
        def entities = [Mock(PurchaseOrder)]
        coordinator = new DependencyCoordinatorServiceImpl([customerStrategy, vendorStrategy])

        customerStrategy.supports(IntegrationEntityType.ITEM) >> false
        vendorStrategy.supports(IntegrationEntityType.ITEM) >> false

        when:
        def result = coordinator.ensureAllDependencies(connection, IntegrationEntityType.ITEM, entities)

        then:
        result.success
    }

    def "should propagate failure from strategy"() {
        given:
        def poStrategy = Mock(DependencyStrategy)
        def entities = [Mock(PurchaseOrder)]
        coordinator = new DependencyCoordinatorServiceImpl([poStrategy])

        poStrategy.supports(IntegrationEntityType.PURCHASE_ORDER) >> true
        poStrategy.ensureDependencies(connection, entities) >> DependencyPushResult.failure("Vendor push failed")

        when:
        def result = coordinator.ensureAllDependencies(connection, IntegrationEntityType.PURCHASE_ORDER, entities)

        then:
        !result.success
        result.message == "Vendor push failed"
    }

    def "should use first matching strategy"() {
        given:
        def firstStrategy = Mock(DependencyStrategy)
        def secondStrategy = Mock(DependencyStrategy)
        def entities = [Mock(PurchaseOrder)]
        coordinator = new DependencyCoordinatorServiceImpl([firstStrategy, secondStrategy])

        firstStrategy.supports(IntegrationEntityType.PURCHASE_ORDER) >> true
        firstStrategy.ensureDependencies(connection, entities) >> DependencyPushResult.success()
        secondStrategy.supports(IntegrationEntityType.PURCHASE_ORDER) >> true

        when:
        def result = coordinator.ensureAllDependencies(connection, IntegrationEntityType.PURCHASE_ORDER, entities)

        then:
        result.success
        0 * secondStrategy.ensureDependencies(_, _)
    }
}
