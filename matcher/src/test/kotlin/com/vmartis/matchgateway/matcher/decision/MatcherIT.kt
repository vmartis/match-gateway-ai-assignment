package com.vmartis.matchgateway.matcher.decision

import com.vmartis.matchgateway.common.template.Cosine
import com.vmartis.matchgateway.matcher.TestcontainersConfiguration
import com.vmartis.matchgateway.matcher.decision.domain.Decision
import com.vmartis.matchgateway.matcher.decision.domain.Outcome
import com.vmartis.matchgateway.matcher.decision.infra.JobQueue
import com.vmartis.matchgateway.matcher.decision.infra.MatcherWorker
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.sqrt

/** AC-24..AC-29, AC-17, INV-4, INV-5: the matcher decides pending verifications from the shared database. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["matcher.scheduling.enabled=false"],
)
@Import(TestcontainersConfiguration::class)
class MatcherIT {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var dataSource: DataSource

    @Autowired lateinit var worker: MatcherWorker

    @Autowired lateinit var queue: JobQueue

    @Autowired lateinit var meters: MeterRegistry

    @BeforeEach
    fun setUp() {
        val migrations = File("../gateway/src/main/resources/db/migration").absoluteFile.normalize()
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("filesystem:$migrations")
            .load()
            .migrate()
        jdbc.sql("truncate verification, idempotency_key, template_version, subject, tenant_settings cascade").update()
    }

    private fun bytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buf.putFloat(it) }
        return buf.array()
    }

    private fun unit(seed: Long): FloatArray {
        val r = Random(seed)
        val v = DoubleArray(128) { r.nextGaussian() }
        val n = sqrt(v.sumOf { it * it })
        return FloatArray(128) { (v[it] / n).toFloat() }
    }

    private fun reference(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            na += a[i].toDouble() * a[i].toDouble()
            nb += b[i].toDouble() * b[i].toDouble()
        }
        return dot / (sqrt(na) * sqrt(nb))
    }

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

    private data class Row(
        val status: String,
        val score: Double?,
        val threshold: Double?,
        val reason: String?,
        val attempts: Int,
        val probeNull: Boolean,
        val leaseNull: Boolean,
        val decided: Boolean,
    )

    private fun row(id: UUID): Row =
        jdbc
            .sql(
                "select status, score, threshold, reason, attempts, probe is null as pn, " +
                    "(lease_token is null and lease_until is null) as ln, decided_at is not null as d " +
                    "from verification where id = :id",
            ).param("id", id)
            .query { rs, _ ->
                Row(
                    rs.getString("status"),
                    rs.getObject("score") as Double?,
                    rs.getObject("threshold") as Double?,
                    rs.getString("reason"),
                    rs.getInt("attempts"),
                    rs.getBoolean("pn"),
                    rs.getBoolean("ln"),
                    rs.getBoolean("d"),
                )
            }.single()

    private fun expireLease(id: UUID) {
        jdbc
            .sql("update verification set lease_until = now() - interval '1 second' where id = :id")
            .param("id", id)
            .update()
    }

    private fun counter(result: String): Double =
        meters
            .find("matchgw.verifications.decided")
            .tag("result", result)
            .counter()
            ?.count() ?: 0.0

    @Test
    fun `AC-24 enrolled pair is decided NO_MATCH or MATCH against threshold 0_5 with float64 score`() {
        val stored = unit(1)
        val probeSame = unit(1)
        val probeOther = unit(2)
        enrol("t1", "s1", bytes(stored))
        val same = pending("t1", "s1", bytes(probeSame))
        val other = pending("t1", "s1", bytes(probeOther))

        assertThat(worker.drain()).isEqualTo(2)

        val a = row(same)
        assertThat(a.status).isEqualTo("MATCH")
        assertThat(a.score).isCloseTo(
            reference(stored, probeSame),
            org.assertj.core.data.Offset
                .offset(1e-5),
        )
        assertThat(a.threshold).isEqualTo(0.5)
        assertThat(a.decided).isTrue()
        assertThat(a.probeNull).isTrue()
        assertThat(a.leaseNull).isTrue()
        val expectedOther = reference(stored, probeOther)
        val b = row(other)
        assertThat(b.status).isEqualTo("NO_MATCH")
        assertThat(expectedOther).isLessThan(0.5)
        assertThat(b.score).isCloseTo(
            expectedOther,
            org.assertj.core.data.Offset
                .offset(1e-5),
        )
        assertThat(b.probeNull).isTrue()
    }

    @Test
    fun `AC-24 score equal to threshold is a MATCH`() {
        val stored = unit(3)
        val probe = unit(4)
        val exact = Cosine.similarity(stored, probe)
        enrol("t1", "s1", bytes(stored))
        jdbc.sql("insert into tenant_settings (tenant_id, threshold) values ('t1', :t)").param("t", exact).update()
        val id = pending("t1", "s1", bytes(probe))

        worker.drain()

        val r = row(id)
        assertThat(r.status).isEqualTo("MATCH")
        assertThat(r.score).isEqualTo(exact)
        assertThat(r.threshold).isEqualTo(exact)
    }

    @Test
    fun `AC-25 threshold changed before drain is used and stored`() {
        val stored = unit(5)
        val probe = unit(5)
        enrol("t1", "s1", bytes(stored))
        val id = pending("t1", "s1", bytes(probe))
        jdbc.sql("insert into tenant_settings (tenant_id, threshold) values ('t1', 0.9)").update()

        worker.drain()

        val r = row(id)
        assertThat(r.threshold).isEqualTo(0.9)
        assertThat(r.status).isEqualTo("MATCH")
    }

    @Test
    fun `AC-25 higher threshold turns a mid score into NO_MATCH`() {
        val stored = unit(6)
        val probe = FloatArray(128) { 0.6f * stored[it] + 0.4f * unit(7)[it] }
        val score = reference(stored, probe)
        assertThat(score).isBetween(0.5, 0.9)
        enrol("t1", "s1", bytes(stored))
        jdbc.sql("insert into tenant_settings (tenant_id, threshold) values ('t1', 0.9)").update()
        val id = pending("t1", "s1", bytes(probe))

        worker.drain()

        assertThat(row(id).status).isEqualTo("NO_MATCH")
        assertThat(row(id).threshold).isEqualTo(0.9)
    }

    @Test
    fun `AC-26 and AC-29 and AC-17 no active template fails SUBJECT_NOT_ENROLLED without retry and clears probe`() {
        val id = pending("t1", "ghost", bytes(unit(8)))

        worker.drain()

        val r = row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("SUBJECT_NOT_ENROLLED")
        assertThat(r.attempts).isEqualTo(1)
        assertThat(r.score).isNull()
        assertThat(r.probeNull).isTrue()
        assertThat(r.leaseNull).isTrue()
        assertThat(r.decided).isTrue()
    }

    @Test
    fun `AC-27 corrupt stored template is retried three times then FAILED MAX_ATTEMPTS and never claimed again`() {
        val corrupt = unit(9).also { it[10] = Float.NaN }
        enrol("t1", "s1", bytes(corrupt))
        val id = pending("t1", "s1", bytes(unit(9)))

        for (expected in 1..3) {
            worker.drain()
            val r = row(id)
            assertThat(r.status).isEqualTo("PENDING")
            assertThat(r.attempts).isEqualTo(expected)
            assertThat(r.leaseNull).isFalse()
            expireLease(id)
        }
        worker.drain()

        val r = row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("MAX_ATTEMPTS")
        assertThat(r.attempts).isEqualTo(3)
        assertThat(r.probeNull).isTrue()
        assertThat(r.leaseNull).isTrue()
        assertThat(r.decided).isTrue()

        assertThat(worker.drain()).isZero()
        assertThat(row(id).attempts).isEqualTo(3)
    }

    @Test
    fun `AC-27 probe with a non-finite value is a processing error counted as an attempt`() {
        enrol("t1", "s1", bytes(unit(10)))
        val badProbe = unit(10).also { it[0] = Float.POSITIVE_INFINITY }
        val id = pending("t1", "s1", bytes(badProbe))

        worker.drain()

        val r = row(id)
        assertThat(r.status).isEqualTo("PENDING")
        assertThat(r.attempts).isEqualTo(1)
        assertThat(r.score).isNull()
    }

    @Test
    fun `AC-28 job claimed by a stalled worker is reclaimed after lease expiry`() {
        enrol("t1", "s1", bytes(unit(11)))
        val id = pending("t1", "s1", bytes(unit(11)))
        val stalled = queue.claim(10)
        assertThat(stalled).hasSize(1)
        assertThat(stalled.single().id).isEqualTo(id)
        assertThat(stalled.single().attempts).isEqualTo(1)
        expireLease(id)

        worker.drain()

        val r = row(id)
        assertThat(r.status).isEqualTo("MATCH")
        assertThat(r.attempts).isEqualTo(2)
        // the stalled worker is now stale: its terminal write must affect nothing
        assertThat(
            queue.complete(
                stalled.single(),
                com.vmartis.matchgateway.matcher.decision.domain.Outcome
                    .Failed("MAX_ATTEMPTS"),
            ),
        ).isFalse()
        assertThat(row(id).status).isEqualTo("MATCH")
    }

    @Test
    fun `INV-1 a worker that lost its lease cannot write while the new holder is still deciding`() {
        // Pins the lease-token guard on its own: the row is still PENDING (status guard alone would let A write).
        enrol("t1", "s1", bytes(unit(14)))
        val id = pending("t1", "s1", bytes(unit(14)))
        val a = queue.claim(10).single()
        expireLease(id)
        val b = queue.claim(10).single()

        assertThat(queue.complete(a, Outcome.Failed("MAX_ATTEMPTS"))).isFalse()
        assertThat(row(id).status).isEqualTo("PENDING")

        assertThat(queue.complete(b, Decision.decide(0.9, 0.5))).isTrue()
        assertThat(row(id).status).isEqualTo("MATCH")
    }

    @Test
    fun `AC-27 claim never takes a job that used all attempts even before the reaper ran`() {
        // Replicas reap and claim independently, so claim itself must respect the cap (CHECK attempts BETWEEN 0 AND 3).
        enrol("t1", "s1", bytes(unit(15)))
        val id = pending("t1", "s1", bytes(unit(15)))
        jdbc
            .sql("update verification set attempts = 3, lease_until = now() - interval '1 second' where id = :id")
            .param("id", id)
            .update()

        assertThat(queue.claim(10)).isEmpty()
        assertThat(row(id).attempts).isEqualTo(3)
    }

    @Test
    fun `AC-27 a failed attempt releases the lease for a retry after the 1 s delay instead of the 10 s lease`() {
        enrol("t1", "s1", bytes(unit(16).also { it[3] = Float.NaN }))
        val id = pending("t1", "s1", bytes(unit(16)))

        worker.drain()

        val released =
            jdbc
                .sql(
                    "select lease_token is null as released, extract(epoch from lease_until - now()) as wait " +
                        "from verification where id = :id",
                ).param("id", id)
                .query { rs, _ -> rs.getBoolean("released") to rs.getDouble("wait") }
                .single()
        assertThat(released.first).isTrue()
        assertThat(released.second).isBetween(0.0, 2.0) // retry-delay 1 s, far below the 10 s lease
    }

    @Test
    fun `INV-1 a stale release cannot drop the new holder's lease`() {
        enrol("t1", "s1", bytes(unit(17)))
        val id = pending("t1", "s1", bytes(unit(17)))
        val a = queue.claim(10).single()
        expireLease(id)
        val b = queue.claim(10).single()

        queue.release(a)

        val token =
            jdbc
                .sql("select lease_token from verification where id = :id")
                .param("id", id)
                .query(java.util.UUID::class.java)
                .single()
        assertThat(token).isEqualTo(b.leaseToken)
    }

    @Test
    fun `AC-28 claim does not take a job whose lease is still valid`() {
        enrol("t1", "s1", bytes(unit(12)))
        pending("t1", "s1", bytes(unit(12)))
        assertThat(queue.claim(10)).hasSize(1)
        assertThat(queue.claim(10)).isEmpty()
    }

    @Test
    fun `INV-4 template of another tenant with the same subject id is never used`() {
        enrol("other", "s1", bytes(unit(13)))
        val id = pending("t1", "s1", bytes(unit(13)))

        worker.drain()

        val r = row(id)
        assertThat(r.status).isEqualTo("FAILED")
        assertThat(r.reason).isEqualTo("SUBJECT_NOT_ENROLLED")
    }

    @Test
    fun `result counter is incremented once per decided verification`() {
        enrol("t1", "s1", bytes(unit(14)))
        pending("t1", "s1", bytes(unit(14)))
        val before = counter("MATCH")

        worker.drain()

        assertThat(counter("MATCH") - before).isEqualTo(1.0)
    }

    @Test
    fun `reaper counts MAX_ATTEMPTS failures as FAILED results`() {
        val id = pending("t1", "s1", bytes(unit(15)))
        jdbc
            .sql("update verification set attempts = 3, lease_until = now() - interval '1 second' where id = :id")
            .param("id", id)
            .update()
        val before = counter("FAILED")

        worker.drain()

        assertThat(row(id).reason).isEqualTo("MAX_ATTEMPTS")
        assertThat(counter("FAILED") - before).isEqualTo(1.0)
    }

    @Test
    fun `drain on an empty table returns 0`() {
        assertThat(worker.drain()).isZero()
    }

    @Test
    fun `drain processes more rows than one batch`() {
        enrol("t1", "s1", bytes(unit(16)))
        val ids = (1..45).map { pending("t1", "s1", bytes(unit(16))) }

        assertThat(worker.drain()).isEqualTo(45)

        ids.forEach { assertThat(row(it).status).isEqualTo("MATCH") }
    }
}
