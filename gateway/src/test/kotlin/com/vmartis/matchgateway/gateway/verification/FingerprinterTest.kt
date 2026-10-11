package com.vmartis.matchgateway.gateway.verification

import com.vmartis.matchgateway.gateway.subject.TestTemplates
import com.vmartis.matchgateway.gateway.verification.infra.Fingerprinter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Step 9: AC-23, ADR-008. Fingerprint = HmacSHA256(tenant 0x00 subject 0x00 probe). */
class FingerprinterTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val probe = TestTemplates.unitBytes(2)
    private val fingerprinter = Fingerprinter(key)

    private fun reference(
        key: ByteArray,
        tenant: String,
        subject: String,
        probe: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(tenant.toByteArray(Charsets.UTF_8))
        mac.update(0)
        mac.update(subject.toByteArray(Charsets.UTF_8))
        mac.update(0)
        mac.update(probe)
        return mac.doFinal()
    }

    @Test
    fun `AC-23 fingerprint equals an independent HmacSHA256 over tenant, subject and probe separated by zero bytes`() {
        assertThat(fingerprinter.fingerprint("tenant-a", "subj-1", probe)).isEqualTo(reference(key, "tenant-a", "subj-1", probe))
    }

    @Test
    fun `AC-23 fingerprint is 32 bytes and deterministic`() {
        val first = fingerprinter.fingerprint("tenant-a", "subj-1", probe)

        assertThat(first).hasSize(32)
        assertThat(Fingerprinter(key.copyOf()).fingerprint("tenant-a", "subj-1", probe.copyOf())).isEqualTo(first)
    }

    @Test
    fun `AC-23 fingerprint differs per tenant, subject, probe and key`() {
        val base = fingerprinter.fingerprint("tenant-a", "subj-1", probe)

        assertThat(fingerprinter.fingerprint("tenant-b", "subj-1", probe)).isNotEqualTo(base)
        assertThat(fingerprinter.fingerprint("tenant-a", "subj-2", probe)).isNotEqualTo(base)
        assertThat(fingerprinter.fingerprint("tenant-a", "subj-1", TestTemplates.unitBytes(3))).isNotEqualTo(base)
        assertThat(fingerprinter.fingerprint("tenant-a", "subj-1", probe.copyOf().also { it[511] = (it[511] + 1).toByte() }))
            .isNotEqualTo(base)
        assertThat(Fingerprinter(ByteArray(32) { 9 }).fingerprint("tenant-a", "subj-1", probe)).isNotEqualTo(base)
    }

    @Test
    fun `AC-23 field boundaries are unambiguous`() {
        assertThat(fingerprinter.fingerprint("ab", "c", probe)).isNotEqualTo(fingerprinter.fingerprint("a", "bc", probe))
        assertThat(fingerprinter.fingerprint("a", "bc", probe)).isNotEqualTo(
            fingerprinter.fingerprint(
                "a",
                "b",
                byteArrayOf('c'.code.toByte()) + probe,
            ),
        )
    }

    @Test
    fun `AC-23 fingerprint is not an unkeyed hash of the probe or of the concatenation`() {
        val fp = fingerprinter.fingerprint("tenant-a", "subj-1", probe)
        val sha = MessageDigest.getInstance("SHA-256")

        assertThat(fp).isNotEqualTo(sha.digest(probe))
        assertThat(fp).isNotEqualTo(sha.digest("tenant-a".toByteArray() + 0 + "subj-1".toByteArray() + 0 + probe))
        assertThat(fp).isNotEqualTo(sha.digest("subj-1".toByteArray() + probe))
        assertThat(fp).isNotEqualTo(probe.copyOf(32))
    }

    @Test
    fun `AC-23 a key that is not exactly 32 bytes is rejected`() {
        listOf(0, 16, 31, 33, 64).forEach { size ->
            assertThatThrownBy {
                Fingerprinter(
                    ByteArray(size),
                )
            }.describedAs("key of $size bytes").isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
