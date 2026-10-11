package com.vmartis.matchgateway.gateway.shared.api

import org.apache.catalina.connector.Connector
import org.apache.tomcat.util.buf.EncodedSolidusHandling
import org.springframework.boot.tomcat.TomcatConnectorCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Encoded slashes are passed through by Tomcat so Spring Security's StrictHttpFirewall rejects them; its default
 * HttpStatusRequestRejectedHandler answers 400 via the error dispatch, i.e. with the constant problem body (AC-12, AM-15).
 * What Tomcat itself rejects (e.g. `%00`, an invalid method) is answered by Boot's own hardened ErrorReportValve
 * (`include-stacktrace: never` → no report, no server info) – deliberately not replaced (CLAUDE.md §3 built-ins).
 */
@Configuration
class RequestRejectionConfig {
    @Bean
    fun encodedSlashesReachTheFirewall() =
        TomcatConnectorCustomizer { connector: Connector ->
            connector.encodedSolidusHandling = EncodedSolidusHandling.PASS_THROUGH.value
        }
}
