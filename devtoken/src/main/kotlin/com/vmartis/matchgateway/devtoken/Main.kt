package com.vmartis.matchgateway.devtoken

import java.nio.file.Path
import java.time.Duration
import kotlin.system.exitProcess

private const val USAGE = """usage:
  devtoken keygen [issuerDir=/keys/issuer] [gatewayDir=/keys/gateway]
  devtoken mint --tenant <id> --sub <subject> --scope "<enroll verify admin>" [--ttl <seconds, max 900>]"""

fun main(args: Array<String>) {
    try {
        when (args.firstOrNull()) {
            "keygen" -> {
                val created = KeyGen.generate(Path.of(args.getOrElse(1) { "/keys/issuer" }), Path.of(args.getOrElse(2) { "/keys/gateway" }))
                println(if (created) "keygen: keys created" else "keygen: keys already exist, unchanged")
            }

            "mint" -> {
                val options = parseOptions(args.drop(1))
                val token =
                    TokenMinter.mint(
                        privateKeyPem = Path.of(System.getenv("DEVTOKEN_PRIVATE_KEY") ?: "/keys/issuer/${KeyGen.PRIVATE_KEY_FILE}"),
                        tenant = options.required("--tenant"),
                        subject = options.required("--sub"),
                        scope = options.required("--scope"),
                        ttl = options["--ttl"]?.let { Duration.ofSeconds(it.toLong()) } ?: Duration.ofMinutes(15),
                        issuer = System.getenv("JWT_ISSUER") ?: "https://match-gateway.invalid/dev-issuer",
                        audience = System.getenv("JWT_AUDIENCE") ?: "match-gateway",
                    )
                println(token)
            }

            else -> {
                fail(USAGE)
            }
        }
    } catch (e: IllegalArgumentException) {
        fail("error: ${e.message}")
    } catch (e: java.nio.file.NoSuchFileException) {
        fail("error: key file not found (${e.file}) – start the stack first: docker compose up -d")
    }
}

private fun parseOptions(args: List<String>): Map<String, String> {
    require(args.size % 2 == 0) { "options must be --name value pairs" }
    return args.chunked(2).associate { (name, value) ->
        require(name in setOf("--tenant", "--sub", "--scope", "--ttl")) { "unknown option $name" }
        name to value
    }
}

private fun Map<String, String>.required(name: String): String = this[name] ?: throw IllegalArgumentException("missing $name")

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(2)
}
