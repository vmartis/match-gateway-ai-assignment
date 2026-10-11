package com.vmartis.matchgateway.gateway.tenant

import com.vmartis.matchgateway.gateway.security.GatewayIntegrationTest
import com.vmartis.matchgateway.gateway.security.JwtTestTokens
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.client.RestTestClient
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.sql.Timestamp
import java.util.stream.Stream

/** Step 7: AC-45, AC-46 and AC-7 (threshold part) through the real filter chain and a real Postgres. */
class ThresholdIT : GatewayIntegrationTest() {
    @Autowired
    lateinit var client: RestTestClient

    @Autowired
    lateinit var jdbc: JdbcClient

    @LocalServerPort
    var port: Int = 0

    private val mapper = JsonMapper.builder().build()

    private data class Response(
        val status: Int,
        val contentType: MediaType?,
        val body: String,
        val headers: HttpHeaders,
    )

    private data class Setting(
        val threshold: Double,
        val updatedAt: Timestamp,
    )

    @BeforeEach
    fun cleanDatabase() {
        jdbc.sql("delete from tenant_settings").update()
    }

    private fun put(
        body: String,
        token: String? = JwtTestTokens.valid(scope = "admin"),
        contentType: String = MediaType.APPLICATION_JSON_VALUE,
        accept: String? = null,
    ): Response {
        val r =
            client
                .put()
                .uri(URI.create("http://localhost:$port/v1/tenant/threshold"))
                .headers { h ->
                    token?.let { h.setBearerAuth(it) }
                    h.set(HttpHeaders.CONTENT_TYPE, contentType)
                    accept?.let { h.set(HttpHeaders.ACCEPT, it) }
                }.body(body)
                .exchange()
                .returnResult(String::class.java)
        return Response(r.status.value(), r.responseHeaders.contentType, r.responseBody ?: "", r.responseHeaders)
    }

    private fun setting(tenant: String): Setting? =
        jdbc
            .sql("select threshold, updated_at from tenant_settings where tenant_id = :t")
            .param("t", tenant)
            .query { rs, _ -> Setting(rs.getDouble("threshold"), rs.getTimestamp("updated_at")) }
            .list()
            .singleOrNull()

    private fun seed(
        tenant: String,
        threshold: Double,
    ): Setting {
        jdbc
            .sql("insert into tenant_settings(tenant_id, threshold, updated_at) values (:t, :v, timestamptz '2026-01-01T00:00:00Z')")
            .param("t", tenant)
            .param("v", threshold)
            .update()
        return checkNotNull(setting(tenant))
    }

    private fun totalRows(): Int = jdbc.sql("select count(*) from tenant_settings").query(Int::class.java).single()

    private fun assertInvalidRequest(r: Response) {
        assertThat(r.status).isEqualTo(400)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        val node = mapper.readTree(r.body)
        assertThat(node["type"].asString()).isEqualTo("urn:matchgw:problem:invalid-request")
        assertThat(node["status"].asInt()).isEqualTo(400)
        assertThat(node.has("instance")).isFalse()
        assertThat(r.body)
            .doesNotContain("Exception")
            .doesNotContain("at com.")
            .doesNotContain("trace")
            .doesNotContain("instance")
            .doesNotContain("path")
    }

    // ---------------------------------------------------------------- AC-45

    @Test
    fun `AC-45 admin sets 0_7 and gets 200 with exactly the stored threshold and a row for the JWT tenant`() {
        val r = put("""{"threshold":0.7}""")

        assertThat(r.status).isEqualTo(200)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_JSON)
        val node = mapper.readTree(r.body)
        assertThat(node.propertyNames().asSequence().toSet()).isEqualTo(setOf("threshold"))
        assertThat(node["threshold"].asDouble()).isEqualTo(0.7)
        assertThat(setting("tenant-a")?.threshold).isEqualTo(0.7)
        assertThat(totalRows()).isEqualTo(1)
    }

    @Test
    fun `AC-45 a second PUT upserts the same row and advances updated_at`() {
        val first = put("""{"threshold":0.7}""")
        val before = checkNotNull(setting("tenant-a"))

        val second = put("""{"threshold":0.3}""")

        assertThat(first.status).isEqualTo(200)
        assertThat(second.status).isEqualTo(200)
        assertThat(mapper.readTree(second.body)["threshold"].asDouble()).isEqualTo(0.3)
        val after = checkNotNull(setting("tenant-a"))
        assertThat(after.threshold).isEqualTo(0.3)
        assertThat(after.updatedAt).isAfter(before.updatedAt)
        assertThat(totalRows()).isEqualTo(1)
    }

    @Test
    fun `AC-45 a tenant that never set a threshold has no row so the default 0_5 applies`() {
        put("""{"threshold":0.7}""", JwtTestTokens.valid(tenant = "tenant-a", scope = "admin"))

        assertThat(setting("tenant-never")).isNull()
        assertThat(setting("tenant-a")?.threshold).isEqualTo(0.7)
    }

    @Test
    @Tag("security")
    fun `API1 BOLA - the tenant comes only from the JWT and never changes another tenant's threshold`() {
        seed("tenant-b", 0.9)

        val r = put("""{"threshold":0.2,"tenantId":"tenant-b","tenant_id":"tenant-b"}""")

        assertThat(r.status).isEqualTo(200)
        assertThat(setting("tenant-a")?.threshold).isEqualTo(0.2)
        assertThat(setting("tenant-b")?.threshold).isEqualTo(0.9)
        assertThat(totalRows()).isEqualTo(2)
    }

    @Test
    fun `AC-45 tenant b setting its own threshold leaves tenant a untouched`() {
        put("""{"threshold":0.8}""", JwtTestTokens.valid(tenant = "tenant-a", scope = "admin"))

        val r = put("""{"threshold":0.4}""", JwtTestTokens.valid(tenant = "tenant-b", scope = "admin"))

        assertThat(r.status).isEqualTo(200)
        assertThat(setting("tenant-a")?.threshold).isEqualTo(0.8)
        assertThat(setting("tenant-b")?.threshold).isEqualTo(0.4)
    }

    // ---------------------------------------------------------------- AC-46

    @ParameterizedTest(name = "AC-46 boundary {0} is accepted and echoed")
    @ValueSource(doubles = [0.0, 1.0])
    fun `AC-46 boundary values 0_0 and 1_0 are accepted and stored`(value: Double) {
        val r = put("""{"threshold":$value}""")

        assertThat(r.status).isEqualTo(200)
        assertThat(mapper.readTree(r.body)["threshold"].asDouble()).isEqualTo(value)
        assertThat(setting("tenant-a")?.threshold).isEqualTo(value)
    }

    @ParameterizedTest(name = "AC-46 API4 invalid threshold body {0} is 400 and leaves 0_8 unchanged")
    @MethodSource("invalidBodies")
    @Tag("security")
    fun `API4 invalid threshold is a constant 400 and the stored threshold is unchanged`(
        name: String,
        body: String,
    ) {
        val seeded = seed("tenant-a", 0.8)

        val r = put(body)

        assertInvalidRequest(r)
        assertThat(setting("tenant-a")).isEqualTo(seeded)
    }

    @ParameterizedTest(name = "AC-46 API4 invalid threshold body {0} creates no row for a tenant without one")
    @MethodSource("invalidBodies")
    @Tag("security")
    fun `API4 invalid threshold creates no row for a tenant that never set one`(
        name: String,
        body: String,
    ) {
        val r = put(body)

        assertInvalidRequest(r)
        assertThat(totalRows()).isZero()
    }

    @Test
    @Tag("security")
    fun `API4 malformed or empty JSON body is a 400 problem and changes nothing`() {
        val seeded = seed("tenant-a", 0.8)

        assertInvalidRequest(put("""{"threshold":"""))
        assertInvalidRequest(put(""))
        assertInvalidRequest(put("""{"threshold":0.5,"threshold":0.6}"""))

        assertThat(setting("tenant-a")).isEqualTo(seeded)
    }

    @Test
    @Tag("security")
    fun `A05 text plain content type is 415 and changes nothing`() {
        val seeded = seed("tenant-a", 0.8)

        val r = put("""{"threshold":0.1}""", contentType = MediaType.TEXT_PLAIN_VALUE)

        assertThat(r.status).isEqualTo(415)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(mapper.readTree(r.body)["type"].asString()).isEqualTo("urn:matchgw:problem:unsupported-media-type")
        assertThat(setting("tenant-a")).isEqualTo(seeded)
    }

    @Test
    @Tag("security")
    fun `A05 Accept text html is 406 and changes nothing`() {
        val seeded = seed("tenant-a", 0.8)

        val r = put("""{"threshold":0.1}""", accept = "text/html")

        assertThat(r.status).isEqualTo(406)
        assertThat(setting("tenant-a")).isEqualTo(seeded)
    }

    // ---------------------------------------------------------------- AC-7 (threshold) and authentication

    @ParameterizedTest(name = "API5 AC-7 scope [{0}] on threshold is 403 and changes nothing")
    @ValueSource(strings = ["enroll", "verify", "enroll verify", "administrator"])
    @Tag("security")
    fun `API5 AC-7 a token without the admin scope gets 403 and no state change`(scope: String) {
        val seeded = seed("tenant-a", 0.8)

        val r = put("""{"threshold":0.1}""", JwtTestTokens.valid(scope = scope))

        assertThat(r.status).isEqualTo(403)
        assertThat(r.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(mapper.readTree(r.body)["status"].asInt()).isEqualTo(403)
        assertThat(setting("tenant-a")).isEqualTo(seeded)
        assertThat(totalRows()).isEqualTo(1)
    }

    @Test
    @Tag("security")
    fun `API5 a valid token without any scope claim gets 403 on threshold and no state change`() {
        val r = put("""{"threshold":0.1}""", JwtTestTokens.missingScope())

        assertThat(r.status).isEqualTo(403)
        assertThat(totalRows()).isZero()
    }

    @ParameterizedTest(name = "API2 threshold with {0} token is 401 and changes nothing")
    @MethodSource("rejectedTokens")
    @Tag("security")
    fun `API2 threshold with a rejected token is 401 with WWW-Authenticate and changes nothing`(
        name: String,
        token: String?,
    ) {
        val seeded = seed("tenant-a", 0.8)

        val r = put("""{"threshold":0.1}""", token)

        assertThat(r.status).isEqualTo(401)
        assertThat(r.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer")
        assertThat(setting("tenant-a")).isEqualTo(seeded)
    }

    @Test
    @Tag("security")
    fun `API5 a forbidden request is rejected before the body is read so a malformed body is still 403`() {
        val r = put("""{"threshold":""", JwtTestTokens.valid(scope = "enroll"))

        assertThat(r.status).isEqualTo(403)
    }

    companion object {
        @JvmStatic
        fun invalidBodies(): Stream<Arguments> =
            Stream.of(
                Arguments.of("-0.01", """{"threshold":-0.01}"""),
                Arguments.of("1.01", """{"threshold":1.01}"""),
                Arguments.of("just above 1", """{"threshold":1.0000001}"""),
                Arguments.of("just below 0", """{"threshold":-0.0000001}"""),
                Arguments.of("integer 2", """{"threshold":2}"""),
                Arguments.of("negative integer", """{"threshold":-1}"""),
                Arguments.of("string abc", """{"threshold":"abc"}"""),
                Arguments.of("numeric string", """{"threshold":"0.7"}"""),
                Arguments.of("null", """{"threshold":null}"""),
                Arguments.of("missing field", "{}"),
                Arguments.of("other field only", """{"value":0.7}"""),
                Arguments.of("NaN literal", """{"threshold":NaN}"""),
                Arguments.of("Infinity literal", """{"threshold":Infinity}"""),
                Arguments.of("1e999 overflow", """{"threshold":1e999}"""),
                // Jackson binds these strings to doubles before coercion rules apply; validation must still reject them.
                Arguments.of("NaN as string", """{"threshold":"NaN"}"""),
                Arguments.of("Infinity as string", """{"threshold":"Infinity"}"""),
                Arguments.of("-Infinity as string", """{"threshold":"-Infinity"}"""),
                Arguments.of("-1e999 overflow", """{"threshold":-1e999}"""),
                Arguments.of("boolean", """{"threshold":true}"""),
                Arguments.of("array", """{"threshold":[0.7]}"""),
                Arguments.of("object", """{"threshold":{"v":0.7}}"""),
                Arguments.of("bare number", "0.7"),
                Arguments.of("JSON null body", "null"),
                Arguments.of("array body", "[0.7]"),
            )

        @JvmStatic
        fun rejectedTokens(): Stream<Arguments> =
            Stream.of(
                Arguments.of("missing", null),
                Arguments.of("malformed", "not.a.jwt"),
                Arguments.of("expired", JwtTestTokens.expired()),
                Arguments.of("not-yet-valid", JwtTestTokens.notYetValid()),
                Arguments.of("wrong-key", JwtTestTokens.wrongKey()),
                Arguments.of("alg-none", JwtTestTokens.algNone()),
                Arguments.of("wrong-issuer", JwtTestTokens.wrongIssuer()),
                Arguments.of("wrong-audience", JwtTestTokens.wrongAudience()),
                Arguments.of("hs256-key-confusion", JwtTestTokens.hs256WithPublicKey()),
                Arguments.of("missing-tenant", JwtTestTokens.missingTenant()),
            )
    }
}
