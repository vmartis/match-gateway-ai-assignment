package com.vmartis.matchgateway.gateway.auth.infra

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusScrapeEndpoint
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * Deny by default. Public without a token: GET /actuator/health on the API port (D-19) and, on the
 * internal management port only, health + prometheus (D-6; EndpointRequest only matches the management
 * server when it runs on its own port). CSRF is off because the API is stateless and bearer-only (ADR-005).
 */
@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        entryPoint: ProblemAuthenticationEntryPoint,
    ): SecurityFilterChain {
        http {
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            authorizeHttpRequests {
                authorize(EndpointRequest.to(HealthEndpoint::class.java, PrometheusScrapeEndpoint::class.java), permitAll)
                authorize(HttpMethod.GET, "/actuator/health", permitAll)
                authorize(anyRequest, authenticated)
            }
            exceptionHandling { authenticationEntryPoint = entryPoint }
        }
        return http.build()
    }
}
