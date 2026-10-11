package com.vmartis.matchgateway.gateway.tenant

import com.vmartis.matchgateway.gateway.subject.api.EnrollmentRequest
import com.vmartis.matchgateway.gateway.verification.api.VerificationRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** AC-41 defence in depth: Spring logs bound request bodies via toString() at DEBUG, so request DTOs never print templates. */
@Tag("security")
class RequestRedactionTest {
    private val template = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFB"

    @Test
    fun `A09 AC-41 enrolment request toString does not contain the template`() {
        assertThat(EnrollmentRequest(template).toString()).doesNotContain(template).contains("<redacted>")
    }

    @Test
    fun `A09 AC-41 verification request toString does not contain the probe but keeps the subject id`() {
        assertThat(VerificationRequest("subj-1", template).toString())
            .doesNotContain(template)
            .contains("subj-1", "<redacted>")
    }
}
