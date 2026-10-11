package com.vmartis.matchgateway.gateway.auth.infra

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.security.converter.RsaKeyConverters
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("app.jwt")
data class JwtProperties(
    val publicKeyLocation: Resource,
    val issuer: String,
    val audience: String,
)

/**
 * Resource-server decoder (ADR-004): public key only, RS256 pinned (rejects alg=none and HS256 key confusion),
 * exp/nbf with 60 s skew, iss, aud, required sub/iat/exp, tenant_id format, TTL ≤ 15 min, iat not in the future.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties::class)
class JwtConfig {
    @Bean
    fun jwtDecoder(properties: JwtProperties): JwtDecoder {
        val publicKey =
            properties.publicKeyLocation.inputStream.use { RsaKeyConverters.x509().convert(it) }
                ?: error("app.jwt.public-key-location does not contain an RSA public key")
        val decoder = NimbusJwtDecoder.withPublicKey(publicKey).signatureAlgorithm(SignatureAlgorithm.RS256).build()
        // Spring's default claim-set converter fills a missing `iat` from `exp`, so no validator can see it is absent:
        // reject it here, before the defaults apply (found by mutation testing in step 2).
        val defaultClaims = MappedJwtClaimSetConverter.withDefaults(emptyMap())
        decoder.setClaimSetConverter { claims ->
            if (claims[JwtClaimNames.IAT] == null) throw BadJwtException("missing iat")
            defaultClaims.convert(claims)
        }
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtTimestampValidator(MAX_CLOCK_SKEW),
                JwtIssuerValidator(properties.issuer),
                JwtAudienceValidator(properties.audience),
                RequiredClaimValidator(JwtClaimNames.SUB) { it is String && it.isNotBlank() },
                RequiredClaimValidator(JwtClaimNames.EXP) { it != null },
                RequiredClaimValidator(TENANT_CLAIM) { it is String && TENANT_ID.matches(it) },
                MaxLifetimeValidator(MAX_TOKEN_LIFETIME),
                NotIssuedInFutureValidator(MAX_CLOCK_SKEW),
            ),
        )
        return decoder
    }

    companion object {
        const val TENANT_CLAIM = "tenant_id"
        val TENANT_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
        private val MAX_CLOCK_SKEW: Duration = Duration.ofSeconds(60)
        private val MAX_TOKEN_LIFETIME: Duration = Duration.ofMinutes(15)
    }
}

/** Rejects tokens whose `exp − iat` exceeds [maxLifetime] (CLAUDE.md §4: TTL ≤ 15 min). */
class MaxLifetimeValidator(
    private val maxLifetime: Duration,
) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val issuedAt = token.issuedAt
        val expiresAt = token.expiresAt
        return if (issuedAt != null && expiresAt != null && Duration.between(issuedAt, expiresAt) <= maxLifetime) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
        }
    }
}

/**
 * Like Spring's JwtClaimValidator, but the predicate also receives a missing claim as `null` – Spring's version is
 * JSpecify-annotated with a non-null type parameter, while a missing claim is exactly what these checks must reject.
 */
class RequiredClaimValidator(
    private val claim: String,
    private val predicate: (Any?) -> Boolean,
) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult =
        if (predicate(token.claims[claim])) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
        }
}

/**
 * Rejects tokens issued in the future (beyond the clock skew). Spring's timestamp validator ignores `iat` and `nbf` is
 * optional, so without this a future-dated token with a 15-minute lifetime would stay valid until far later.
 */
class NotIssuedInFutureValidator(
    private val clockSkew: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val issuedAt = token.issuedAt
        return if (issuedAt != null && !issuedAt.isAfter(clock.instant().plus(clockSkew))) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
        }
    }
}
