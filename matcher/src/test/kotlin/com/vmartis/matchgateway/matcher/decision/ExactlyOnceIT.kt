package com.vmartis.matchgateway.matcher.decision

import com.vmartis.matchgateway.matcher.TestcontainersConfiguration
import com.vmartis.matchgateway.matcher.decision.domain.Outcome
import com.vmartis.matchgateway.matcher.decision.infra.JobQueue
import com.vmartis.matchgateway.matcher.decision.infra.MatcherWorker
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.simple.JdbcClient
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.SQLException
import java.sql.Timestamp
import java.util.Random
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.math.sqrt

/** AC-34, AC-35, AC-36, NFR-7, INV-1: every verification gets exactly one terminal outcome, which is then immutable. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["matcher.scheduling.enabled=false"],
)
@Import(TestcontainersConfiguration::class)
class ExactlyOnceIT {
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

    private fun enrol(
        subject: String,
        template: ByteArray,
    ) {
        jdbc.sql("insert into subject (tenant_id, subject_id, last_version) values ('t1', :s, 1)").param("s", subject).update()
        jdbc
            .sql("insert into template_version (tenant_id, subject_id, version, template, active) values ('t1', :s, 1, :b, true)")
            .param("s", subject)
            .param("b", template)
            .update()
    }

    private fun pending(
        subject: String,
        probe: ByteArray,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql("insert into verification (id, tenant_id, subject_id, status, probe) values (:id, 't1', :s, 'PENDING', :p)")
            .param("id", id)
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
        val decidedAt: Timestamp?,
    )

    private fun row(id: UUID): Row =
        jdbc
            .sql("select status, score, threshold, reason, decided_at from verification where id = :id")
            .param("id", id)
            .query { rs, _ ->
                Row(
                    rs.getString("status"),
                    rs.getObject("score") as Double?,
                    rs.getObject("threshold") as Double?,
                    rs.getString("reason"),
                    rs.getTimestamp("decided_at"),
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

    private val results = listOf("MATCH", "NO_MATCH", "FAILED")

    private fun decidedTotal(): Double = results.sumOf(::counter)

    private fun rootSqlState(e: Throwable): String? {
        var c: Throwable? = e
        var state: String? = null
        while (c != null) {
            if (c is SQLException) state = c.sqlState
            c = c.cause
        }
        return state
    }

    private fun decidedMatchRow(): UUID {
        enrol("s1", bytes(unit(1)))
        val id = pending("s1", bytes(unit(1)))
        worker.drain()
        assertThat(row(id).status).isEqualTo("MATCH")
        return id
    }

    @Test
    fun `AC-34 500 verifications drained by 3 concurrent workers are decided exactly once each`() {
        val total = 500
        (0 until 20).forEach { enrol("s$it", bytes(unit(it.toLong()))) }
        val rnd = Random(42)
        val ids =
            (0 until total).map { i ->
                when {
                    i % 10 == 0 -> pending("ghost$i", bytes(unit(1000L + i)))
                    i % 2 == 0 -> pending("s${i % 20}", bytes(unit((i % 20).toLong())))
                    else -> pending("s${i % 20}", bytes(unit(5000L + rnd.nextInt(1000))))
                }
            }
        val before = results.associateWith(::counter)

        val pool = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        val perWorker =
            try {
                val futures =
                    (1..3).map {
                        pool.submit<Int> {
                            start.await()
                            worker.drain()
                        }
                    }
                start.countDown()
                futures.map { it.get(120, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
        // Applied transitions: every job was processed by exactly one of the three workers.
        assertThat(perWorker.sum()).isEqualTo(total)
        assertThat(worker.drain()).isZero()

        assertThat(jdbc.sql("select count(*) from verification where status <> 'PENDING'").query(Long::class.java).single())
            .isEqualTo(total.toLong())
        assertThat(jdbc.sql("select count(*) from verification where status = 'PENDING'").query(Long::class.java).single()).isZero()
        assertThat(
            jdbc
                .sql("select count(*) from verification where probe is not null or lease_token is not null or lease_until is not null")
                .query(Long::class.java)
                .single(),
        ).isZero()
        assertThat(jdbc.sql("select count(*) from verification where attempts <> 1").query(Long::class.java).single()).isZero()
        assertThat(
            jdbc
                .sql("select count(*) from verification where status = 'FAILED' and reason = 'SUBJECT_NOT_ENROLLED'")
                .query(Long::class.java)
                .single(),
        ).isEqualTo(50L)
        assertThat(ids.map { row(it).status }.toSet()).containsExactlyInAnyOrder("MATCH", "NO_MATCH", "FAILED")
        // The metric agrees with the database per result, not only in sum.
        val byStatus =
            jdbc
                .sql("select status, count(*) as n from verification group by status")
                .query { rs, _ -> rs.getString("status") to rs.getLong("n").toDouble() }
                .list()
                .toMap()
        assertThat(results.associateWith { counter(it) - before.getValue(it) }).isEqualTo(byStatus)
    }

    @Test
    fun `AC-35 stale writer after lease expiry cannot overwrite the outcome written by the new holder`() {
        enrol("s1", bytes(unit(2)))
        val id = pending("s1", bytes(unit(2)))
        val a = queue.claim(10).single()
        expireLease(id)
        val b = queue.claim(10).single()
        assertThat(b.id).isEqualTo(id)
        assertThat(b.leaseToken).isNotEqualTo(a.leaseToken)

        assertThat(queue.complete(b, Outcome.Decided("MATCH", 0.9, 0.5))).isTrue()
        val decided = row(id)
        assertThat(decided.status).isEqualTo("MATCH")
        assertThat(decided.decidedAt).isNotNull()
        val before = decidedTotal()

        assertThat(queue.complete(a, Outcome.Decided("NO_MATCH", 0.1, 0.5))).isFalse()
        assertThat(queue.complete(a, Outcome.Failed("MAX_ATTEMPTS"))).isFalse()
        assertThat(queue.complete(b, Outcome.Decided("NO_MATCH", 0.1, 0.5))).isFalse()

        val after = row(id)
        assertThat(after).isEqualTo(decided)
        assertThat(after.score).isEqualTo(0.9)
        assertThat(after.threshold).isEqualTo(0.5)
        assertThat(after.reason).isNull()
        assertThat(decidedTotal()).isEqualTo(before)
    }

    @Test
    fun `AC-35 stale worker blocked mid-decision writes nothing and is not counted when it resumes`() {
        enrol("s1", bytes(unit(6)))
        val id = pending("s1", bytes(unit(6)))
        val before = results.associateWith(::counter)
        val pool = Executors.newSingleThreadExecutor()
        try {
            dataSource.connection.use { lock ->
                lock.autoCommit = false
                // Holds worker A after it claimed the job and read the template, before its guarded write.
                lock.createStatement().use { it.execute("lock table tenant_settings in access exclusive mode") }
                val a = pool.submit<Int> { worker.drain() }
                awaitBlockedOnTenantSettings()

                // A's lease expires; B reclaims and decides differently (template erased meanwhile -> FAILED).
                expireLease(id)
                jdbc.sql("delete from template_version where tenant_id = 't1' and subject_id = 's1'").update()
                assertThat(worker.drain()).isEqualTo(1)
                val decidedByB = row(id)
                assertThat(decidedByB.status).isEqualTo("FAILED")
                assertThat(decidedByB.reason).isEqualTo("SUBJECT_NOT_ENROLLED")

                lock.rollback()
                // A finishes its decision (MATCH, score 1.0) but its lease-token-guarded write changes 0 rows.
                assertThat(a.get(30, TimeUnit.SECONDS)).isEqualTo(1)
                assertThat(row(id)).isEqualTo(decidedByB)
            }
        } finally {
            pool.shutdownNow()
        }
        assertThat(results.associateWith { counter(it) - before.getValue(it) })
            .isEqualTo(mapOf("MATCH" to 0.0, "NO_MATCH" to 0.0, "FAILED" to 1.0))
    }

    private fun awaitBlockedOnTenantSettings() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (
            jdbc
                .sql("select count(*) from pg_locks where relation = 'tenant_settings'::regclass and not granted")
                .query(Long::class.java)
                .single() == 0L
        ) {
            check(System.nanoTime() < deadline) { "worker never reached the tenant_settings read" }
            Thread.onSpinWait()
        }
    }

    @Test
    fun `AC-36 after all rows are terminal nothing can be claimed or reaped`() {
        enrol("s1", bytes(unit(3)))
        repeat(5) { pending("s1", bytes(unit(3))) }
        pending("ghost", bytes(unit(4)))
        // Exhausted job: reaped to FAILED/MAX_ATTEMPTS with attempts = 3, so a later reap() would match it but for its status.
        val exhausted = pending("s1", bytes(unit(3)))
        jdbc.sql("update verification set attempts = 3 where id = :id").param("id", exhausted).update()
        assertThat(worker.drain()).isEqualTo(6)
        val reaped = row(exhausted)
        assertThat(reaped.status).isEqualTo("FAILED")
        assertThat(reaped.reason).isEqualTo("MAX_ATTEMPTS")

        assertThat(queue.claim(100)).isEmpty()
        assertThat(queue.reap()).isZero()
        assertThat(worker.drain()).isZero()
        assertThat(row(exhausted)).isEqualTo(reaped)
        assertThat(jdbc.sql("select count(*) from verification where status <> 'PENDING'").query(Long::class.java).single())
            .isEqualTo(7L)
    }

    @Test
    fun `INV-1 raw update of a decided row is rejected by the database with check_violation`() {
        val id = decidedMatchRow()
        val before = row(id)

        assertThatThrownBy {
            jdbc.sql("update verification set status = 'NO_MATCH', score = 0.0 where id = :id").param("id", id).update()
        }.isInstanceOf(DataAccessException::class.java)
            .satisfies({ assertThat(rootSqlState(it)).isEqualTo("23514") })

        assertThat(row(id)).isEqualTo(before)
    }

    @Test
    fun `INV-1 raw update of the reason of a FAILED row is rejected`() {
        val id = pending("ghost", bytes(unit(5)))
        worker.drain()
        val before = row(id)
        assertThat(before.status).isEqualTo("FAILED")
        assertThat(before.reason).isEqualTo("SUBJECT_NOT_ENROLLED")

        assertThatThrownBy {
            jdbc.sql("update verification set reason = 'SUBJECT_ERASED' where id = :id").param("id", id).update()
        }.isInstanceOf(DataAccessException::class.java)
            .satisfies({ assertThat(rootSqlState(it)).isEqualTo("23514") })

        assertThat(row(id)).isEqualTo(before)
    }

    @Test
    fun `INV-1 the immutability trigger does not block deleting a decided row`() {
        val id = decidedMatchRow()

        assertThat(jdbc.sql("delete from verification where id = :id").param("id", id).update()).isEqualTo(1)

        assertThat(
            jdbc
                .sql("select count(*) from verification where id = :id")
                .param("id", id)
                .query(Long::class.java)
                .single(),
        ).isZero()
    }

    @Test
    fun `INV-1 flyway history contains migration version 2 terminal immutability`() {
        val versions = jdbc.sql("select version from flyway_schema_history where success").query(String::class.java).list()

        assertThat(versions).contains("1", "2")
    }
}
