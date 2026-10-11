package com.vmartis.matchgateway.gateway.security

import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/**
 * Test-only token factory. Signs with an in-memory RSA 2048 key pair whose public half is published as an
 * X.509 PEM file (the gateway's `app.jwt.public-key-location`). Every hostile variant of the JWT negative set
 * (ADR-004, AC-6) is produced here. Timestamps are relative to the wall clock because the gateway under test
 * uses the system clock.
 */
object JwtTestTokens {
    const val ISSUER = "https://match-gateway.invalid/dev-issuer"
    const val AUDIENCE = "match-gateway"

    private val mapper = JsonMapper.builder().build()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").also { it.initialize(2048) }.generateKeyPair()

    private val keyPair = newKeyPair()
    private val otherKeyPair = newKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val privateKey = keyPair.private as RSAPrivateKey

    val publicKeyLocation: String

    init {
        val pem =
            "-----BEGIN PUBLIC KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(publicKey.encoded) +
                "\n-----END PUBLIC KEY-----\n"
        val file = Files.createTempFile("jwt-test-public", ".pem")
        Files.writeString(file, pem)
        file.toFile().deleteOnExit()
        publicKeyLocation = "file:$file"
    }

    private class Spec(
        val tenant: Any? = "tenant-a",
        val sub: String? = "client-1",
        val scope: String? = "enroll verify admin",
        val issuer: String = ISSUER,
        val audience: String = AUDIENCE,
        val iat: Instant? = Instant.now(),
        val nbf: Instant? = iat,
        val exp: Instant? = iat?.plusSeconds(600),
        val scp: List<String>? = null,
    )

    private fun claims(s: Spec): JwtClaimsSet {
        val b =
            JwtClaimsSet
                .builder()
                .issuer(s.issuer)
                .audience(listOf(s.audience))
                .id(UUID.randomUUID().toString())
        s.iat?.let { b.issuedAt(it) }
        s.exp?.let { b.expiresAt(it) }
        s.scp?.let { b.claim("scp", it) }
        s.nbf?.let { b.notBefore(it) }
        s.sub?.let { b.subject(it) }
        s.tenant?.let { b.claim("tenant_id", it) }
        s.scope?.let { b.claim("scope", it) }
        return b.build()
    }

    private fun rs256(
        spec: Spec,
        pair: KeyPair = keyPair,
    ): String {
        val encoder = NimbusJwtEncoder.withKeyPair(pair.public as RSAPublicKey, pair.private as RSAPrivateKey).build()
        val header = JwsHeader.with(SignatureAlgorithm.RS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims(spec))).tokenValue
    }

    fun valid(
        tenant: String = "tenant-a",
        sub: String = "client-1",
        scope: String = "enroll verify admin",
    ): String = rs256(Spec(tenant = tenant, sub = sub, scope = scope))

    /**
     * Token with explicit timestamps given as offsets in seconds from now (null = claim omitted). Whole seconds only,
     * so exp - iat boundaries (900 / 901) are exact.
     */
    fun withTimes(
        iatOffset: Long?,
        nbfOffset: Long?,
        expOffset: Long?,
    ): String {
        val now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        return rs256(
            Spec(
                iat = iatOffset?.let { now.plusSeconds(it) },
                nbf = nbfOffset?.let { now.plusSeconds(it) },
                exp = expOffset?.let { now.plusSeconds(it) },
            ),
        )
    }

    /** Token whose tenant_id claim has an arbitrary JSON type/value (number, injection characters, ...). */
    fun withTenantClaim(value: Any?): String = rs256(Spec(tenant = value))

    /** Token that carries the OAuth2 `scp` claim only (no `scope`); the gateway must ignore it. */
    fun scpOnly(vararg scopes: String): String = rs256(Spec(scope = null, scp = scopes.toList()))

    fun expired(): String {
        val now = Instant.now()
        return rs256(Spec(iat = now.minusSeconds(900), nbf = now.minusSeconds(900), exp = now.minusSeconds(120)))
    }

    fun notYetValid(): String {
        val now = Instant.now()
        return rs256(Spec(iat = now, nbf = now.plusSeconds(120), exp = now.plusSeconds(600)))
    }

    fun wrongKey(): String = rs256(Spec(), otherKeyPair)

    fun wrongIssuer(): String = rs256(Spec(issuer = "https://evil.example"))

    fun wrongAudience(): String = rs256(Spec(audience = "some-other-api"))

    fun missingTenant(): String = rs256(Spec(tenant = null))

    fun badTenant(): String = rs256(Spec(tenant = "a b"))

    fun missingSub(): String = rs256(Spec(sub = null))

    fun ttlTooLong(): String {
        val now = Instant.now()
        return rs256(Spec(iat = now, nbf = now, exp = now.plusSeconds(901)))
    }

    fun missingScope(): String = rs256(Spec(scope = null))

    /** Unsigned token: {"alg":"none"} header, valid-looking claims, empty signature segment. */
    fun algNone(): String {
        val now = Instant.now()
        val header = b64.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = b64.encodeToString(claimsJson(now, "tenant-a").toByteArray())
        return "$header.$payload."
    }

    /** Algorithm confusion: HMAC-SHA256 signed with the RSA public key's encoded bytes as the secret. */
    fun hs256WithPublicKey(): String {
        val encoder = NimbusJwtEncoder.withSecretKey(SecretKeySpec(publicKey.encoded, "HmacSHA256")).build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return encoder.encode(JwtEncoderParameters.from(header, claims(Spec()))).tokenValue
    }

    /** A valid token whose payload is swapped for different claims (tenant-b) while the original signature is kept. */
    fun tamperedPayload(): String {
        val parts = valid().split(".")
        val payload = b64.encodeToString(claimsJson(Instant.now(), "tenant-b").toByteArray())
        return "${parts[0]}.$payload.${parts[2]}"
    }

    private fun claimsJson(
        now: Instant,
        tenant: String,
    ): String =
        mapper.writeValueAsString(
            linkedMapOf(
                "sub" to "client-1",
                "tenant_id" to tenant,
                "scope" to "enroll verify admin",
                "iss" to ISSUER,
                "aud" to listOf(AUDIENCE),
                "iat" to now.epochSecond,
                "nbf" to now.epochSecond,
                "exp" to now.epochSecond + 600,
                "jti" to UUID.randomUUID().toString(),
            ),
        )
}
