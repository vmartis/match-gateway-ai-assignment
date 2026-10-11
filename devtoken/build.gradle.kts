// Dev-only token tool (D-4, AM-1): `keygen` (one-shot compose service) and `mint` (scripts/token.sh).
// Plain Kotlin CLI – no Spring Boot runtime; versions come from the Boot BOM.
plugins {
    application
}

application {
    mainClass = "com.vmartis.matchgateway.devtoken.MainKt"
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework.security:spring-security-oauth2-jose")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
