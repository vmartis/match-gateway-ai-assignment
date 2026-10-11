package com.vmartis.matchgateway.devtoken

import org.springframework.security.converter.RsaKeyConverters
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Mints RS256 dev tokens with the claims the gateway requires (brief L21–22, ADR-004). */
object TokenMinter {
    private val TENANT = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val SCOPES = setOf("enroll", "verify", "admin")
    private val MAX_TTL: Duration = Duration.ofMinutes(15)

    fun mint(
        privateKeyPem: Path,
        tenant: String,
        subject: String,
        scope: String,
        ttl: Duration = MAX_TTL,
        issuer: String = "https://match-gateway.invalid/dev-issuer",
        audience: String = "match-gateway",
        clock: Clock = Clock.systemUTC(),
    ): String {
        require(!ttl.isNegative && !ttl.isZero && ttl <= MAX_TTL) { "ttl must be > 0 and <= 15 min" }
        require(TENANT.matches(tenant)) { "tenant must match ${TENANT.pattern}" }
        require(subject.isNotBlank()) { "subject must not be blank" }
        // Spring's encoder reads `iss` as a URL (JwtClaimsSet.getIssuer), so a non-URL issuer would fail later anyway.
        require(runCatching { java.net.URI(issuer).toURL() }.isSuccess) { "issuer must be a URL" }
        val scopes = scope.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(scopes.isNotEmpty() && SCOPES.containsAll(scopes)) { "scope must be a space-separated subset of $SCOPES" }

        val privateKey =
            Files.newInputStream(privateKeyPem).use { RsaKeyConverters.pkcs8().convert(it) }
                as? RSAPrivateCrtKey ?: error("private key must be an RSA PKCS#8 key with CRT parameters")
        val publicKey =
            KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent))
                as RSAPublicKey

        val issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val claims =
            JwtClaimsSet
                .builder()
                .issuer(issuer)
                .audience(listOf(audience))
                .subject(subject)
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .id(UUID.randomUUID().toString())
                .claim("tenant_id", tenant)
                .claim("scope", scopes.joinToString(" "))
                .build()
        val header = JwsHeader.with(SignatureAlgorithm.RS256).build()
        return NimbusJwtEncoder
            .withKeyPair(publicKey, privateKey)
            .build()
            .encode(JwtEncoderParameters.from(header, claims))
            .tokenValue
    }
}
