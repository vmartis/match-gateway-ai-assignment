package com.vmartis.matchgateway.gateway.auth.infra

import jakarta.servlet.DispatcherType
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Deny by default, stateless, bearer-only (CSRF off – ADR-005). Scope checks are URL rules here, so a caller without the
 * scope is rejected before the request body is read (D-18); tenant ownership is enforced in SQL by each feature.
 * Public without a token: the standard actuator health endpoint and its probes (D-23).
 */
@Configuration
class SecurityConfig {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        entryPoint: ProblemAuthenticationEntryPoint,
        accessDenied: ProblemAccessDeniedHandler,
    ): SecurityFilterChain {
        http {
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            // Machine callers only: an empty CORS allowlist, so browsers never get Access-Control-Allow-Origin.
            cors { configurationSource = UrlBasedCorsConfigurationSource() }
            headers {
                contentSecurityPolicy { policyDirectives = "frame-ancestors 'none'" }
                referrerPolicy { policy = ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER }
            }
            authorizeHttpRequests {
                // Standard actuator health (incl. liveness/readiness groups) is public; no other endpoint is exposed (D-23).
                authorize(EndpointRequest.to(HealthEndpoint::class.java), permitAll)
                // Container error dispatches (e.g. 404 for an unknown path) carry no authentication in a stateless chain.
                authorize(DispatcherTypeRequestMatcher(DispatcherType.ERROR), permitAll)
                // Scope rules per PATH for every method: HEAD/PATCH/… cannot slip past a GET-only rule (review step 2).
                authorize("/v1/subjects/{subjectId}/enrollment", hasAuthority("SCOPE_enroll"))
                authorize("/v1/subjects/{subjectId}", hasAuthority("SCOPE_admin"))
                authorize("/v1/verifications", hasAuthority("SCOPE_verify"))
                authorize("/v1/verifications/{verificationId}", hasAuthority("SCOPE_verify"))
                authorize("/v1/tenant/threshold", hasAuthority("SCOPE_admin"))
                // Deny by default: an endpoint without a rule here is unreachable (ARCHITECTURE T2).
                authorize(anyRequest, denyAll)
            }
            oauth2ResourceServer {
                jwt { jwtAuthenticationConverter = scopeOnlyAuthenticationConverter() }
                authenticationEntryPoint = entryPoint
                accessDeniedHandler = accessDenied
            }
            exceptionHandling {
                authenticationEntryPoint = entryPoint
                accessDeniedHandler = accessDenied
            }
        }
        return http.build()
    }
}

/** Authorities only from the `scope` claim (D-5) – the default converter would also read `scp`. */
private fun scopeOnlyAuthenticationConverter(): JwtAuthenticationConverter =
    JwtAuthenticationConverter().apply {
        setJwtGrantedAuthoritiesConverter(
            JwtGrantedAuthoritiesConverter().apply {
                setAuthoritiesClaimName("scope")
                setAuthorityPrefix("SCOPE_")
            },
        )
    }
