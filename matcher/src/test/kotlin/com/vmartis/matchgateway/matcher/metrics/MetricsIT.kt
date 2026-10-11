package com.vmartis.matchgateway.matcher.metrics

import com.vmartis.matchgateway.matcher.TestcontainersConfiguration
import com.vmartis.matchgateway.matcher.decision.infra.DecisionProcessor
import com.vmartis.matchgateway.matcher.decision.infra.QueueDepthGauge
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.math.sqrt

/**
 * AC-51 (ADR-006, D-27, D-28): the matcher exposes decided counter, processing timer, latency histogram and queue gauge.
 * The listeners are not started: the tests call DecisionProcessor directly, so the deltas are exact.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.rabbitmq.listener.simple.auto-startup=false"],
)
// Boot disables metrics export in tests by default; the scrape needs the real Prometheus registry.
@AutoConfigureMetrics
@Import(TestcontainersConfiguration::class)
class MetricsIT {
    @LocalServerPort var port: Int = 0

    @Autowired lateinit var jdbc: JdbcClient

    @Autowired lateinit var dataSource: DataSource

    @Autowired lateinit var processor: DecisionProcessor

    @Autowired lateinit var gauge: QueueDepthGauge

    private val http = HttpClient.newHttpClient()

    private val tenant = "tenantzz-metrics"

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

    private class Scrape(
        val status: Int,
        val contentType: String,
        val body: String,
    ) {
        private val samples: List<Pair<String, Double>> =
            body
                .lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.substringBeforeLast(' ') to it.substringAfterLast(' ').toDouble() }

        /** Sum of all series of [name] whose label set contains every `key="value"` in [labels]. */
        fun value(
            name: String,
            vararg labels: String,
        ): Double =
            samples
                .filter { (series, _) -> series.substringBefore('{') == name && labels.all { series.contains(it) } }
                .sumOf { it.second }

        fun has(name: String): Boolean = samples.any { it.first.substringBefore('{') == name }

        fun series(prefix: String): List<String> = samples.map { it.first }.filter { it.startsWith(prefix) }
    }

    private fun scrape(): Scrape {
        val response =
            http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        return Scrape(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), response.body())
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
        jdbc
            .sql(
                "insert into subject (tenant_id, subject_id, last_version) values (:t, :s, 1)",
            ).param("t", tenant)
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

    private fun decided(
        s: Scrape,
        result: String,
    ) = s.value("matchgw_verifications_decided_total", "result=\"$result\"")

    @Test
    fun `AC-51 scrape exposes decided counter by result, processing timer and latency histogram with exact deltas`() {
        enrol("subjzz-a", bytes(unit(1)))
        enrol("subjzz-b", bytes(unit(2)))
        val before = scrape()

        val ids =
            listOf(
                pending("subjzz-a", bytes(unit(1))), // MATCH
                pending("subjzz-a", bytes(unit(1))), // MATCH
                pending("subjzz-b", bytes(unit(777))), // NO_MATCH
                pending("subjzz-ghost", bytes(unit(3))), // FAILED
            )
        assertThat(ids.map(processor::process)).containsOnly(true)
        gauge.refresh()
        val after = scrape()

        assertThat(after.status).isEqualTo(200)
        assertThat(decided(after, "MATCH") - decided(before, "MATCH")).isEqualTo(2.0)
        assertThat(decided(after, "NO_MATCH") - decided(before, "NO_MATCH")).isEqualTo(1.0)
        assertThat(decided(after, "FAILED") - decided(before, "FAILED")).isEqualTo(1.0)

        assertThat(after.has("matchgw_matcher_processing_seconds_sum")).isTrue()
        assertThat(
            after.value("matchgw_matcher_processing_seconds_count") - before.value("matchgw_matcher_processing_seconds_count"),
        ).isEqualTo(4.0)
        assertThat(
            after.value("matchgw_matcher_processing_seconds_sum"),
        ).isGreaterThan(before.value("matchgw_matcher_processing_seconds_sum"))

        assertThat(after.series("matchgw_verification_latency_seconds_bucket")).isNotEmpty()
        assertThat(
            after.value("matchgw_verification_latency_seconds_count") - before.value("matchgw_verification_latency_seconds_count"),
        ).isEqualTo(4.0)
        assertThat(after.value("matchgw_verification_latency_seconds_sum")).isGreaterThanOrEqualTo(0.0)
        assertThat(after.value("matchgw_verification_latency_seconds_bucket", "le=\"+Inf\""))
            .isEqualTo(after.value("matchgw_verification_latency_seconds_count"))
    }

    @Test
    fun `AC-51 queue depth gauge equals the number of PENDING rows after refresh`() {
        enrol("subjzz-a", bytes(unit(1)))
        val first = (1..3).map { pending("subjzz-a", bytes(unit(1))) }
        gauge.refresh()
        assertThat(scrape().value("matchgw_queue_depth")).isEqualTo(3.0)

        assertThat(first.map(processor::process)).containsOnly(true)
        val second = (1..5).map { pending("subjzz-a", bytes(unit(1))) }
        gauge.refresh()
        assertThat(scrape().value("matchgw_queue_depth")).isEqualTo(5.0)

        assertThat(second.map(processor::process)).containsOnly(true)
        gauge.refresh()
        val drained = scrape()
        assertThat(drained.has("matchgw_queue_depth")).isTrue()
        assertThat(drained.value("matchgw_queue_depth")).isEqualTo(0.0)
    }

    @Test
    fun `AC-51 scrape is plain Prometheus text and needs no authentication on the internal port`() {
        val s = scrape()

        assertThat(s.status).isEqualTo(200)
        assertThat(s.contentType).matches("(?i)(text/plain|application/openmetrics-text).*")
        assertThat(s.body).contains("# TYPE matchgw_queue_depth gauge")
    }

    @Test
    @Tag("security")
    fun `API3 no tenant or subject label on any matchgw series and no tenant or subject value in the scrape`() {
        enrol("subjzz-a", bytes(unit(1)))
        val ok = pending("subjzz-a", bytes(unit(1)))
        val ghost = pending("subjzz-ghost", bytes(unit(3)))
        assertThat(listOf(ok, ghost).map(processor::process)).containsOnly(true)
        gauge.refresh()
        val s = scrape()

        val matchgw = s.series("matchgw_")
        assertThat(matchgw).isNotEmpty()
        val forbidden = Regex("""[{,]\s*(tenant|tenant_id|subject|subject_id)\s*=""")
        assertThat(matchgw.filter { forbidden.containsMatchIn(it) }).isEmpty()
        assertThat(s.body).doesNotContain(tenant).doesNotContain("subjzz")
        // Result is the only dimension of the decided counter.
        assertThat(s.series("matchgw_verifications_decided_total").all { Regex("""\{result="[A-Z_]+"}""").containsMatchIn(it) }).isTrue()
    }

    @Test
    fun `AC-51 INV-1 a stale writer adds a processing sample but no latency sample and no decided count`() {
        enrol("subjzz-a", bytes(unit(6)))
        val id = pending("subjzz-a", bytes(unit(6)))
        val before = scrape()
        val pool =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            dataSource.connection.use { lock ->
                lock.autoCommit = false
                // Holds consumer A after it loaded the job and read the template, before its guarded write.
                lock.createStatement().use { it.execute("lock table tenant_settings in access exclusive mode") }
                val a = pool.submit<Boolean> { processor.process(id) }
                awaitBlockedOnTenantSettings()

                jdbc.sql("delete from template_version where tenant_id = :t and subject_id = 'subjzz-a'").param("t", tenant).update()
                assertThat(processor.process(id)).isTrue() // B decides FAILED / SUBJECT_NOT_ENROLLED

                lock.rollback()
                assertThat(a.get(30, TimeUnit.SECONDS)).isFalse() // A's guarded write changes 0 rows
            }
        } finally {
            pool.shutdownNow()
        }
        val after = scrape()

        assertThat(decided(after, "FAILED") - decided(before, "FAILED")).isEqualTo(1.0)
        assertThat(decided(after, "MATCH") - decided(before, "MATCH")).isEqualTo(0.0)
        assertThat(
            after.value("matchgw_verification_latency_seconds_count") - before.value("matchgw_verification_latency_seconds_count"),
        ).isEqualTo(1.0)
        assertThat(
            after.value("matchgw_matcher_processing_seconds_count") - before.value("matchgw_matcher_processing_seconds_count"),
        ).isEqualTo(2.0)
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
}
