package com.vmartis.matchgateway.matcher.decision.infra

import com.vmartis.matchgateway.common.template.Cosine
import com.vmartis.matchgateway.common.template.TemplateCodec
import com.vmartis.matchgateway.matcher.decision.domain.Decision
import com.vmartis.matchgateway.matcher.decision.domain.Job
import com.vmartis.matchgateway.matcher.decision.domain.Outcome
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID

/**
 * Decides one verification per message (ADR-009). Decision and guarded write share one transaction whose timeout is the
 * processing deadline (Spring applies it as JDBC query timeouts), so a stall ends in an exception and a redelivery.
 */
@Component
class DecisionProcessor(
    private val queue: JobQueue,
    private val jdbc: JdbcClient,
    private val meters: MeterRegistry,
    transactionManager: PlatformTransactionManager,
    properties: MatcherProperties,
) {
    private val transactions =
        TransactionTemplate(transactionManager).apply { timeout = properties.processingDeadline.toSecondsCeil() }

    private val processing = Timer.builder("matchgw.matcher.processing").register(meters)

    // decidedAt − createdAt of each written outcome, as a histogram for server-side percentiles (AC-51, D-13).
    private val latency = Timer.builder("matchgw.verification.latency").publishPercentileHistogram().register(meters)

    /** True iff this call wrote the outcome. Unknown or terminal ids are a no-op. Throws on processing errors (→ reject). */
    fun process(id: UUID): Boolean {
        val sample = Timer.start()
        try {
            val written =
                transactions.execute { _ ->
                    queue.load(id)?.let { job ->
                        decide(job).let { o ->
                            queue.complete(job, o)?.let {
                                o to
                                    it
                            }
                        }
                    }
                }
            // Counted only after the commit, and only if this call wrote the one outcome (INV-1).
            written?.let { (outcome, decidedAfter) ->
                recordDecided(if (outcome is Outcome.Decided) outcome.status else "FAILED", decidedAfter)
            }
            return written != null
        } finally {
            sample.stop(processing)
        }
    }

    /** FAILED/MAX_ATTEMPTS for a dead-lettered job; true iff this call wrote it (AC-27). */
    fun failMaxAttempts(id: UUID): Boolean {
        val decidedAfter = transactions.execute { _ -> queue.failMaxAttempts(id) }
        decidedAfter?.let { recordDecided("FAILED", it) }
        return decidedAfter != null
    }

    private fun decide(job: Job): Outcome {
        val template =
            jdbc
                .sql("SELECT template FROM template_version WHERE tenant_id = :tenant AND subject_id = :subject AND active")
                .param("tenant", job.tenantId)
                .param("subject", job.subjectId)
                .query(ByteArray::class.java)
                .optional()
                .orElse(null) ?: return Outcome.Failed("SUBJECT_NOT_ENROLLED")
        val threshold =
            jdbc
                .sql("SELECT threshold FROM tenant_settings WHERE tenant_id = :tenant")
                .param("tenant", job.tenantId)
                .query(Double::class.java)
                .optional()
                .orElse(DEFAULT_THRESHOLD)
        val score = Cosine.similarity(TemplateCodec.toFloats(job.probe), TemplateCodec.toFloats(template))
        check(score.isFinite()) { "non-finite score" }
        return Decision.decide(score, threshold)
    }

    private fun recordDecided(
        result: String,
        decidedAfter: Duration,
    ) {
        meters.counter("matchgw.verifications.decided", "result", result).increment()
        latency.record(decidedAfter)
    }

    private fun Duration.toSecondsCeil(): Int = ((toMillis() + 999) / 1000).toInt()

    private companion object {
        const val DEFAULT_THRESHOLD = 0.5
    }
}
