package com.vmartis.matchgateway.matcher.metrics

import com.vmartis.matchgateway.matcher.MatcherFixtures
import com.vmartis.matchgateway.matcher.TestcontainersConfiguration
import com.vmartis.matchgateway.matcher.Vectors.bytes
import com.vmartis.matchgateway.matcher.Vectors.unit
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.rabbitmq.RabbitMQContainer
import javax.sql.DataSource

/** AC-51: the queue-depth gauge keeps refreshing while a consumer is stuck mid-decision (code review step 10, MAJOR; D-28). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["matcher.queue-depth-refresh=100ms"],
)
@Import(TestcontainersConfiguration::class)
class QueueDepthSchedulingIT {
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

    private fun depth(): Double = meters.get("matchgw.queue.depth").gauge().value()

    @Test
    fun `AC-51 queue depth keeps refreshing while a consumer is blocked mid-decision`() {
        fx.enrol("t1", "s1", bytes(unit(1)))
        val ids = mutableListOf<java.util.UUID>()
        fx.lockTenantSettings().use { lock ->
            // The consumer reads tenant_settings while deciding: it stays blocked, the gauge must still follow the backlog.
            ids += fx.submit("t1", "s1", bytes(unit(1)))
            fx.awaitBlockedOnTenantSettings(1)
            repeat(4) { ids += fx.submit("t1", "s1", bytes(unit(1))) }

            fx.await("gauge to show the backlog of 5", 10) { depth() == 5.0 }

            lock.release()
        }
        fx.await("gauge to drop to 0 after the backlog is decided", 10) { depth() == 0.0 }
        fx.awaitAllTerminal(ids)
        assertThat(fx.count("select count(*) from verification where status = 'MATCH'")).isEqualTo(5)
    }
}
