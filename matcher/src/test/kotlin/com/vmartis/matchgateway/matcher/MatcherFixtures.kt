package com.vmartis.matchgateway.matcher

import com.vmartis.matchgateway.common.messaging.Topology
import io.micrometer.core.instrument.MeterRegistry
import org.flywaydb.core.Flyway
import org.springframework.amqp.core.MessageBuilder
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.rabbitmq.RabbitMQContainer
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.Connection
import java.util.Random
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.math.sqrt

/** Vector helpers shared by the matcher tests. Templates and probes are raw little-endian float32 x 128. */
object Vectors {
    fun bytes(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buf.putFloat(it) }
        return buf.array()
    }

    /** Deterministic random unit vector. */
    fun unit(seed: Long): FloatArray {
        val r = Random(seed)
        val v = DoubleArray(128) { r.nextGaussian() }
        val n = sqrt(v.sumOf { it * it })
        return FloatArray(128) { (v[it] / n).toFloat() }
    }

    /** float64 reference cosine similarity. */
    fun reference(
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
}

data class VerificationRow(
    val status: String,
    val score: Double?,
    val threshold: Double?,
    val reason: String?,
    val probeNull: Boolean,
    val decided: Boolean,
)

/** An open ACCESS EXCLUSIVE lock on tenant_settings: a decision blocks on it after it read the template (mid-decision). */
class TenantSettingsLock(
    private val connection: Connection,
) : AutoCloseable {
    private var released = false

    fun release() {
        if (!released) {
            released = true
            connection.rollback()
        }
    }

    override fun close() {
        try {
            release()
        } finally {
            connection.close()
        }
    }
}

/**
 * Fixtures for the broker-driven matcher tests (D-28, ADR-009): schema, verification rows, publishing of job messages, waiting
 * with deadlines (no fixed sleeps) and metric / queue readers.
 */
class MatcherFixtures(
    val jdbc: JdbcClient,
    private val dataSource: DataSource,
    val rabbit: RabbitTemplate,
    private val admin: RabbitAdmin,
    private val meters: MeterRegistry,
    val broker: RabbitMQContainer,
) {
    /** Migrates with the gateway's migrations (the matcher does not own Flyway), empties the tables and both queues. */
    fun reset() {
        val migrations = File("../gateway/src/main/resources/db/migration").absoluteFile.normalize()
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("filesystem:$migrations")
            .load()
            .migrate()
        // Declares the topology if the context has not connected yet, then drops leftovers of an earlier test.
        admin.initialize()
        admin.purgeQueue(Topology.QUEUE, false)
        admin.purgeQueue(Topology.DEAD_LETTER_QUEUE, false)
        jdbc.sql("truncate verification, idempotency_key, template_version, subject, tenant_settings cascade").update()
    }

    fun enrol(
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

    fun setThreshold(
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

    /** Inserts a PENDING verification row WITHOUT publishing a message. */
    fun pending(
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

    fun publish(id: UUID) = rabbit.convertAndSend(Topology.EXCHANGE, Topology.ROUTING_KEY, id.toString())

    fun publishRaw(body: ByteArray) = rabbit.send(Topology.EXCHANGE, Topology.ROUTING_KEY, MessageBuilder.withBody(body).build())

    fun publishDeadLetter(id: UUID) = rabbit.convertAndSend(Topology.DEAD_LETTER_EXCHANGE, Topology.ROUTING_KEY, id.toString())

    /** Inserts a PENDING row and publishes its id, as the gateway does. */
    fun submit(
        tenant: String,
        subject: String,
        probe: ByteArray,
    ): UUID = pending(tenant, subject, probe).also(::publish)

    fun row(id: UUID): VerificationRow =
        jdbc
            .sql(
                "select status, score, threshold, reason, probe is null as pn, decided_at is not null as d from verification where id = :id",
            ).param("id", id)
            .query { rs, _ ->
                VerificationRow(
                    rs.getString("status"),
                    rs.getObject("score") as Double?,
                    rs.getObject("threshold") as Double?,
                    rs.getString("reason"),
                    rs.getBoolean("pn"),
                    rs.getBoolean("d"),
                )
            }.single()

    fun count(sql: String): Long = jdbc.sql(sql).query(Long::class.java).single()

    // ------------------------------------------------------------------ metrics

    fun counter(result: String): Double =
        meters
            .find("matchgw.verifications.decided")
            .tag("result", result)
            .counter()
            ?.count() ?: 0.0

    fun counters(): Map<String, Double> = listOf("MATCH", "NO_MATCH", "FAILED").associateWith(::counter)

    /** Number of processing attempts so far (the timer also records attempts that ended in an error). */
    fun processingCount(): Long = meters.find("matchgw.matcher.processing").timer()?.count() ?: 0L

    fun latencyCount(): Long = meters.find("matchgw.verification.latency").timer()?.count() ?: 0L

    // ------------------------------------------------------------------ waiting

    fun await(
        what: String,
        timeoutSeconds: Long = 30,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out after $timeoutSeconds s waiting for $what" }
            Thread.sleep(25)
        }
    }

    fun awaitTerminal(
        id: UUID,
        timeoutSeconds: Long = 30,
    ) = await("verification $id to become terminal", timeoutSeconds) {
        jdbc
            .sql("select status from verification where id = :id")
            .param("id", id)
            .query(String::class.java)
            .single() != "PENDING"
    }

    fun awaitAllTerminal(
        ids: Collection<UUID>,
        timeoutSeconds: Long = 30,
    ) = ids.forEach { awaitTerminal(it, timeoutSeconds) }

    // ------------------------------------------------------------------ blocking a decision mid-flight

    fun lockTenantSettings(): TenantSettingsLock {
        val connection = dataSource.connection
        connection.autoCommit = false
        connection.createStatement().use { it.execute("lock table tenant_settings in access exclusive mode") }
        return TenantSettingsLock(connection)
    }

    fun blockedOnTenantSettings(): Long =
        count("select count(*) from pg_locks where relation = 'tenant_settings'::regclass and not granted")

    fun awaitBlockedOnTenantSettings(
        waiters: Long = 1,
        timeoutSeconds: Long = 30,
    ) = await("$waiters decision(s) blocked on tenant_settings", timeoutSeconds) { blockedOnTenantSettings() >= waiters }

    // ------------------------------------------------------------------ broker

    /** Ready + unacknowledged messages of [queue], read from the broker itself. */
    fun queueMessages(queue: String): Long {
        val result = broker.execInContainer("rabbitmqctl", "list_queues", "name", "messages", "--formatter", "json")
        check(result.exitCode == 0) { "rabbitmqctl failed: ${result.stderr}" }
        val rows = JsonMapper.builder().build().readTree(result.stdout)
        return (0 until rows.size()).map { rows[it] }.single { it["name"].asString() == queue }["messages"].asLong()
    }

    fun awaitQueuesEmpty(timeoutSeconds: Long = 30) =
        await("both queues to be empty", timeoutSeconds) {
            queueMessages(Topology.QUEUE) == 0L && queueMessages(Topology.DEAD_LETTER_QUEUE) == 0L
        }
}
