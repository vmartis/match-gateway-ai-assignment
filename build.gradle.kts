import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
}

subprojects {
    group = "com.vmartis"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        }
    }

    // Lockfiles pin every resolved version; osv-scanner reads them (verify.sh, ARCHITECTURE T6).
    dependencyLocking {
        lockAllConfigurations()
    }
    // Scan scope = what ships in the images (D-20): the ktlint tool classpath is not locked, so osv-scanner skips it.
    configurations.matching { it.name.startsWith("ktlint") }.configureEach {
        resolutionStrategy.deactivateDependencyLocking()
    }

    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("resolves all configurations to write lockfiles")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "run with --write-locks" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        // The Mockito agent (from the Spring test starters) appends to the boot classpath, which makes CDS warn.
        jvmArgs("-Xshare:off")
    }
}
