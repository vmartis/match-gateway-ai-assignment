package com.vmartis.matchgateway.devtoken

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.security.converter.RsaKeyConverters
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class DevTokenTest {
    @TempDir
    lateinit var tmp: Path

    private val fixedNow = Instant.parse("2026-01-01T12:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private fun dirs(): Pair<Path, Path> {
        val issuer = Files.createDirectory(tmp.resolve("issuer"))
        val gateway = Files.createDirectory(tmp.resolve("gateway"))
        return issuer to gateway
    }

    private fun perms(p: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

    private fun publicKey(path: Path): RSAPublicKey = Files.newInputStream(path).use { RsaKeyConverters.x509().convert(it) }

    private fun verify(
        token: String,
        gatewayDir: Path,
    ): Jwt {
        // Timestamp validation is irrelevant here (fixed clock in the past); only the signature is under test.
        val decoder = NimbusJwtDecoder.withPublicKey(publicKey(gatewayDir.resolve("jwt-public.pem"))).build()
        decoder.setJwtValidator {
            org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
                .success()
        }
        return decoder.decode(token)
    }

    @Test
    fun `keygen creates the three key files with exact permissions`() {
        val (issuer, gateway) = dirs()

        assertThat(KeyGen.generate(issuer, gateway)).isTrue()

        val priv = issuer.resolve("jwt-private.pem")
        val pub = gateway.resolve("jwt-public.pem")
        val hmac = gateway.resolve("idempotency-hmac.key")
        assertThat(perms(priv)).isEqualTo("r--------")
        assertThat(perms(pub)).isEqualTo("r--r--r--")
        assertThat(perms(hmac)).isEqualTo("r--r--r--")
        // Parse instead of matching the PEM header text: proves PKCS#8 / X.509 and keeps key-like literals out of the repo.
        assertThat(Files.newInputStream(priv).use { RsaKeyConverters.pkcs8().convert(it) }).isInstanceOf(RSAPrivateKey::class.java)
        assertThat(Files.newInputStream(pub).use { RsaKeyConverters.x509().convert(it) }).isInstanceOf(RSAPublicKey::class.java)
        assertThat(Files.size(hmac)).isEqualTo(32L)
    }

    @Test
    fun `keygen produces an RSA 3072 key pair that matches`() {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)

        val pub = publicKey(gateway.resolve("jwt-public.pem"))
        assertThat(pub.modulus.bitLength()).isEqualTo(3072)
        val pem = Files.readString(issuer.resolve("jwt-private.pem"))
        val priv = RsaKeyConverters.pkcs8().convert(pem.byteInputStream()) as RSAPrivateCrtKey
        assertThat(priv.modulus).isEqualTo(pub.modulus)
    }

    @Test
    fun `keygen is idempotent and leaves existing files untouched`() {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)
        val files = listOf(issuer.resolve("jwt-private.pem"), gateway.resolve("jwt-public.pem"), gateway.resolve("idempotency-hmac.key"))
        val before = files.map { Files.readAllBytes(it) }

        assertThat(KeyGen.generate(issuer, gateway)).isFalse()

        files.forEachIndexed { i, f -> assertThat(Files.readAllBytes(f)).isEqualTo(before[i]) }
    }

    @Test
    fun `keygen regenerates the complete set when the public key is missing and the new pair matches`() {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)
        val priv = issuer.resolve("jwt-private.pem")
        val pub = gateway.resolve("jwt-public.pem")
        val hmac = gateway.resolve("idempotency-hmac.key")
        val oldPrivate = Files.readAllBytes(priv)
        val oldHmac = Files.readAllBytes(hmac)
        Files.delete(pub)

        assertThat(KeyGen.generate(issuer, gateway)).isTrue()

        assertThat(listOf(priv, pub, hmac)).allSatisfy { assertThat(it).exists() }
        assertThat(perms(priv)).isEqualTo("r--------")
        assertThat(perms(pub)).isEqualTo("r--r--r--")
        assertThat(perms(hmac)).isEqualTo("r--r--r--")
        assertThat(Files.readAllBytes(priv)).isNotEqualTo(oldPrivate)
        assertThat(Files.readAllBytes(hmac)).isNotEqualTo(oldHmac)
        val token = TokenMinter.mint(priv, "tenant-a", "client-1", "verify", clock = clock)
        assertThat(verify(token, gateway).subject).isEqualTo("client-1")
    }

    @Test
    @Tag("security")
    fun `A02 keygen never writes the private key into the gateway directory`() {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)

        val names = Files.list(gateway).use { s -> s.map { it.fileName.toString() }.toList() }
        assertThat(names).containsExactlyInAnyOrder("jwt-public.pem", "idempotency-hmac.key")
        assertThat(Files.readString(gateway.resolve("jwt-public.pem"))).doesNotContain("PRIVATE KEY")
        assertThat(Files.list(issuer).use { s -> s.map { it.fileName.toString() }.toList() }).containsExactly("jwt-private.pem")
    }

    @Test
    fun `mint produces a verifiable RS256 token with all claims`() {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)

        val token = TokenMinter.mint(issuer.resolve("jwt-private.pem"), "tenant-a", "client-1", "enroll verify", clock = clock)

        val jwt = verify(token, gateway)
        assertThat(jwt.headers["alg"]).isEqualTo("RS256")
        assertThat(jwt.subject).isEqualTo("client-1")
        assertThat(jwt.getClaimAsString("tenant_id")).isEqualTo("tenant-a")
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("enroll verify")
        assertThat(jwt.issuer.toString()).isEqualTo("https://match-gateway.invalid/dev-issuer")
        assertThat(jwt.audience).containsExactly("match-gateway")
        assertThat(jwt.issuedAt).isEqualTo(fixedNow)
        assertThat(jwt.notBefore).isEqualTo(fixedNow)
        assertThat(jwt.expiresAt).isEqualTo(fixedNow.plusSeconds(900))
        assertThat(Duration.between(jwt.issuedAt, jwt.expiresAt).seconds).isEqualTo(900L)
        assertThat(
            java.util.UUID
                .fromString(jwt.id)
                .toString(),
        ).isEqualTo(jwt.id)
    }

    @Test
    fun `mint rejects an issuer that is not a URL`() {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)
        assertThatThrownBy {
            TokenMinter.mint(issuer.resolve("jwt-private.pem"), "tenant-a", "client-1", "verify", issuer = "iss-x", clock = clock)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `mint honours custom ttl issuer and audience and uses a fresh jti each time`() {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)
        val key = issuer.resolve("jwt-private.pem")

        val t1 = TokenMinter.mint(key, "t.1_x-y", "s", "admin", Duration.ofMinutes(15), "https://other.invalid/iss", "aud-x", clock)
        val t2 = TokenMinter.mint(key, "t.1_x-y", "s", "admin", Duration.ofSeconds(1), "https://other.invalid/iss", "aud-x", clock)

        val j1 = verify(t1, gateway)
        val j2 = verify(t2, gateway)
        assertThat(j1.issuer.toString()).isEqualTo("https://other.invalid/iss")
        assertThat(j1.audience).containsExactly("aud-x")
        assertThat(j2.expiresAt).isEqualTo(fixedNow.plusSeconds(1))
        assertThat(j1.id).isNotEqualTo(j2.id)
    }

    @Test
    fun `mint accepts the boundary values 64 char tenant and exactly 15 minutes`() {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)

        val token =
            TokenMinter.mint(
                issuer.resolve("jwt-private.pem"),
                "a".repeat(64),
                "s",
                "enroll verify admin",
                Duration.ofMinutes(15),
                clock = clock,
            )

        assertThat(verify(token, gateway).getClaimAsString("tenant_id")).isEqualTo("a".repeat(64))
    }

    @Test
    @Tag("security")
    fun `A02 token signed by minter does not verify with another public key`() {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)
        val other = Files.createDirectory(tmp.resolve("other"))
        val otherGw = Files.createDirectory(tmp.resolve("otherGw"))
        KeyGen.generate(other, otherGw)
        val token = TokenMinter.mint(issuer.resolve("jwt-private.pem"), "t", "s", "admin", clock = clock)

        assertThatThrownBy { verify(token, otherGw) }.isInstanceOf(org.springframework.security.oauth2.jwt.JwtException::class.java)
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1, 901])
    fun `mint rejects ttl that is not positive or exceeds 15 minutes`(seconds: Long) {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)

        assertThatThrownBy {
            TokenMinter.mint(issuer.resolve("jwt-private.pem"), "t", "s", "admin", Duration.ofSeconds(seconds), clock = clock)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `mint rejects a 16 minute ttl`() {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)

        assertThatThrownBy {
            TokenMinter.mint(issuer.resolve("jwt-private.pem"), "t", "s", "admin", Duration.ofMinutes(16), clock = clock)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["a b", "", "ten/ant", "té", "a;b"])
    fun `mint rejects malformed tenant`(tenant: String) {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)

        assertThatThrownBy {
            TokenMinter.mint(issuer.resolve("jwt-private.pem"), tenant, "s", "admin", clock = clock)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `mint rejects a 65 char tenant`() {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)

        assertThatThrownBy {
            TokenMinter.mint(issuer.resolve("jwt-private.pem"), "a".repeat(65), "s", "admin", clock = clock)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["root", "", "   ", "enroll root", "ENROLL", "enroll,verify"])
    fun `mint rejects unknown or blank scope`(scope: String) {
        val (issuer, gateway) = dirs()
        KeyGen.generate(issuer, gateway)

        assertThatThrownBy {
            TokenMinter.mint(issuer.resolve("jwt-private.pem"), "t", "s", scope, clock = clock)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
