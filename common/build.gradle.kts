// Pure Kotlin: no Spring on the classpath (CLAUDE.md §3, ADR-001).
dependencies {
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
