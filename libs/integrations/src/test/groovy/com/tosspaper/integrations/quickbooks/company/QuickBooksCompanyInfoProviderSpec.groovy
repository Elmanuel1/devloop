package com.tosspaper.integrations.quickbooks.company

import com.intuit.ipp.data.CompanyInfo
import com.intuit.ipp.exception.FMSException
import com.intuit.ipp.services.DataService
import com.intuit.ipp.services.QueryResult
import com.tosspaper.integrations.common.exception.IntegrationException
import com.tosspaper.integrations.quickbooks.client.QuickBooksClientFactory
import com.tosspaper.integrations.quickbooks.client.QuickBooksResilienceHelper
import com.tosspaper.models.domain.integration.IntegrationProvider
import java.util.function.Supplier
import spock.lang.Specification
import spock.lang.Subject

class QuickBooksCompanyInfoProviderSpec extends Specification {

    QuickBooksClientFactory clientFactory = Mock()
    QuickBooksResilienceHelper resilienceHelper = Mock()

    @Subject
    QuickBooksCompanyInfoProvider provider = new QuickBooksCompanyInfoProvider(clientFactory, resilienceHelper)

    def "should return correct provider ID"() {
        expect:
            provider.providerId == IntegrationProvider.QUICKBOOKS
    }

    def "fetchCompanyInfo should return company info with legal name"() {
        given: "QBO returns company info with legal name"
            def qboInfo = new CompanyInfo()
            qboInfo.id = "qb-comp-1"
            qboInfo.legalName = "ACME Corp Legal"
            qboInfo.companyName = "ACME Corp"

            def queryResult = Mock(QueryResult)
            queryResult.getEntities() >> [qboInfo]

            def dataService = Mock(DataService)
            dataService.executeQuery(_) >> queryResult

            clientFactory.createDataService("token-123", "realm-456") >> dataService
            resilienceHelper.execute("realm-456", _) >> { String realmId, Supplier supplier ->
                supplier.get()
            }

        when: "fetching company info"
            def result = provider.fetchCompanyInfo("token-123", "realm-456")

        then: "legal name is preferred"
            result.companyId() == "qb-comp-1"
            result.companyName() == "ACME Corp Legal"
    }

    def "fetchCompanyInfo should use company name when legal name is null"() {
        given: "QBO returns company info without legal name"
            def qboInfo = new CompanyInfo()
            qboInfo.id = "qb-comp-2"
            qboInfo.legalName = null
            qboInfo.companyName = "ACME Corp"

            def queryResult = Mock(QueryResult)
            queryResult.getEntities() >> [qboInfo]

            def dataService = Mock(DataService)
            dataService.executeQuery(_) >> queryResult

            clientFactory.createDataService("token", "realm") >> dataService
            resilienceHelper.execute("realm", _) >> { String realmId, Supplier supplier ->
                supplier.get()
            }

        when: "fetching company info"
            def result = provider.fetchCompanyInfo("token", "realm")

        then: "company name is used as fallback"
            result.companyName() == "ACME Corp"
    }

    def "fetchCompanyInfo should throw when no company info found"() {
        given: "QBO returns empty results"
            def queryResult = Mock(QueryResult)
            queryResult.getEntities() >> []

            def dataService = Mock(DataService)
            dataService.executeQuery(_) >> queryResult

            clientFactory.createDataService("token", "realm") >> dataService
            resilienceHelper.execute("realm", _) >> { String realmId, Supplier supplier ->
                supplier.get()
            }

        when: "fetching company info"
            provider.fetchCompanyInfo("token", "realm")

        then: "exception thrown"
            thrown(IntegrationException)
    }

    def "fetchCompanyInfo should throw when null results"() {
        given: "QBO returns null entity list"
            def queryResult = Mock(QueryResult)
            queryResult.getEntities() >> null

            def dataService = Mock(DataService)
            dataService.executeQuery(_) >> queryResult

            clientFactory.createDataService("token", "realm") >> dataService
            resilienceHelper.execute("realm", _) >> { String realmId, Supplier supplier ->
                supplier.get()
            }

        when: "fetching company info"
            provider.fetchCompanyInfo("token", "realm")

        then: "exception thrown"
            thrown(IntegrationException)
    }

    def "fetchCompanyInfo should wrap FMSException in IntegrationException"() {
        given: "QBO throws FMSException"
            def dataService = Mock(DataService)
            dataService.executeQuery(_) >> { throw new FMSException("QBO error") }

            clientFactory.createDataService("token", "realm") >> dataService
            resilienceHelper.execute("realm", _) >> { String realmId, Supplier supplier ->
                supplier.get()
            }

        when: "fetching company info"
            provider.fetchCompanyInfo("token", "realm")

        then: "IntegrationException thrown"
            thrown(IntegrationException)
    }
}
