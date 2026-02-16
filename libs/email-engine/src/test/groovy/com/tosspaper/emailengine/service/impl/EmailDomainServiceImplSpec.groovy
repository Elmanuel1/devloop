package com.tosspaper.emailengine.service.impl

import org.springframework.web.client.RestTemplate
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for EmailDomainServiceImpl to ensure domain validation
 * correctly identifies personal and disposable domains.
 */
class EmailDomainServiceImplSpec extends Specification {

    @TempDir
    Path tempDir

    @Subject
    EmailDomainServiceImpl service

    def setup() {
        service = new EmailDomainServiceImpl()
    }

    // ==================== Personal Domain Tests ====================

    def "should identify gmail.com as blocked domain"() {
        expect:
        service.isBlockedDomain("gmail.com") == true
    }

    def "should identify yahoo.com as blocked domain"() {
        expect:
        service.isBlockedDomain("yahoo.com") == true
    }

    def "should identify outlook.com as blocked domain"() {
        expect:
        service.isBlockedDomain("outlook.com") == true
    }

    def "should identify hotmail.com as blocked domain"() {
        expect:
        service.isBlockedDomain("hotmail.com") == true
    }

    def "should identify live.com as blocked domain"() {
        expect:
        service.isBlockedDomain("live.com") == true
    }

    def "should identify icloud.com as blocked domain"() {
        expect:
        service.isBlockedDomain("icloud.com") == true
    }

    def "should identify aol.com as blocked domain"() {
        expect:
        service.isBlockedDomain("aol.com") == true
    }

    def "should identify mail.com as blocked domain"() {
        expect:
        service.isBlockedDomain("mail.com") == true
    }

    def "should identify protonmail.com as blocked domain"() {
        expect:
        service.isBlockedDomain("protonmail.com") == true
    }

    def "should identify yandex.com as blocked domain"() {
        expect:
        service.isBlockedDomain("yandex.com") == true
    }

    // ==================== Case Insensitivity Tests ====================

    def "should be case insensitive for personal domains"() {
        expect:
        service.isBlockedDomain("GMAIL.COM") == true
        service.isBlockedDomain("Gmail.Com") == true
        service.isBlockedDomain("gMaIl.CoM") == true
    }

    // ==================== Non-Blocked Domain Tests ====================

    def "should not block business domain"() {
        expect:
        service.isBlockedDomain("acme-corp.com") == false
    }

    def "should not block vendor domain"() {
        expect:
        service.isBlockedDomain("vendor-solutions.com") == false
    }

    // ==================== Null/Empty Handling Tests ====================

    def "should return false for null domain"() {
        expect:
        service.isBlockedDomain(null) == false
    }

    def "should return false for empty domain"() {
        expect:
        service.isBlockedDomain("") == false
    }

    def "should return false for blank domain"() {
        expect:
        service.isBlockedDomain("   ") == false
    }

    // ==================== Disposable Domain Tests ====================

    def "should load disposable domains from file on initialization"() {
        given:
        def blocklistFile = tempDir.resolve("disposable-domains.txt")
        Files.write(blocklistFile, """
# Disposable email domains
tempmail.com
guerrillamail.com
10minutemail.com
        """.trim().bytes)

        service.blocklistFile = blocklistFile.toString()

        when:
        service.loadBlocklistFromFile()

        then:
        service.isBlockedDomain("tempmail.com") == true
        service.isBlockedDomain("guerrillamail.com") == true
        service.isBlockedDomain("10minutemail.com") == true
    }

    def "should skip comment lines in blocklist file"() {
        given:
        def blocklistFile = tempDir.resolve("disposable-domains.txt")
        Files.write(blocklistFile, """
# This is a comment
spam.com
# Another comment
trash.com
        """.trim().bytes)

        service.blocklistFile = blocklistFile.toString()

        when:
        service.loadBlocklistFromFile()

        then:
        service.isBlockedDomain("spam.com") == true
        service.isBlockedDomain("trash.com") == true
    }

    def "should skip empty lines in blocklist file"() {
        given:
        def blocklistFile = tempDir.resolve("disposable-domains.txt")
        Files.write(blocklistFile, """
spam.com

trash.com

        """.bytes)

        service.blocklistFile = blocklistFile.toString()

        when:
        service.loadBlocklistFromFile()

        then:
        service.isBlockedDomain("spam.com") == true
        service.isBlockedDomain("trash.com") == true
    }

    def "should trim whitespace from domains in blocklist"() {
        given:
        def blocklistFile = tempDir.resolve("disposable-domains.txt")
        Files.write(blocklistFile, "  spam.com  \n  trash.com  ".bytes)

        service.blocklistFile = blocklistFile.toString()

        when:
        service.loadBlocklistFromFile()

        then:
        service.isBlockedDomain("spam.com") == true
        service.isBlockedDomain("trash.com") == true
    }

    def "should convert disposable domains to lowercase"() {
        given:
        def blocklistFile = tempDir.resolve("disposable-domains.txt")
        Files.write(blocklistFile, "SPAM.COM\nTRASH.com".bytes)

        service.blocklistFile = blocklistFile.toString()

        when:
        service.loadBlocklistFromFile()

        then:
        service.isBlockedDomain("spam.com") == true
        service.isBlockedDomain("SPAM.COM") == true
        service.isBlockedDomain("trash.com") == true
    }

    def "should handle missing blocklist file gracefully"() {
        given:
        service.blocklistFile = "/nonexistent/path/blocklist.txt"

        when:
        service.loadBlocklistFromFile()

        then:
        noExceptionThrown()
    }

    def "should combine personal and disposable domains"() {
        given:
        def blocklistFile = tempDir.resolve("disposable-domains.txt")
        Files.write(blocklistFile, "tempmail.com".bytes)

        service.blocklistFile = blocklistFile.toString()

        when:
        service.loadBlocklistFromFile()

        then:
        service.isBlockedDomain("gmail.com") == true // personal
        service.isBlockedDomain("tempmail.com") == true // disposable
        service.isBlockedDomain("business.com") == false // neither
    }

    // ==================== updateBlocklistFromGitHub Tests ====================

    def "updateBlocklistFromGitHub should update disposable domains from remote source"() {
        given: "a mock RestTemplate that returns blocklist content"
        def mockRestTemplate = Mock(RestTemplate)
        def blocklistContent = """
# GitHub blocklist
newdisposable.com
throwaway.email
        """.trim()

        and: "a temporary file for persistence"
        def blocklistFile = tempDir.resolve("disposable-domains.txt")
        service.blocklistFile = blocklistFile.toString()
        service.fetchUrl = "https://example.com/blocklist.txt"

        // Use reflection to inject the mock RestTemplate
        def field = EmailDomainServiceImpl.getDeclaredField("restTemplate")
        field.setAccessible(true)
        field.set(service, mockRestTemplate)

        when: "updating from GitHub"
        service.updateBlocklistFromGitHub()

        then: "RestTemplate is called with correct URL"
        1 * mockRestTemplate.getForObject("https://example.com/blocklist.txt", String.class) >> blocklistContent

        and: "new domains are loaded"
        service.isBlockedDomain("newdisposable.com") == true
        service.isBlockedDomain("throwaway.email") == true

        and: "personal domains are still blocked"
        service.isBlockedDomain("gmail.com") == true

        and: "blocklist is saved to file"
        def savedContent = Files.readString(blocklistFile)
        savedContent.contains("newdisposable.com")
        savedContent.contains("throwaway.email")
    }

    def "updateBlocklistFromGitHub should handle empty response gracefully"() {
        given: "a mock RestTemplate that returns empty content"
        def mockRestTemplate = Mock(RestTemplate)
        service.fetchUrl = "https://example.com/blocklist.txt"

        def field = EmailDomainServiceImpl.getDeclaredField("restTemplate")
        field.setAccessible(true)
        field.set(service, mockRestTemplate)

        when: "updating from GitHub with empty response"
        service.updateBlocklistFromGitHub()

        then: "RestTemplate returns empty"
        1 * mockRestTemplate.getForObject(_, String.class) >> ""

        and: "no exception is thrown"
        noExceptionThrown()

        and: "personal domains are still blocked"
        service.isBlockedDomain("gmail.com") == true
    }

    def "updateBlocklistFromGitHub should handle null response gracefully"() {
        given: "a mock RestTemplate that returns null"
        def mockRestTemplate = Mock(RestTemplate)
        service.fetchUrl = "https://example.com/blocklist.txt"

        def field = EmailDomainServiceImpl.getDeclaredField("restTemplate")
        field.setAccessible(true)
        field.set(service, mockRestTemplate)

        when: "updating from GitHub with null response"
        service.updateBlocklistFromGitHub()

        then: "RestTemplate returns null"
        1 * mockRestTemplate.getForObject(_, String.class) >> null

        and: "no exception is thrown"
        noExceptionThrown()
    }

    def "updateBlocklistFromGitHub should handle network errors gracefully"() {
        given: "a mock RestTemplate that throws exception"
        def mockRestTemplate = Mock(RestTemplate)
        service.fetchUrl = "https://example.com/blocklist.txt"

        def field = EmailDomainServiceImpl.getDeclaredField("restTemplate")
        field.setAccessible(true)
        field.set(service, mockRestTemplate)

        when: "updating from GitHub fails"
        service.updateBlocklistFromGitHub()

        then: "RestTemplate throws exception"
        1 * mockRestTemplate.getForObject(_, String.class) >> { throw new RuntimeException("Network error") }

        and: "exception is handled gracefully"
        noExceptionThrown()
    }

    def "updateBlocklistFromGitHub should skip comment lines from remote source"() {
        given: "a mock RestTemplate with comments in content"
        def mockRestTemplate = Mock(RestTemplate)
        def blocklistContent = """
# This is a comment
disposable1.com
# Another comment
disposable2.com
        """.trim()

        service.fetchUrl = "https://example.com/blocklist.txt"
        service.blocklistFile = tempDir.resolve("test.txt").toString()

        def field = EmailDomainServiceImpl.getDeclaredField("restTemplate")
        field.setAccessible(true)
        field.set(service, mockRestTemplate)

        when: "updating from GitHub"
        service.updateBlocklistFromGitHub()

        then:
        1 * mockRestTemplate.getForObject(_, String.class) >> blocklistContent

        and: "only non-comment domains are loaded"
        service.isBlockedDomain("disposable1.com") == true
        service.isBlockedDomain("disposable2.com") == true
    }

    def "updateBlocklistFromGitHub should handle file write errors gracefully"() {
        given: "a mock RestTemplate and invalid file path"
        def mockRestTemplate = Mock(RestTemplate)
        def blocklistContent = "disposable.com"

        service.fetchUrl = "https://example.com/blocklist.txt"
        service.blocklistFile = "/invalid/path/that/does/not/exist/blocklist.txt"

        def field = EmailDomainServiceImpl.getDeclaredField("restTemplate")
        field.setAccessible(true)
        field.set(service, mockRestTemplate)

        when: "updating from GitHub with invalid file path"
        service.updateBlocklistFromGitHub()

        then: "content is fetched"
        1 * mockRestTemplate.getForObject(_, String.class) >> blocklistContent

        and: "no exception is thrown despite file write failure"
        noExceptionThrown()

        and: "domains are still loaded in memory"
        service.isBlockedDomain("disposable.com") == true
    }

    def "updateBlocklistFromGitHub should replace existing domains"() {
        given: "initial blocklist loaded from file"
        def blocklistFile = tempDir.resolve("disposable-domains.txt")
        Files.write(blocklistFile, "olddomain.com".bytes)

        service.blocklistFile = blocklistFile.toString()
        service.loadBlocklistFromFile()

        and: "new content from GitHub"
        def mockRestTemplate = Mock(RestTemplate)
        def newContent = "newdomain.com"

        service.fetchUrl = "https://example.com/blocklist.txt"

        def field = EmailDomainServiceImpl.getDeclaredField("restTemplate")
        field.setAccessible(true)
        field.set(service, mockRestTemplate)

        when: "updating from GitHub"
        service.updateBlocklistFromGitHub()

        then:
        1 * mockRestTemplate.getForObject(_, String.class) >> newContent

        and: "old domains are replaced"
        service.isBlockedDomain("olddomain.com") == false
        service.isBlockedDomain("newdomain.com") == true

        and: "personal domains are still blocked"
        service.isBlockedDomain("gmail.com") == true
    }

    def "loadBlocklistFromFile should handle IOException gracefully"() {
        given: "a file that will cause IOException"
        service.blocklistFile = "/proc/invalid-file-that-causes-io-error"

        when: "loading blocklist"
        service.loadBlocklistFromFile()

        then: "no exception is thrown"
        noExceptionThrown()
    }

    def "loadBlocklistFromFile should clear existing disposable domains before loading"() {
        given: "initial domains loaded"
        def blocklistFile = tempDir.resolve("initial.txt")
        Files.write(blocklistFile, "initial.com".bytes)
        service.blocklistFile = blocklistFile.toString()
        service.loadBlocklistFromFile()

        and: "new blocklist file with different domains"
        def newBlocklistFile = tempDir.resolve("updated.txt")
        Files.write(newBlocklistFile, "updated.com".bytes)
        service.blocklistFile = newBlocklistFile.toString()

        when: "loading new blocklist"
        service.loadBlocklistFromFile()

        then: "old domains are cleared"
        service.isBlockedDomain("initial.com") == false
        service.isBlockedDomain("updated.com") == true
    }
}
