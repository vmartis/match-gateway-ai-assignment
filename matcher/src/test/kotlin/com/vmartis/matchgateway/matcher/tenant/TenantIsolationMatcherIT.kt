package com.vmartis.matchgateway.matcher.tenant

import com.vmartis.matchgateway.matcher.MatcherFixtures
import com.vmartis.matchgateway.matcher.TestcontainersConfiguration
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Random
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.sqrt

/**
 * Step 7, matcher side: AC-38 (B never compared with A's template), AC-40 (per-tenant threshold), AC-45 (later decisions use
 * the stored threshold) and AC-41 (no template or probe bytes in matcher logs, including the failure paths).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["logging.level.com.vmartis=DEBUG"],
)
@Import(TestcontainersConfiguration::class)
@ExtendWith(OutputCaptureExtension::class)
class TenantIsolationMatcherIT {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var dataSource: DataSource

    @Autowired lateinit var rabbit: RabbitTemplate

    @Autowired lateinit var admin: RabbitAdmin

    @Autowired lateinit var meters: MeterRegistry

    @Autowired lateinit var broker: RabbitMQContainer

    private lateinit var fx: MatcherFixtures

    @BeforeEach
    fun setUp() {
        fx = MatcherFixtures(jdbc, dataSource, rabbit, admin, meters, broker)
        fx.reset()
    }

    /** Publishes the job messages (as the gateway does) and waits until the listener has decided all of them. */
    private fun decide(vararg ids: UUID) {
        ids.forEach(fx::publish)
        fx.awaitAllTerminal(ids.toList(), 60)
    }

    private fun bytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun random(seed: Long): FloatArray {
        val r = Random(seed)
        val v = DoubleArray(128) { r.nextGaussian() }
        val n = sqrt(v.sumOf { it * it })
        return FloatArray(128) { (v[it] / n).toFloat() }
    }

    /** e0 – the enrolled template of the threshold tests. */
    private fun axis() = FloatArray(128).also { it[0] = 1f }

    /** Unit vector whose cosine similarity with [axis] is 0.6. */
    private fun cosine06() =
        FloatArray(128).also {
            it[0] = 0.6f
            it[1] = 0.8f
        }

    private fun b64(floats: FloatArray): String = Base64.getEncoder().encodeToString(bytes(floats))

    private fun enrol(
        tenant: String,
        subject: String,
        template: ByteArray,
    ) {
        jdbc
            .sql("insert into subject (tenant_id, subject_id, last_version) values (:t, :s, 1)")
            .param("t", tenant)
            .param("s", subject)
            .update()
        jdbc
            .sql("insert into template_version (tenant_id, subject_id, version, template, active) values (:t, :s, 1, :b, true)")
            .param("t", tenant)
            .param("s", subject)
            .param("b", template)
            .update()
    }

    private fun pending(
        tenant: String,
        subject: String,
        probe: ByteArray,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql("insert into verification (id, tenant_id, subject_id, status, probe) values (:id, :t, :s, 'PENDING', :p)")
            .param("id", id)
            .param("t", tenant)
            .param("s", subject)
            .param("p", probe)
            .update()
        return id
    }

    private fun setThreshold(
        tenant: String,
        value: Double,
    ) {
        jdbc
            .sql(
                "insert into tenant_settings (tenant_id, threshold) values (:t, :v) " +
                    "on conflict (tenant_id) do update set threshold = excluded.threshold, updated_at = now()",
            ).param("t", tenant)
            .param("v", value)
            .update()
    }

    private data class Row(
        val status: String,
        val score: Double?,
        val threshold: Double?,
        val reason: String?,
    )

    private fun row(id: UUID): Row =
        jdbc
            .sql("select status, score, threshold, reason from verification where id = :id")
            .param("id", id)
            .query { rs, _ ->
                Row(
                    rs.getString("status"),
                    rs.getObject("score") as Double?,
                    rs.getObject("threshold") as Double?,
                    rs.getString("reason"),
                )
            }.single()

    private fun storedTemplates(tenant: String): List<List<Byte>> =
        jdbc
            .sql("select template from template_version where tenant_id = :t order by subject_id, version")
            .param("t", tenant)
            .query { rs, _ -> rs.getBytes(1).toList() }
            .list()

    // ---------------------------------------------------------------- AC-38

    @Test
    @Tag("security")
    fun `API1 AC-38 BOLA - tenant B verifying s1 with A's exact template is FAILED SUBJECT_NOT_ENROLLED and never compared`() {
        val x = random(1)
        enrol("tenant-a", "s1", bytes(x))
        val aOwn = pending("tenant-a", "s1", bytes(x))
        val b = pending("tenant-b", "s1", bytes(x))
        val aTemplates = storedTemplates("tenant-a")

        decide(aOwn, b)

        val r = row(b)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("SUBJECT_NOT_ENROLLED")
        assertThat(r.score).isNull()
        assertThat(r.threshold).isNull()
        assertThat(row(aOwn).status).isEqualTo("MATCH")
        assertThat(storedTemplates("tenant-a")).isEqualTo(aTemplates)
        assertThat(storedTemplates("tenant-b")).isEmpty()
    }

    @Test
    @Tag("security")
    fun `API1 AC-38 once B enrols its own s1 B is compared with B's template only`() {
        val a = random(2)
        val bOwn = random(3)
        enrol("tenant-a", "s1", bytes(a))
        enrol("tenant-b", "s1", bytes(bOwn))
        val withA = pending("tenant-b", "s1", bytes(a))
        val withOwn = pending("tenant-b", "s1", bytes(bOwn))

        decide(withA, withOwn)

        assertThat(row(withOwn).status).isEqualTo("MATCH")
        val r = row(withA)
        assertThat(r.status).isEqualTo("NO_MATCH")
        assertThat(r.score).isLessThan(0.5)
    }

    // ---------------------------------------------------------------- AC-40 / AC-45

    @Test
    @Tag("security")
    fun `API1 AC-40 tenant A with threshold 0_8 and tenant B with the default are each decided with their own threshold`() {
        enrol("tenant-a", "s1", bytes(axis()))
        enrol("tenant-b", "s1", bytes(axis()))
        setThreshold("tenant-a", 0.8)
        val a = pending("tenant-a", "s1", bytes(cosine06()))
        val b = pending("tenant-b", "s1", bytes(cosine06()))

        decide(a, b)

        val ra = row(a)
        assertThat(ra.status).isEqualTo("NO_MATCH")
        assertThat(ra.threshold).isEqualTo(0.8)
        assertThat(ra.score).isBetween(0.599, 0.601)
        val rb = row(b)
        assertThat(rb.status).isEqualTo("MATCH")
        assertThat(rb.threshold).isEqualTo(0.5)
        assertThat(rb.score).isBetween(0.599, 0.601)
    }

    @Test
    fun `AC-40 a threshold of tenant A set after B's verification was decided does not alter B's decided row`() {
        enrol("tenant-b", "s1", bytes(axis()))
        val b = pending("tenant-b", "s1", bytes(cosine06()))
        decide(b)
        val before = row(b)

        setThreshold("tenant-a", 0.99)
        setThreshold("tenant-b", 0.99)

        assertThat(row(b)).isEqualTo(before)
        assertThat(before.threshold).isEqualTo(0.5)
    }

    @Test
    fun `AC-45 later decisions use the stored 0_7 and then the updated 0_55 while earlier rows keep their threshold`() {
        enrol("tenant-a", "s1", bytes(axis()))
        setThreshold("tenant-a", 0.7)
        val first = pending("tenant-a", "s1", bytes(cosine06()))
        decide(first)

        setThreshold("tenant-a", 0.55)
        val second = pending("tenant-a", "s1", bytes(cosine06()))
        decide(second)

        assertThat(row(first).status).isEqualTo("NO_MATCH")
        assertThat(row(first).threshold).isEqualTo(0.7)
        assertThat(row(second).status).isEqualTo("MATCH")
        assertThat(row(second).threshold).isEqualTo(0.55)
    }

    @Test
    fun `AC-45 thresholds 0_0 and 1_0 are used as stored`() {
        enrol("tenant-a", "s1", bytes(axis()))
        enrol("tenant-b", "s1", bytes(axis()))
        setThreshold("tenant-a", 0.0)
        setThreshold("tenant-b", 1.0)
        val a = pending("tenant-a", "s1", bytes(cosine06()))
        val b = pending("tenant-b", "s1", bytes(cosine06()))

        decide(a, b)

        assertThat(row(a).status).isEqualTo("MATCH")
        assertThat(row(a).threshold).isEqualTo(0.0)
        assertThat(row(b).status).isEqualTo("NO_MATCH")
        assertThat(row(b).threshold).isEqualTo(1.0)
    }

    // ---------------------------------------------------------------- AC-41 matcher side

    @Test
    @Tag("security")
    fun `A09 AC-41 matcher logs on success, SUBJECT_NOT_ENROLLED and failing paths contain no template or probe base64`(
        output: CapturedOutput,
    ) {
        val enrolled = random(10)
        val okProbe = random(10)
        val strangerProbe = random(11)
        val poisonProbe = random(12).also { it[3] = Float.POSITIVE_INFINITY }
        val corruptStored = random(13).also { it[7] = Float.NaN }
        val corruptProbe = random(13)
        enrol("tenant-a", "s1", bytes(enrolled))
        enrol("tenant-a", "corrupt", bytes(corruptStored))
        val success = pending("tenant-a", "s1", bytes(okProbe))
        val notEnrolled = pending("tenant-b", "s1", bytes(strangerProbe))
        val poison = pending("tenant-a", "s1", bytes(poisonProbe))
        val corrupt = pending("tenant-a", "corrupt", bytes(corruptProbe))

        decide(success, notEnrolled)
        decide(poison, corrupt) // 3 deliveries each, then the dead-letter consumer ends them
        fx.awaitQueuesEmpty()

        // the interesting paths really ran
        assertThat(row(success).status).isEqualTo("MATCH")
        assertThat(row(notEnrolled).reason).isEqualTo("SUBJECT_NOT_ENROLLED")
        assertThat(row(poison).reason).isEqualTo("MAX_ATTEMPTS")
        assertThat(row(corrupt).reason).isEqualTo("MAX_ATTEMPTS")
        val vectors = listOf(enrolled, okProbe, strangerProbe, poisonProbe, corruptStored, corruptProbe)
        vectors.map(::b64).forEach { s ->
            assertThat(output.all).doesNotContain(s)
            assertThat(output.all).doesNotContain(s.substring(s.length / 2 - 24, s.length / 2 + 24))
        }
        // The matcher holds raw bytes, so decoded shapes are the likelier leak: a float printed as text, or hex bytes.
        vectors.forEach { v ->
            assertThat(output.all).doesNotContain(v.map(Float::toString).first { it.length >= 8 })
            assertThat(output.all).doesNotContain(bytes(v).copyOfRange(0, 8).toHexString())
        }
        assertThat(output.all).doesNotContain("Bearer ")
        // Guard that the failing paths really logged, so the scan above covered them (restored, code review step 13).
        assertThat(output.all).contains("attempt failed")
    }
}
