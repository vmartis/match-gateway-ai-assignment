package com.vmartis.matchgateway.matcher.metrics

import com.vmartis.matchgateway.matcher.TestcontainersConfiguration
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** AC-51: the queue-depth gauge keeps refreshing while the worker is stuck in a long drain (code review step 10, MAJOR). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["matcher.poll-interval=50ms", "matcher.queue-depth-refresh=100ms"],
)
@Import(TestcontainersConfiguration::class)
class QueueDepthSchedulingIT {
    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var dataSource: DataSource

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

    private fun depth(): Double = meters.get("matchgw.queue.depth").gauge().value()

    private fun awaitTrue(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    private fun pending(): UUID {
        val id = UUID.randomUUID()
        jdbc
            .sql("insert into verification (id, tenant_id, subject_id, status, probe) values (:id, 't1', 's1', 'PENDING', :p)")
            .param("id", id)
            .param(
                "p",
                ByteArray(512).also {
                    it[3] = 0x3f
                    it[2] = 0x80.toByte()
                },
            ) // unit vector e0
            .update()
        return id
    }

    @Test
    fun `AC-51 queue depth keeps refreshing while drain is blocked mid-decision`() {
        jdbc.sql("insert into subject (tenant_id, subject_id, last_version) values ('t1', 's1', 1)").update()
        jdbc
            .sql("insert into template_version (tenant_id, subject_id, version, template, active) values ('t1', 's1', 1, :t, true)")
            .param(
                "t",
                ByteArray(512).also {
                    it[3] = 0x3f
                    it[2] = 0x80.toByte()
                },
            ).update()
        dataSource.connection.use { lock ->
            lock.autoCommit = false
            // The worker reads tenant_settings while deciding: this holds drain() (and the scheduler thread running it).
            lock.createStatement().use { it.execute("lock table tenant_settings in access exclusive mode") }
            pending()
            awaitTrue("worker blocked") {
                jdbc
                    .sql("select count(*) from pg_locks where relation = 'tenant_settings'::regclass and not granted")
                    .query(Long::class.java)
                    .single() > 0
            }
            repeat(4) { pending() }

            awaitTrue("gauge to show the backlog of 5") { depth() == 5.0 }

            lock.rollback()
        }
        awaitTrue("gauge to drop to 0 after the backlog drains") { depth() == 0.0 }
        assertThat(jdbc.sql("select count(*) from verification where status = 'MATCH'").query(Long::class.java).single()).isEqualTo(5)
    }
}
