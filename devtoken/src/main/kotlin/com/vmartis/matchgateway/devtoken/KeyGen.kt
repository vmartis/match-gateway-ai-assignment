package com.vmartis.matchgateway.devtoken

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Base64

/**
 * Generates the dev signing key pair and the idempotency HMAC key (ADR-004). The private key goes only to the issuer
 * directory (mounted into the devtoken tool); the gateway's directory gets the public key and the HMAC key.
 */
object KeyGen {
    const val PRIVATE_KEY_FILE = "jwt-private.pem"
    const val PUBLIC_KEY_FILE = "jwt-public.pem"
    const val HMAC_KEY_FILE = "idempotency-hmac.key"

    /** Returns false (and changes nothing) when the complete key set already exists. */
    fun generate(
        issuerDir: Path,
        gatewayDir: Path,
    ): Boolean {
        val privateKeyPath = issuerDir.resolve(PRIVATE_KEY_FILE)
        val complete = listOf(privateKeyPath, gatewayDir.resolve(PUBLIC_KEY_FILE), gatewayDir.resolve(HMAC_KEY_FILE)).all(Files::exists)
        if (complete) return false
        // A partial set (e.g. only one volume removed) is regenerated completely, so the pair always matches.
        Files.deleteIfExists(privateKeyPath)
        Files.createDirectories(issuerDir)
        Files.createDirectories(gatewayDir)

        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val hmacKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

        writeFile(gatewayDir.resolve(PUBLIC_KEY_FILE), pem("PUBLIC KEY", keyPair.public.encoded), "r--r--r--")
        writeFile(gatewayDir.resolve(HMAC_KEY_FILE), hmacKey, "r--r--r--")
        // Written last: its existence marks a complete key set.
        writeFile(privateKeyPath, pem("PRIVATE KEY", keyPair.private.encoded), "r--------")
        return true
    }

    private fun pem(
        type: String,
        der: ByteArray,
    ): ByteArray {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $type-----\n$body\n-----END $type-----\n".toByteArray()
    }

    /** Created owner-only, written, then narrowed to [finalPermissions] – never world-readable while being written. */
    private fun writeFile(
        path: Path,
        content: ByteArray,
        finalPermissions: String,
    ) {
        Files.deleteIfExists(path)
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.write(path, content)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(finalPermissions))
    }
}
