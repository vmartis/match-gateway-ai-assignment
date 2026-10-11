package com.vmartis.matchgateway.matcher.decision.infra

import com.vmartis.matchgateway.common.template.Cosine
import com.vmartis.matchgateway.common.template.TemplateCodec
import com.vmartis.matchgateway.matcher.decision.domain.Decision
import com.vmartis.matchgateway.matcher.decision.domain.Job
import com.vmartis.matchgateway.matcher.decision.domain.Outcome
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/** Decides claimed verifications. Each job is decided and written in one transaction; errors release the lease. */
@Component
class MatcherWorker(
    private val queue: JobQueue,
    private val jdbc: JdbcClient,
    private val properties: MatcherProperties,
    private val transactions: TransactionTemplate,
    private val meters: MeterRegistry,
) {
    /** Reaps exhausted jobs, then claims and processes batches until a batch comes back short. Returns jobs processed. */
    fun drain(): Int {
        var processed = 0
        do {
            // Reap on every batch, not only at the start: under sustained load drain() may never return (reviews step 5).
            decided("FAILED", queue.reap())
            val batch = queue.claim(properties.batchSize)
            batch.forEach(::process)
            processed += batch.size
        } while (batch.size == properties.batchSize)
        return processed
    }

    private fun process(job: Job) {
        try {
            // Counted only after the commit, and only if this claimant wrote the one outcome (INV-1).
            val written = transactions.execute { decide(job).takeIf { queue.complete(job, it) } }
            written?.let { decided(if (it is Outcome.Decided) it.status else "FAILED") }
        } catch (e: RuntimeException) {
            // Only the job id and the exception class: messages may carry data (CLAUDE.md §4).
            log.warn("Verification {} attempt {} failed: {}", job.id, job.attempts, e.javaClass.simpleName)
            queue.release(job)
        }
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

    private fun decided(
        result: String,
        count: Int = 1,
    ) {
        if (count > 0) meters.counter("matchgw.verifications.decided", "result", result).increment(count.toDouble())
    }

    private companion object {
        const val DEFAULT_THRESHOLD = 0.5
        val log = LoggerFactory.getLogger(MatcherWorker::class.java)
    }
}
